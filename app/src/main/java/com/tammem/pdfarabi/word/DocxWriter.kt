package com.tammem.pdfarabi.word

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rebuilds document.xml from the editor's blocks. Paragraphs, runs, tables, pictures and anything the
 * app cannot edit are re-emitted from their original XML; only the properties the user changed are
 * rewritten, so the file keeps everything else (styles, headers, sections, fields, shapes…).
 */
class DocxWriter(private val pkg: DocxPackage, private val conv: Converted) {

    private val doc = conv.doc
    private val store = conv.store
    private val main = pkg.mainPart
    private val newRels = ArrayList<DocxPackage.Rel>()
    private val existingRelIds = HashSet<String>()
    private var relSeq = 0
    private var docPrId = conv.maxDocPrId
    private val usedStyles = HashSet<String>()
    private val listTokens = LinkedHashMap<String, Int>()
    private val listKinds = LinkedHashSet<String>()
    private var needTableGrid = false
    private val pCount = HashMap<Int, Int>()
    /** Bookmark halves and field parts still in the document; a piece whose partner was deleted is not written. */
    private val present = HashSet<String>()
    private val pSeen = HashMap<Int, Int>()
    private val newMediaExt = HashSet<String>()

    init {
        pkg.rels(main).forEach { existingRelIds += it.id }
    }

    private fun newRelId(): String {
        while (true) {
            val id = "rIdPA${++relSeq}"
            if (id !in existingRelIds) { existingRelIds += id; return id }
        }
    }

    // ------------------------------------------------------------------ entry

    /** Applies the edited blocks and returns the updated package parts. */
    fun build(json: JSONObject): LinkedHashMap<String, ByteArray> {
        val blocks = json.getJSONArray("blocks")
        page = json.optJSONObject("page")
        allSects = Ox.findAll(Ox.child(conv.root, "body"), "sectPr").filter { it.parentNode?.localName == "pPr" || it.parentNode?.localName == "body" }
        countParagraphs(blocks)
        // list tokens first: numbering ids are needed while writing paragraphs
        collectLists(blocks)
        val numIds = writeNumbering()

        val body = StringBuilder()
        writeBlocks(blocks, body, numIds, false)
        if (blocks.length() == 0 || lastIsTable(blocks)) body.append("<w:p/>")

        val root = conv.root
        val extra = LinkedHashMap<String, String>()
        fun ns(prefix: String, uri: String) {
            val declared = root.getAttributeNS(Ox.XMLNS, prefix)
            if (declared.isEmpty()) extra["xmlns:$prefix"] = uri
        }
        ns("w", Ox.W); ns("r", Ox.R); ns("wp", Ox.WP); ns("a", Ox.A); ns("pic", Ox.PIC)

        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n")
        out.append(Ox.startTag(root, extra))
        if (changed("bg")) {
            val bg = page?.optString("bg", "") ?: ""
            if (bg.length == 6) out.append("<w:background w:color=\"").append(bg).append("\"/>")
        } else conv.background?.let { Ox.write(it, out) }
        out.append("<w:body>")
        out.append(body)
        val finalSect = (conv.finalSectPr?.cloneNode(true) as? Element)
            ?: if (page != null) doc.createElementNS(Ox.W, "w:sectPr") else null
        finalSect?.let { patchSect(it, conv.finalSectPr ?: it); Ox.write(it, out) }
        out.append("</w:body></")
        out.append(root.nodeName).append('>')

        val entries = LinkedHashMap(pkg.entries)
        entries[main] = out.toString().toByteArray(Charsets.UTF_8)
        // pictures inserted in the app and then deleted again are not kept in the file
        val referenced = newRels.filter { it.type == Ox.REL_IMAGE }.map { pkg.resolve(main, it.target) }.toSet()
        entries.keys.filter { it.startsWith(NEW_MEDIA) && it !in referenced }.forEach { entries.remove(it) }
        writePageParts(entries)
        writeStyles(entries)
        writeRels(entries)
        writeContentTypes(entries)
        return entries
    }

    // ------------------------------------------------------------------ page setup & decoration

    private var page: JSONObject? = null
    private var allSects: List<Element> = emptyList()

    private fun changed(k: String) = page?.optJSONObject("changed")?.optInt(k, 0) == 1

    /** Header/footer parts to decorate: (part path, kind "header"|"footer", type default|first|even). */
    private val hfTargets = LinkedHashMap<String, Pair<String, String>>()
    private val newParts = LinkedHashMap<String, String>()
    private var newPartSeq = 0

    private fun twAttr(e: Element, name: String, v: Int) = Ox.setVal(e, v.toString(), name)

