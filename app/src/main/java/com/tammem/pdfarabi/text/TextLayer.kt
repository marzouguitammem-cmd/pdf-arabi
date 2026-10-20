package com.tammem.pdfarabi.text

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One drawn character (or ligature) with its box in page points (top-left origin, displayed orientation). */
class Glyph(
    var text: String,
    var l: Float,
    var t: Float,
    var r: Float,
    var b: Float,
    val baseline: Float,
    val size: Float,
    val font: String?,
    val synthetic: Boolean = false
) {
    val cx: Float get() = (l + r) / 2f
    val isSpace: Boolean get() = text.isBlank()
    val isMark: Boolean get() = text.isNotEmpty() && text.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}

/** Arabic text inside PDFs is stored in visual order; this rebuilds reading (logical) order. */
object TextOrder {
    private fun isArabicDigit(c: Char) = c in '٠'..'٩' || c in '۰'..'۹'

    fun isRtlStrong(g: Glyph) = g.text.any {
        val d = Character.getDirectionality(it)
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    } && !g.text.any { isArabicDigit(it) }

    fun isLtrStrong(g: Glyph) = g.text.any {
        Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT || it.isDigit()
    }

    private fun isNeutral(g: Glyph) = !isRtlStrong(g) && !isLtrStrong(g)

    private val mirror = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    fun lineIsRtl(glyphs: List<Glyph>): Boolean {
        var r = 0
        var l = 0
        for (g in glyphs) for (c in g.text) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> r++
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> l++
            }
        }
        return r > 0 && r >= l
    }

    /** [visual] = glyphs sorted left to right. Returns them in reading order (text of mirrored brackets fixed). */
    fun logical(visual: List<Glyph>, rtl: Boolean): List<Glyph> {
        val list = if (rtl) visual.asReversed().toMutableList() else visual.toMutableList()
        val inRun = BooleanArray(list.size)
        val strong: (Glyph) -> Boolean = if (rtl) ::isLtrStrong else ::isRtlStrong
        var k = 0
        while (k < list.size) {
            if (!strong(list[k])) { k++; continue }
            var j = k
            var m = k + 1
            while (m < list.size) {
                if (strong(list[m])) { j = m; m++ } else if (isNeutral(list[m])) m++ else break
            }
            list.subList(k, j + 1).reverse()
            for (x in k..j) inRun[x] = true
            k = j + 1
        }
        if (!rtl) return list
        return list.mapIndexed { i, g ->
            if (!inRun[i] && g.text.length == 1 && mirror.containsKey(g.text[0])) {
                Glyph(mirror.getValue(g.text[0]).toString(), g.l, g.t, g.r, g.b, g.baseline, g.size, g.font, g.synthetic)
            } else g
        }
    }

    fun textOf(visual: List<Glyph>, rtl: Boolean): String =
        logical(visual, rtl).joinToString("") { it.text }.replace(Regex(" {2,}"), " ").trim()
}

/** Search normalisation: ignores harakat, tatweel, hamza forms, letter case and French accents. */
object Norm {
    fun char(c: Char): String = when {
        c in 'ً'..'ٟ' || c == 'ٰ' || c in 'ۖ'..'ۭ' || c == 'ـ' -> ""
        c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' -> "ا"
        c == 'ى' -> "ي"
        c == 'ة' -> "ه"
        c in '٠'..'٩' -> ('0' + (c - '٠')).toString()
        c in '۰'..'۹' -> ('0' + (c - '۰')).toString()
        c.isWhitespace() -> " "
        c.code < 0x0600 -> {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            d.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
        }
        else -> c.toString()
    }

    fun of(s: String): String {
        val sb = StringBuilder()
        for (c in s) for (n in char(c)) {
            if (n == ' ' && (sb.isEmpty() || sb.last() == ' ')) continue
            sb.append(n)
        }
        return sb.toString().trim()
    }
}

class TLine(val visual: List<Glyph>, val rtl: Boolean) {
    val logical: List<Glyph> = TextOrder.logical(visual, rtl)
    val box: RectF
    val baseline: Float
    val size: Float
    val font: String?
    private val norm: String
    private val normMap: IntArray

    init {
        val solid = visual.filter { !it.isSpace }
        val src = if (solid.isEmpty()) visual else solid
        box = RectF(src.minOf { it.l }, src.minOf { it.t }, src.maxOf { it.r }, src.maxOf { it.b })
        baseline = src.map { it.baseline }.sorted()[src.size / 2]
        size = src.map { it.size }.sorted()[src.size / 2]
        font = src.mapNotNull { it.font }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        logical.forEachIndexed { gi, g ->
            for (c in g.text) for (n in Norm.char(c)) {
                if (n == ' ' && (sb.isEmpty() || sb.last() == ' ')) continue
                sb.append(n)
                map += gi
            }
        }
        norm = sb.toString()
        normMap = map.toIntArray()
    }

