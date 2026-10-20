package com.tammem.pdfarabi.io

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Really removes the letters that sit under replaced text (not just hides them), so the old
 * words can't be copied or found by search. Removed glyphs are replaced by the same amount of
 * spacing, so every other letter on the line stays exactly where it was.
 */
object Redactor {

    /** A rectangle in PDF user space (y up). */
    class URect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun area() = max(0f, x1 - x0) * max(0f, y1 - y0)
    }

    private class Mat(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        /** this, then [o]. */
        fun times(o: Mat) = Mat(
            a * o.a + b * o.c, a * o.b + b * o.d,
            c * o.a + d * o.c, c * o.b + d * o.d,
            e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f
        )
        fun x(px: Float, py: Float) = px * a + py * c + e
        fun y(px: Float, py: Float) = px * b + py * d + f

        companion object {
            val I = Mat(1f, 0f, 0f, 1f, 0f, 0f)
            fun t(tx: Float, ty: Float) = Mat(1f, 0f, 0f, 1f, tx, ty)
        }
    }

    private class GState(
        var ctm: Mat = Mat.I,
        var font: PDFont? = null,
        var fs: Float = 0f,
        var tc: Float = 0f,
        var tw: Float = 0f,
        var th: Float = 1f,
        var tl: Float = 0f,
        var rise: Float = 0f
    ) {
        fun copy() = GState(ctm, font, fs, tc, tw, th, tl, rise)
    }

    private class Piece(val bytes: ByteArray, val adv: Float, val remove: Boolean)

    private fun num(b: COSBase?): Float = (b as? COSNumber)?.floatValue() ?: 0f

    /** Returns true when at least one glyph was removed. */
    fun redact(doc: PDDocument, page: PDPage, rects: List<URect>): Boolean {
        if (rects.isEmpty()) return false
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens: List<Any> = parser.tokens
        val resources = page.resources

        val out = ArrayList<Any>(tokens.size + 16)
        val operands = ArrayList<COSBase>()
        val stack = ArrayDeque<GState>()
        var gs = GState()
        var tm = Mat.I
        var tlm = Mat.I
        var lost = false
        var changed = false

        fun glyphRemoved(w0: Float, asc: Float, desc: Float): Boolean {
            val f = gs
            val trm = Mat(f.fs * f.th, 0f, 0f, f.fs, 0f, f.rise).times(tm).times(f.ctm)
            val xs = floatArrayOf(trm.x(0f, -desc), trm.x(w0, -desc), trm.x(0f, asc), trm.x(w0, asc))
            val ys = floatArrayOf(trm.y(0f, -desc), trm.y(w0, -desc), trm.y(0f, asc), trm.y(w0, asc))
            val gx0 = xs.min(); val gx1 = xs.max(); val gy0 = ys.min(); val gy1 = ys.max()
            val cx = (gx0 + gx1) / 2f
            val cy = (gy0 + gy1) / 2f
            val garea = max(1e-3f, (gx1 - gx0) * (gy1 - gy0))
            // Only letters that really sit under the cover: centre inside AND most of the glyph inside.
            for (r in rects) {
                if (cx !in r.x0..r.x1 || cy !in r.y0..r.y1) continue
                val ix = min(gx1, r.x1) - max(gx0, r.x0)
                val iy = min(gy1, r.y1) - max(gy0, r.y0)
                if (ix > 0 && iy > 0 && ix * iy / garea > 0.6f) return true
            }
            return false
        }

        /** Walks one string, advancing the text matrix. Null when the font can't be measured. */
        fun show(bytes: ByteArray): List<Piece>? {
            val font = gs.font
            if (font == null || font is PDType3Font || lost || gs.fs == 0f || gs.th == 0f) return null
            val vertical = runCatching { font.isVertical }.getOrDefault(true)
            if (vertical) return null
            val fd = runCatching { font.fontDescriptor }.getOrNull()
            val asc = (fd?.ascent?.takeIf { it > 0f }?.div(1000f) ?: 0.9f).coerceIn(0.6f, 1.2f)
            val desc = (fd?.descent?.takeIf { it < 0f }?.let { -it / 1000f } ?: 0.3f).coerceIn(0.1f, 0.6f)
            val input = ByteArrayInputStream(bytes)
            val pieces = ArrayList<Piece>()
            while (input.available() > 0) {
                val before = bytes.size - input.available()
                val code = font.readCode(input)
                val after = bytes.size - input.available()
                if (after <= before) return null
                val w0 = font.getWidth(code) / 1000f
                val isSpace = after - before == 1 && code == 32
                val remove = glyphRemoved(w0, asc, desc)
                val adv = (w0 * gs.fs + gs.tc + (if (isSpace) gs.tw else 0f)) * gs.th
                tm = Mat.t(adv, 0f).times(tm)
                pieces += Piece(bytes.copyOfRange(before, after), adv, remove)
            }
            return pieces
        }

        fun buildArray(items: List<Any>): COSArray {
            val arr = COSArray()
            val buf = ByteArrayOutputStream()
            var pending = 0f
            fun flushBuf() {
                if (buf.size() > 0) { arr.add(COSString(buf.toByteArray())); buf.reset() }
            }
            fun flushNum() {
                if (pending != 0f) { arr.add(COSFloat(pending)); pending = 0f }
            }
            for (it in items) {
                when (it) {
                    is Piece -> if (!it.remove) {
                        flushNum(); buf.write(it.bytes)
                    } else {
                        flushBuf(); pending += -it.adv * 1000f / (gs.fs * gs.th)
                    }
                    is Float -> { flushBuf(); pending += it }
                }
            }
            flushBuf()
            flushNum()
            return arr
        }

        /** Processes a Tj-like string; returns the replacement TJ array or null to keep the original. */
        fun showSimple(s: COSString): COSArray? {
            val pieces = show(s.bytes)
            if (pieces == null) { lost = true; return null }
            if (pieces.none { it.remove }) return null
            return buildArray(pieces)
        }

        for (tok in tokens) {
            if (tok is COSBase) { operands.add(tok); continue }
            if (tok !is Operator) { out.add(tok); continue }
            var replacement: List<Any>? = null
            try {
                when (tok.name) {
                    "q" -> stack.addLast(gs.copy())
                    "Q" -> gs = stack.removeLastOrNull() ?: gs
                    "cm" -> if (operands.size >= 6) {
                        val m = Mat(num(operands[0]), num(operands[1]), num(operands[2]), num(operands[3]), num(operands[4]), num(operands[5]))
                        gs.ctm = m.times(gs.ctm)
                    }
                    "BT" -> { tm = Mat.I; tlm = Mat.I; lost = false }
                    "Tf" -> if (operands.size >= 2) {
                        val name = operands[0] as? COSName
                        gs.font = if (name != null) runCatching { resources?.getFont(name) }.getOrNull() else null
                        gs.fs = num(operands[1])
                    }
                    "Tc" -> gs.tc = num(operands.getOrNull(0))
                    "Tw" -> gs.tw = num(operands.getOrNull(0))
                    "Tz" -> gs.th = num(operands.getOrNull(0)) / 100f
                    "TL" -> gs.tl = num(operands.getOrNull(0))
                    "Ts" -> gs.rise = num(operands.getOrNull(0))
                    "Td" -> { tlm = Mat.t(num(operands.getOrNull(0)), num(operands.getOrNull(1))).times(tlm); tm = tlm }
                    "TD" -> {
                        gs.tl = -num(operands.getOrNull(1))
                        tlm = Mat.t(num(operands.getOrNull(0)), num(operands.getOrNull(1))).times(tlm); tm = tlm
                    }
                    "Tm" -> if (operands.size >= 6) {
                        tlm = Mat(num(operands[0]), num(operands[1]), num(operands[2]), num(operands[3]), num(operands[4]), num(operands[5]))
                        tm = tlm
                        lost = false
                    }
                    "T*" -> { tlm = Mat.t(0f, -gs.tl).times(tlm); tm = tlm }
                    "Tj" -> {
                        val s = operands.getOrNull(0) as? COSString
                        if (s != null) showSimple(s)?.let { replacement = listOf(it, Operator.getOperator("TJ")) }
                    }
                    "'" -> {
                        tlm = Mat.t(0f, -gs.tl).times(tlm); tm = tlm
                        val s = operands.getOrNull(0) as? COSString
                        if (s != null) showSimple(s)?.let {
                            replacement = listOf(Operator.getOperator("T*"), it, Operator.getOperator("TJ"))
                        }
                    }
                    "\"" -> {
                        val aw = operands.getOrNull(0)
                        val ac = operands.getOrNull(1)
                        gs.tw = num(aw)
                        gs.tc = num(ac)
                        tlm = Mat.t(0f, -gs.tl).times(tlm); tm = tlm
                        val s = operands.getOrNull(2) as? COSString
                        if (s != null && aw != null && ac != null) showSimple(s)?.let {
                            replacement = listOf(
                                aw, Operator.getOperator("Tw"), ac, Operator.getOperator("Tc"),
                                Operator.getOperator("T*"), it, Operator.getOperator("TJ")
                            )
                        }
                    }
                    "TJ" -> {
                        val arr = operands.getOrNull(0) as? COSArray
                        if (arr != null && !lost && gs.font != null) {
                            val items = ArrayList<Any>()
                            var ok = true
                            var any = false
                            for (i in 0 until arr.size()) {
                                val el = arr.getObject(i)
                                when (el) {
                                    is COSString -> {
                                        val p = show(el.bytes)
                                        if (p == null) { ok = false; break }
                                        if (p.any { it.remove }) any = true
                                        items.addAll(p)
                                    }
                                    is COSNumber -> {
                                        val n = el.floatValue()
                                        tm = Mat.t(-n / 1000f * gs.fs * gs.th, 0f).times(tm)
                                        items.add(n)
                                    }
                                }
                            }
                            if (!ok) lost = true
                            else if (any) replacement = listOf(buildArray(items), Operator.getOperator("TJ"))
                        } else lost = true
                    }
                }
            } catch (e: Exception) {
                lost = true
                replacement = null
            }
            val rep = replacement
            if (rep != null) {
                out.addAll(rep)
                changed = true
            } else {
                out.addAll(operands)
                out.add(tok)
            }
            operands.clear()
        }
        out.addAll(operands)
        if (!changed) return false

        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { os ->
            ContentStreamWriter(os).writeTokens(out)
        }
        page.setContents(stream)
        return true
    }
}
