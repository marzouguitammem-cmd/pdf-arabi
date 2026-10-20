package com.tammem.pdfarabi.word

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Run (character) properties. Sizes are half-points, colours are RRGGBB. */
class RP {
    var b: Boolean? = null; var bCs: Boolean? = null
    var i: Boolean? = null; var iCs: Boolean? = null
    var u: String? = null
    var strike: Boolean? = null; var dstrike: Boolean? = null
    var va: String? = null
    var sz: Int? = null; var szCs: Int? = null
    var f: String? = null; var fCs: String? = null
    var color: String? = null
    var hl: String? = null
    var shd: String? = null
    var caps: Boolean? = null; var smallCaps: Boolean? = null; var vanish: Boolean? = null
    var rtl: Boolean? = null; var cs: Boolean? = null
    var spacing: Int? = null

    fun merge(o: RP): RP {
        o.b?.let { b = it }; o.bCs?.let { bCs = it }; o.i?.let { i = it }; o.iCs?.let { iCs = it }
        o.u?.let { u = it }; o.strike?.let { strike = it }; o.dstrike?.let { dstrike = it }; o.va?.let { va = it }
        o.sz?.let { sz = it }; o.szCs?.let { szCs = it }; o.f?.let { f = it }; o.fCs?.let { fCs = it }
        o.color?.let { color = it }; o.hl?.let { hl = it }; o.shd?.let { shd = it }; o.caps?.let { caps = it }
        o.smallCaps?.let { smallCaps = it }; o.vanish?.let { vanish = it }; o.rtl?.let { rtl = it }; o.cs?.let { cs = it }
        o.spacing?.let { spacing = it }
        return this
    }

    fun copy(): RP = RP().merge(this)
}

/** Paragraph properties (twips). */
class PP {
    var st: String? = null
    var jc: String? = null
    var bidi: Boolean? = null
    var numId: String? = null
    var ilvl: Int? = null
    var indS: Int? = null; var indE: Int? = null; var indF: Int? = null
    var line: Int? = null; var lineRule: String? = null
    var before: Int? = null; var after: Int? = null
    var shd: String? = null
    var borders: String? = null

    fun merge(o: PP): PP {
        o.st?.let { st = it }; o.jc?.let { jc = it }; o.bidi?.let { bidi = it }; o.numId?.let { numId = it }
        o.ilvl?.let { ilvl = it }; o.indS?.let { indS = it }; o.indE?.let { indE = it }; o.indF?.let { indF = it }
        o.line?.let { line = it }; o.lineRule?.let { lineRule = it }; o.before?.let { before = it }; o.after?.let { after = it }
        o.shd?.let { shd = it }; o.borders?.let { borders = it }
        return this
    }

    fun copy(): PP = PP().merge(this)
}

class StyleDef(val id: String, val type: String, val name: String, val basedOn: String?, val pPr: Element?, val rPr: Element?,
               val tblPr: Element?, val isDefault: Boolean, val quick: Boolean)

class NumLevel(val fmt: String, val text: String, val start: Int, val indS: Int?, val indF: Int?)
class NumDef(val numId: String, val abs: String, val levels: List<NumLevel>)

// ---- items kept so the writer can rebuild untouched parts exactly
class PItem(val el: Element, val hadNumPr: Boolean)
class RItem(val el: Element)
class ImgItem(val run: Element, val drawing: Element, val wPx: Int, val hPx: Int)
/**
 * Untouched XML. [provides] names the bookmark halves and field parts it holds; it is written back only when all of
 * [requires] is still in the document, so a bookmark or a field cut in two by an edit never reaches the file.
 */
class RawItem(val xml: String, val provides: List<String> = emptyList(), val requires: List<String> = emptyList())
class LinkItem(val el: Element)
class TblItem(val el: Element, val grid: List<Int>)
class TrItem(val el: Element)
class TcItem(val el: Element, val bg: String)

class Store {
    val items = ArrayList<Any>()
    val origP = HashMap<Int, Map<String, String?>>()
    val origR = HashMap<Int, Map<String, String?>>()
    fun add(o: Any): Int { items += o; return items.size - 1 }
}

class Converted(
    val html: String,
    val store: Store,
    val doc: Document,
    val root: Element,
    val background: Element?,
    val finalSectPr: Element?,
    val defStyle: String,
    val styleIds: Set<String>,
    val styleBase: Map<String, Map<String, String?>>,
    val contentWidthTwips: Int,
    val rtl: Boolean,
    val maxDocPrId: Int,
    val hasTableGrid: Boolean,
    val quickStyles: List<Pair<String, String>>,
    val fonts: List<String>
)

/** Texts shown inside the page (page-break label, style names), in the app language. */
class WordStrings(val pageBreak: String, val styleNames: Map<String, String>, val chart: String, val shape: String, val equation: String)

/** Built-in style definitions added to a document when the user applies them and the file lacks them. */
object BuiltinStyles {
    val xml = linkedMapOf(
        "Title" to """<w:style xmlns:w="${Ox.W}" w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="%DEF%"/><w:next w:val="%DEF%"/><w:qFormat/><w:pPr><w:spacing w:before="120" w:after="240"/><w:jc w:val="center"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="1F3864"/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr></w:style>""",
        "Heading1" to """<w:style xmlns:w="${Ox.W}" w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="%DEF%"/><w:next w:val="%DEF%"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="2E74B5"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>""",
        "Heading2" to """<w:style xmlns:w="${Ox.W}" w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="%DEF%"/><w:next w:val="%DEF%"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="2E74B5"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>""",
        "Heading3" to """<w:style xmlns:w="${Ox.W}" w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="%DEF%"/><w:next w:val="%DEF%"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val="1F4D78"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style>""",
        "TableGrid" to """<w:style xmlns:w="${Ox.W}" w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:basedOn w:val="TableNormal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr></w:style>"""
    )
}

/** Lists the user creates in the app (the writer adds matching definitions to numbering.xml). */
object NewLists {
    class Lvl(val fmt: String, val text: String)

    fun levels(kind: String): List<Lvl> = (0 until 9).map { l ->
        when (kind) {
            "bullet" -> Lvl("bullet", listOf("•", "◦", "▪")[l % 3])
            "abjad" -> if (l % 2 == 0) Lvl("arabicAbjad", "%${l + 1})") else Lvl("decimal", "%${l + 1}.")
            else -> when (l % 3) {
                0 -> Lvl("decimal", "%${l + 1}.")
                1 -> Lvl("lowerLetter", "%${l + 1}.")
                else -> Lvl("lowerRoman", "%${l + 1}.")
            }
        }
    }

    val kinds = listOf("bullet", "decimal", "abjad")
}

class DocxReader(private val pkg: DocxPackage, private val strings: WordStrings) {

    private val store = Store()
    private val styles = LinkedHashMap<String, StyleDef>()
    private var defStyle = ""
    private val defRP = RP()
    private val defPP = PP()
    private val nums = HashMap<String, NumDef>()
    private var themeMajor = "Times New Roman"; private var themeMinor = "Calibri"
    private var themeMajorCs = "Times New Roman"; private var themeMinorCs = "Arial"
    private var curPart = pkg.mainPart
    /** A field (table of contents, …) whose start was seen but whose end is in a later run or paragraph. */
    private class OpenField(val id: Int) { val parts = ArrayList<String>(); var inResult = false; var depth = 0 }
    /** Fields still open, and bookmarks found between paragraphs, waiting for the next paragraph. */
    private class FlowState { val open = ArrayList<OpenField>(); val pendingMarks = ArrayList<Element>() }
    private var flow = FlowState()
    private var fieldSeq = 0
    private val goBack = HashSet<String>()
    private var noteCounter = 0
    private var maxDocPr = 0
    private var rtlVotes = 0
    private var ltrVotes = 0
    private val usedFonts = LinkedHashSet<String>()

    // ------------------------------------------------------------------ entry