    val text: String get() = TextOrder.textOf(visual, rtl)

    fun find(q: String, out: MutableList<RectF>) {
        if (q.isEmpty()) return
        var from = 0
        while (true) {
            val idx = norm.indexOf(q, from)
            if (idx < 0) break
            val a = normMap[idx]
            val b = normMap[idx + q.length - 1]
            val r = RectF()
            for (gi in min(a, b)..max(a, b)) {
                val g = logical[gi]
                if (g.isSpace) continue
                if (r.isEmpty) r.set(g.l, g.t, g.r, g.b) else r.union(g.l, g.t, g.r, g.b)
            }
            if (!r.isEmpty) out += r
            from = idx + 1
        }
    }
}

/** Selected text on a page. */
class Selection(val parts: List<Pair<TLine, List<Glyph>>>) {
    val box: RectF = RectF().also { r ->
        for ((_, gs) in parts) for (g in gs) {
            if (g.isSpace) continue
            if (r.isEmpty) r.set(g.l, g.t, g.r, g.b) else r.union(g.l, g.t, g.r, g.b)
        }
    }
    val rtl: Boolean get() = parts.first().first.rtl
    val baseline: Float get() = parts.first().first.baseline
    val size: Float get() {
        val s = parts.flatMap { p -> p.second.filter { !it.isSpace }.map { it.size } }.sorted()
        return if (s.isEmpty()) parts.first().first.size else s[s.size / 2]
    }
    val font: String? get() = parts.first().first.font
    val lineCount: Int get() = parts.size
    val text: String get() = parts.joinToString("\n") { (line, gs) -> TextOrder.textOf(gs, line.rtl) }
}

