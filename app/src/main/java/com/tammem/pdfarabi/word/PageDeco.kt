package com.tammem.pdfarabi.word

import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page decoration in WordprocessingML: page borders, text watermarks (VML in the header) and page numbers. */
object PageDeco {

    /** w:pgBorders values that are lines; anything else is one of Word's picture borders (stars, apples…). */
    val LINE_BORDERS = setOf(
        "single", "thick", "double", "dotted", "dashed", "dotDash", "dotDotDash", "triple", "thinThickSmallGap",
        "thickThinSmallGap", "thinThickThinSmallGap", "thinThickMediumGap", "thickThinMediumGap", "thinThickThinMediumGap",
        "thinThickLargeGap", "thickThinLargeGap", "thinThickThinLargeGap", "wave", "doubleWave", "dashSmallGap",
        "dashDotStroked", "threeDEmboss", "threeDEngrave", "outset", "inset", "nil", "none"
    )

    val SECTPR_ORDER = listOf("headerReference", "footerReference", "footnotePr", "endnotePr", "type", "pgSz", "pgMar",
        "paperSrc", "pgBorders", "lnNumType", "pgNumType", "cols", "formProt", "vAlign", "noEndnote", "titlePg",
        "textDirection", "bidi", "rtlGutter", "docGrid", "printerSettings", "sectPrChange")

    const val CT_HEADER = "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
    const val CT_FOOTER = "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"
    const val REL_SETTINGS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings"
    const val O = "urn:schemas-microsoft-com:office:office"

