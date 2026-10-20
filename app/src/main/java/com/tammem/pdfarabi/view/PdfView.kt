package com.tammem.pdfarabi.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.model.Annot
import com.tammem.pdfarabi.model.Batch
import com.tammem.pdfarabi.model.Boxed
import com.tammem.pdfarabi.model.Cmd
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.Geo
import com.tammem.pdfarabi.model.Ids
import com.tammem.pdfarabi.model.InkAnnot
import com.tammem.pdfarabi.model.MarkAnnot
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.model.RemoveAnnot
import com.tammem.pdfarabi.model.ReplaceAnnot
import com.tammem.pdfarabi.model.ShapeAnnot
import com.tammem.pdfarabi.model.ShapeKind
import com.tammem.pdfarabi.model.TextAnnot
import com.tammem.pdfarabi.model.AddAnnot
import com.tammem.pdfarabi.render.AnnotPainter
import com.tammem.pdfarabi.render.RenderEngine
import com.tammem.pdfarabi.render.TileRequest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { READ, SELECT, PEN, HIGHLIGHT, ERASER, SHAPE, REPLACE, TEXT, MARK, STAMP, SIGN }

/**
 * The document view: pages stacked vertically and fitted to the screen width (like WPS),
 * pinch to zoom, double-tap zoom, fling, a fast-scroll handle, and the editing tools.
 */
class PdfView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs), DocModel.Listener {

    interface Host {
        fun onPageChanged(index: Int, count: Int)
        fun onTapEmpty()
        fun onPlace(pageIndex: Int, x: Float, y: Float)
        fun onReplaceTap(pageIndex: Int, x: Float, y: Float)
        fun onReplaceRect(pageIndex: Int, rect: RectF)
        fun onCommit(cmd: Cmd)
        fun onSelectionChanged()
        fun onEditText(pageIndex: Int, a: TextAnnot)
        fun onZoomChanged(zoom: Float) {}
    }

    var host: Host? = null
    private var model: DocModel? = null
    private var engine: RenderEngine? = null
    private val painter = AnnotPainter(false)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---------- tool styles (set by the editor screen) ----------
    var penColor = Color.RED
    var penWidth = 1.6f
    var hlColor = 0x66FFEB3B
    var hlWidth = 12f
    var shapeKind = ShapeKind.RECT
    var shapeColor = Color.RED
    var shapeWidth = 1.6f

    var tool: Tool = Tool.READ
        set(v) {
            if (field == v) return
            cancelTool()
            field = v
            if (v != Tool.SELECT) select(null, null)
            invalidate()
        }

    var nightMode = false
        set(v) { field = v; invalidate() }

    /** Space covered by the top bar / bottom bars, so first and last pages stay reachable. */
    var insetTop = 0f
        set(v) { if (field != v) { val old = field; field = v; sy -= (v - old); clampScroll(); invalidate() } }
    var insetBottom = 0f
        set(v) { if (field != v) { field = v; clampScroll(); invalidate() } }

    // ---------- layout ----------
    private val sideMargin get() = 5f * density
    private val gap get() = 9f * density
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var docH = 0f
    private var cw = 1f
    var zoom = 1f
        private set
    private var sx = 0f
    private var sy = 0f
    private val minZoom = 0.5f
    private val maxZoom = 8f
    private var lastReportedPage = -1
    private var pendingPage = -1