class PageText(val lines: List<TLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()

    private fun lineAt(x: Float, y: Float): TLine? {
        var best: TLine? = null
        var bestD = Float.MAX_VALUE
        for (ln in lines) {
            val padY = ln.size * 0.25f
            val padX = ln.size * 0.8f
            if (x < ln.box.left - padX || x > ln.box.right + padX) continue
            val dy = when {
                y < ln.box.top - padY -> ln.box.top - y
                y > ln.box.bottom + padY -> y - ln.box.bottom
                else -> 0f
            }
            if (dy > ln.size * 0.6f) continue
            val d = dy + abs(y - ln.baseline) * 0.1f
            if (d < bestD) { bestD = d; best = ln }
        }
        return best
    }

    /** The word under a tap. */
    fun wordAt(x: Float, y: Float): Selection? {
        val ln = lineAt(x, y) ?: return null
        val v = ln.visual
        var idx = -1
        var bestD = Float.MAX_VALUE
        for ((i, g) in v.withIndex()) {
            if (g.isSpace) continue
            val d = if (x in g.l..g.r) 0f else min(abs(x - g.l), abs(x - g.r))
            if (d < bestD) { bestD = d; idx = i }
        }
        if (idx < 0) return null
        var a = idx
        var b = idx
        while (a - 1 >= 0 && !v[a - 1].isSpace) a--
        while (b + 1 < v.size && !v[b + 1].isSpace) b++
        return Selection(listOf(ln to v.subList(a, b + 1).toList()))
    }

    /** The whole line (or table cell) under a tap. */
    fun lineSelectionAt(x: Float, y: Float): Selection? {
        val ln = lineAt(x, y) ?: return null
        return Selection(listOf(ln to ln.visual))
    }

    /** Everything whose centre is inside [r]. */
    fun inRect(r: RectF): Selection? {
        val parts = ArrayList<Pair<TLine, List<Glyph>>>()
        for (ln in lines) {
            val v = ln.visual
            var a = -1
            var b = -1
            for ((i, g) in v.withIndex()) {
                if (g.isSpace) continue
                val cy = (g.t + g.b) / 2f
                if (r.contains(g.cx, cy) || (g.cx in r.left..r.right && g.baseline in r.top..r.bottom)) {
                    if (a < 0) a = i
                    b = i
                }
            }
            if (a >= 0) parts += ln to v.subList(a, b + 1).toList()
        }
        if (parts.isEmpty()) return null
        parts.sortBy { it.first.baseline }
        return Selection(parts)
    }

    fun find(normalizedQuery: String): List<RectF> {
        val out = ArrayList<RectF>()
        for (ln in lines) ln.find(normalizedQuery, out)
        return out
    }

    companion object {
        val EMPTY = PageText(emptyList())

        /**
         * Extracts the text of one page. [dispW]/[dispH] are the page size used on screen,
         * so boxes line up with what the user sees.
         */
        fun extract(doc: PDDocument, pageIndex: Int, dispW: Float, dispH: Float): PageText {
            val page = doc.getPage(pageIndex)
            val rot = ((page.rotation % 360) + 360) % 360
            val crop = page.cropBox
            val pw = if (rot % 180 == 0) crop.width else crop.height
            val ph = if (rot % 180 == 0) crop.height else crop.width
            val kx = if (pw > 0) dispW / pw else 1f
            val ky = if (ph > 0) dispH / ph else 1f

            val positions = ArrayList<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    positions.add(text)
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)

            val glyphs = ArrayList<Glyph>(positions.size)
            for (tp in positions) {
                val dir = ((tp.dir.roundToInt() % 360) + 360) % 360
                if (dir != rot) continue
                val u = tp.unicode ?: continue
                if (u.isEmpty()) continue
                var fs = tp.fontSizeInPt
                if (fs.isNaN() || fs <= 0.5f) fs = tp.heightDir * 1.6f
                if (fs.isNaN() || fs <= 0.5f) fs = 10f
                val fd = runCatching { tp.font?.fontDescriptor }.getOrNull()
                val asc = (fd?.ascent?.takeIf { it > 0f }?.div(1000f) ?: 0.92f).coerceIn(0.72f, 1.15f)
                val desc = (fd?.descent?.takeIf { it < 0f }?.let { -it / 1000f } ?: 0.32f).coerceIn(0.18f, 0.6f)
                var text = Normalizer.normalize(u, Normalizer.Form.NFKC)
                if (text.isNotBlank()) text = text.trim()
                if (text.isEmpty()) continue
                val x = tp.xDirAdj * kx
                val base = tp.yDirAdj * ky
                val w = max(0f, tp.widthDirAdj * kx)
                val fsk = fs * ky
                val fontName = runCatching { tp.font?.name }.getOrNull()
                glyphs += Glyph(text, x, base - asc * fsk, x + w, base + desc * fsk, base, fsk, fontName)
            }
            if (glyphs.isEmpty()) return EMPTY

            val marks = glyphs.filter { it.isMark }
            val bases = glyphs.filter { !it.isMark && !it.isSpace }.sortedWith(compareBy({ it.baseline }, { it.l }))

            // 1) group into rows by baseline
            val rows = ArrayList<MutableList<Glyph>>()
            var rowBase = Float.NaN
            var rowSize = 0f
            for (g in bases) {
                val cur = rows.lastOrNull()
                if (cur != null && abs(g.baseline - rowBase) <= 0.42f * max(rowSize, g.size)) {
                    cur += g
                } else {
                    rows += mutableListOf(g)
                    rowBase = g.baseline
                    rowSize = g.size
                }
            }

            // 2) attach harakat to the letter they sit on
            for (m in marks) {
                var best: Glyph? = null
                var bestD = Float.MAX_VALUE
                for (row in rows) {
                    val first = row[0]
                    if (abs(first.baseline - m.baseline) > first.size * 1.2f) continue
                    for (g in row) {
                        val d = if (m.cx in g.l..g.r) 0f else min(abs(m.cx - g.l), abs(m.cx - g.r))
                        if (d < bestD) { bestD = d; best = g }
                    }
                }
                val g = best ?: continue
                if (bestD <= g.size) {
                    g.text += m.text
                    g.t = min(g.t, m.t)
                    g.b = max(g.b, m.b)
                }
            }

            // 3) split rows into segments at big gaps (columns, table cells) and add spaces
            val lines = ArrayList<TLine>()
            for (row in rows) {
                row.sortBy { it.l }
                var seg = ArrayList<Glyph>()
                var prev: Glyph? = null
                fun flush() {
                    if (seg.isNotEmpty()) lines += TLine(seg, TextOrder.lineIsRtl(seg))
                    seg = ArrayList()
                }
                for (g in row) {
                    val p = prev
                    if (p != null) {
                        val gap = g.l - p.r
                        val s = max(p.size, g.size)
                        if (gap > max(1.7f * s, 14f)) {
                            flush()
                        } else if (gap > 0.2f * s) {
                            seg.add(Glyph(" ", p.r, min(p.t, g.t), g.l, max(p.b, g.b), p.baseline, s, null, true))
                        }
                    }
                    seg.add(g)
                    prev = g
                }
                flush()
            }
            return PageText(lines)
        }
    }
}