    fun convert(view: String): Converted {
        readTheme()
        readStyles()
        readNumbering()
        val docBytes = pkg.bytes(pkg.mainPart) ?: error("no document part")
        val doc = Ox.parse(docBytes)
        val root = doc.documentElement
        val bodyEl = Ox.child(root, "body") ?: error("no body")
        val background = Ox.child(root, "background")
        Ox.findAll(root, "docPr", Ox.WP).forEach { e -> e.getAttribute("id").toIntOrNull()?.let { maxDocPr = max(maxDocPr, it) } }

        val sb = StringBuilder()
        blocks(bodyEl, sb, false)
        val sect = Ox.child(bodyEl, "sectPr")

        // page geometry
        val pgSz = Ox.child(sect, "pgSz")
        val pgMar = Ox.child(sect, "pgMar")
        val pw = Ox.twips(pgSz, "w") ?: 11906
        val ph = Ox.twips(pgSz, "h") ?: 16838
        val mt = Ox.twips(pgMar, "top")?.let { kotlin.math.abs(it) } ?: 1440
        val mb = Ox.twips(pgMar, "bottom")?.let { kotlin.math.abs(it) } ?: 1440
        val ml = Ox.twips(pgMar, "left") ?: Ox.twips(pgMar, "start") ?: 1440
        val mr = Ox.twips(pgMar, "right") ?: Ox.twips(pgMar, "end") ?: 1440
        val px = { t: Int -> (t / 15.0).roundToInt() }

        val rtl = rtlVotes >= ltrVotes && rtlVotes > 0 || (rtlVotes == 0 && ltrVotes == 0 && Locale.getDefault().language == "ar")

        val hdr = headerFooter(sect, "headerReference", Ox.REL_HEADER)
        val ftr = headerFooter(sect, "footerReference", Ox.REL_FOOTER)

        val info = JSONObject()
        info.put("page", JSONObject().put("w", px(pw)).put("h", px(ph)).put("mt", px(mt)).put("mb", px(mb)).put("ml", px(ml)).put("mr", px(mr)))
        info.put("defStyle", defStyle)
        info.put("rtl", rtl)
        info.put("view", view)
        info.put("defaults", rpJson(defRP, null))
        val stylesJson = JSONObject()
        val base = HashMap<String, Map<String, String?>>()
        val quick = JSONArray()
        allParagraphStyles().forEach { id ->
            val (rp, pp) = styleEffective(id)
            val j = rpJson(rp, pp)
            j.put("name", styleName(id))
            stylesJson.put(id, j)
            base[id] = pBase(pp, id)
        }
        info.put("styles", stylesJson)
        quickStyles().forEach { quick.put(it) }
        info.put("quick", quick)
        val numsJson = JSONObject()
        for ((id, n) in nums) {
            val levels = JSONArray()
            n.levels.forEach { l -> levels.put(JSONObject().put("f", l.fmt).put("t", l.text).put("s", l.start)) }
            numsJson.put(id, JSONObject().put("abs", n.abs).put("levels", levels))
        }
        info.put("nums", numsJson)
        val newNums = JSONObject()
        NewLists.kinds.forEach { k ->
            val levels = JSONArray()
            NewLists.levels(k).forEach { l -> levels.put(JSONObject().put("f", l.fmt).put("t", l.text).put("s", 1)) }
            newNums.put(k, JSONObject().put("kind", k).put("levels", levels))
        }
        info.put("newNums", newNums)
        info.put("strings", JSONObject().put("pageBreak", strings.pageBreak))
        info.put("fonts", JSONArray(usedFonts.toList()))

        val css = "#paper{--pw:${px(pw)}px;--ph:${px(ph)}px;--mt:${px(mt)}px;--mb:${px(mb)}px;--ml:${px(ml)}px;--mr:${px(mr)}px;}"
        val dir = if (rtl) "rtl" else "ltr"
        val html = StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=0.3, maximum-scale=6, user-scalable=yes\">")
        html.append("<link rel=\"stylesheet\" href=\"/editor/editor.css\"><style>").append(css).append("</style></head>")
        html.append("<body class=\"view-").append(if (view == "mobile") "mobile" else "page").append("\"><div id=\"paper\">")
        html.append("<div id=\"hdr\" class=\"hf\" dir=\"").append(dir).append("\">").append(hdr).append("</div>")
        html.append("<div id=\"body\" dir=\"").append(dir).append("\" spellcheck=\"false\" autocapitalize=\"sentences\">")
        html.append(sb)
        html.append("</div><div id=\"ftr\" class=\"hf\" dir=\"").append(dir).append("\">").append(ftr).append("</div></div>")
        html.append("<script>window.DOCINFO=").append(info.toString().replace("</", "<\\/")).append(";</script>")
        html.append("<script src=\"/editor/editor.js\"></script></body></html>")

