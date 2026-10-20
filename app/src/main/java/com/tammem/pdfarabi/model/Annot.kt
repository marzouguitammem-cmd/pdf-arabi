package com.tammem.pdfarabi.model

import android.graphics.RectF
import com.tammem.pdfarabi.render.TextBlocks
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Everything the user adds on a page. Coordinates are PDF points in the page's own orientation
 * (top-left origin), before any rotation the user applies to the page in the app.
 * Annotations are immutable: an edit replaces the object, which makes undo/redo trivial.
 */

object Ids {
    private val next = AtomicLong(System.currentTimeMillis() * 1000)
    fun new(): Long = next.incrementAndGet()
}

enum class ShapeKind { RECT, OVAL, LINE, ARROW }
enum class MarkKind { CHECK, CROSS, STAR, QUESTION }

const val ALIGN_RIGHT = 0
const val ALIGN_CENTER = 1
const val ALIGN_LEFT = 2

sealed class Annot {
    abstract val id: Long
    abstract val color: Int
    abstract fun bounds(): RectF
    abstract fun translated(dx: Float, dy: Float): Annot
    abstract fun scaled(f: Float, ax: Float, ay: Float): Annot
    abstract fun withColor(c: Int): Annot
    abstract fun withId(newId: Long): Annot
    /** Distance-based hit test, [tol] in points. */
    abstract fun hit(x: Float, y: Float, tol: Float): Boolean
    open val canRecolor: Boolean get() = true
}

/** Annotations drawn inside a (possibly rotated) box. */
interface Boxed {
    val cx: Float
    val cy: Float
    val boxW: Float
    val boxH: Float
    val rot: Float
}

object Geo {
    fun corners(cx: Float, cy: Float, w: Float, h: Float, rotDeg: Float): FloatArray {
        val r = Math.toRadians(rotDeg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val hw = w / 2f
        val hh = h / 2f
        val pts = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            out[i * 2] = cx + x * c - y * s
            out[i * 2 + 1] = cy + x * s + y * c
        }
        return out
    }

    fun boundsOf(pts: FloatArray, pad: Float = 0f): RectF {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            l = min(l, pts[i]); r = max(r, pts[i])
            t = min(t, pts[i + 1]); b = max(b, pts[i + 1])
            i += 2
        }
        if (l > r) return RectF()
        return RectF(l - pad, t - pad, r + pad, b + pad)
    }

    /** Point in rotated box (with tolerance). */
    fun inBox(b: Boxed, x: Float, y: Float, tol: Float): Boolean {
        val r = Math.toRadians(-b.rot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val dx = x - b.cx
        val dy = y - b.cy
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        return abs(lx) <= b.boxW / 2 + tol && abs(ly) <= b.boxH / 2 + tol
    }

    fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return hypot(px - x1, py - y1)
        var t = ((px - x1) * dx + (py - y1) * dy) / len2
        t = t.coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    fun scalePoint(v: Float, a: Float, f: Float) = a + (v - a) * f
}

data class InkAnnot(
    override val id: Long,
    val pts: FloatArray,
    override val color: Int,
    val width: Float,
    val highlighter: Boolean
) : Annot() {
    override fun bounds() = Geo.boundsOf(pts, width / 2f)
    override fun translated(dx: Float, dy: Float) =
        copy(pts = FloatArray(pts.size) { if (it % 2 == 0) pts[it] + dx else pts[it] + dy })
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        pts = FloatArray(pts.size) { if (it % 2 == 0) Geo.scalePoint(pts[it], ax, f) else Geo.scalePoint(pts[it], ay, f) },
        width = width * f
    )
    override fun withColor(c: Int) = copy(color = if (highlighter) (c and 0x00FFFFFF) or (color and 0xFF000000.toInt()) else c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float): Boolean {
        val lim = width / 2f + tol
        if (pts.size == 2) return hypot(x - pts[0], y - pts[1]) <= lim
        var i = 0
        while (i + 3 < pts.size) {
            if (Geo.distToSegment(x, y, pts[i], pts[i + 1], pts[i + 2], pts[i + 3]) <= lim) return true
            i += 2
        }
        return false
    }
}

data class ShapeAnnot(
    override val id: Long,
    val kind: ShapeKind,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    override val color: Int,
    val width: Float,
    val fill: Int = 0
) : Annot() {
    val arrowLen: Float get() = max(8f, width * 4.5f)
    override fun bounds(): RectF {
        val pad = width / 2f + if (kind == ShapeKind.ARROW) arrowLen else 0f
        return RectF(min(x1, x2) - pad, min(y1, y2) - pad, max(x1, x2) + pad, max(y1, y2) + pad)
    }
    override fun translated(dx: Float, dy: Float) = copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        x1 = Geo.scalePoint(x1, ax, f), y1 = Geo.scalePoint(y1, ay, f),
        x2 = Geo.scalePoint(x2, ax, f), y2 = Geo.scalePoint(y2, ay, f), width = width * f
    )
    override fun withColor(c: Int) = copy(color = c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float): Boolean {
        val lim = width / 2f + tol
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> Geo.distToSegment(x, y, x1, y1, x2, y2) <= lim
            ShapeKind.RECT -> {
                val l = min(x1, x2); val r = max(x1, x2); val t = min(y1, y2); val b = max(y1, y2)
                if (fill != 0 && x in l..r && y in t..b) true
                else Geo.distToSegment(x, y, l, t, r, t) <= lim || Geo.distToSegment(x, y, r, t, r, b) <= lim ||
                    Geo.distToSegment(x, y, r, b, l, b) <= lim || Geo.distToSegment(x, y, l, b, l, t) <= lim
            }
            ShapeKind.OVAL -> {
                val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
                val rx = max(abs(x2 - x1) / 2, 0.5f); val ry = max(abs(y2 - y1) / 2, 0.5f)
                val nx = (x - cx) / rx; val ny = (y - cy) / ry
                val d = hypot(nx, ny)
                if (fill != 0 && d <= 1f) true else abs(d - 1f) * min(rx, ry) <= lim
            }
        }
    }
}

