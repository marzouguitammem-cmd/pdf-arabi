package com.tammem.pdfarabi.word

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** WordprocessingML helpers: namespaces, DOM access, a faithful serializer and schema-order inserts. */
object Ox {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    const val V = "urn:schemas-microsoft-com:vml"
    const val WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"

    const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    const val REL_LINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
    const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
    const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    const val REL_THEME = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme"
    const val REL_HEADER = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header"
    const val REL_FOOTER = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"

    fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun newDoc(): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().newDocument()
    }

    fun isW(n: Node?, local: String): Boolean =
        n is Element && n.namespaceURI == W && n.localName == local

    fun kids(e: Element?): List<Element> {
        if (e == null) return emptyList()
        val out = ArrayList<Element>()
        var n = e.firstChild
        while (n != null) {
            if (n is Element) out += n
            n = n.nextSibling
        }
        return out
    }

    fun child(e: Element?, local: String, ns: String = W): Element? {
        var n = e?.firstChild
        while (n != null) {
            if (n is Element && n.localName == local && n.namespaceURI == ns) return n
            n = n.nextSibling
        }
        return null
    }

    fun children(e: Element?, local: String, ns: String = W): List<Element> =
        kids(e).filter { it.localName == local && it.namespaceURI == ns }

    /** First descendant with this name. */
    fun find(e: Element?, local: String, ns: String = W): Element? {
        if (e == null) return null
        val list = e.getElementsByTagNameNS(ns, local)
        return if (list.length > 0) list.item(0) as Element else null
    }

    fun findAll(e: Element?, local: String, ns: String = W): List<Element> {
        if (e == null) return emptyList()
        val list = e.getElementsByTagNameNS(ns, local)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** w:val (or another w: attribute) of an element. */
    fun a(e: Element?, local: String = "val", ns: String = W): String? {
        if (e == null) return null
        val v = e.getAttributeNS(ns, local)
        if (v.isNotEmpty()) return v
        val plain = e.getAttribute(local)
        return plain.ifEmpty { if (e.hasAttributeNS(ns, local)) "" else null }
    }

    fun has(e: Element?, local: String, ns: String = W): Boolean =
        e != null && (e.hasAttributeNS(ns, local) || e.hasAttribute(local))

    fun twips(e: Element?, local: String): Int? = a(e, local)?.toDoubleOrNull()?.toInt()

    /** OOXML on/off property: present without val, or val true/1/on. */
    fun onOff(e: Element?): Boolean? {
        if (e == null) return null
        val v = a(e) ?: return true
        return when (v.lowercase()) {
            "", "true", "1", "on" -> true
            "false", "0", "off", "none" -> false
            else -> true
        }
    }

    fun textOf(e: Element?): String = e?.textContent ?: ""

    // ------------------------------------------------------------------ serializer

    fun esc(s: String, attr: Boolean = false): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                attr && c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> if (attr) sb.append("&#").append(c.code).append(';') else sb.append(c)
                c.code < 0x20 -> {}
                c == '￾' || c == '￿' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun xml(n: Node): String {
        val sb = StringBuilder()
        write(n, sb)
        return sb.toString()
    }

    fun write(n: Node, sb: StringBuilder) {
        when (n.nodeType) {
            Node.ELEMENT_NODE -> {
                val e = n as Element
                sb.append('<').append(e.nodeName)
                val at = e.attributes
                for (i in 0 until at.length) {
                    val a = at.item(i) as Attr
                    sb.append(' ').append(a.name).append("=\"").append(esc(a.value, true)).append('"')
                }
                if (!e.hasChildNodes()) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    var c = e.firstChild
                    while (c != null) { write(c, sb); c = c.nextSibling }
                    sb.append("</").append(e.nodeName).append('>')
                }
            }
            Node.TEXT_NODE -> sb.append(esc(n.nodeValue ?: ""))
            Node.CDATA_SECTION_NODE -> sb.append(esc(n.nodeValue ?: ""))
            Node.DOCUMENT_NODE -> {
                var c = n.firstChild
                while (c != null) { write(c, sb); c = c.nextSibling }
            }
            else -> {}
        }
    }

    /** Start tag of an element with all its attributes (namespace declarations included). */
    fun startTag(e: Element, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("<").append(e.nodeName)
        val at = e.attributes
        val seen = HashSet<String>()
        for (i in 0 until at.length) {
            val a = at.item(i) as Attr
            seen += a.name
            sb.append(' ').append(a.name).append("=\"").append(esc(a.value, true)).append('"')
        }
        for ((k, v) in extra) if (k !in seen) sb.append(' ').append(k).append("=\"").append(esc(v, true)).append('"')
        return sb.append('>').toString()
    }

    // ------------------------------------------------------------------ schema order

    val RPR_ORDER = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
        "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern",
        "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em",
        "lang", "eastAsianLayout", "specVanish", "oMath")

    val PPR_ORDER = listOf("pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
        "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct",
        "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind",
        "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment",
        "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")

    val TCPR_ORDER = listOf("cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar",
        "textDirection", "tcFitText", "vAlign", "hideMark", "headers", "cellIns", "cellDel", "cellMerge", "tcPrChange")

    val TBLPR_ORDER = listOf("tblStyle", "tblpPr", "tblOverlap", "bidiVisual", "tblStyleRowBandSize",
        "tblStyleColBandSize", "tblW", "jc", "tblCellSpacing", "tblInd", "tblBorders", "shd", "tblLayout", "tblCellMar",
        "tblLook", "tblCaption", "tblDescription", "tblPrChange")

    /** Returns the w:[local] child, creating it at its schema position when missing. */
    fun ensure(parent: Element, local: String, order: List<String>): Element {
        child(parent, local)?.let { return it }
        val e = parent.ownerDocument.createElementNS(W, "w:$local")
        val idx = order.indexOf(local)
        var ref: Node? = null
        if (idx >= 0) {
            for (k in kids(parent)) {
                if (k.namespaceURI != W) continue
                val ki = order.indexOf(k.localName)
                if (ki > idx) { ref = k; break }
            }
        }
        parent.insertBefore(e, ref)
        return e
    }

    /** Puts w: children back in schema order (unknown/extension elements keep their order at the end). */
    fun sortChildren(parent: Element, order: List<String>) {
        val kids = kids(parent)
        if (kids.size < 2) return
        fun rank(e: Element): Int = if (e.namespaceURI == W) order.indexOf(e.localName).let { if (it < 0) 10_000 else it } else 20_000
        val sorted = kids.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        if (sorted == kids) return
        kids.forEach { parent.removeChild(it) }
        sorted.forEach { parent.appendChild(it) }
    }

    fun remove(parent: Element?, local: String) {
        if (parent == null) return
        children(parent, local).forEach { parent.removeChild(it) }
    }

    fun setVal(e: Element, value: String?, attr: String = "val") {
        if (value == null) e.removeAttributeNS(W, attr) else e.setAttributeNS(W, "w:$attr", value)
    }

    /** Sets an on/off property: true -> <w:x/>, false -> <w:x w:val="0"/>, null -> removed. */
    fun setOnOff(parent: Element, local: String, v: Boolean?, order: List<String>) {
        if (v == null) { remove(parent, local); return }
        val e = ensure(parent, local, order)
        if (v) e.removeAttributeNS(W, "val") else setVal(e, "0")
    }

    /** Creates w:[local] in [doc] with the given w: attributes. */
    fun el(doc: Document, local: String, vararg attrs: Pair<String, String>): Element {
        val e = doc.createElementNS(W, "w:$local")
        for ((k, v) in attrs) e.setAttributeNS(W, "w:$k", v)
        return e
    }
}