        return Converted(
            html.toString(), store, doc, root, background, sect, defStyle, realIds.toSet(), base,
            max(1440, pw - ml - mr), rtl, maxDocPr, realIds.contains("TableGrid"),
            quickStyles().map { it to styleName(it) }, usedFonts.toList()
        )
    }

    // ------------------------------------------------------------------ theme, styles, numbering

    private fun readTheme() {
        val part = pkg.partFor(Ox.REL_THEME) ?: return
        val doc = runCatching { Ox.parse(pkg.bytes(part)!!) }.getOrNull() ?: return
        fun font(group: String): Pair<String?, String?> {
            val g = Ox.find(doc.documentElement, group, Ox.A) ?: return null to null
            val latin = Ox.child(g, "latin", Ox.A)?.getAttribute("typeface")?.ifEmpty { null }
            var cs = Ox.child(g, "cs", Ox.A)?.getAttribute("typeface")?.ifEmpty { null }
            if (cs == null) cs = Ox.children(g, "font", Ox.A).firstOrNull { it.getAttribute("script") == "Arab" }?.getAttribute("typeface")?.ifEmpty { null }
            return latin to cs
        }
        val (mj, mjCs) = font("majorFont")
        val (mn, mnCs) = font("minorFont")
        mj?.let { themeMajor = it }; mn?.let { themeMinor = it }
        mjCs?.let { themeMajorCs = it }; mnCs?.let { themeMinorCs = it }
    }

    private fun themeFont(v: String?): String? = when (v) {
        null, "" -> null
        "majorHAnsi", "majorAscii", "majorEastAsia" -> themeMajor
        "minorHAnsi", "minorAscii", "minorEastAsia" -> themeMinor
        "majorBidi" -> themeMajorCs
        "minorBidi" -> themeMinorCs
        else -> null
    }

    private fun readStyles() {
        val part = pkg.partFor(Ox.REL_STYLES)
        val doc = part?.let { runCatching { Ox.parse(pkg.bytes(it)!!) }.getOrNull() }
        val root = doc?.documentElement
        if (root != null) {
            val dd = Ox.child(root, "docDefaults")
            parseRPr(Ox.child(Ox.child(dd, "rPrDefault"), "rPr"), defRP)
            parsePPr(Ox.child(Ox.child(dd, "pPrDefault"), "pPr"), defPP)
            for (s in Ox.children(root, "style")) {
                val id = s.getAttributeNS(Ox.W, "styleId").ifEmpty { s.getAttribute("w:styleId") }
                if (id.isEmpty()) continue
                val type = s.getAttributeNS(Ox.W, "type").ifEmpty { "paragraph" }
                val def = s.getAttributeNS(Ox.W, "default").lowercase() in setOf("1", "true", "on")
                styles[id] = StyleDef(id, type, Ox.a(Ox.child(s, "name")) ?: id, Ox.a(Ox.child(s, "basedOn")),
                    Ox.child(s, "pPr"), Ox.child(s, "rPr"), Ox.child(s, "tblPr"), def, Ox.child(s, "qFormat") != null)
                if (type == "paragraph" && def) defStyle = id
            }
        }
        if (defStyle.isEmpty()) defStyle = when {
            styles.containsKey("Normal") -> "Normal"
            else -> styles.values.firstOrNull { it.type == "paragraph" }?.id ?: "Normal"
        }
        if (defRP.sz == null) defRP.sz = 20
        if (defRP.szCs == null) defRP.szCs = defRP.sz
        if (defRP.f == null) defRP.f = "Times New Roman"
        if (defRP.fCs == null) defRP.fCs = defRP.f
        realIds.addAll(styles.keys)
        // Built-ins the user can apply even if the file does not define them yet.
        for ((id, x) in BuiltinStyles.xml) {
            if (styles.containsKey(id)) continue
            val e = Ox.parse(x.replace("%DEF%", defStyle).toByteArray()).documentElement
            val type = e.getAttributeNS(Ox.W, "type")
            styles[id] = StyleDef(id, type, Ox.a(Ox.child(e, "name")) ?: id, Ox.a(Ox.child(e, "basedOn")), Ox.child(e, "pPr"),
                Ox.child(e, "rPr"), Ox.child(e, "tblPr"), false, true)
        }
    }

    private val realIds = HashSet<String>()

    private fun chain(id: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var cur = id
        var guard = 0
        while (cur != null && guard++ < 20) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    /** Defaults + paragraph style chain (fonts and paragraph layout). */
    private fun styleEffective(id: String): Pair<RP, PP> {
        val rp = defRP.copy()
        val pp = defPP.copy()
        for (s in chain(id)) {
            parseRPr(s.rPr, rp)
            parsePPr(s.pPr, pp)
        }
        return rp to pp
    }

    private fun charStyle(id: String?): RP {
        val rp = RP()
        for (s in chain(id)) parseRPr(s.rPr, rp)
        return rp
    }

    private fun allParagraphStyles(): List<String> = styles.values.filter { it.type == "paragraph" }.map { it.id }

    private fun quickStyles(): List<String> {
        val wanted = listOf(defStyle, "Title", "Subtitle", "Heading1", "Heading2", "Heading3", "Heading4")
        val out = LinkedHashSet<String>()
        wanted.forEach { if (styles[it]?.type == "paragraph") out += it }
        styles.values.filter { it.type == "paragraph" && it.quick }.take(16).forEach { out += it.id }
        return out.toList()
    }

    private fun styleName(id: String): String {
        strings.styleNames[id]?.let { return it }
        val n = styles[id]?.name ?: id
        val m = Regex("(?i)^heading (\\d)$").find(n)
        if (m != null) strings.styleNames["Heading${m.groupValues[1]}"]?.let { return it }
        return n
    }

    private fun readNumbering() {
        val part = pkg.partFor(Ox.REL_NUMBERING) ?: return
        val doc = runCatching { Ox.parse(pkg.bytes(part)!!) }.getOrNull() ?: return
        val root = doc.documentElement
        val abstracts = HashMap<String, Element>()
        Ox.children(root, "abstractNum").forEach { abstracts[it.getAttributeNS(Ox.W, "abstractNumId")] = it }
        for (n in Ox.children(root, "num")) {
            val id = n.getAttributeNS(Ox.W, "numId")
            val absId = Ox.a(Ox.child(n, "abstractNumId")) ?: continue
            var abs = abstracts[absId] ?: continue
            // numStyleLink -> follow the style's numbering
            Ox.a(Ox.child(abs, "numStyleLink"))?.let { link ->
                val st = styles[link]
                val nid = Ox.a(Ox.child(Ox.child(st?.pPr, "numPr"), "numId"))
                val other = Ox.children(root, "num").firstOrNull { it.getAttributeNS(Ox.W, "numId") == nid }
                val oa = Ox.a(Ox.child(other, "abstractNumId"))
                if (oa != null) abstracts[oa]?.let { abs = it }
            }
            val overrides = HashMap<Int, Int>()
            Ox.children(n, "lvlOverride").forEach { o ->
                val l = o.getAttributeNS(Ox.W, "ilvl").toIntOrNull() ?: return@forEach
                Ox.a(Ox.child(o, "startOverride"))?.toIntOrNull()?.let { overrides[l] = it }
            }
            val levels = (0 until 9).map { l ->
                val lvl = Ox.children(abs, "lvl").firstOrNull { it.getAttributeNS(Ox.W, "ilvl") == l.toString() }
                val fmt = Ox.a(Ox.child(lvl, "numFmt")) ?: "decimal"
                var text = Ox.a(Ox.child(lvl, "lvlText")) ?: "%${l + 1}."
                if (fmt == "bullet") {
                    val font = Ox.child(Ox.child(lvl, "rPr"), "rFonts")?.let { Ox.a(it, "ascii") ?: Ox.a(it, "hAnsi") }
                    text = bulletChar(text, font)
                }
                val start = overrides[l] ?: Ox.a(Ox.child(lvl, "start"))?.toIntOrNull() ?: 1
                val pp = PP()
                parsePPr(Ox.child(lvl, "pPr"), pp)
                NumLevel(fmt, text, start, pp.indS, pp.indF)
            }
            nums[id] = NumDef(id, absId, levels)
        }
    }

    private fun bulletChar(t: String, font: String?): String {
        if (t.isEmpty()) return "•"
        val c = t[0]
        val f = (font ?: "").lowercase()
        val code = c.code
        val sym = if (code in 0xF000..0xF0FF) code - 0xF000 else code
        if (f.contains("wingdings")) {
            return when (sym) {
                0xA7, 0x6E -> "▪"; 0xD8 -> "➢"; 0xFC -> "✓"; 0x76 -> "❖"; 0xA8, 0x6F, 0x71 -> "□"; 0x9F, 0x6C -> "●"
                0xFB -> "✗"; 0xE0, 0xE8 -> "➔"; 0xAB -> "★"; 0x77 -> "◆"; else -> "•"
            }
        }
        if (f.contains("symbol")) return when (sym) { 0xB7 -> "•"; 0xA8 -> "♦"; 0x2D -> "–"; else -> "•" }
        if (code in 0xF000..0xF0FF) return "•"
        if (c == 'o' && f.contains("courier")) return "◦"
        return t
    }

    private fun numLevel(numId: String?, ilvl: Int?): NumLevel? {
        if (numId == null || numId == "0") return null
        return nums[numId]?.levels?.getOrNull(ilvl ?: 0)
    }

    // ------------------------------------------------------------------ property parsing

    private fun parseRPr(e: Element?, rp: RP) {
        if (e == null) return
        for (c in Ox.kids(e)) {
            if (c.namespaceURI != Ox.W) continue
            when (c.localName) {
                "rFonts" -> {
                    val ascii = themeFont(Ox.a(c, "asciiTheme")) ?: Ox.a(c, "ascii")?.ifEmpty { null } ?: Ox.a(c, "hAnsi")?.ifEmpty { null }
                    val cs = themeFont(Ox.a(c, "cstheme")) ?: Ox.a(c, "cs")?.ifEmpty { null }
                    ascii?.let { rp.f = it }
                    cs?.let { rp.fCs = it }
                }
                "b" -> rp.b = Ox.onOff(c)
                "bCs" -> rp.bCs = Ox.onOff(c)
                "i" -> rp.i = Ox.onOff(c)
                "iCs" -> rp.iCs = Ox.onOff(c)
                "caps" -> rp.caps = Ox.onOff(c)
                "smallCaps" -> rp.smallCaps = Ox.onOff(c)
                "strike" -> rp.strike = Ox.onOff(c)
                "dstrike" -> rp.dstrike = Ox.onOff(c)
                "vanish" -> rp.vanish = Ox.onOff(c)
                "color" -> Ox.a(c)?.let { v -> rp.color = if (v.equals("auto", true) || v.length != 6) null else v.uppercase() }
                "sz" -> Ox.a(c)?.toDoubleOrNull()?.let { rp.sz = it.roundToInt() }
                "szCs" -> Ox.a(c)?.toDoubleOrNull()?.let { rp.szCs = it.roundToInt() }
                "highlight" -> Ox.a(c)?.let { v -> rp.hl = if (v == "none") null else v }
                "u" -> rp.u = Ox.a(c) ?: "single"
                "shd" -> Ox.a(c, "fill")?.let { v -> if (!v.equals("auto", true) && v.length == 6) rp.shd = v.uppercase() }
                "vertAlign" -> rp.va = when (Ox.a(c)) { "superscript" -> "sup"; "subscript" -> "sub"; else -> "" }
                "rtl" -> rp.rtl = Ox.onOff(c)
                "cs" -> rp.cs = Ox.onOff(c)
                "spacing" -> Ox.a(c)?.toIntOrNull()?.let { rp.spacing = it }
            }
        }
    }

    private fun parsePPr(e: Element?, pp: PP) {
        if (e == null) return
        for (c in Ox.kids(e)) {
            if (c.namespaceURI != Ox.W) continue
            when (c.localName) {
                "pStyle" -> pp.st = Ox.a(c)
                "jc" -> pp.jc = when (Ox.a(c)) {
                    "center" -> "center"
                    "right", "end" -> "end"
                    "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> "justify"
                    else -> "start"
                }
                "bidi" -> pp.bidi = Ox.onOff(c)
                "numPr" -> {
                    Ox.a(Ox.child(c, "numId"))?.let { pp.numId = it }
                    Ox.a(Ox.child(c, "ilvl"))?.toIntOrNull()?.let { pp.ilvl = it }
                }
                "ind" -> {
                    (Ox.twips(c, "start") ?: Ox.twips(c, "left"))?.let { pp.indS = it }
                    (Ox.twips(c, "end") ?: Ox.twips(c, "right"))?.let { pp.indE = it }
                    Ox.twips(c, "hanging")?.let { pp.indF = -it }
                    if (!Ox.has(c, "hanging")) Ox.twips(c, "firstLine")?.let { pp.indF = it }
                }
                "spacing" -> {
                    Ox.twips(c, "before")?.let { pp.before = it }
                    Ox.twips(c, "after")?.let { pp.after = it }
                    Ox.twips(c, "line")?.let { pp.line = it; pp.lineRule = Ox.a(c, "lineRule") ?: "auto" }
                }
                "shd" -> Ox.a(c, "fill")?.let { v -> if (!v.equals("auto", true) && v.length == 6) pp.shd = v.uppercase() }
                "pBdr" -> pp.borders = bordersCss(c, mapOf("top" to "border-top", "bottom" to "border-bottom", "left" to "border-inline-start",
                    "start" to "border-inline-start", "right" to "border-inline-end", "end" to "border-inline-end"))
            }
        }
    }

    private fun borderCss(b: Element?): String? {
        if (b == null) return null
        val v = Ox.a(b) ?: return null
        if (v == "nil" || v == "none") return "none"
        val sz = Ox.a(b, "sz")?.toIntOrNull() ?: 4
        val col = Ox.a(b, "color")?.takeIf { it.length == 6 && !it.equals("auto", true) } ?: "000000"
        var w = max(0.5, sz / 8.0)
        val style = when {
            v.startsWith("double") || v.contains("Double") -> { w = max(w, 2.0); "double" }
            v.startsWith("dot") && !v.startsWith("dotDash") -> "dotted"
            v.startsWith("dash") || v.startsWith("dotDash") -> "dashed"
            v.startsWith("thick") -> { w = max(w, 1.5); "solid" }
            else -> "solid"
        }
        return String.format(Locale.US, "%.2fpt %s #%s", w, style, col)
    }

    private fun bordersCss(e: Element, map: Map<String, String>): String {
        val sb = StringBuilder()
        for ((side, prop) in map) Ox.child(e, side)?.let { b -> borderCss(b)?.let { sb.append(prop).append(':').append(it).append(';') } }
        return sb.toString()
    }

    // ------------------------------------------------------------------ data attributes

    private fun rpJson(rp: RP, pp: PP?): JSONObject {
        val j = JSONObject()
        rp.sz?.let { j.put("sz", it) }
        rp.szCs?.let { j.put("szCs", it) }
        rp.f?.let { j.put("f", it); usedFonts += it }
        rp.fCs?.let { j.put("fCs", it); usedFonts += it }
        rp.b?.let { j.put("b", it) }
        rp.bCs?.let { j.put("bCs", it) }
        rp.i?.let { j.put("i", it) }
        rp.iCs?.let { j.put("iCs", it) }
        rp.color?.let { j.put("c", it) }
        if (pp != null) {
            j.put("al", pp.jc ?: "start")
            pp.bidi?.let { j.put("dir", if (it) "rtl" else "ltr") }
            j.put("ind", "${pp.indS ?: 0},${pp.indE ?: 0},${pp.indF ?: 0}")
            if (pp.line != null) j.put("ls", "${pp.line},${pp.lineRule ?: "auto"}")
            j.put("sb", (pp.before ?: 0).toString())
            j.put("sa", (pp.after ?: 0).toString())
        }
        return j
    }

    /** What a fresh paragraph in this style looks like (used to keep new paragraphs free of redundant formatting). */
    private fun pBase(pp: PP, id: String): Map<String, String?> = mapOf(
        "st" to id,
        "al" to (pp.jc ?: "start"),
        "dir" to if (pp.bidi == true) "rtl" else "ltr",
        "num" to pp.numId?.takeIf { it != "0" },
        "lvl" to if (pp.numId != null && pp.numId != "0") (pp.ilvl ?: 0).toString() else null,
        "ind" to "${pp.indS ?: 0},${pp.indE ?: 0},${pp.indF ?: 0}",
        "ls" to if (pp.line != null) "${pp.line},${pp.lineRule ?: "auto"}" else "",
        "sb" to (pp.before ?: 0).toString(),
        "sa" to (pp.after ?: 0).toString()
    )

    private fun attr(sb: StringBuilder, k: String, v: String?) {
        if (v == null) return
        sb.append(' ').append(k).append("=\"").append(Ox.esc(v, true)).append('"')
    }

    private fun hasArabic(s: CharSequence): Boolean = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }

    // ------------------------------------------------------------------ blocks

    private fun blocks(container: Element, sb: StringBuilder, display: Boolean) {
        val startLen = sb.length
        blockKids(container, sb, display)
        // bookmarks after the last paragraph (Word often ends one there) go at the end of that paragraph
        if (!display && flow.pendingMarks.isNotEmpty()) {
            val idx = sb.lastIndexOf("</p>")
            if (idx >= startLen) {
                val marks = StringBuilder()
                flow.pendingMarks.forEach { bookmark(it, marks) }
                sb.insert(idx, marks)
                flow.pendingMarks.clear()
            }
        }
    }

    private fun blockKids(container: Element, sb: StringBuilder, display: Boolean) {
        for (c in Ox.kids(container)) {
            if (c.namespaceURI == Ox.W) {
                when (c.localName) {
                    "p" -> paragraph(c, sb, display)
                    "tbl" -> table(c, sb, display)
                    "sdt" -> Ox.child(c, "sdtContent")?.let { blocks(it, sb, display) }
                    "customXml", "smartTag" -> blocks(c, sb, display)
                    "bookmarkStart", "bookmarkEnd" -> if (!display) flow.pendingMarks += c
                    "sectPr", "tcPr", "trPr", "tblPr", "tblGrid", "tblPrEx", "proofErr", "permStart", "permEnd", "commentRangeStart",
                    "commentRangeEnd", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd" -> {}
                    "ins", "moveTo" -> blocks(c, sb, display)
                    "del", "moveFrom" -> {}
                    else -> rawBlock(c, sb, display)
                }
            } else if (c.namespaceURI == Ox.MC && c.localName == "AlternateContent") {
                rawBlock(c, sb, display)
            }
        }
    }

    private fun rawBlock(c: Element, sb: StringBuilder, display: Boolean) {
        if (display) return
        val xml = Ox.xml(c)
        val x = store.add(RawItem(xml, marksIn(xml)))
        sb.append("<div class=\"rawb ph\" data-x=\"").append(x).append("\" contenteditable=\"false\">")
            .append(Ox.esc(strings.shape)).append("</div>")
    }

    private fun paragraph(p: Element, sb: StringBuilder, display: Boolean) {
        val pPr = Ox.child(p, "pPr")
        val direct = PP()
        parsePPr(pPr, direct)
        val st = direct.st?.takeIf { styles[it]?.type == "paragraph" } ?: defStyle
        val spp = styleEffective(st).second
        val eff = spp.copy()
        // numbering: direct numPr overrides the style's
        val numId = direct.numId ?: spp.numId
        val ilvl = direct.ilvl ?: spp.ilvl ?: 0
        val lvl = numLevel(numId, ilvl)
        if (lvl != null) {
            lvl.indS?.let { eff.indS = it }
            lvl.indF?.let { eff.indF = it }
        }
        eff.merge(direct)
        eff.st = st
        eff.numId = if (lvl != null) numId else null
        eff.ilvl = if (lvl != null) ilvl else null
        val dir = if (eff.bidi == true) "rtl" else "ltr"
        if (dir == "rtl") rtlVotes++ else ltrVotes++
        val base = pBase(eff, st)

        val x = if (display) -1 else store.add(PItem(p, Ox.child(pPr, "numPr") != null))
        if (x >= 0) store.origP[x] = base
        val xs = StringBuilder()
        eff.shd?.let { xs.append("background-color:#").append(it).append(';') }
        eff.borders?.let { xs.append(it).append("padding-inline:2pt;") }

        sb.append("<p")
        if (x >= 0) attr(sb, "data-x", x.toString())
        attr(sb, "data-st", st)
        attr(sb, "data-al", base["al"])
        attr(sb, "dir", dir)
        if (base["num"] != null) {
            attr(sb, "data-num", base["num"])
            attr(sb, "data-lvl", base["lvl"])
        }
        attr(sb, "data-ind", base["ind"])
        base["ls"]?.takeIf { it.isNotEmpty() }?.let { attr(sb, "data-ls", it) }
        attr(sb, "data-sb", base["sb"])
        attr(sb, "data-sa", base["sa"])
        if (xs.isNotEmpty()) attr(sb, "data-xs", xs.toString())
        sb.append('>')
        val start = sb.length
        if (!display && flow.pendingMarks.isNotEmpty()) {
            flow.pendingMarks.forEach { bookmark(it, sb) }
            flow.pendingMarks.clear()
        }
        inline(Ox.kids(p), sb, display, dir)
        if (sb.length == start) sb.append("<br>")
        sb.append("</p>")
    }

    // ------------------------------------------------------------------ inline content

    /** A bookmark (target of a table of contents entry or a cross-reference): kept as an invisible mark. */
    private fun bookmark(c: Element, sb: StringBuilder) {
        val id = Ox.a(c, "id") ?: return
        val start = c.localName == "bookmarkStart"
        if (start && Ox.a(c, "name") == "_GoBack") { goBack += id; return }
        if (!start && id in goBack) return
        val item = if (start) RawItem(Ox.xml(c), listOf("bs:$id"), listOf("be:$id"))
                   else RawItem(Ox.xml(c), listOf("be:$id"), listOf("bs:$id"))
        sb.append("<span class=\"raw bm\" data-k=\"raw\" data-x=\"").append(store.add(item)).append("\" contenteditable=\"false\"></span>")
    }

    /** One run of a field's code (or its end mark) that belongs to a field spanning several runs or paragraphs. */
    private fun fieldPart(f: OpenField, c: Element, sb: StringBuilder, display: Boolean) {
        if (display) return
        val token = "f${f.id}:${f.parts.size}"
        f.parts += token
        val xml = Ox.xml(c)
        val item = RawItem(xml, listOf(token) + marksIn(xml), f.parts)
        sb.append("<span class=\"raw bm\" data-k=\"raw\" data-x=\"").append(store.add(item)).append("\" contenteditable=\"false\"></span>")
    }

    private val markRe = Regex("""<(?:\w+:)?bookmark(Start|End)\b[^>]*?\s(?:\w+:)?id="([^"]*)"""")

    /** Bookmark halves inside a piece of XML kept as it is. */
    private fun marksIn(xml: String): List<String> {
        if (!xml.contains("bookmark")) return emptyList()
        return markRe.findAll(xml).map { (if (it.groupValues[1] == "Start") "bs:" else "be:") + it.groupValues[2] }.toList()
    }

    /** Text boxes, headers and footers are read on their own, without the body's open fields. */
    private inline fun <T> nested(block: () -> T): T {
        val saved = flow
        flow = FlowState()
        try { return block() } finally { flow = saved }
    }

    private fun hasFld(r: Element, type: String): Boolean =
        Ox.children(r, "fldChar").any { Ox.a(it, "fldCharType") == type }

    private fun inline(kids: List<Element>, sb: StringBuilder, display: Boolean, pDir: String) {
        var i = 0
        while (i < kids.size) {
            val c = kids[i]
            val isRun = Ox.isW(c, "r")
            val top = flow.open.lastOrNull()
            if (isRun && top != null && !top.inResult) {
                // the code of a field that started earlier: kept as it is, out of sight
                var closed = false
                for (fc in Ox.children(c, "fldChar")) when (Ox.a(fc, "fldCharType")) {
                    "begin" -> top.depth++
                    "separate" -> if (top.depth == 0) top.inResult = true
                    "end" -> if (top.depth == 0) closed = true else top.depth--
                }
                fieldPart(top, c, sb, display)
                if (closed) flow.open.removeAt(flow.open.size - 1)
                i++
                continue
            }
            if (isRun && top != null && top.inResult && hasFld(c, "end") && !hasFld(c, "begin")) {
                fieldPart(top, c, sb, display)
                flow.open.removeAt(flow.open.size - 1)
                i++
                continue
            }
            if (isRun && hasFld(c, "begin")) {
                var depth = 0
                var end = -1
                for (k in i until kids.size) {
                    val r = kids[k]
                    if (!Ox.isW(r, "r")) continue
                    if (hasFld(r, "begin")) depth++
                    if (hasFld(r, "end")) { depth--; if (depth == 0) { end = k; break } }
                }
                if (end >= 0) {
                    field(kids.subList(i, end + 1), sb, display)
                    i = end + 1
                } else {
                    // ends in a later paragraph (a table of contents): its code is kept, its result stays editable
                    val f = OpenField(++fieldSeq)
                    flow.open += f
                    var seenBegin = false
                    for (fc in Ox.children(c, "fldChar")) when (Ox.a(fc, "fldCharType")) {
                        "begin" -> if (seenBegin) f.depth++ else seenBegin = true
                        "separate" -> if (f.depth == 0) f.inResult = true
                        "end" -> if (f.depth > 0) f.depth--
                    }
                    fieldPart(f, c, sb, display)
                    i++
                }
                continue
            }
            if (c.namespaceURI == Ox.W) {
                when (c.localName) {
                    "r" -> run(c, sb, display)
                    "hyperlink" -> hyperlink(c, sb, display, pDir)
                    "ins", "moveTo", "smartTag", "customXml", "dir", "bdo" -> inline(Ox.kids(c), sb, display, pDir)
                    "sdt" -> Ox.child(c, "sdtContent")?.let { inline(Ox.kids(it), sb, display, pDir) }
                    "fldSimple" -> {
                        val txt = Ox.findAll(c, "t").joinToString("") { it.textContent }
                        rawInline(Ox.xml(c), "<span class=\"raw fld\">" + Ox.esc(txt.ifEmpty { "#" }) + "</span>", sb, display)
                    }
                    "bookmarkStart", "bookmarkEnd" -> if (!display) bookmark(c, sb)
                    "pPr", "proofErr", "permStart", "permEnd", "commentRangeStart",
                    "commentRangeEnd", "del", "moveFrom", "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart",
                    "moveToRangeEnd", "lastRenderedPageBreak" -> {}
                    else -> rawInline(Ox.xml(c), "", sb, display)
                }
            } else if (c.namespaceURI == Ox.M && (c.localName == "oMath" || c.localName == "oMathPara")) {
                val txt = Ox.findAll(c, "t", Ox.M).joinToString(" ") { it.textContent }
                rawInline(Ox.xml(c), "<span class=\"raw fld\" style=\"font-style:italic\">" + Ox.esc(txt.ifEmpty { strings.equation }) + "</span>", sb, display)
            } else if (c.namespaceURI == Ox.MC && c.localName == "AlternateContent") {
                rawInline(Ox.xml(c), altPreview(c), sb, display)
            }
            i++
        }
    }

    private fun rawInline(xml: String, previewHtml: String, sb: StringBuilder, display: Boolean) {
        if (display) {
            sb.append(previewHtml.ifEmpty { "" })
            return
        }
        val x = store.add(RawItem(xml, marksIn(xml)))
        val inner = previewHtml.ifEmpty { "<span class=\"raw\"></span>" }
        // the outer span carries the identity; the preview inside is only for looks
        sb.append("<span class=\"raw\" data-k=\"raw\" data-x=\"").append(x).append("\" contenteditable=\"false\">")
            .append(inner).append("</span>")
    }

    private fun field(runs: List<Element>, sb: StringBuilder, display: Boolean) {
        val xml = StringBuilder()
        runs.forEach { Ox.write(it, xml) }
        var inResult = false
        val txt = StringBuilder()
        var depth = 0
        for (r in runs) {
            if (!Ox.isW(r, "r")) continue
            if (hasFld(r, "begin")) depth++
            if (hasFld(r, "separate") && depth == 1) inResult = true
            if (hasFld(r, "end")) { depth--; if (depth == 0) inResult = false }
            if (inResult) Ox.children(r, "t").forEach { txt.append(it.textContent) }
        }
        val first = runs.firstOrNull { Ox.isW(it, "r") }
        val rp = effectiveRun(first)
        val style = runStyleAttrs(rp, hasArabic(txt))
        rawInline(xml.toString(), "<span class=\"raw fld\"$style>" + Ox.esc(txt.toString().ifEmpty { "#" }) + "</span>", sb, display)
    }

    private fun effectiveRun(r: Element?): RP {
        val rPr = Ox.child(r, "rPr")
        val rp = charStyle(Ox.a(Ox.child(rPr, "rStyle")))
        parseRPr(rPr, rp)
        return rp
    }

    /** Inline style attribute for previews (fields, footnote numbers). */
    private fun runStyleAttrs(rp: RP, cs: Boolean): String {
        val st = StringBuilder()
        (if (cs) rp.szCs ?: rp.sz else rp.sz)?.let { st.append("font-size:").append(it / 2.0).append("pt;") }
        if ((if (cs) rp.bCs else rp.b) == true) st.append("font-weight:700;")
        rp.color?.let { st.append("color:#").append(it).append(';') }
        return if (st.isEmpty()) "" else " style=\"$st\""
    }

    private fun runData(rp: RP, text: CharSequence): Pair<Map<String, String?>, Boolean> {
        val cs = rp.rtl == true || rp.cs == true || hasArabic(text)
        val m = LinkedHashMap<String, String?>()
        val b = if (cs) rp.bCs else rp.b
        val i = if (cs) rp.iCs else rp.i
        m["b"] = b?.let { if (it) "1" else "0" }
        m["i"] = i?.let { if (it) "1" else "0" }
        m["u"] = rp.u
        m["s"] = (rp.strike ?: rp.dstrike)?.let { if (it) "1" else "0" }
        m["va"] = rp.va?.takeIf { it.isNotEmpty() }
        m["sz"] = (if (cs) rp.szCs ?: rp.sz else rp.sz)?.toString()
        m["f"] = (if (cs) rp.fCs ?: rp.f else rp.f)?.also { usedFonts += it }
        m["c"] = rp.color
        m["hl"] = rp.hl ?: rp.shd
        return m to cs
    }

    private fun runXs(rp: RP): String {
        val xs = StringBuilder()
        if (rp.caps == true) xs.append("text-transform:uppercase;")
        if (rp.smallCaps == true) xs.append("font-variant:small-caps;")
        rp.spacing?.let { if (it != 0) xs.append("letter-spacing:").append(it / 20.0).append("pt;") }
        if (rp.vanish == true) xs.append("opacity:.45;")
        return xs.toString()
    }

    private fun run(r: Element, sb: StringBuilder, display: Boolean) {
        val rPr = Ox.child(r, "rPr")
        val rp = effectiveRun(r)
        // the run's whole text decides whether it is Arabic (complex script) text
        val allText = StringBuilder()
        Ox.children(r, "t").forEach { allText.append(it.textContent) }
        val (data, cs) = runData(rp, allText)
        val x = if (display) -1 else store.add(RItem(r))
        if (x >= 0) store.origR[x] = data
        val xs = runXs(rp)
        val buf = StringBuilder()
        fun open(): String {
            val o = StringBuilder("<span")
            if (x >= 0) attr(o, "data-x", x.toString())
            attr(o, "data-cs", if (cs) "1" else "0")
            for ((k, v) in data) attr(o, "data-$k", v)
            if (xs.isNotEmpty()) attr(o, "data-xs", xs)
            o.append('>')
            return o.toString()
        }
        fun flush() {
            if (buf.isEmpty()) return
            sb.append(open()).append(Ox.esc(buf.toString())).append("</span>")
            buf.setLength(0)
        }
        for (c in Ox.kids(r)) {
            if (c.namespaceURI == Ox.W) {
                when (c.localName) {
                    "t" -> buf.append(c.textContent)
                    "tab", "ptab" -> buf.append('\t')
                    "br" -> {
                        flush()
                        if (Ox.a(c, "type") == "page") sb.append("<span class=\"pb\" data-k=\"pb\" contenteditable=\"false\"></span>")
                        else sb.append("<br data-k=\"br\">")
                    }
                    "cr" -> { flush(); sb.append("<br data-k=\"br\">") }
                    "noBreakHyphen" -> buf.append('‑')
                    "sym" -> {
                        // a Symbol / Wingdings character (☐, ✓, ➢…) stays exactly as it is in the file
                        flush()
                        val st = StringBuilder()
                        data["sz"]?.toIntOrNull()?.let { st.append("font-size:").append(it / 2.0).append("pt;") }
                        data["c"]?.takeIf { it.length == 6 }?.let { st.append("color:#").append(it).append(';') }
                        rawInline(wrapRun(rPr, c), "<span class=\"raw sym\" style=\"$st\">" + Ox.esc(symChar(c)) + "</span>", sb, display)
                    }
                    "drawing" -> { flush(); drawing(c, r, rPr, sb, display) }
                    "pict", "object" -> { flush(); rawInline(wrapRun(rPr, c), pictPreview(c), sb, display) }
                    "footnoteReference", "endnoteReference" -> {
                        flush()
                        noteCounter++
                        rawInline(wrapRun(rPr, c), "<span class=\"raw note\">" + noteCounter + "</span>", sb, display)
                    }
                    "rPr", "softHyphen", "lastRenderedPageBreak", "commentReference", "annotationRef", "separator",
                    "continuationSeparator", "delText", "fldChar", "instrText", "delInstrText", "footnoteRef", "endnoteRef" -> {}
                    else -> { flush(); rawInline(wrapRun(rPr, c), "", sb, display) }
                }
            } else if (c.namespaceURI == Ox.MC && c.localName == "AlternateContent") {
                flush()
                rawInline(wrapRun(rPr, c), altPreview(c), sb, display)
            }
        }
        flush()
    }

    private fun wrapRun(rPr: Element?, c: Element): String {
        val sb = StringBuilder("<w:r>")
        if (rPr != null) Ox.write(rPr, sb)
        Ox.write(c, sb)
        return sb.append("</w:r>").toString()
    }

    private fun symChar(e: Element): String {
        val font = (Ox.a(e, "font") ?: "").lowercase()
        val code = Ox.a(e, "char")?.toIntOrNull(16) ?: return ""
        val c = if (code >= 0xF000) code - 0xF000 else code
        if (font.contains("wingdings")) {
            return when (c) {
                0xA8, 0x6F, 0x71, 0x72 -> "☐"; 0xFE -> "☑"; 0xFD -> "☒"; 0xFC -> "✓"; 0xFB -> "✗"; 0x9F, 0x6C -> "●"
                0xA7 -> "▪"; 0xD8 -> "➢"; 0x76 -> "❖"; 0xAB -> "★"; 0x4A -> "☺"; 0x4C -> "☹"; 0xE0, 0xE8 -> "➔"
                0x22 -> "✂"; 0x28 -> "☎"; 0x2A -> "✉"; 0x3F -> "✍"
                else -> "•"
            }
        }
        if (font.contains("symbol")) {
            return when (c) {
                0xB7 -> "•"; 0xB4 -> "×"; 0xB8 -> "÷"; 0xA3 -> "≤"; 0xB3 -> "≥"; 0xB9 -> "≠"; 0xB1 -> "±"; 0xD6 -> "√"
                0xA5 -> "∞"; 0x70 -> "π"; 0xAE -> "→"; 0xAC -> "←"; 0xDE -> "⇒"; 0xB0 -> "°"
                else -> if (c in 0x20..0x7E) c.toChar().toString() else "•"
            }
        }
        return if (code < 0xF000) code.toChar().toString() else "•"
    }

    private fun mediaPath(embed: String?): String? {
        if (embed.isNullOrEmpty()) return null
        val rel = pkg.rels(curPart).firstOrNull { it.id == embed } ?: return null
        if (rel.external) return null
        val p = pkg.resolve(curPart, rel.target)
        return if (pkg.entries.containsKey(p)) p else null
    }

    private fun viewable(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }

    private fun drawing(d: Element, r: Element, rPr: Element?, sb: StringBuilder, display: Boolean) {
        val extent = Ox.find(d, "extent", Ox.WP)
        val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 0L
        val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 0L
        val w = max(8, (cx / 9525.0).roundToInt())
        val h = max(8, (cy / 9525.0).roundToInt())
        val isPic = Ox.find(d, "pic", Ox.PIC) != null
        val blip = Ox.find(d, "blip", Ox.A)
        val media = mediaPath(blip?.getAttributeNS(Ox.R, "embed"))
        if (isPic && media != null && viewable(media) && Ox.find(d, "txbxContent") == null) {
            if (display) {
                sb.append("<img src=\"/m/").append(Ox.esc(media, true)).append("\" style=\"width:${w}px;height:${h}px\">")
                return
            }
            val x = store.add(ImgItem(r, d, w, h))
            sb.append("<img data-k=\"img\" data-x=\"").append(x).append("\" data-m=\"").append(Ox.esc(media, true))
                .append("\" src=\"/m/").append(Ox.esc(media, true)).append("\" style=\"width:${w}px;height:${h}px\">")
            return
        }
        rawInline(wrapRun(rPr, d), shapePreview(d, w, h), sb, display)
    }

    private fun shapePreview(container: Element, w: Int, h: Int): String {
        val tx = Ox.find(container, "txbxContent")
        if (tx != null) {
            val inner = StringBuilder()
            nested { blocks(tx, inner, true) }
            return "<span class=\"raw box\" style=\"width:${w}px;min-height:${h}px\">${phrasing(inner)}</span>"
        }
        val blip = Ox.find(container, "blip", Ox.A)
        val media = mediaPath(blip?.getAttributeNS(Ox.R, "embed"))
        if (media != null && viewable(media)) return "<img class=\"raw\" src=\"/m/${Ox.esc(media, true)}\" style=\"width:${w}px;height:${h}px\">"
        val label = if (Ox.find(container, "chart", "http://schemas.openxmlformats.org/drawingml/2006/chart") != null) strings.chart else strings.shape
        return "<span class=\"raw ph\" style=\"width:${w}px;height:${h}px\">${Ox.esc(label)}</span>"
    }

    private fun pictPreview(c: Element): String {
        val img = Ox.find(c, "imagedata", Ox.V)
        val rid = img?.getAttributeNS(Ox.R, "id")?.ifEmpty { null }
        val shape = Ox.find(c, "shape", Ox.V) ?: Ox.find(c, "rect", Ox.V)
        var w = 120; var h = 60
        shape?.getAttribute("style")?.let { st ->
            Regex("width:\\s*([0-9.]+)(pt|px|in|cm)?").find(st)?.let { w = cssLen(it.groupValues[1], it.groupValues[2]) }
            Regex("height:\\s*([0-9.]+)(pt|px|in|cm)?").find(st)?.let { h = cssLen(it.groupValues[1], it.groupValues[2]) }
        }
        val media = mediaPath(rid)
        if (media != null && viewable(media)) return "<img class=\"raw\" src=\"/m/${Ox.esc(media, true)}\" style=\"width:${w}px;height:${h}px\">"
        val tx = Ox.find(c, "txbxContent")
        if (tx != null) {
            val inner = StringBuilder()
            nested { blocks(tx, inner, true) }
            return "<span class=\"raw box\" style=\"width:${w}px;min-height:${h}px\">${phrasing(inner)}</span>"
        }
        return "<span class=\"raw ph\" style=\"width:${w}px;height:${h}px\">${Ox.esc(strings.shape)}</span>"
    }

    /**
     * A text box sits inside a paragraph, and a <p> or <table> there would make the HTML parser close that paragraph
     * (its text would then turn into real, duplicated paragraphs). Its content is drawn with spans instead.
     */
    private fun phrasing(html: CharSequence): String = html.toString()
        .replace(Regex("<colgroup>.*?</colgroup>"), "")
        .replace("<tbody>", "").replace("</tbody>", "")
        .replace("<table class=\"tbl\"", "<span class=\"ptbl\"").replace("</table>", "</span>")
        .replace(Regex("<p(?=[\\s>])"), "<span class=\"pp\"").replace("</p>", "</span>")
        .replace(Regex("<tr(?=[\\s>])"), "<span class=\"ptr\"").replace("</tr>", "</span>")
        .replace(Regex("<td(?=[\\s>])"), "<span class=\"ptd\"").replace("</td>", "</span>")

    private fun cssLen(v: String, unit: String): Int {
        val d = v.toDoubleOrNull() ?: return 60
        return when (unit) {
            "px" -> d
            "in" -> d * 96
            "cm" -> d * 96 / 2.54
            else -> d * 96 / 72
        }.roundToInt().coerceIn(8, 2000)
    }

    private fun altPreview(alt: Element): String {
        val choice = Ox.child(alt, "Choice", Ox.MC)
        val fallback = Ox.child(alt, "Fallback", Ox.MC)
        val d = Ox.find(choice, "drawing")
        if (d != null) {
            val extent = Ox.find(d, "extent", Ox.WP)
            val w = max(8, ((extent?.getAttribute("cx")?.toLongOrNull() ?: 0L) / 9525.0).roundToInt())
            val h = max(8, ((extent?.getAttribute("cy")?.toLongOrNull() ?: 0L) / 9525.0).roundToInt())
            return shapePreview(d, w, h)
        }
        if (fallback != null) return pictPreview(fallback)
        return ""
    }

    private fun hyperlink(h: Element, sb: StringBuilder, display: Boolean, pDir: String) {
        val rid = h.getAttributeNS(Ox.R, "id")
        val anchor = Ox.a(h, "anchor")
        val href = when {
            rid.isNotEmpty() -> pkg.rels(curPart).firstOrNull { it.id == rid }?.target ?: "#"
            anchor != null -> "#$anchor"
            else -> "#"
        }
        if (display) {
            inline(Ox.kids(h), sb, true, pDir)
            return
        }
        val x = store.add(LinkItem(h))
        sb.append("<a data-k=\"a\" data-x=\"").append(x).append("\" href=\"").append(Ox.esc(href, true)).append("\">")
        inline(Ox.kids(h), sb, false, pDir)
        sb.append("</a>")
    }

    // ------------------------------------------------------------------ tables

    private class CellInfo(val tc: Element, val col: Int, val span: Int, var rowspan: Int = 1, var skip: Boolean = false)

    private fun tableBorders(tblPr: Element?): Map<String, String?> {
        val out = HashMap<String, String?>()
        val sides = listOf("top", "bottom", "left", "right", "start", "end", "insideH", "insideV")
        fun apply(b: Element?) {
            if (b == null) return
            for (s in sides) Ox.child(b, s)?.let { e ->
                val key = when (s) { "start" -> "left"; "end" -> "right"; else -> s }
                out[key] = borderCss(e)
            }
        }
        val styleId = Ox.a(Ox.child(tblPr, "tblStyle"))
        for (s in chain(styleId)) apply(Ox.child(s.tblPr, "tblBorders"))
        if (styleId != null && out.isEmpty() && (styleId.equals("TableGrid", true) || styles[styleId]?.name?.equals("Table Grid", true) == true)) {
            val d = "0.50pt solid #000000"
            sides.forEach { if (it != "start" && it != "end") out[it] = d }
        }
        apply(Ox.child(tblPr, "tblBorders"))
        return out
    }

    private fun table(t: Element, sb: StringBuilder, display: Boolean) {
        val tblPr = Ox.child(t, "tblPr")
        val rtl = Ox.onOff(Ox.child(tblPr, "bidiVisual")) == true
        val borders = tableBorders(tblPr)
        val grid = Ox.children(Ox.child(t, "tblGrid"), "gridCol").map { Ox.twips(it, "w") ?: 0 }
        val mar = Ox.child(tblPr, "tblCellMar")
        val padS = (Ox.twips(Ox.child(mar, "left") ?: Ox.child(mar, "start"), "w") ?: 108) / 20.0
        val padE = (Ox.twips(Ox.child(mar, "right") ?: Ox.child(mar, "end"), "w") ?: 108) / 20.0
        val padT = (Ox.twips(Ox.child(mar, "top"), "w") ?: 0) / 20.0
        val padB = (Ox.twips(Ox.child(mar, "bottom"), "w") ?: 0) / 20.0

        // rows (row-level content controls unwrapped)
        val rows = ArrayList<Element>()
        fun collectRows(e: Element) {
            for (c in Ox.kids(e)) {
                if (Ox.isW(c, "tr")) rows += c
                else if (Ox.isW(c, "sdt")) Ox.child(c, "sdtContent")?.let { collectRows(it) }
                else if (Ox.isW(c, "customXml")) collectRows(c)
            }
        }
        collectRows(t)
        // bookmarks between rows or after the last one: starts go into the first cell, ends after the table
        val tblMarks = if (display) emptyList() else
            (Ox.kids(t) + rows.flatMap { Ox.kids(it) }).filter { Ox.isW(it, "bookmarkStart") || Ox.isW(it, "bookmarkEnd") }
        flow.pendingMarks += tblMarks.filter { it.localName == "bookmarkStart" }

        // pass 1: grid positions and vertical merges
        val layout = ArrayList<List<CellInfo>>()
        val open = HashMap<Int, CellInfo>()
        for (tr in rows) {
            val cells = ArrayList<Element>()
            fun collectCells(e: Element) {
                for (c in Ox.kids(e)) {
                    if (Ox.isW(c, "tc")) cells += c
                    else if (Ox.isW(c, "sdt")) Ox.child(c, "sdtContent")?.let { collectCells(it) }
                    else if (Ox.isW(c, "customXml")) collectCells(c)
                }
            }
            collectCells(tr)
            var col = Ox.a(Ox.child(Ox.child(tr, "trPr"), "gridBefore"))?.toIntOrNull() ?: 0
            val list = ArrayList<CellInfo>()
            for (tc in cells) {
                val tcPr = Ox.child(tc, "tcPr")
                val span = Ox.a(Ox.child(tcPr, "gridSpan"))?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val vm = Ox.child(tcPr, "vMerge")
                val info = CellInfo(tc, col, span)
                if (vm != null) {
                    val v = Ox.a(vm)
                    if (v == "restart") open[col] = info
                    else {
                        val origin = open[col]
                        if (origin != null) { origin.rowspan++; info.skip = true } else open[col] = info
                    }
                } else open.remove(col)
                list += info
                col += span
            }
            layout += list
        }

        val x = if (display) -1 else store.add(TblItem(t, grid))
        val tw = Ox.child(tblPr, "tblW")
        val style = StringBuilder()
        when (Ox.a(tw, "type")) {
            "dxa" -> Ox.twips(tw, "w")?.takeIf { it > 0 }?.let { style.append("width:").append((it / 15.0).roundToInt()).append("px;") }
            "pct" -> Ox.a(tw, "w")?.let { v ->
                val pct = if (v.endsWith("%")) v.dropLast(1).toDoubleOrNull() else v.toDoubleOrNull()?.div(50.0)
                pct?.let { style.append("width:").append(it.roundToInt()).append("%;") }
            }
        }
        when (Ox.a(Ox.child(tblPr, "jc"))) {
            "center" -> style.append("margin-left:auto;margin-right:auto;")
            "right", "end" -> style.append("margin-inline-start:auto;")
        }
        Ox.twips(Ox.child(tblPr, "tblInd"), "w")?.takeIf { it != 0 }?.let { style.append("margin-inline-start:").append(it / 20.0).append("pt;") }
        sb.append("<table class=\"tbl\"")
        if (x >= 0) attr(sb, "data-x", x.toString())
        attr(sb, "dir", if (rtl) "rtl" else "ltr")
        if (style.isNotEmpty()) attr(sb, "style", style.toString())
        sb.append('>')
        if (grid.isNotEmpty() && grid.all { it > 0 }) {
            sb.append("<colgroup>")
            grid.forEach { sb.append("<col style=\"width:").append((it / 15.0).roundToInt()).append("px\">") }
            sb.append("</colgroup>")
        }
        sb.append("<tbody>")
        val totalCols = max(grid.size, layout.maxOfOrNull { r -> r.maxOfOrNull { it.col + it.span } ?: 0 } ?: 0)
        layout.forEachIndexed { ri, cells ->
            val tr = rows[ri]
            val trPr = Ox.child(tr, "trPr")
            val rx = if (display) -1 else store.add(TrItem(tr))
            sb.append("<tr")
            if (rx >= 0) attr(sb, "data-x", rx.toString())
            Ox.twips(Ox.child(trPr, "trHeight"), "val")?.takeIf { it > 0 }?.let { attr(sb, "style", "height:${(it / 15.0).roundToInt()}px") }
            sb.append('>')
            for (ci in cells) {
                if (ci.skip) continue
                val tcPr = Ox.child(ci.tc, "tcPr")
                val bg = Ox.a(Ox.child(tcPr, "shd"), "fill")?.takeIf { it.length == 6 && !it.equals("auto", true) }?.uppercase() ?: ""
                val cx = if (display) -1 else store.add(TcItem(ci.tc, bg))
                val cs = StringBuilder()
                cs.append(String.format(Locale.US, "padding-top:%.1fpt;padding-bottom:%.1fpt;padding-inline-start:%.1fpt;padding-inline-end:%.1fpt;", padT, padB, padS, padE))
                val tcW = Ox.child(tcPr, "tcW")
                if (Ox.a(tcW, "type") == "dxa") Ox.twips(tcW, "w")?.takeIf { it > 0 }?.let { cs.append("width:").append((it / 15.0).roundToInt()).append("px;") }
                when (Ox.a(Ox.child(tcPr, "vAlign"))) { "center" -> cs.append("vertical-align:middle;"); "bottom" -> cs.append("vertical-align:bottom;") }
                val lastRow = ri + ci.rowspan - 1 >= layout.size - 1
                val firstCol = ci.col == 0
                val lastCol = ci.col + ci.span >= totalCols
                val tcb = Ox.child(tcPr, "tcBorders")
                fun side(own: String, alt: String?, tableKey: String): String? =
                    borderCss(Ox.child(tcb, own) ?: alt?.let { Ox.child(tcb, it) }) ?: borders[tableKey]
                side("top", null, if (ri == 0) "top" else "insideH")?.let { cs.append("border-top:").append(it).append(';') }
                side("bottom", null, if (lastRow) "bottom" else "insideH")?.let { cs.append("border-bottom:").append(it).append(';') }
                side("left", "start", if (firstCol) "left" else "insideV")?.let { cs.append("border-inline-start:").append(it).append(';') }
                side("right", "end", if (lastCol) "right" else "insideV")?.let { cs.append("border-inline-end:").append(it).append(';') }
                if (bg.isNotEmpty()) cs.append("background-color:#").append(bg).append(';')
                sb.append("<td")
                if (cx >= 0) attr(sb, "data-x", cx.toString())
                if (ci.span > 1) attr(sb, "colspan", ci.span.toString())
                if (ci.rowspan > 1) attr(sb, "rowspan", ci.rowspan.toString())
                if (bg.isNotEmpty()) attr(sb, "data-bg", bg)
                attr(sb, "style", cs.toString())
                sb.append('>')
                val start = sb.length
                blocks(ci.tc, sb, display)
                if (sb.length == start) sb.append("<p data-st=\"").append(Ox.esc(defStyle, true)).append("\"><br></p>")
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        flow.pendingMarks += tblMarks.filter { it.localName == "bookmarkEnd" }
    }

    // ------------------------------------------------------------------ headers & footers

    private fun headerFooter(sect: Element?, refName: String, relType: String): String {
        val refs = Ox.children(sect, refName)
        val ref = refs.firstOrNull { Ox.a(it, "type") == "default" } ?: refs.firstOrNull() ?: return ""
        val rid = ref.getAttributeNS(Ox.R, "id")
        val rel = pkg.rels(pkg.mainPart).firstOrNull { it.id == rid && it.type == relType } ?: return ""
        val part = pkg.resolve(pkg.mainPart, rel.target)
        val bytes = pkg.bytes(part) ?: return ""
        val doc = runCatching { Ox.parse(bytes) }.getOrNull() ?: return ""
        val saved = curPart
        curPart = part
        val sb = StringBuilder()
        val votesR = rtlVotes; val votesL = ltrVotes
        runCatching { nested { blocks(doc.documentElement, sb, true) } }
        rtlVotes = votesR; ltrVotes = votesL
        curPart = saved
        val text = sb.toString()
        // nothing but empty paragraphs -> no header shown
        return if (text.replace(Regex("<[^>]+>"), "").isBlank() && !text.contains("<img")) "" else text
    }
}