    /** Applies the Page tab's changes to one section's properties ([orig] tells which section it was). */
    private fun patchSect(sp: Element, orig: Element) {
        val pg = page ?: return
        val order = PageDeco.SECTPR_ORDER
        // a document without section properties gets the full page description
        val fresh = conv.finalSectPr == null
        if (changed("size") || (fresh && Ox.child(sp, "pgSz") == null)) {
            val e = Ox.ensure(sp, "pgSz", order)
            twAttr(e, "w", pg.optInt("w", 11906))
            twAttr(e, "h", pg.optInt("h", 16838))
            if (pg.optString("orient") == "landscape") Ox.setVal(e, "landscape", "orient") else e.removeAttributeNS(Ox.W, "orient")
        }
        if (changed("margins") || (fresh && Ox.child(sp, "pgMar") == null)) {
            val e = Ox.ensure(sp, "pgMar", order)
            twAttr(e, "top", pg.optInt("mt", 1440)); twAttr(e, "bottom", pg.optInt("mb", 1440))
            twAttr(e, "left", pg.optInt("ml", 1440)); twAttr(e, "right", pg.optInt("mr", 1440))
            if (Ox.a(e, "header") == null) twAttr(e, "header", 708)
            if (Ox.a(e, "footer") == null) twAttr(e, "footer", 708)
            if (Ox.a(e, "gutter") == null) twAttr(e, "gutter", 0)
        }
        if (changed("border")) {
            Ox.remove(sp, "pgBorders")
            pg.optJSONObject("border")?.let { b ->
                val e = Ox.ensure(sp, "pgBorders", order)
                Ox.setVal(e, if (b.optString("from") == "text") "text" else "page", "offsetFrom")
                val art = b.optBoolean("art")
                val color = b.optString("color", "auto").let { if (it.length == 6) it else "auto" }
                for (side in listOf("top", "left", "bottom", "right")) {
                    e.appendChild(Ox.el(doc, side, "val" to b.optString("style", "single"),
                        "sz" to (if (art) b.optInt("sz", 20).coerceIn(1, 31) else b.optInt("sz", 4).coerceIn(2, 96)).toString(),
                        "space" to b.optInt("space", 24).coerceIn(0, 31).toString(), "color" to color))
                }
            }
        }
        val first = allSects.firstOrNull() === orig || allSects.isEmpty()
        val wm = changed("wm") && page?.optJSONObject("wm")?.optString("text").orEmpty().isNotEmpty()
        val pn = changed("pn") && page?.optJSONObject("pn") != null
        val pnTop = page?.optJSONObject("pn")?.optString("pos", "bc")?.startsWith("t") == true
        val titlePg = Ox.onOff(Ox.child(sp, "titlePg")) == true
        fun target(kind: String, type: String, create: Boolean) {
            val local = if (kind == "header") "headerReference" else "footerReference"
            val ref = Ox.children(sp, local).firstOrNull { (Ox.a(it, "type") ?: "default") == type }
            if (ref != null) {
                val rel = pkg.rels(main).firstOrNull { it.id == ref.getAttributeNS(Ox.R, "id") }
                    ?: newRels.firstOrNull { it.id == ref.getAttributeNS(Ox.R, "id") }
                if (rel != null) hfTargets[pkg.resolve(main, rel.target)] = kind to type
                return
            }
            if (!create) return
            // a new header/footer for this section
            val dir = if (pkg.mainDir.isEmpty()) "" else pkg.mainDir + "/"
            var path: String
            do { path = "$dir${kind}_pa${++newPartSeq}.xml" } while (pkg.entries.containsKey(path))
            val rid = newRelId()
            newRels += DocxPackage.Rel(rid, if (kind == "header") Ox.REL_HEADER else Ox.REL_FOOTER, relTarget(path), false)
            overrides[path] = if (kind == "header") PageDeco.CT_HEADER else PageDeco.CT_FOOTER
            newParts[path] = kind
            hfTargets[path] = kind to type
            val e = doc.createElementNS(Ox.W, "w:$local")
            Ox.setVal(e, type, "type")
            e.setAttributeNS(Ox.R, "r:id", rid)
            sp.insertBefore(e, sp.firstChild)
        }
        if (changed("wm")) {
            // every header the section has loses its old watermark; the first section gets headers if it had none
            for (t in listOf("default", "first", "even")) target("header", t, wm && first && (t == "default" || (t == "first" && titlePg)))
        }
        if (changed("pn")) {
            for (t in listOf("default", "first", "even")) {
                target("header", t, false)
                target("footer", t, false)
            }
            if (pn && first) target(if (pnTop) "header" else "footer", "default", true)
        }
        Ox.sortChildren(sp, order)
    }