data class TextAnnot(
    override val id: Long,
    override val cx: Float,
    override val cy: Float,
    override val rot: Float,
    val text: String,
    val font: String,
    val size: Float,
    override val color: Int,
    val bold: Boolean,
    val align: Int,
    val bg: Int = 0,
    val border: Boolean = false,
    val wrap: Float = 0f
) : Annot(), Boxed {
    override val boxW: Float get() = TextBlocks.get(this).boxW
    override val boxH: Float get() = TextBlocks.get(this).boxH
    override fun bounds() = Geo.boundsOf(Geo.corners(cx, cy, boxW, boxH, rot))
    override fun translated(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        cx = Geo.scalePoint(cx, ax, f), cy = Geo.scalePoint(cy, ay, f),
        size = (size * f).coerceIn(3f, 300f), wrap = if (wrap > 0) wrap * f else 0f
    )
    override fun withColor(c: Int) = copy(color = c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float) = Geo.inBox(this, x, y, tol)

    /**
     * Same annotation with new content, keeping the anchored edge in place: the right edge for
     * right-aligned (Arabic) text, the left edge for left-aligned text, the centre otherwise.
     * The top edge always stays put.
     */
    fun reanchored(newer: TextAnnot): TextAnnot {
        val ow = boxW
        val oh = boxH
        val nw = newer.boxW
        val nh = newer.boxH
        val sx = when (newer.align) {
            ALIGN_RIGHT -> -1f
            ALIGN_LEFT -> 1f
            else -> 0f
        }
        val lx = sx * (nw - ow) / 2f
        val ly = (nh - oh) / 2f
        val r = Math.toRadians(rot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return newer.copy(cx = cx + lx * c - ly * s, cy = cy + lx * s + ly * c, rot = rot)
    }
}

/** Paints over original content with the background colour (and can remove the letters underneath). */
data class CoverAnnot(
    override val id: Long,
    val l: Float, val t: Float, val r: Float, val b: Float,
    override val color: Int,
    val removeText: Boolean
) : Annot() {
    override fun bounds() = RectF(l, t, r, b)
    override fun translated(dx: Float, dy: Float) = copy(l = l + dx, t = t + dy, r = r + dx, b = b + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        l = Geo.scalePoint(l, ax, f), t = Geo.scalePoint(t, ay, f),
        r = Geo.scalePoint(r, ax, f), b = Geo.scalePoint(b, ay, f)
    )
    override fun withColor(c: Int) = copy(color = c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float) = x in (l - tol)..(r + tol) && y in (t - tol)..(b + tol)
}

data class ImageAnnot(
    override val id: Long,
    override val cx: Float,
    override val cy: Float,
    override val boxW: Float,
    override val boxH: Float,
    override val rot: Float,
    val path: String
) : Annot(), Boxed {
    override val color: Int get() = 0
    override val canRecolor: Boolean get() = false
    override fun bounds() = Geo.boundsOf(Geo.corners(cx, cy, boxW, boxH, rot))
    override fun translated(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        cx = Geo.scalePoint(cx, ax, f), cy = Geo.scalePoint(cy, ay, f),
        boxW = max(4f, boxW * f), boxH = max(4f, boxH * f)
    )
    override fun withColor(c: Int) = this
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float) = Geo.inBox(this, x, y, tol)
}

data class SignAnnot(
    override val id: Long,
    override val cx: Float,
    override val cy: Float,
    override val boxW: Float,
    override val boxH: Float,
    override val rot: Float,
    val strokes: List<FloatArray>,
    override val color: Int,
    val penRel: Float
) : Annot(), Boxed {
    override fun bounds() = Geo.boundsOf(Geo.corners(cx, cy, boxW, boxH, rot))
    override fun translated(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        cx = Geo.scalePoint(cx, ax, f), cy = Geo.scalePoint(cy, ay, f),
        boxW = max(4f, boxW * f), boxH = max(4f, boxH * f)
    )
    override fun withColor(c: Int) = copy(color = c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float) = Geo.inBox(this, x, y, tol)
}

data class MarkAnnot(
    override val id: Long,
    override val cx: Float,
    override val cy: Float,
    val size: Float,
    override val rot: Float,
    val kind: MarkKind,
    override val color: Int
) : Annot(), Boxed {
    override val boxW: Float get() = size
    override val boxH: Float get() = size
    override fun bounds() = Geo.boundsOf(Geo.corners(cx, cy, size, size, rot))
    override fun translated(dx: Float, dy: Float) = copy(cx = cx + dx, cy = cy + dy)
    override fun scaled(f: Float, ax: Float, ay: Float) = copy(
        cx = Geo.scalePoint(cx, ax, f), cy = Geo.scalePoint(cy, ay, f), size = (size * f).coerceIn(4f, 400f)
    )
    override fun withColor(c: Int) = copy(color = c)
    override fun withId(newId: Long) = copy(id = newId)
    override fun hit(x: Float, y: Float, tol: Float) = Geo.inBox(this, x, y, tol)
}

/** Rotates a boxed annotation by 90° (used by the selection bar). */
fun Annot.rotated90(): Annot = when (this) {
    is TextAnnot -> copy(rot = (rot + 90f) % 360f)
    is ImageAnnot -> copy(rot = (rot + 90f) % 360f)
    is SignAnnot -> copy(rot = (rot + 90f) % 360f)
    is MarkAnnot -> copy(rot = (rot + 90f) % 360f)
    else -> this
}
