package com.tammem.pdfarabi.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.model.Annot
import com.tammem.pdfarabi.model.CoverAnnot
import com.tammem.pdfarabi.model.ImageAnnot
import com.tammem.pdfarabi.model.InkAnnot
import com.tammem.pdfarabi.model.MarkAnnot
import com.tammem.pdfarabi.model.MarkKind
import com.tammem.pdfarabi.model.ShapeAnnot
import com.tammem.pdfarabi.model.ShapeKind
import com.tammem.pdfarabi.model.SignAnnot
import com.tammem.pdfarabi.model.TextAnnot
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Decoded pictures of image annotations (kept small enough for the screen). */
object ImageCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(path: String, maxSide: Int = 1600): Bitmap? {
        val k = "$path#$maxSide"
        cache.get(k)?.let { return it }
        val b = decode(path, maxSide) ?: return null
        cache.put(k, b)
        return b
    }

    fun decode(path: String, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        null
    }
}

/**
 * Draws annotations in page coordinates (points). The same code paints the screen and the saved
 * PDF, so what the user sees is exactly what gets written.
 */
class AnnotPainter(private val forExport: Boolean) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paths = WeakHashMap<Annot, Path>()
    private val rect = RectF()

    fun draw(c: Canvas, a: Annot) {
        when (a) {
            is InkAnnot -> drawInk(c, a)
            is ShapeAnnot -> drawShape(c, a)
            is TextAnnot -> drawText(c, a)
            is CoverAnnot -> {
                fill.color = a.color or 0xFF000000.toInt()
                c.drawRect(a.l, a.t, a.r, a.b, fill)
            }
            is ImageAnnot -> drawImage(c, a)
            is SignAnnot -> drawSign(c, a)
            is MarkAnnot -> drawMark(c, a)
        }
    }

    private fun inkPath(a: InkAnnot): Path {
        if (!forExport) paths[a]?.let { return it }
        val p = Path()
        val pts = a.pts
        val n = pts.size / 2
        if (n == 0) return p
        p.moveTo(pts[0], pts[1])
        if (n == 1) {
            p.lineTo(pts[0] + 0.01f, pts[1])
        } else if (n == 2) {
            p.lineTo(pts[2], pts[3])
        } else {
            for (i in 1 until n - 1) {
                val x = pts[i * 2]
                val y = pts[i * 2 + 1]
                val nx = pts[i * 2 + 2]
                val ny = pts[i * 2 + 3]
                p.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
            }
            p.lineTo(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1])
        }
        if (!forExport) paths[a] = p
        return p
    }

    private fun drawInk(c: Canvas, a: InkAnnot) {
        stroke.color = a.color
        stroke.strokeWidth = a.width
        stroke.strokeCap = if (a.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        c.drawPath(inkPath(a), stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun drawShape(c: Canvas, a: ShapeAnnot) {
        stroke.color = a.color
        stroke.strokeWidth = a.width
        val l = min(a.x1, a.x2)
        val t = min(a.y1, a.y2)
        val r = max(a.x1, a.x2)
        val b = max(a.y1, a.y2)
        when (a.kind) {
            ShapeKind.RECT -> {
                if (a.fill != 0) { fill.color = a.fill; c.drawRect(l, t, r, b, fill) }
                c.drawRect(l, t, r, b, stroke)
            }
            ShapeKind.OVAL -> {
                rect.set(l, t, r, b)
                if (a.fill != 0) { fill.color = a.fill; c.drawOval(rect, fill) }
                c.drawOval(rect, stroke)
            }
            ShapeKind.LINE -> c.drawLine(a.x1, a.y1, a.x2, a.y2, stroke)
            ShapeKind.ARROW -> {
                c.drawLine(a.x1, a.y1, a.x2, a.y2, stroke)
                val ang = atan2((a.y2 - a.y1).toDouble(), (a.x2 - a.x1).toDouble())
                val len = a.arrowLen
                val spread = Math.toRadians(28.0)
                val p = Path()
                p.moveTo((a.x2 - len * cos(ang - spread)).toFloat(), (a.y2 - len * sin(ang - spread)).toFloat())
                p.lineTo(a.x2, a.y2)
                p.lineTo((a.x2 - len * cos(ang + spread)).toFloat(), (a.y2 - len * sin(ang + spread)).toFloat())
                c.drawPath(p, stroke)
            }
        }
    }

    private fun drawText(c: Canvas, a: TextAnnot) {
        val block = if (forExport) TextBlocks.build(a) else TextBlocks.get(a)
        c.save()
        c.translate(a.cx, a.cy)
        if (a.rot != 0f) c.rotate(a.rot)
        c.translate(-block.boxW / 2f, -block.boxH / 2f)
        val radius = a.size * 0.3f
        if (a.bg != 0) {
            fill.color = a.bg
            rect.set(0f, 0f, block.boxW, block.boxH)
            c.drawRoundRect(rect, radius, radius, fill)
        }
        if (a.border) {
            stroke.color = a.color
            stroke.strokeWidth = max(0.6f, a.size * 0.075f)
            val h = stroke.strokeWidth / 2f
            rect.set(h, h, block.boxW - h, block.boxH - h)
            c.drawRoundRect(rect, radius, radius, stroke)
        }
        block.draw(c)
        c.restore()
    }

    private fun drawImage(c: Canvas, a: ImageAnnot) {
        val bmp = ImageCache.get(a.path, if (forExport) 2200 else 1400)
        c.save()
        c.translate(a.cx, a.cy)
        if (a.rot != 0f) c.rotate(a.rot)
        rect.set(-a.boxW / 2f, -a.boxH / 2f, a.boxW / 2f, a.boxH / 2f)
        if (bmp != null) {
            c.drawBitmap(bmp, null, rect, bmpPaint)
        } else {
            fill.color = Color.LTGRAY
            c.drawRect(rect, fill)
        }
        c.restore()
    }

    private fun drawSign(c: Canvas, a: SignAnnot) {
        c.save()
        c.translate(a.cx - a.boxW / 2f, a.cy - a.boxH / 2f)
        if (a.rot != 0f) c.rotate(a.rot, a.boxW / 2f, a.boxH / 2f)
        stroke.color = a.color
        stroke.strokeWidth = max(0.4f, a.penRel * a.boxH)
        for (s in a.strokes) {
            val n = s.size / 2
            if (n == 0) continue
            val p = Path()
            p.moveTo(s[0] * a.boxW, s[1] * a.boxH)
            if (n == 1) p.lineTo(s[0] * a.boxW + 0.01f, s[1] * a.boxH)
            for (i in 1 until n - 1) {
                val x = s[i * 2] * a.boxW
                val y = s[i * 2 + 1] * a.boxH
                val nx = s[i * 2 + 2] * a.boxW
                val ny = s[i * 2 + 3] * a.boxH
                p.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
            }
            if (n > 1) p.lineTo(s[(n - 1) * 2] * a.boxW, s[(n - 1) * 2 + 1] * a.boxH)
            c.drawPath(p, stroke)
        }
        c.restore()
    }

    private fun drawMark(c: Canvas, a: MarkAnnot) {
        val s = a.size
        c.save()
        c.translate(a.cx, a.cy)
        if (a.rot != 0f) c.rotate(a.rot)
        stroke.color = a.color
        stroke.strokeWidth = s * 0.13f
        when (a.kind) {
            MarkKind.CHECK -> {
                val p = Path()
                p.moveTo(-0.38f * s, 0.02f * s)
                p.lineTo(-0.12f * s, 0.3f * s)
                p.lineTo(0.42f * s, -0.32f * s)
                c.drawPath(p, stroke)
            }
            MarkKind.CROSS -> {
                c.drawLine(-0.3f * s, -0.3f * s, 0.3f * s, 0.3f * s, stroke)
                c.drawLine(0.3f * s, -0.3f * s, -0.3f * s, 0.3f * s, stroke)
            }
            MarkKind.STAR -> {
                fill.color = a.color
                c.drawPath(starPath(s * 0.48f), fill)
            }
            MarkKind.QUESTION -> {
                val tp = TextBlocks.paintFor(
                    TextAnnot(0, 0f, 0f, 0f, "؟", "amiri", s, a.color, true, 1)
                )
                tp.textAlign = Paint.Align.CENTER
                tp.typeface = Fonts.typeface("amiri", true)
                val fm = tp.fontMetrics
                c.drawText("؟", 0f, -(fm.ascent + fm.descent) / 2f, tp)
            }
        }
        c.restore()
    }

    private fun starPath(r: Float): Path {
        val p = Path()
        val inner = r * 0.45f
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else inner
            val ang = Math.toRadians((-90 + i * 36).toDouble())
            val x = (rad * cos(ang)).toFloat()
            val y = (rad * sin(ang)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }
}