    /** Writes the watermark / page numbers into the headers and footers chosen by [patchSect]. */
    private fun writePageParts(entries: LinkedHashMap<String, ByteArray>) {
        val pg = page ?: return
        if (!changed("wm") && !changed("pn") && !changed("bg")) return
        if (changed("bg")) settingsBackground(entries, pg.optString("bg", "").length == 6)
        if (!changed("wm") && !changed("pn")) return
        val wm = pg.optJSONObject("wm")?.takeIf { it.optString("text").isNotEmpty() && changed("wm") }
        val pn = pg.optJSONObject("pn")?.takeIf { changed("pn") }
        val cw = max(1440, pg.optInt("w", 11906) - pg.optInt("ml", 1440) - pg.optInt("mr", 1440))
        val ch = max(1440, pg.optInt("h", 16838) - pg.optInt("mt", 1440) - pg.optInt("mb", 1440))
        var n = 0
        for ((path, kt) in hfTargets) {
            val (kind, _) = kt
            val bytes = entries[path]
            val d = if (bytes != null) runCatching { Ox.parse(bytes) }.getOrNull() ?: continue
                    else Ox.parse(PageDeco.newPart(if (kind == "header") "hdr" else "ftr", "").toByteArray(Charsets.UTF_8))
            var touched = bytes == null
            // old page numbers go first, so a watermark added below is never taken with them
            if (changed("pn") && bytes != null && PageDeco.removePageNumbers(d)) touched = true
            if (kind == "header" && changed("wm")) {
                if (PageDeco.removeWatermarks(d)) touched = true
                if (wm != null) {
                    val xml = Ox.xml(d.documentElement)
                    PageDeco.addWatermark(d, PageDeco.watermarkRun(wm.optString("text"), wm.optString("color", "C0C0C0").let { if (it.length == 6) it else "C0C0C0" },
                        wm.optBoolean("diag", true), wm.optString("font", "Arial").ifEmpty { "Arial" }, ++n, cw, ch, !xml.contains("_x0000_t136")))
                    touched = true
                }
            }
            if (changed("pn")) {
                if (pn != null) {
                    val top = pn.optString("pos", "bc").startsWith("t")
                    if ((kind == "header") == top) {
                        PageDeco.addPageNumber(d, PageDeco.pageNumberParagraph(pn.optString("pos", "bc"), pn.optString("fmt", "n"), conv.rtl), top)
                        touched = true
                    }
                }
            }
            if (touched) entries[path] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(d.documentElement)).toByteArray(Charsets.UTF_8)
        }
    }

    /** Word shows the page colour only when the settings ask for it. */
    private fun settingsBackground(entries: LinkedHashMap<String, ByteArray>, on: Boolean) {
        val part = pkg.partFor(PageDeco.REL_SETTINGS) ?: return
        val d = entries[part]?.let { runCatching { Ox.parse(it) }.getOrNull() } ?: return
        val root = d.documentElement
        val cur = Ox.child(root, "displayBackgroundShape")
        if (on && cur == null) {
            val early = setOf("writeProtection", "view", "zoom", "removePersonalInformation", "removeDateAndTime", "doNotDisplayPageBoundaries")
            val ref = Ox.kids(root).firstOrNull { !(it.namespaceURI == Ox.W && it.localName in early) }
            root.insertBefore(d.createElementNS(Ox.W, "w:displayBackgroundShape"), ref)
        } else if (!on && cur != null) {
            root.removeChild(cur)
        } else return
        entries[part] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(root)).toByteArray(Charsets.UTF_8)
    }

    private fun lastIsTable(blocks: JSONArray): Boolean =
        blocks.length() > 0 && blocks.getJSONObject(blocks.length() - 1).optString("t") == "tbl"

    private fun countParagraphs(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "p" -> {
                    if (o.has("x")) { val x = o.getInt("x"); pCount[x] = (pCount[x] ?: 0) + 1 }
                    o.optJSONArray("c")?.let { collectBookmarks(it) }
                }
                "tbl" -> forCells(o) { countParagraphs(it.getJSONArray("c")) }
                "rawb" -> (store.items.getOrNull(o.optInt("x", -1)) as? RawItem)?.let { present += it.provides }
            }
        }
    }

    private fun collectBookmarks(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "raw" -> (store.items.getOrNull(o.optInt("x", -1)) as? RawItem)?.let { present += it.provides }
                "a" -> o.optJSONArray("c")?.let { collectBookmarks(it) }
            }
        }
    }

    private fun keepRaw(item: RawItem): Boolean = item.requires.all { it in present }

    private fun forCells(t: JSONObject, fn: (JSONObject) -> Unit) {
        val rows = t.getJSONArray("rows")
        for (r in 0 until rows.length()) {
            val cells = rows.getJSONObject(r).getJSONArray("cells")
            for (c in 0 until cells.length()) fn(cells.getJSONObject(c))
        }
    }

    private fun collectLists(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "p" -> {
                    val num = o.optString("num", "")
                    if (num.startsWith("new-")) {
                        val kind = num.removePrefix("new-").substringBefore('-')
                        listKinds += kind
                        listTokens[num] = 0
                    }
                }
                "tbl" -> forCells(o) { collectLists(it.getJSONArray("c")) }
            }
        }
    }

    // ------------------------------------------------------------------ blocks

    private fun writeBlocks(arr: JSONArray, sb: StringBuilder, numIds: Map<String, Int>, inCell: Boolean) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "p" -> writeP(o, sb, numIds)
                "tbl" -> writeTable(o, sb, numIds)
                "rawb" -> (store.items.getOrNull(o.optInt("x", -1)) as? RawItem)?.let { sb.append(it.xml) }
            }
        }
    }

    private fun p(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null

    private fun writeP(o: JSONObject, sb: StringBuilder, numIds: Map<String, Int>) {
        val x = o.optInt("x", -1)
        val item = store.items.getOrNull(x) as? PItem
        val orig: Map<String, String?>? = if (item != null) store.origP[x] else null
        val now = mapOf(
            "st" to p(o, "st"), "al" to p(o, "al"), "dir" to p(o, "dir"), "num" to p(o, "num"),
            "lvl" to p(o, "lvl"), "ind" to p(o, "ind"), "ls" to (p(o, "ls") ?: ""), "sb" to p(o, "sb"), "sa" to p(o, "sa")
        )
        val st = now["st"]?.ifEmpty { null } ?: conv.defStyle
        val base = orig ?: conv.styleBase[st] ?: conv.styleBase[conv.defStyle] ?: emptyMap()

        val pPr: Element = (Ox.child(item?.el, "pPr")?.cloneNode(true) as? Element) ?: doc.createElementNS(Ox.W, "w:pPr")
        val order = Ox.PPR_ORDER

        if (orig == null || orig["st"] != st) {
            if (st == conv.defStyle) Ox.remove(pPr, "pStyle")
            else Ox.setVal(Ox.ensure(pPr, "pStyle", order), st)
        }
        if (st != conv.defStyle) usedStyles += st

        val al = now["al"] ?: "start"
        if (al != (base["al"] ?: "start")) {
            Ox.setVal(Ox.ensure(pPr, "jc", order), when (al) { "center" -> "center"; "end" -> "right"; "justify" -> "both"; else -> "left" })
        }
        val dir = now["dir"] ?: "ltr"
        if (dir != (base["dir"] ?: "ltr")) Ox.setOnOff(pPr, "bidi", dir == "rtl", order)

        val num = now["num"]
        val lvl = now["lvl"] ?: "0"
        if (num != base["num"] || (num != null && lvl != base["lvl"])) {
            if (num != null) {
                val numId = if (num.startsWith("new-")) numIds[num]?.toString() else num
                if (numId != null) {
                    val np = Ox.ensure(pPr, "numPr", order)
                    while (np.firstChild != null) np.removeChild(np.firstChild)
                    np.appendChild(Ox.el(doc, "ilvl", "val" to lvl))
                    np.appendChild(Ox.el(doc, "numId", "val" to numId))
                }
            } else {
                if (item?.hadNumPr == true && conv.styleBase[st]?.get("num") == null) Ox.remove(pPr, "numPr")
                else {
                    val np = Ox.ensure(pPr, "numPr", order)
                    while (np.firstChild != null) np.removeChild(np.firstChild)
                    np.appendChild(Ox.el(doc, "numId", "val" to "0"))
                }
            }
        }

        val ind = now["ind"] ?: "0,0,0"
        if (ind != (base["ind"] ?: "0,0,0")) {
            val v = ind.split(',').map { it.toIntOrNull() ?: 0 }
            val e = Ox.ensure(pPr, "ind", order)
            listOf("start", "end", "leftChars", "rightChars", "startChars", "endChars", "firstLineChars", "hangingChars", "firstLine", "hanging")
                .forEach { e.removeAttributeNS(Ox.W, it) }
            Ox.setVal(e, v.getOrElse(0) { 0 }.toString(), "left")
            Ox.setVal(e, v.getOrElse(1) { 0 }.toString(), "right")
            val f = v.getOrElse(2) { 0 }
            if (f < 0) Ox.setVal(e, (-f).toString(), "hanging") else if (f > 0) Ox.setVal(e, f.toString(), "firstLine")
        }

        val ls = now["ls"] ?: ""
        val sbv = now["sb"] ?: "0"
        val sav = now["sa"] ?: "0"
        if (ls != (base["ls"] ?: "") || sbv != (base["sb"] ?: "0") || sav != (base["sa"] ?: "0")) {
            val e = Ox.ensure(pPr, "spacing", order)
            if (ls != (base["ls"] ?: "") && ls.isNotEmpty()) {
                val parts = ls.split(',')
                Ox.setVal(e, parts[0], "line")
                Ox.setVal(e, parts.getOrElse(1) { "auto" }, "lineRule")
            }
            if (sbv != (base["sb"] ?: "0")) {
                Ox.setVal(e, sbv, "before"); e.removeAttributeNS(Ox.W, "beforeAutospacing"); e.removeAttributeNS(Ox.W, "beforeLines")
            }
            if (sav != (base["sa"] ?: "0")) {
                Ox.setVal(e, sav, "after"); e.removeAttributeNS(Ox.W, "afterAutospacing"); e.removeAttributeNS(Ox.W, "afterLines")
            }
        }

        // A paragraph split in two by Enter: only the first keeps "page break before", only the last keeps the section break.
        if (x >= 0) {
            val seen = (pSeen[x] ?: 0) + 1
            pSeen[x] = seen
            val total = pCount[x] ?: 1
            if (seen > 1) Ox.remove(pPr, "pageBreakBefore")
            if (seen < total) Ox.remove(pPr, "sectPr")
        }
        if (page != null) Ox.child(pPr, "sectPr")?.let { sp -> patchSect(sp, Ox.child(Ox.child(item?.el, "pPr"), "sectPr") ?: sp) }

        sb.append(if (item != null) Ox.startTag(item.el) else "<w:p>")
        Ox.sortChildren(pPr, Ox.PPR_ORDER)
        Ox.child(pPr, "rPr")?.let { Ox.sortChildren(it, Ox.RPR_ORDER) }
        if (pPr.hasChildNodes()) Ox.write(pPr, sb)
        writeInline(o.optJSONArray("c") ?: JSONArray(), sb, dir == "rtl")
        sb.append("</w:p>")
    }

    // ------------------------------------------------------------------ inline

    private fun writeInline(arr: JSONArray, sb: StringBuilder, rtl: Boolean) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "r" -> writeRun(o, sb)
                "br" -> sb.append("<w:r><w:br/></w:r>")
                "pb" -> sb.append("<w:r><w:br w:type=\"page\"/></w:r>")
                "raw" -> (store.items.getOrNull(o.optInt("x", -1)) as? RawItem)?.takeIf { keepRaw(it) }?.let { sb.append(it.xml) }
                "img" -> writeImage(o, sb)
                "a" -> writeLink(o, sb, rtl)
            }
        }
    }

    private val hlNames = setOf("yellow", "green", "cyan", "magenta", "blue", "red", "darkBlue", "darkCyan", "darkGreen",
        "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray", "black", "white")

    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    private fun writeRun(o: JSONObject, sb: StringBuilder) {
        val txt = o.optString("txt").replace("​", "").replace("‍", "")
        if (txt.isEmpty()) return
        val x = o.optInt("x", -1)
        val item = store.items.getOrNull(x) as? RItem
        val orig: Map<String, String?> = if (item != null) store.origR[x] ?: emptyMap() else emptyMap()
        val rPr: Element = (Ox.child(item?.el, "rPr")?.cloneNode(true) as? Element) ?: doc.createElementNS(Ox.W, "w:rPr")
        val order = Ox.RPR_ORDER
        fun now(k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        fun changed(k: String) = now(k) != orig[k]

        if (changed("b")) { val v = now("b")?.let { it == "1" }; Ox.setOnOff(rPr, "b", v, order); Ox.setOnOff(rPr, "bCs", v, order) }
        if (changed("i")) { val v = now("i")?.let { it == "1" }; Ox.setOnOff(rPr, "i", v, order); Ox.setOnOff(rPr, "iCs", v, order) }
        if (changed("s")) {
            val v = now("s")?.let { it == "1" }
            Ox.setOnOff(rPr, "strike", v, order)
            if (v != true) Ox.remove(rPr, "dstrike")
        }
        if (changed("u")) {
            val v = now("u")
            if (v == null) Ox.remove(rPr, "u") else Ox.setVal(Ox.ensure(rPr, "u", order), v)
        }
        if (changed("va")) {
            when (now("va")) {
                "sup" -> Ox.setVal(Ox.ensure(rPr, "vertAlign", order), "superscript")
                "sub" -> Ox.setVal(Ox.ensure(rPr, "vertAlign", order), "subscript")
                else -> Ox.remove(rPr, "vertAlign")
            }
        }
        if (changed("sz")) {
            val v = now("sz")
            if (v == null) { Ox.remove(rPr, "sz"); Ox.remove(rPr, "szCs") }
            else { Ox.setVal(Ox.ensure(rPr, "sz", order), v); Ox.setVal(Ox.ensure(rPr, "szCs", order), v) }
        }
        if (changed("f")) {
            val v = now("f")
            if (v == null) Ox.remove(rPr, "rFonts")
            else {
                val e = Ox.ensure(rPr, "rFonts", order)
                listOf("asciiTheme", "hAnsiTheme", "cstheme").forEach { e.removeAttributeNS(Ox.W, it) }
                Ox.setVal(e, v, "ascii"); Ox.setVal(e, v, "hAnsi"); Ox.setVal(e, v, "cs")
            }
        }
        if (changed("c")) {
            val v = now("c")
            if (v == null) Ox.remove(rPr, "color")
            else {
                val e = Ox.ensure(rPr, "color", order)
                listOf("themeColor", "themeShade", "themeTint").forEach { e.removeAttributeNS(Ox.W, it) }
                Ox.setVal(e, v)
            }
        }
        if (changed("hl")) {
            val v = now("hl")
            when {
                v == null -> { Ox.remove(rPr, "highlight"); if (orig["hl"]?.let { it !in hlNames } == true) Ox.remove(rPr, "shd") }
                v in hlNames -> Ox.setVal(Ox.ensure(rPr, "highlight", order), v)
                else -> {
                    Ox.remove(rPr, "highlight")
                    val e = Ox.ensure(rPr, "shd", order)
                    Ox.setVal(e, "clear"); Ox.setVal(e, "auto", "color"); Ox.setVal(e, v, "fill")
                }
            }
        }
        if (item == null && hasArabic(txt)) Ox.setOnOff(rPr, "rtl", true, order)

        sb.append(if (item != null) Ox.startTag(item.el) else "<w:r>")
        Ox.sortChildren(rPr, Ox.RPR_ORDER)
        if (rPr.hasChildNodes()) Ox.write(rPr, sb)
        val seg = StringBuilder()
        fun flushText() {
            if (seg.isEmpty()) return
            sb.append("<w:t xml:space=\"preserve\">").append(Ox.esc(seg.toString())).append("</w:t>")
            seg.setLength(0)
        }
        for (c in txt) {
            when (c) {
                '\t' -> { flushText(); sb.append("<w:tab/>") }
                '\n' -> { flushText(); sb.append("<w:br/>") }
                '‑' -> { flushText(); sb.append("<w:noBreakHyphen/>") }
                else -> seg.append(c)
            }
        }
        flushText()
        sb.append("</w:r>")
    }

    private fun writeImage(o: JSONObject, sb: StringBuilder) {
        val w = max(1, o.optInt("w", 100))
        val h = max(1, o.optInt("h", 100))
        val cx = (w * 9525L)
        val cy = (h * 9525L)
        val item = store.items.getOrNull(o.optInt("x", -1)) as? ImgItem
        if (item != null && !o.has("new")) {
            val d = item.drawing.cloneNode(true) as Element
            if (abs(w - item.wPx) > 1 || abs(h - item.hPx) > 1) {
                Ox.findAll(d, "extent", Ox.WP).forEach { it.setAttribute("cx", cx.toString()); it.setAttribute("cy", cy.toString()) }
                Ox.find(d, "spPr", Ox.PIC)?.let { sp -> Ox.find(sp, "ext", Ox.A)?.let { it.setAttribute("cx", cx.toString()); it.setAttribute("cy", cy.toString()) } }
            }
            sb.append("<w:r>")
            Ox.child(item.run, "rPr")?.let { Ox.write(it, sb) }
            Ox.write(d, sb)
            sb.append("</w:r>")
            return
        }
        val media = o.optString("m")
        if (media.isEmpty() || !pkg.entries.containsKey(media)) return
        val rid = newRelId()
        newRels += DocxPackage.Rel(rid, Ox.REL_IMAGE, relTarget(media), false)
        newMediaExt += media.substringAfterLast('.', "png").lowercase()
        val id = ++docPrId
        sb.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            .append("<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            .append("<wp:docPr id=\"$id\" name=\"Picture $id\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>")
            .append("<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"").append(Ox.esc(media.substringAfterLast('/'), true)).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            .append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
    }

    /** Relationship target of a part, relative to the main document's folder. */
    private fun relTarget(path: String): String {
        val dir = pkg.mainDir
        return if (dir.isNotEmpty() && path.startsWith("$dir/")) path.removePrefix("$dir/") else "/$path"
    }

    private fun writeLink(o: JSONObject, sb: StringBuilder, rtl: Boolean) {
        val item = store.items.getOrNull(o.optInt("x", -1)) as? LinkItem
        if (item != null) {
            sb.append(Ox.startTag(item.el))
        } else {
            val href = o.optString("href")
            if (href.startsWith("#")) {
                sb.append("<w:hyperlink w:anchor=\"").append(Ox.esc(href.removePrefix("#"), true)).append("\" w:history=\"1\">")
            } else {
                val rid = newRelId()
                newRels += DocxPackage.Rel(rid, Ox.REL_LINK, href, true)
                sb.append("<w:hyperlink r:id=\"").append(rid).append("\" w:history=\"1\">")
            }
        }
        writeInline(o.optJSONArray("c") ?: JSONArray(), sb, rtl)
        sb.append("</w:hyperlink>")
    }

    // ------------------------------------------------------------------ tables

    private class Origin(val cell: JSONObject, val tcItem: TcItem?, val col: Int, val span: Int, var rowsLeft: Int)

    private fun writeTable(o: JSONObject, sb: StringBuilder, numIds: Map<String, Int>) {
        val item = store.items.getOrNull(o.optInt("x", -1)) as? TblItem
        val rtl = o.optString("dir") == "rtl"
        val rows = o.getJSONArray("rows")

        // grid size
        var cols = 0
        run {
            val covered = HashMap<Int, Int>() // col -> remaining rows
            for (r in 0 until rows.length()) {
                val cells = rows.getJSONObject(r).getJSONArray("cells")
                var c = 0
                var count = 0
                for (k in 0 until cells.length()) {
                    while ((covered[c] ?: 0) > 0) { c++; count++ }
                    val cell = cells.getJSONObject(k)
                    val cs = max(1, cell.optInt("cs", 1))
                    val rs = max(1, cell.optInt("rs", 1))
                    if (rs > 1) for (q in c until c + cs) covered[q] = rs
                    c += cs
                    count += cs
                }
                while ((covered[c] ?: 0) > 0) { c++; count++ }
                cols = max(cols, count)
                for (key in covered.keys.toList()) covered[key] = (covered[key] ?: 0) - 1
            }
        }
        cols = max(1, cols)
        val origGrid = item?.grid ?: emptyList()
        val gridChanged = origGrid.size != cols
        val gridW: List<Int> = if (!gridChanged && origGrid.all { it > 0 }) origGrid else {
            val total = if (origGrid.sum() > 0) origGrid.sum() else conv.contentWidthTwips
            List(cols) { total / cols }
        }

        // table properties
        val tblPr: Element = if (item != null) {
            (Ox.child(item.el, "tblPr")?.cloneNode(true) as? Element) ?: doc.createElementNS(Ox.W, "w:tblPr")
        } else {
            needTableGrid = true
            val e = doc.createElementNS(Ox.W, "w:tblPr")
            Ox.setVal(Ox.ensure(e, "tblStyle", Ox.TBLPR_ORDER), "TableGrid")
            val tw = Ox.ensure(e, "tblW", Ox.TBLPR_ORDER); Ox.setVal(tw, "0", "w"); Ox.setVal(tw, "auto", "type")
            val b = Ox.ensure(e, "tblBorders", Ox.TBLPR_ORDER)
            for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                b.appendChild(Ox.el(doc, side, "val" to "single", "sz" to "4", "space" to "0", "color" to "auto"))
            }
            val look = Ox.ensure(e, "tblLook", Ox.TBLPR_ORDER)
            Ox.setVal(look, "04A0"); Ox.setVal(look, "1", "firstRow"); Ox.setVal(look, "0", "lastRow")
            Ox.setVal(look, "1", "firstColumn"); Ox.setVal(look, "0", "lastColumn"); Ox.setVal(look, "0", "noHBand"); Ox.setVal(look, "1", "noVBand")
            e
        }
        val hadBidi = Ox.onOff(Ox.child(tblPr, "bidiVisual")) == true
        if (rtl != hadBidi) Ox.setOnOff(tblPr, "bidiVisual", if (rtl) true else null, Ox.TBLPR_ORDER)

        sb.append("<w:tbl>")
        Ox.sortChildren(tblPr, Ox.TBLPR_ORDER)
        Ox.write(tblPr, sb)
        if (!gridChanged && item != null && Ox.child(item.el, "tblGrid") != null) {
            Ox.write(Ox.child(item.el, "tblGrid")!!, sb)
        } else {
            sb.append("<w:tblGrid>")
            gridW.forEach { sb.append("<w:gridCol w:w=\"").append(it).append("\"/>") }
            sb.append("</w:tblGrid>")
        }

        val open = HashMap<Int, Origin>() // grid col -> vertical merge in progress
        for (r in 0 until rows.length()) {
            val row = rows.getJSONObject(r)
            val trItem = store.items.getOrNull(row.optInt("x", -1)) as? TrItem
            sb.append(if (trItem != null) Ox.startTag(trItem.el) else "<w:tr>")
            trItem?.el?.let { tr -> Ox.child(tr, "tblPrEx")?.let { Ox.write(it, sb) }; Ox.child(tr, "trPr")?.let { Ox.write(it, sb) } }
            val cells = row.getJSONArray("cells")
            var c = 0
            fun continuation() {
                while (true) {
                    val og = open[c] ?: return
                    if (og.rowsLeft <= 0) { open.remove(c); return }
                    writeCell(og.cell, og.tcItem, og.span, "continue", sb, numIds, gridW, og.col, gridChanged, true)
                    og.rowsLeft--
                    if (og.rowsLeft <= 0) open.remove(c)
                    c += og.span
                }
            }
            for (k in 0 until cells.length()) {
                continuation()
                val cell = cells.getJSONObject(k)
                val cs = max(1, cell.optInt("cs", 1))
                val rs = max(1, cell.optInt("rs", 1))
                val tcItem = store.items.getOrNull(cell.optInt("x", -1)) as? TcItem
                writeCell(cell, tcItem, cs, if (rs > 1) "restart" else null, sb, numIds, gridW, c, gridChanged, false)
                if (rs > 1) open[c] = Origin(cell, tcItem, c, cs, rs - 1)
                c += cs
            }
            continuation()
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
    }

    private fun writeCell(cell: JSONObject, tcItem: TcItem?, span: Int, vMerge: String?, sb: StringBuilder,
                          numIds: Map<String, Int>, gridW: List<Int>, col: Int, gridChanged: Boolean, continuation: Boolean) {
        val tcPr: Element = (Ox.child(tcItem?.el, "tcPr")?.cloneNode(true) as? Element) ?: doc.createElementNS(Ox.W, "w:tcPr")
        val order = Ox.TCPR_ORDER
        if (span > 1) Ox.setVal(Ox.ensure(tcPr, "gridSpan", order), span.toString()) else Ox.remove(tcPr, "gridSpan")
        Ox.remove(tcPr, "hMerge")
        when (vMerge) {
            "restart" -> Ox.setVal(Ox.ensure(tcPr, "vMerge", order), "restart")
            "continue" -> { val e = Ox.ensure(tcPr, "vMerge", order); e.removeAttributeNS(Ox.W, "val") }
            else -> Ox.remove(tcPr, "vMerge")
        }
        val origSpan = Ox.a(Ox.child(Ox.child(tcItem?.el, "tcPr"), "gridSpan"))?.toIntOrNull() ?: 1
        if (gridChanged || tcItem == null || origSpan != span) {
            var w = 0
            for (q in col until col + span) w += gridW.getOrElse(q) { gridW.lastOrNull() ?: 1000 }
            val e = Ox.ensure(tcPr, "tcW", order)
            Ox.setVal(e, w.toString(), "w"); Ox.setVal(e, "dxa", "type")
        }
        val bg = cell.optString("bg", "")
        if (bg != (tcItem?.bg ?: "")) {
            if (bg.isEmpty()) Ox.remove(tcPr, "shd")
            else {
                val e = Ox.ensure(tcPr, "shd", order)
                Ox.setVal(e, "clear"); Ox.setVal(e, "auto", "color"); Ox.setVal(e, bg, "fill")
                listOf("themeFill", "themeFillShade", "themeFillTint").forEach { e.removeAttributeNS(Ox.W, it) }
            }
        }
        cell.optString("bd", "").takeIf { it.isNotEmpty() }?.let { bd ->
            // sides changed in the app: 1 = thin line, 0 = none, k = as it was
            val v = bd.split(',')
            val b = Ox.ensure(tcPr, "tcBorders", order)
            listOf("top" to 0, "left" to 2, "bottom" to 1, "right" to 3).forEach { (side, i) ->
                val x = v.getOrElse(i) { "k" }
                if (x == "k") return@forEach
                Ox.remove(b, side)
                Ox.remove(b, if (side == "left") "start" else if (side == "right") "end" else side)
                val e = if (x == "1") Ox.el(doc, side, "val" to "single", "sz" to "4", "space" to "0", "color" to "auto")
                        else Ox.el(doc, side, "val" to "nil")
                b.appendChild(e)
            }
            Ox.sortChildren(b, listOf("top", "left", "start", "bottom", "right", "end", "insideH", "insideV", "tl2br", "tr2bl"))
            if (!b.hasChildNodes()) tcPr.removeChild(b)
        }
        cell.optString("va", "").takeIf { it.isNotEmpty() }?.let { va ->
            if (va == "top") Ox.remove(tcPr, "vAlign") else Ox.setVal(Ox.ensure(tcPr, "vAlign", order), va)
        }
        sb.append(if (tcItem != null) Ox.startTag(tcItem.el) else "<w:tc>")
        Ox.sortChildren(tcPr, Ox.TCPR_ORDER)
        if (tcPr.hasChildNodes()) Ox.write(tcPr, sb)
        if (continuation) {
            sb.append("<w:p/>")
        } else {
            val content = cell.optJSONArray("c") ?: JSONArray()
            val inner = StringBuilder()
            writeBlocks(content, inner, numIds, true)
            sb.append(inner)
            if (content.length() == 0 || content.getJSONObject(content.length() - 1).optString("t") != "p") sb.append("<w:p/>")
        }
        sb.append("</w:tc>")
    }

    // ------------------------------------------------------------------ numbering

    private fun writeNumbering(): Map<String, Int> {
        if (listTokens.isEmpty()) return emptyMap()
        var part = pkg.partFor(Ox.REL_NUMBERING)
        val ndoc = part?.let { runCatching { Ox.parse(pkg.bytes(it)!!) }.getOrNull() }
            ?: Ox.parse("<w:numbering xmlns:w=\"${Ox.W}\"/>".toByteArray())
        val root = ndoc.documentElement
        var maxAbs = 0
        var maxNum = 0
        Ox.children(root, "abstractNum").forEach { maxAbs = max(maxAbs, it.getAttributeNS(Ox.W, "abstractNumId").toIntOrNull() ?: 0) }
        Ox.children(root, "num").forEach { maxNum = max(maxNum, it.getAttributeNS(Ox.W, "numId").toIntOrNull() ?: 0) }
        val firstNum = Ox.children(root, "num").firstOrNull()
        val absFor = HashMap<String, Int>()
        for (kind in listKinds) {
            val id = ++maxAbs
            absFor[kind] = id
            val a = ndoc.createElementNS(Ox.W, "w:abstractNum")
            a.setAttributeNS(Ox.W, "w:abstractNumId", id.toString())
            a.appendChild(Ox.el(ndoc, "multiLevelType", "val" to "hybridMultilevel"))
            NewLists.levels(kind).forEachIndexed { l, lv ->
                val e = ndoc.createElementNS(Ox.W, "w:lvl")
                e.setAttributeNS(Ox.W, "w:ilvl", l.toString())
                e.appendChild(Ox.el(ndoc, "start", "val" to "1"))
                e.appendChild(Ox.el(ndoc, "numFmt", "val" to lv.fmt))
                e.appendChild(Ox.el(ndoc, "lvlText", "val" to lv.text))
                e.appendChild(Ox.el(ndoc, "lvlJc", "val" to "left"))
                val pPr = ndoc.createElementNS(Ox.W, "w:pPr")
                pPr.appendChild(Ox.el(ndoc, "ind", "left" to (720 * (l + 1)).toString(), "hanging" to "360"))
                e.appendChild(pPr)
                a.appendChild(e)
            }
            root.insertBefore(a, firstNum)
        }
        val out = HashMap<String, Int>()
        for (token in listTokens.keys) {
            val kind = token.removePrefix("new-").substringBefore('-')
            val abs = absFor[kind] ?: continue
            val id = ++maxNum
            out[token] = id
            val n = ndoc.createElementNS(Ox.W, "w:num")
            n.setAttributeNS(Ox.W, "w:numId", id.toString())
            n.appendChild(Ox.el(ndoc, "abstractNumId", "val" to abs.toString()))
            val ov = ndoc.createElementNS(Ox.W, "w:lvlOverride")
            ov.setAttributeNS(Ox.W, "w:ilvl", "0")
            ov.appendChild(Ox.el(ndoc, "startOverride", "val" to "1"))
            n.appendChild(ov)
            root.appendChild(n)
        }
        if (part == null) {
            part = (if (pkg.mainDir.isEmpty()) "" else pkg.mainDir + "/") + "numbering.xml"
            newRels += DocxPackage.Rel(newRelId(), Ox.REL_NUMBERING, relTarget(part), false)
            overrides[part] = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
        }
        numberingBytes = part to ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(root)).toByteArray(Charsets.UTF_8)
        return out
    }

    private var numberingBytes: Pair<String, ByteArray>? = null
    private val overrides = LinkedHashMap<String, String>()

    // ------------------------------------------------------------------ styles, rels, content types

    private fun writeStyles(entries: LinkedHashMap<String, ByteArray>) {
        numberingBytes?.let { entries[it.first] = it.second }
        val missing = LinkedHashSet<String>()
        usedStyles.filter { it !in conv.styleIds && BuiltinStyles.xml.containsKey(it) }.forEach { missing += it }
        if (needTableGrid && !conv.hasTableGrid) missing += "TableGrid"
        if (missing.isEmpty()) return
        var part = pkg.partFor(Ox.REL_STYLES)
        val sdoc = part?.let { runCatching { Ox.parse(pkg.bytes(it)!!) }.getOrNull() }
            ?: Ox.parse("<w:styles xmlns:w=\"${Ox.W}\"/>".toByteArray())
        val root = sdoc.documentElement
        for (id in missing) {
            val x = BuiltinStyles.xml[id] ?: continue
            val e = Ox.parse(x.replace("%DEF%", conv.defStyle).toByteArray()).documentElement
            root.appendChild(sdoc.importNode(e, true))
        }
        if (part == null) {
            part = (if (pkg.mainDir.isEmpty()) "" else pkg.mainDir + "/") + "styles.xml"
            newRels += DocxPackage.Rel(newRelId(), Ox.REL_STYLES, relTarget(part), false)
            overrides[part] = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
        }
        entries[part] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(root)).toByteArray(Charsets.UTF_8)
    }

    private fun writeRels(entries: LinkedHashMap<String, ByteArray>) {
        if (newRels.isEmpty()) return
        val path = pkg.relsPathFor(main)
        val rdoc = entries[path]?.let { runCatching { Ox.parse(it) }.getOrNull() }
            ?: Ox.parse("<Relationships xmlns=\"${Ox.PKG_REL}\"/>".toByteArray())
        val root = rdoc.documentElement
        for (r in newRels) {
            val e = rdoc.createElementNS(Ox.PKG_REL, "Relationship")
            e.setAttribute("Id", r.id)
            e.setAttribute("Type", r.type)
            e.setAttribute("Target", r.target)
            if (r.external) e.setAttribute("TargetMode", "External")
            root.appendChild(e)
        }
        entries[path] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(root)).toByteArray(Charsets.UTF_8)
    }

    private fun writeContentTypes(entries: LinkedHashMap<String, ByteArray>) {
        if (newMediaExt.isEmpty() && overrides.isEmpty()) return
        val path = "[Content_Types].xml"
        val cdoc = entries[path]?.let { runCatching { Ox.parse(it) }.getOrNull() } ?: return
        val root = cdoc.documentElement
        val defaults = Ox.kids(root).filter { it.localName == "Default" }.map { it.getAttribute("Extension").lowercase() }.toSet()
        val mimes = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "bmp" to "image/bmp", "webp" to "image/webp")
        for (ext in newMediaExt) {
            if (ext in defaults) continue
            val e = cdoc.createElementNS(Ox.CT, "Default")
            e.setAttribute("Extension", ext)
            e.setAttribute("ContentType", mimes[ext] ?: "image/$ext")
            root.insertBefore(e, root.firstChild)
        }
        val existing = Ox.kids(root).filter { it.localName == "Override" }.map { it.getAttribute("PartName") }.toSet()
        for ((part, type) in overrides) {
            val name = "/$part"
            if (name in existing) continue
            val e = cdoc.createElementNS(Ox.CT, "Override")
            e.setAttribute("PartName", name)
            e.setAttribute("ContentType", type)
            root.appendChild(e)
        }
        entries[path] = ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" + Ox.xml(root)).toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Prefix of pictures the app adds to a document. */
        const val NEW_MEDIA = "word/media/pa_"

        @Suppress("unused")
        fun px(t: Int) = (t / 15.0).roundToInt()
    }
}