    /** Root of a header or footer the app creates. */
    fun newPart(tag: String, inner: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n<w:$tag xmlns:w=\"${Ox.W}\" xmlns:r=\"${Ox.R}\" " +
            "xmlns:v=\"${Ox.V}\" xmlns:o=\"$O\">$inner</w:$tag>"

    private val pageField = Regex("""(instr="[^"]*\bPAGE\b)|(<w:instrText[^>]*>[^<]*\bPAGE\b)""", RegexOption.IGNORE_CASE)

    fun hasPageField(xml: String): Boolean = pageField.containsMatchIn(xml)

    // ------------------------------------------------------------------ watermark

    fun isWatermarkShape(e: Element): Boolean {
        val id = e.getAttribute("id")
        return id.contains("WaterMark", true) || id.startsWith("PAWatermark")
    }

    /** The text watermark of a header part, if it has one: {text, color, diag, font}. */
    fun findWatermark(d: Document): JSONObject? {
        val shapes = d.getElementsByTagNameNS(Ox.V, "shape")
        for (i in 0 until shapes.length) {
            val sh = shapes.item(i) as Element
            if (!isWatermarkShape(sh)) continue
            val tp = Ox.child(sh, "textpath", Ox.V) ?: continue
            val text = tp.getAttribute("string").ifEmpty { null } ?: continue
            val style = sh.getAttribute("style")
            val font = Regex("""font-family:\s*"?([^";]+)""").find(tp.getAttribute("style"))?.groupValues?.get(1)?.trim()
            return JSONObject().put("text", text).put("color", vmlColor(sh.getAttribute("fillcolor")))
                .put("diag", style.contains("rotation:315") || style.contains("rotation:-45")).apply { if (font != null) put("font", font) }
        }
        return null
    }

    private fun vmlColor(c: String): String {
        val v = c.trim().removePrefix("#").substringBefore(' ')
        if (v.length == 6 && v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return v.uppercase()
        return when (v.lowercase()) {
            "silver" -> "C0C0C0"; "gray", "grey" -> "808080"; "black" -> "000000"; "red" -> "FF0000"; "blue" -> "0000FF"
            "green" -> "008000"; "yellow" -> "FFFF00"; "navy" -> "000080"; "maroon" -> "800000"; "teal" -> "008080"
            else -> "C0C0C0"
        }
    }

    /** Removes every text watermark from a header; true when something was removed. */
    fun removeWatermarks(d: Document): Boolean {
        val shapes = d.getElementsByTagNameNS(Ox.V, "shape")
        val runs = LinkedHashSet<Element>()
        for (i in 0 until shapes.length) {
            val sh = shapes.item(i) as Element
            if (!isWatermarkShape(sh)) continue
            runOf(sh)?.let { runs += it }
        }
        runs.forEach { it.parentNode?.removeChild(it) }
        return runs.isNotEmpty()
    }

    private fun runOf(e: Element): Element? {
        var n: org.w3c.dom.Node? = e
        while (n != null) {
            if (n is Element && n.namespaceURI == Ox.W && n.localName == "r") return n
            n = n.parentNode
        }
        return null
    }

    private fun esc(s: String) = Ox.esc(s, true)

    /**
     * A diagonal (or level) text watermark as Word writes it: a VML WordArt shape centred on the page margins,
     * behind the text. [cwTw]/[chTw] are the text area width and height in twips.
     */
    fun watermarkRun(text: String, color: String, diag: Boolean, font: String, n: Int, cwTw: Int, chTw: Int, withType: Boolean): String {
        val cw = cwTw / 20.0
        val ch = chTw / 20.0
        val ratio = max(1.0, text.length * 0.36)
        val maxW = if (diag) sqrt(cw * cw + ch * ch) * 0.78 else cw
        val maxH = if (diag) ch * 0.42 else ch * 0.3
        var w = maxW
        var h = w / ratio
        if (h > maxH) { h = maxH; w = min(maxW, h * ratio) }
        val f = { v: Double -> ((v * 100).roundToInt() / 100.0).toString() }
        val type = if (!withType) "" else
            "<v:shapetype id=\"_x0000_t136\" coordsize=\"21600,21600\" o:spt=\"136\" adj=\"10800\" path=\"m@7,l@8,m@5,21600l@6,21600e\">" +
                "<v:formulas><v:f eqn=\"sum #0 0 10800\"/><v:f eqn=\"prod #0 2 1\"/><v:f eqn=\"sum 21600 0 @1\"/><v:f eqn=\"sum 0 0 @2\"/>" +
                "<v:f eqn=\"sum 21600 0 @3\"/><v:f eqn=\"if @0 @3 0\"/><v:f eqn=\"if @0 21600 @1\"/><v:f eqn=\"if @0 0 @2\"/>" +
                "<v:f eqn=\"if @0 @4 21600\"/><v:f eqn=\"mid @5 @6\"/><v:f eqn=\"mid @8 @5\"/><v:f eqn=\"mid @7 @8\"/>" +
                "<v:f eqn=\"mid @6 @7\"/><v:f eqn=\"sum @6 0 @5\"/></v:formulas>" +
                "<v:path textpathok=\"t\" o:connecttype=\"custom\" o:connectlocs=\"@9,0;@10,10800;@11,21600;@12,10800\" o:connectangles=\"270,180,90,0\"/>" +
                "<v:textpath on=\"t\" fitshape=\"t\"/><v:handles><v:h position=\"#0,bottomRight\" xrange=\"6629,14971\"/></v:handles>" +
                "<o:lock v:ext=\"edit\" text=\"t\" shapetype=\"t\"/></v:shapetype>"
        val style = "position:absolute;margin-left:0;margin-top:0;width:${f(w)}pt;height:${f(h)}pt;" +
            (if (diag) "rotation:315;" else "") +
            "z-index:-251651072;mso-position-horizontal:center;mso-position-horizontal-relative:margin;" +
            "mso-position-vertical:center;mso-position-vertical-relative:margin"
        return "<w:r><w:rPr><w:noProof/></w:rPr><w:pict>$type" +
            "<v:shape id=\"PAWatermark$n\" o:spid=\"_x0000_s${4000 + n}\" type=\"#_x0000_t136\" style=\"$style\" " +
            "o:allowincell=\"f\" fillcolor=\"#${color}\" stroked=\"f\"><v:fill opacity=\".5\"/>" +
            "<v:textpath style=\"font-family:&quot;${esc(font)}&quot;;font-size:1pt\" string=\"${esc(text)}\"/></v:shape>" +
            "</w:pict></w:r>"
    }

    /** Puts the watermark run at the start of the header's first paragraph (a paragraph is added if needed). */
    fun addWatermark(d: Document, runXml: String) {
        val root = d.documentElement
        ensureNs(root)
        var p = Ox.kids(root).firstOrNull { Ox.isW(it, "p") }
        if (p == null) {
            p = d.createElementNS(Ox.W, "w:p")
            root.insertBefore(p, root.firstChild)
        }
        val frag = fragment(d, runXml)
        val pPr = Ox.child(p, "pPr")
        val ref = if (pPr != null) pPr.nextSibling else p.firstChild
        for (k in frag) p.insertBefore(k, ref)
    }

    // ------------------------------------------------------------------ page numbers

    private val pageish = Regex("""^[\s\d\-–—/|.:()]*(?:(?:page|pages|sur|of|de|الصفحة|صفحة|من)[\s\d\-–—/|.:()]*)*$""", RegexOption.IGNORE_CASE)

    fun isNumberPara(p: Element): Boolean {
        val xml = Ox.xml(p)
        if (!hasPageField(xml)) return false
        val txt = Ox.findAll(p, "t").joinToString("") { it.textContent }
        return pageish.matches(txt)
    }

    /** Removes the page-number paragraphs of a header or footer; true when something was removed. */
    fun removePageNumbers(d: Document): Boolean {
        val root = d.documentElement
        var removed = false
        for (k in Ox.kids(root)) {
            when {
                Ox.isW(k, "p") && isNumberPara(k) -> { removeKeepingWatermark(root, k); removed = true }
                Ox.isW(k, "sdt") -> {
                    val gallery = Ox.find(k, "docPartGallery")?.let { Ox.a(it) } ?: ""
                    val paras = Ox.findAll(Ox.child(k, "sdtContent"), "p")
                    if (gallery.contains("Page Numbers", true) || (paras.isNotEmpty() && paras.all { isNumberPara(it) })) {
                        removeKeepingWatermark(root, k); removed = true
                    }
                }
            }
        }
        if (Ox.kids(root).none { Ox.isW(it, "p") || Ox.isW(it, "tbl") || Ox.isW(it, "sdt") }) root.appendChild(d.createElementNS(Ox.W, "w:p"))
        return removed
    }

    /** Removes [el]; a watermark it carried moves to an empty paragraph left in its place. */
    private fun removeKeepingWatermark(root: Element, el: Element) {
        val shapes = el.getElementsByTagNameNS(Ox.V, "shape")
        val keep = LinkedHashSet<Element>()
        for (i in 0 until shapes.length) {
            val sh = shapes.item(i) as Element
            if (isWatermarkShape(sh)) runOf(sh)?.let { keep += it }
        }
        if (keep.isNotEmpty()) {
            val p = el.ownerDocument.createElementNS(Ox.W, "w:p")
            keep.forEach { p.appendChild(it) }
            root.insertBefore(p, el)
        }
        root.removeChild(el)
    }

    /** A paragraph with the page number: [pos] is t|b + l|c|r (as seen on the page), [fmt] n | dash | of | word. */
    fun pageNumberParagraph(pos: String, fmt: String, rtl: Boolean): String {
        val bidi = fmt == "word" && rtl
        val h = pos.getOrElse(1) { 'c' }
        // in a right-to-left paragraph Word reads left/right as start/end
        val jc = when (h) { 'l' -> if (bidi) "right" else "left"; 'r' -> if (bidi) "left" else "right"; else -> "center" }
        val rPr = if (bidi) "<w:rPr><w:rtl/></w:rPr>" else ""
        fun t(s: String) = "<w:r>$rPr<w:t xml:space=\"preserve\">${Ox.esc(s)}</w:t></w:r>"
        fun fld(code: String) = "<w:fldSimple w:instr=\" $code \"><w:r>$rPr<w:t>1</w:t></w:r></w:fldSimple>"
        val body = when (fmt) {
            "dash" -> t("- ") + fld("PAGE") + t(" -")
            "of" -> fld("PAGE") + t(" / ") + fld("NUMPAGES")
            "word" -> if (rtl) t("الصفحة ") + fld("PAGE") + t(" من ") + fld("NUMPAGES") else t("Page ") + fld("PAGE") + t(" sur ") + fld("NUMPAGES")
            else -> fld("PAGE")
        }
        return "<w:p><w:pPr>" + (if (bidi) "<w:bidi/>" else "") + "<w:jc w:val=\"$jc\"/></w:pPr>$body</w:p>"
    }

    /** Adds the number paragraph at the top of a header or at the bottom of a footer. */
    fun addPageNumber(d: Document, paraXml: String, top: Boolean) {
        val root = d.documentElement
        val frag = fragment(d, paraXml)
        val ref = if (top) root.firstChild else null
        for (k in frag) root.insertBefore(k, ref)
    }

    // ------------------------------------------------------------------ helpers

    private fun ensureNs(root: Element) {
        if (root.getAttributeNS(Ox.XMLNS, "v").isEmpty()) root.setAttributeNS(Ox.XMLNS, "xmlns:v", Ox.V)
        if (root.getAttributeNS(Ox.XMLNS, "o").isEmpty()) root.setAttributeNS(Ox.XMLNS, "xmlns:o", O)
    }

    /** Parses a piece of XML written with the w:, v: and o: prefixes into nodes of [d]. */
    private fun fragment(d: Document, xml: String): List<org.w3c.dom.Node> {
        val wrap = "<x xmlns:w=\"${Ox.W}\" xmlns:r=\"${Ox.R}\" xmlns:v=\"${Ox.V}\" xmlns:o=\"$O\">$xml</x>"
        val tmp = Ox.parse(wrap.toByteArray(Charsets.UTF_8)).documentElement
        val out = ArrayList<org.w3c.dom.Node>()
        var n = tmp.firstChild
        while (n != null) { out += d.importNode(n, true); n = n.nextSibling }
        return out
    }
}