    // ---------- drawing helpers ----------
    private val bgColor = ContextCompat.getColor(context, R.color.reader_bg)
    private val borderPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.page_border) }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val invertFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    )
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.RED)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = primary
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val marqueeFill = Paint().apply { color = (primary and 0x00FFFFFF) or 0x22000000 }
    private val marqueeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primary; style = Paint.Style.STROKE; strokeWidth = 1.4f * density
    }
    private val hitPaint = Paint().apply { color = 0x55FFD54F }
    private val hitCurrentPaint = Paint().apply { color = 0x88FF9800.toInt() }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15f * density; textAlign = Paint.Align.CENTER
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.tajawal)
    }
    private val mtx = Matrix()
    private val inv = Matrix()
    private val drawM = Matrix()
    private val rTmp = RectF()
    private val rTmp2 = RectF()
    private val pts = FloatArray(8)

    // ---------- selection / previews / search ----------
    private var selPageId = -1L
    private var selAnnot: Annot? = null
    private var previewPageId = -1L
    private var previewAnnots: List<Annot> = emptyList()
    private var previewHideId = -1L
    private val hidden = HashSet<Long>()
    private var searchHits: Map<Long, List<RectF>> = emptyMap()
    private var currentHitPage = -1L
    private var currentHit: RectF? = null

    // ---------- gestures ----------
    private enum class Mode { NONE, NAV, TOOL, PENDING }
    private var mode = Mode.NONE
    private var scaling = false
    private var downX = 0f
    private var downY = 0f
    private val scroller = OverScroller(context)
    private var zoomAnim: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            scheduleSettle()
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (mode != Mode.NAV) return false
            scrollByPx(distanceX, distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (mode != Mode.NAV || scaling) return false
            fling(velocityX, velocityY)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tool == Tool.READ) { host?.onTapEmpty(); return true }
            if (tool == Tool.SELECT) { tapSelect(e.x, e.y); return true }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (tool != Tool.REPLACE || mode != Mode.PENDING) return
            val i = pageAt(downX, downY)
            if (i < 0) return
            mode = Mode.TOOL
            marqueePage = i
            marquee = RectF(downX, downY, downX, downY)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != Tool.READ && tool != Tool.SELECT) return false
            if (tool == Tool.SELECT) {
                val hit = annotAt(e.x, e.y)
                if (hit != null && hit.second is TextAnnot) {
                    val idx = model?.indexOfPage(hit.first) ?: -1
                    if (idx >= 0) { select(hit.first, hit.second); host?.onEditText(idx, hit.second as TextAnnot) }
                    return true
                }
            }
            doubleTapZoom(e.x, e.y)
            return true
        }
    })

    // =====================================================================
    // Document
    // =====================================================================

    fun setDocument(m: DocModel, e: RenderEngine) {
        model?.removeListener(this)
        model = m
        engine = e
        m.addListener(this)
        e.onBitmapReady = { invalidate() }
        zoom = 1f
        sx = 0f
        sy = -insetTop
        relayout(false)
        invalidate()
    }

    override fun onModelChanged(structure: Boolean) {
        if (structure) {
            engine?.clearTiles()
            relayout(true)
            scheduleSettle()
        }
        validateSelection()
        invalidate()
        reportPage()
    }

    val pageCount: Int get() = model?.pages?.size ?: 0

    private fun relayout(keep: Boolean) {
        val m = model ?: return
        if (width == 0) return
        var anchorPage = -1
        var anchorFrac = 0f
        if (keep && tops.isNotEmpty()) {
            val docY = (sy + insetTop) / zoom
            anchorPage = pageAtDocY(docY).coerceAtLeast(0)
            if (anchorPage < tops.size) anchorFrac = (docY - tops[anchorPage]) / heights[anchorPage].coerceAtLeast(1f)
        }
        val n = m.pages.size
        cw = (width - 2 * sideMargin).coerceAtLeast(1f)
        tops = FloatArray(n)
        heights = FloatArray(n)
        var y = gap
        for (i in 0 until n) {
            val p = m.pages[i]
            tops[i] = y
            heights[i] = cw * m.visualH(p) / m.visualW(p)
            y += heights[i] + gap
        }
        docH = y
        if (keep && anchorPage >= 0 && n > 0) {
            val i = anchorPage.coerceAtMost(n - 1)
            sy = (tops[i] + anchorFrac.coerceIn(-0.2f, 1.2f) * heights[i]) * zoom - insetTop
        }
        if (pendingPage >= 0 && n > 0) {
            val i = pendingPage.coerceIn(0, n - 1)
            sy = tops[i] * zoom - insetTop - gap * zoom / 2
            pendingPage = -1
        }
        clampScroll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine?.clearTiles()
        relayout(oldw > 0)
        scheduleSettle()
    }

    private fun offX() = if (zoom < 1f) (width - width * zoom) / 2f else 0f
    private fun maxSx() = max(0f, width * zoom - width)
    private fun minSy() = -insetTop
    private fun maxSy() = max(minSy(), docH * zoom - height + insetBottom)

    private fun clampScroll() {
        sx = sx.coerceIn(0f, maxSx())
        sy = sy.coerceIn(minSy(), maxSy())
    }

    fun pageRect(i: Int, out: RectF) {
        val l = sideMargin * zoom - sx + offX()
        val t = tops[i] * zoom - sy
        out.set(l, t, l + cw * zoom, t + heights[i] * zoom)
    }

    /** Page (base points) -> screen pixels. */
    fun pageMatrix(i: Int, out: Matrix) {
        val m = model ?: return
        val p = m.pages[i]
        val bw = m.baseW(p)
        val bh = m.baseH(p)
        val k = cw * zoom / m.visualW(p)
        out.reset()
        when (p.rot) {
            90 -> { out.setRotate(90f); out.postTranslate(bh, 0f) }
            180 -> { out.setRotate(180f); out.postTranslate(bw, bh) }
            270 -> { out.setRotate(270f); out.postTranslate(0f, bw) }
        }
        out.postScale(k, k)
        pageRect(i, rTmp2)
        out.postTranslate(rTmp2.left, rTmp2.top)
    }

    /** Screen pixels per page point. */
    fun pageScale(i: Int): Float {
        val m = model ?: return 1f
        return cw * zoom / m.visualW(m.pages[i])
    }

    private fun toBase(i: Int, x: Float, y: Float, out: FloatArray) {
        pageMatrix(i, mtx)
        mtx.invert(inv)
        out[0] = x; out[1] = y
        inv.mapPoints(out, 0, out, 0, 1)
    }

    private fun pageAtDocY(docY: Float): Int {
        if (tops.isEmpty()) return -1
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] - gap / 2 <= docY) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pageAt(x: Float, y: Float): Int {
        if (tops.isEmpty()) return -1
        val i = pageAtDocY((y + sy) / zoom)
        if (i < 0) return -1
        pageRect(i, rTmp)
        rTmp.inset(-gap / 2, -gap / 2)
        return if (rTmp.contains(x, y)) i else -1
    }

    private fun visibleRange(): IntArray {
        if (tops.isEmpty()) return intArrayOf(0, -1)
        val first = pageAtDocY(sy / zoom).coerceAtLeast(0)
        var last = first
        val bottomDoc = (sy + height) / zoom
        while (last + 1 < tops.size && tops[last + 1] < bottomDoc) last++
        return intArrayOf(first, last)
    }

    fun currentPage(): Int {
        if (tops.isEmpty()) return 0
        val content = height - insetTop - insetBottom
        val docY = (sy + insetTop + content * 0.4f) / zoom
        return pageAtDocY(docY).coerceIn(0, tops.size - 1)
    }

    private fun reportPage() {
        val n = pageCount
        if (n == 0) return
        val c = currentPage()
        if (c != lastReportedPage) {
            lastReportedPage = c
            host?.onPageChanged(c, n)
        }
    }

    fun scrollToPage(i: Int) {
        if (tops.isEmpty() || width == 0) { pendingPage = i; return }
        val idx = i.coerceIn(0, tops.size - 1)
        sy = tops[idx] * zoom - insetTop - gap * zoom / 2
        clampScroll()
        engine?.clearTiles()
        invalidate()
        scheduleSettle()
        reportPage()
    }

    /** Scrolls so that [r] (base points of page [i]) sits near the top of the visible area. */
    fun ensureVisible(i: Int, r: RectF, topFraction: Float = 0.15f) {
        if (i !in tops.indices) return
        pageMatrix(i, mtx)
        rTmp.set(r)
        mtx.mapRect(rTmp)
        val content = height - insetTop - insetBottom
        val wantTop = insetTop + content * topFraction
        sy += rTmp.top - wantTop
        if (rTmp.left < 0 || rTmp.right > width) sx += rTmp.centerX() - width / 2f
        clampScroll()
        invalidate()
        scheduleSettle()
        reportPage()
    }

    /** Centre of the visible part of page [i], in base points. */
    fun visibleCenter(i: Int): FloatArray {
        val out = FloatArray(2)
        if (i !in tops.indices) return out
        pageRect(i, rTmp)
        val top = max(rTmp.top, insetTop)
        val bottom = min(rTmp.bottom, height - insetBottom)
        val left = max(rTmp.left, 0f)
        val right = min(rTmp.right, width.toFloat())
        toBase(i, (left + right) / 2f, (top + max(bottom, top + 1f)) / 2f, out)
        return out
    }

    // =====================================================================
    // Zoom & scroll
    // =====================================================================

    private fun zoomTo(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        if (z == zoom) return
        val docX = (fx + sx - offX()) / zoom
        val docY = (fy + sy) / zoom
        zoom = z
        sx = docX * z - fx + offX()
        sy = docY * z - fy
        clampScroll()
        invalidate()
        reportPage()
        host?.onZoomChanged(zoom)
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val start = zoom
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                zoomTo(start + (target - start) * t, fx, fy)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { scheduleSettle() }
            })
            start()
        }
    }

    private fun doubleTapZoom(x: Float, y: Float) {
        if (zoom < 1.4f) animateZoom(2.4f, x, y) else animateZoom(1f, width / 2f, y)
    }

    fun resetZoom() = animateZoom(1f, width / 2f, height / 2f)

    private fun scrollByPx(dx: Float, dy: Float) {
        sx += dx
        sy += dy
        clampScroll()
        invalidate()
        reportPage()
        showThumb()
    }

    private fun fling(vx: Float, vy: Float) {
        scroller.forceFinished(true)
        scroller.fling(
            sx.roundToInt(), sy.roundToInt(), -vx.roundToInt(), -vy.roundToInt(),
            0, maxSx().roundToInt(), minSy().roundToInt(), maxSy().roundToInt()
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            clampScroll()
            reportPage()
            showThumb()
            postInvalidateOnAnimation()
            if (scroller.isFinished) scheduleSettle()
        }
    }

    // ---------- sharp tiles when zoomed ----------
    private val settle = Runnable { updateTiles() }

    private fun scheduleSettle() {
        removeCallbacks(settle)
        postDelayed(settle, 140)
    }

    private fun baseBitmapWidth(p: PageItem): Int {
        val m = model ?: return 256
        val k1 = cw / m.visualW(p)
        val w = m.baseW(p) * k1
        return (((w / 32f).roundToInt()) * 32).coerceIn(64, 2400)
    }

    private fun updateTiles() {
        val m = model ?: return
        val e = engine ?: return
        if (scaling || !scroller.isFinished || zoomAnim?.isRunning == true) { scheduleSettle(); return }
        val range = visibleRange()
        val keep = HashSet<Long>()
        val reqs = ArrayList<TileRequest>()
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        for (i in range[0]..range[1]) {
            val p = m.pages[i]
            val bw = m.baseW(p)
            val bh = m.baseH(p)
            val sNow = pageScale(i)
            val baseScale = baseBitmapWidth(p) / bw
            if (sNow <= baseScale * 1.15f) continue
            pageRect(i, rTmp)
            val vis = RectF()
            if (!vis.setIntersect(rTmp, view)) continue
            pageMatrix(i, mtx)
            mtx.invert(inv)
            inv.mapRect(vis)
            if (!vis.intersect(0f, 0f, bw, bh)) continue
            keep += p.id
            val t = e.tile(p.id)
            if (t != null && abs(t.scale - sNow) / sNow < 0.03f && t.region.contains(vis)) continue
            reqs += TileRequest(p, vis, sNow)
        }
        e.dropTilesExcept(keep)
        if (reqs.isNotEmpty()) e.requestTiles(reqs) else invalidate()
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val m = model ?: return
        val e = engine ?: return
        if (tops.size != m.pages.size) relayout(true)
        if (tops.isEmpty()) return
        val range = visibleRange()
        val want = HashSet<Long>()
        for (i in max(0, range[0] - 2)..min(tops.size - 1, range[1] + 2)) want += m.pages[i].id
        e.wanted = want
        for (i in range[0]..range[1]) drawPage(canvas, i, m, e)
        for (i in intArrayOf(range[0] - 1, range[1] + 1, range[1] + 2)) {
            if (i in tops.indices) e.requestBase(m.pages[i], baseBitmapWidth(m.pages[i]))
        }
        drawMarquee(canvas)
        drawThumb(canvas)
    }

    private fun drawPage(c: Canvas, i: Int, m: DocModel, e: RenderEngine) {
        val p = m.pages[i]
        pageRect(i, rTmp)
        c.drawRect(rTmp.left - 1, rTmp.top - 1, rTmp.right + 1, rTmp.bottom + 1, borderPaint)
        pagePaint.color = if (nightMode) Color.rgb(30, 30, 30) else Color.WHITE
        c.drawRect(rTmp, pagePaint)
        val bw = m.baseW(p)
        val bh = m.baseH(p)
        pageMatrix(i, mtx)
        bmpPaint.colorFilter = if (nightMode) invertFilter else null
        val wantW = baseBitmapWidth(p)
        var bmp = e.base(p, wantW)
        if (bmp == null) {
            e.requestBase(p, wantW)
            bmp = e.anyBase(p, wantW)
        }
        if (bmp != null) {
            drawM.set(mtx)
            drawM.preScale(bw / bmp.width, bh / bmp.height)
            c.drawBitmap(bmp, drawM, bmpPaint)
        }
        val tile = e.tile(p.id)
        if (tile != null) {
            drawM.set(mtx)
            drawM.preTranslate(tile.region.left, tile.region.top)
            drawM.preScale(tile.region.width() / tile.bitmap.width, tile.region.height() / tile.bitmap.height)
            c.drawBitmap(tile.bitmap, drawM, bmpPaint)
        }

        c.save()
        c.concat(mtx)
        c.clipRect(0f, 0f, bw, bh)
        searchHits[p.id]?.forEach { c.drawRect(it, hitPaint) }
        if (currentHitPage == p.id) currentHit?.let { c.drawRect(it, hitCurrentPaint) }
        for (a in p.annots) {
            if (a.id in hidden || a.id == previewHideId && previewPageId == p.id) continue
            if (drag != null && drag?.pageId == p.id && drag?.original === a) continue
            painter.draw(c, a)
        }
        drag?.let { if (it.pageId == p.id) painter.draw(c, it.current) }
        if (previewPageId == p.id) previewAnnots.forEach { painter.draw(c, it) }
        if (strokePage == i) {
            livePaint.color = if (tool == Tool.HIGHLIGHT) hlColor else penColor
            livePaint.strokeWidth = if (tool == Tool.HIGHLIGHT) hlWidth else penWidth
            livePaint.strokeCap = if (tool == Tool.HIGHLIGHT) Paint.Cap.SQUARE else Paint.Cap.ROUND
            c.drawPath(livePath, livePaint)
        }
        liveShape?.let { if (shapePage == i) painter.draw(c, it) }
        c.restore()

        val sel = selAnnot
        if (sel != null && selPageId == p.id && tool == Tool.SELECT) drawSelection(c, i, drag?.current ?: sel)
    }

    private fun selectionCorners(a: Annot): FloatArray =
        if (a is Boxed) Geo.corners(a.cx, a.cy, a.boxW, a.boxH, a.rot)
        else a.bounds().let { floatArrayOf(it.left, it.top, it.right, it.top, it.right, it.bottom, it.left, it.bottom) }

    private fun drawSelection(c: Canvas, i: Int, a: Annot) {
        val corners = selectionCorners(a)
        pageMatrix(i, mtx)
        mtx.mapPoints(corners)
        val path = Path()
        path.moveTo(corners[0], corners[1])
        for (k in 1 until 4) path.lineTo(corners[k * 2], corners[k * 2 + 1])
        path.close()
        val pad = 4f * density
        c.save()
        c.drawPath(path, selPaint)
        c.restore()
        val hx = corners[4]
        val hy = corners[5]
        c.drawCircle(hx + pad * 0.3f, hy + pad * 0.3f, 9f * density, handleFill)
        c.drawCircle(hx + pad * 0.3f, hy + pad * 0.3f, 9f * density, handleRing)
    }

    private fun handlePoint(i: Int, a: Annot): FloatArray {
        val corners = selectionCorners(a)
        pageMatrix(i, mtx)
        mtx.mapPoints(corners)
        return floatArrayOf(corners[4], corners[5])
    }

    // =====================================================================
    // Selection, previews, search highlights
    // =====================================================================

    fun selection(): Pair<PageItem, Annot>? {
        val m = model ?: return null
        val a = selAnnot ?: return null
        val p = m.page(selPageId) ?: return null
        return p to a
    }

    fun select(pageId: Long?, a: Annot?) {
        val changed = selAnnot !== a
        selPageId = pageId ?: -1L
        selAnnot = a
        if (changed) host?.onSelectionChanged()
        invalidate()
    }

    private fun validateSelection() {
        val a = selAnnot ?: return
        val p = model?.page(selPageId)
        if (p == null) { select(null, null); return }
        if (p.annots.any { it === a }) return
        val byId = p.annots.firstOrNull { it.id == a.id }
        if (byId != null) { selAnnot = byId; host?.onSelectionChanged() } else select(null, null)
    }

    fun setPreview(pageId: Long, annots: List<Annot>, hideId: Long = -1L) {
        previewPageId = pageId
        previewAnnots = annots
        previewHideId = hideId
        invalidate()
    }

    fun clearPreview() = setPreview(-1L, emptyList(), -1L)

    fun setSearchHits(hits: Map<Long, List<RectF>>) {
        searchHits = hits
        invalidate()
    }

    fun setCurrentHit(pageId: Long, r: RectF?) {
        currentHitPage = pageId
        currentHit = r
        invalidate()
    }

    private fun annotAt(x: Float, y: Float): Pair<Long, Annot>? {
        val m = model ?: return null
        val i = pageAt(x, y)
        if (i < 0) return null
        val p = m.pages[i]
        val b = FloatArray(2)
        toBase(i, x, y, b)
        val tol = 8f * density / pageScale(i)
        for (a in p.annots.asReversed()) if (a.hit(b[0], b[1], tol)) return p.id to a
        return null
    }

    private fun tapSelect(x: Float, y: Float) {
        val hit = annotAt(x, y)
        if (hit != null) select(hit.first, hit.second) else select(null, null)
    }

    // =====================================================================
    // Touch
    // =====================================================================

    private class Drag(val pageId: Long, val pageIndex: Int, val original: Annot, val resize: Boolean,
                       val startX: Float, val startY: Float, val cx: Float, val cy: Float) {
        var current: Annot = original
    }

    private var drag: Drag? = null

    // stroke
    private var strokePage = -1
    private var strokePts = FloatArray(256)
    private var strokeN = 0
    private val livePath = Path()

    // shape
    private var shapePage = -1
    private var shapeStart = FloatArray(2)
    private var liveShape: ShapeAnnot? = null

    // eraser
    private var erasePage = -1
    private val erased = ArrayList<Pair<Long, Annot>>()

    // replace marquee (screen coords)
    private var marquee: RectF? = null
    private var marqueePage = -1

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (model == null) return false
        if (thumbTouch(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                zoomAnim?.cancel()
                downX = e.x
                downY = e.y
                mode = when {
                    startTool(e) -> Mode.TOOL
                    tool == Tool.TEXT || tool == Tool.MARK || tool == Tool.STAMP || tool == Tool.SIGN ||
                        tool == Tool.REPLACE -> Mode.PENDING
                    else -> Mode.NAV
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.TOOL || mode == Mode.PENDING) cancelTool()
                mode = Mode.NAV
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.TOOL) moveTool(e)
                else if (mode == Mode.PENDING && hypot(e.x - downX, e.y - downY) > touchSlop) mode = Mode.NAV
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.TOOL) endTool(e)
                else if (mode == Mode.PENDING) {
                    val i = pageAt(e.x, e.y)
                    if (i >= 0) {
                        val b = FloatArray(2)
                        toBase(i, e.x, e.y, b)
                        if (tool == Tool.REPLACE) host?.onReplaceTap(i, b[0], b[1]) else host?.onPlace(i, b[0], b[1])
                    }
                }
                mode = Mode.NONE
                scheduleSettle()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTool()
                mode = Mode.NONE
            }
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    private fun startTool(e: MotionEvent): Boolean {
        val i = pageAt(e.x, e.y)
        when (tool) {
            Tool.READ, Tool.TEXT, Tool.MARK, Tool.STAMP, Tool.SIGN -> return false
            Tool.SELECT -> {
                val sel = selAnnot ?: return false
                val m = model ?: return false
                val si = m.indexOfPage(selPageId)
                if (si < 0) return false
                val b = FloatArray(2)
                toBase(si, e.x, e.y, b)
                val bounds = sel.bounds()
                val c = if (sel is Boxed) floatArrayOf(sel.cx, sel.cy) else floatArrayOf(bounds.centerX(), bounds.centerY())
                val hp = handlePoint(si, sel)
                if (hypot(e.x - hp[0], e.y - hp[1]) < 26f * density) {
                    drag = Drag(selPageId, si, sel, true, b[0], b[1], c[0], c[1])
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (si == i && sel.hit(b[0], b[1], 10f * density / pageScale(si))) {
                    drag = Drag(selPageId, si, sel, false, b[0], b[1], c[0], c[1])
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            Tool.PEN, Tool.HIGHLIGHT -> {
                if (i < 0) return true
                strokePage = i
                strokeN = 0
                livePath.reset()
                val b = FloatArray(2)
                toBase(i, e.x, e.y, b)
                addStrokePoint(b[0], b[1], force = true)
                livePath.moveTo(b[0], b[1])
                livePath.lineTo(b[0] + 0.01f, b[1])
                invalidate()
                return true
            }
            Tool.ERASER -> {
                erasePage = i
                erased.clear()
                eraseAt(e.x, e.y)
                return true
            }
            Tool.SHAPE -> {
                if (i < 0) return true
                shapePage = i
                toBase(i, e.x, e.y, shapeStart)
                liveShape = null
                return true
            }
            Tool.REPLACE -> return false
        }
        return false
    }

    private fun addStrokePoint(x: Float, y: Float, force: Boolean = false) {
        if (!force && strokeN > 0) {
            val lx = strokePts[(strokeN - 1) * 2]
            val ly = strokePts[(strokeN - 1) * 2 + 1]
            val minD = 1.2f * density / pageScale(strokePage)
            if (hypot(x - lx, y - ly) < minD) return
        }
        if ((strokeN + 1) * 2 > strokePts.size) strokePts = strokePts.copyOf(strokePts.size * 2)
        strokePts[strokeN * 2] = x
        strokePts[strokeN * 2 + 1] = y
        strokeN++
    }

    private fun moveTool(e: MotionEvent) {
        when (tool) {
            Tool.SELECT -> {
                val d = drag ?: return
                val b = FloatArray(2)
                toBase(d.pageIndex, e.x, e.y, b)
                d.current = if (d.resize) {
                    val d0 = hypot(d.startX - d.cx, d.startY - d.cy).coerceAtLeast(1f)
                    val d1 = hypot(b[0] - d.cx, b[1] - d.cy)
                    d.original.scaled((d1 / d0).coerceIn(0.1f, 20f), d.cx, d.cy)
                } else {
                    d.original.translated(b[0] - d.startX, b[1] - d.startY)
                }
                invalidate()
            }
            Tool.PEN, Tool.HIGHLIGHT -> {
                if (strokePage < 0) return
                val b = FloatArray(2)
                for (h in 0 until e.historySize) {
                    toBase(strokePage, e.getHistoricalX(h), e.getHistoricalY(h), b)
                    appendLive(b[0], b[1])
                }
                toBase(strokePage, e.x, e.y, b)
                appendLive(b[0], b[1])
                invalidate()
            }
            Tool.ERASER -> {
                for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(h), e.getHistoricalY(h))
                eraseAt(e.x, e.y)
            }
            Tool.SHAPE -> {
                if (shapePage < 0) return
                val b = FloatArray(2)
                toBase(shapePage, e.x, e.y, b)
                liveShape = ShapeAnnot(0, shapeKind, shapeStart[0], shapeStart[1], b[0], b[1], shapeColor, shapeWidth)
                invalidate()
            }
            Tool.REPLACE -> {
                val r = marquee ?: return
                r.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                invalidate()
            }
            else -> {}
        }
    }

    private fun appendLive(x: Float, y: Float) {
        val before = strokeN
        addStrokePoint(x, y)
        if (strokeN > before && strokeN >= 2) {
            val px = strokePts[(strokeN - 2) * 2]
            val py = strokePts[(strokeN - 2) * 2 + 1]
            livePath.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
        }
    }

    private fun endTool(e: MotionEvent) {
        val m = model ?: return
        when (tool) {
            Tool.SELECT -> {
                val d = drag ?: return
                drag = null
                if (d.current !== d.original) {
                    host?.onCommit(ReplaceAnnot(d.pageId, d.original, d.current))
                    select(d.pageId, d.current)
                }
                invalidate()
            }
            Tool.PEN, Tool.HIGHLIGHT -> {
                val i = strokePage
                strokePage = -1
                if (i < 0 || strokeN == 0) { invalidate(); return }
                val hl = tool == Tool.HIGHLIGHT
                var p = strokePts.copyOf(strokeN * 2)
                if (hl) p = straighten(p, hlWidth)
                val a = InkAnnot(Ids.new(), p, if (hl) hlColor else penColor, if (hl) hlWidth else penWidth, hl)
                host?.onCommit(AddAnnot(m.pages[i].id, a))
                livePath.reset()
                invalidate()
            }
            Tool.ERASER -> {
                if (erased.isNotEmpty()) {
                    host?.onCommit(Batch(erased.map { RemoveAnnot(it.first, it.second) }))
                }
                erased.clear()
                hidden.clear()
                invalidate()
            }
            Tool.SHAPE -> {
                val s = liveShape
                val i = shapePage
                liveShape = null
                shapePage = -1
                if (s != null && i >= 0) {
                    val b = s.bounds()
                    if (max(b.width(), b.height()) > 4f) host?.onCommit(AddAnnot(m.pages[i].id, s.copy(id = Ids.new())))
                }
                invalidate()
            }
            Tool.REPLACE -> {
                val r = marquee
                val i = marqueePage
                marquee = null
                invalidate()
                if (r == null || i < 0) return
                if (r.width() < touchSlop && r.height() < touchSlop) {
                    val b = FloatArray(2)
                    toBase(i, e.x, e.y, b)
                    host?.onReplaceTap(i, b[0], b[1])
                } else {
                    pageMatrix(i, mtx)
                    mtx.invert(inv)
                    val rr = RectF(r)
                    inv.mapRect(rr)
                    host?.onReplaceRect(i, rr)
                }
            }
            else -> {}
        }
    }

    private fun cancelTool() {
        drag = null
        strokePage = -1
        livePath.reset()
        liveShape = null
        shapePage = -1
        marquee = null
        if (erased.isNotEmpty()) {
            host?.onCommit(Batch(erased.map { RemoveAnnot(it.first, it.second) }))
            erased.clear()
        }
        hidden.clear()
        invalidate()
    }

    /** A nearly straight highlighter stroke becomes a clean straight bar. */
    private fun straighten(p: FloatArray, width: Float): FloatArray {
        val n = p.size / 2
        if (n < 3) return p
        val x1 = p[0]; val y1 = p[1]; val x2 = p[p.size - 2]; val y2 = p[p.size - 1]
        val len = hypot(x2 - x1, y2 - y1)
        if (len < width * 2.5f) return p
        var maxDev = 0f
        for (i in 0 until n) maxDev = max(maxDev, Geo.distToSegment(p[i * 2], p[i * 2 + 1], x1, y1, x2, y2))
        if (maxDev > width * 0.7f) return p
        val ang = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
        val flat = abs(ang) < 7 || abs(abs(ang) - 180) < 7
        return if (flat) {
            val y = (y1 + y2) / 2f
            floatArrayOf(x1, y, x2, y)
        } else floatArrayOf(x1, y1, x2, y2)
    }

    private fun eraseAt(x: Float, y: Float) {
        val m = model ?: return
        val i = pageAt(x, y)
        if (i < 0) return
        val p = m.pages[i]
        val b = FloatArray(2)
        toBase(i, x, y, b)
        val tol = 10f * density / pageScale(i)
        var changed = false
        for (a in p.annots) {
            if (a.id in hidden) continue
            if (a !is InkAnnot && a !is ShapeAnnot && a !is MarkAnnot) continue
            if (a.hit(b[0], b[1], tol)) {
                hidden += a.id
                erased += p.id to a
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun drawMarquee(c: Canvas) {
        val r = marquee ?: return
        c.drawRect(r, marqueeFill)
        c.drawRect(r, marqueeStroke)
    }

    // =====================================================================
    // Fast-scroll handle
    // =====================================================================

    private var thumbAlpha = 0f
    private var thumbDragging = false
    private val hideThumb = Runnable {
        ValueAnimator.ofFloat(thumbAlpha, 0f).apply {
            duration = 300
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun thumbEnabled() = docH * zoom > height * 2.5f && pageCount > 2

    private fun showThumb() {
        if (!thumbEnabled()) return
        thumbAlpha = 1f
        removeCallbacks(hideThumb)
        postDelayed(hideThumb, 1400)
    }

    private val thumbW get() = 6f * density
    private val thumbH get() = 54f * density
    private fun thumbOnLeft() = layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun thumbTop(): Float {
        val range = maxSy() - minSy()
        val frac = if (range <= 0f) 0f else (sy - minSy()) / range
        val track = height - insetTop - insetBottom - thumbH - 16f * density
        return insetTop + 8f * density + track * frac
    }

    private fun drawThumb(c: Canvas) {
        if (!thumbEnabled() || (thumbAlpha <= 0f && !thumbDragging)) return
        val a = if (thumbDragging) 1f else thumbAlpha
        val top = thumbTop()
        val x = if (thumbOnLeft()) 4f * density else width - 4f * density - thumbW
        thumbPaint.alpha = (a * 230).toInt()
        rTmp.set(x, top, x + thumbW, top + thumbH)
        c.drawRoundRect(rTmp, thumbW, thumbW, thumbPaint)
        if (thumbDragging) {
            val label = "${currentPage() + 1} / $pageCount"
            val bw = bubbleText.measureText(label) + 28f * density
            val bh = 36f * density
            val bx = if (thumbOnLeft()) x + thumbW + 14f * density else x - 14f * density - bw
            val by = top + thumbH / 2 - bh / 2
            rTmp.set(bx, by, bx + bw, by + bh)
            c.drawRoundRect(rTmp, bh / 2, bh / 2, bubblePaint)
            c.drawText(label, rTmp.centerX(), rTmp.centerY() - (bubbleText.ascent() + bubbleText.descent()) / 2, bubbleText)
        }
    }

    private fun thumbTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!thumbEnabled() || thumbAlpha <= 0.05f) return false
                val x = if (thumbOnLeft()) 0f else width - 36f * density
                val top = thumbTop()
                if (e.x >= x && e.x <= x + 36f * density && e.y >= top - 16f * density && e.y <= top + thumbH + 16f * density) {
                    thumbDragging = true
                    downY = e.y - top
                    removeCallbacks(hideThumb)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!thumbDragging) return false
                val track = height - insetTop - insetBottom - thumbH - 16f * density
                val frac = ((e.y - downY - insetTop - 8f * density) / track.coerceAtLeast(1f)).coerceIn(0f, 1f)
                sy = minSy() + (maxSy() - minSy()) * frac
                clampScroll()
                reportPage()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!thumbDragging) return false
                thumbDragging = false
                engine?.clearTiles()
                showThumb()
                scheduleSettle()
                invalidate()
                return true
            }
        }
        return thumbDragging
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settle)
        removeCallbacks(hideThumb)
        zoomAnim?.cancel()
    }
}
