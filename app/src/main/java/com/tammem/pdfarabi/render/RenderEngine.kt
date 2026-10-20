package com.tammem.pdfarabi.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.model.PdfSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A sharp rendering of the visible part of a zoomed page. */
class Tile(val pageId: Long, val region: RectF, val scale: Float, val bitmap: Bitmap)

class TileRequest(val page: PageItem, val region: RectF, val scale: Float)

/**
 * Renders pages with Android's built-in PDF engine (PDFium) on one background thread.
 * Keeps a cache of whole-page bitmaps plus one sharp tile per visible page when zoomed in.
 */
class RenderEngine(private val model: DocModel) {

    companion object {
        val dispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }.asCoroutineDispatcher()
        private const val MAX_PIXELS = 14_000_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>()

    private val baseCache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val tiles = HashMap<Long, Tile>()
    private var tileJob: Job? = null

    /** Page ids worth rendering right now (visible or close to it). */
    @Volatile
    var wanted: Set<Long> = emptySet()
    var onBitmapReady: (() -> Unit)? = null
    var onThumbReady: (() -> Unit)? = null
    @Volatile
    private var closed = false

    private fun cacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 5).coerceIn(48L * 1024 * 1024, 220L * 1024 * 1024).toInt()
    }

    // ---------------- render thread helpers ----------------

    private fun renderer(file: File): PdfRenderer {
        renderers[file.path]?.let { return it.second }
        check(!closed) { "closed" }
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
        renderers[file.path] = fd to r
        return r
    }

    private fun renderWhole(file: File, page: Int, widthPx: Int): Bitmap {
        val pg = renderer(file).openPage(page)
        try {
            var w = widthPx.coerceAtLeast(8)
            var h = max(1, (w.toFloat() * pg.height / pg.width.coerceAtLeast(1)).roundToInt())
            if (w.toLong() * h > MAX_PIXELS) {
                val f = sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h))
                w = max(8, (w * f).toInt())
                h = max(1, (h * f).toInt())
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            pg.close()
        }
    }

    private fun renderRegionNow(file: File, page: Int, region: RectF, scale: Float): Bitmap {
        val pg = renderer(file).openPage(page)
        try {
            var s = scale
            var bw = ceil(region.width() * s).toInt()
            var bh = ceil(region.height() * s).toInt()
            if (bw.toLong() * bh > MAX_PIXELS) {
                val f = sqrt(MAX_PIXELS.toDouble() / (bw.toDouble() * bh)).toFloat()
                s *= f
                bw = ceil(region.width() * s).toInt()
                bh = ceil(region.height() * s).toInt()
            }
            val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(s, s)
            m.postTranslate(-region.left * s, -region.top * s)
            pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            pg.close()
        }
    }

    // ---------------- public API ----------------

    /** Opens a source and reads its page sizes. Throws SecurityException for password-protected files. */
    suspend fun openSource(src: PdfSource) = withContext(dispatcher) {
        val r = renderer(src.file)
        val n = r.pageCount
        val w = FloatArray(n)
        val h = FloatArray(n)
        for (i in 0 until n) {
            val p = r.openPage(i)
            w[i] = p.width.toFloat().coerceAtLeast(1f)
            h[i] = p.height.toFloat().coerceAtLeast(1f)
            p.close()
        }
        src.pageCount = n
        src.widths = w
        src.heights = h
    }

    private fun baseKey(p: PageItem, w: Int) = "${model.sources[p.src].file.path}:${p.srcPage}:$w"

    fun base(p: PageItem, w: Int): Bitmap? = baseCache.get(baseKey(p, w))

    /** Any cached whole-page bitmap of this page (used while the right size is being rendered). */
    fun anyBase(p: PageItem, preferW: Int): Bitmap? {
        base(p, preferW)?.let { return it }
        val prefix = "${model.sources[p.src].file.path}:${p.srcPage}:"
        var best: Bitmap? = null
        for ((k, v) in baseCache.snapshot()) {
            if (k.startsWith(prefix) && (best == null || v.width > best.width)) best = v
        }
        return best
    }

    fun requestBase(p: PageItem, w: Int) {
        if (closed) return
        val key = baseKey(p, w)
        if (pending.contains(key) || baseCache.get(key) != null) return
        pending += key
        val file = model.sources[p.src].file
        val pageId = p.id
        val srcPage = p.srcPage
        scope.launch {
            val bmp = withContext(dispatcher) {
                if (closed || pageId !in wanted) null
                else runCatching { renderWhole(file, srcPage, w) }.getOrNull()
            }
            pending -= key
            if (bmp != null) {
                baseCache.put(key, bmp)
                onBitmapReady?.invoke()
            }
        }
    }

    fun tile(pageId: Long): Tile? = tiles[pageId]

    fun dropTilesExcept(keep: Set<Long>) {
        tiles.keys.retainAll(keep)
    }

    fun clearTiles() {
        tileJob?.cancel()
        tiles.clear()
    }

    fun requestTiles(reqs: List<TileRequest>) {
        tileJob?.cancel()
        if (closed || reqs.isEmpty()) return
        tileJob = scope.launch {
            for (r in reqs) {
                val file = model.sources[r.page.src].file
                val srcPage = r.page.srcPage
                val bmp = withContext(dispatcher) {
                    ensureActive()
                    runCatching { renderRegionNow(file, srcPage, r.region, r.scale) }.getOrNull()
                } ?: continue
                tiles[r.page.id] = Tile(r.page.id, RectF(r.region), r.scale, bmp)
                onBitmapReady?.invoke()
            }
        }
    }

    /** Renders part of a page right away (for analysing colours under text). */
    suspend fun renderRegion(p: PageItem, region: RectF, scale: Float): Bitmap? {
        val file = model.sources[p.src].file
        return withContext(dispatcher) {
            runCatching { renderRegionNow(file, p.srcPage, region, scale) }.getOrNull()
        }
    }

    suspend fun renderPageBitmap(p: PageItem, widthPx: Int): Bitmap? {
        val file = model.sources[p.src].file
        return withContext(dispatcher) { runCatching { renderWhole(file, p.srcPage, widthPx) }.getOrNull() }
    }

    fun thumb(p: PageItem, w: Int): Bitmap? = thumbCache.get(baseKey(p, w))

    fun requestThumb(p: PageItem, w: Int) {
        if (closed) return
        val key = "t" + baseKey(p, w)
        if (pending.contains(key) || thumbCache.get(baseKey(p, w)) != null) return
        pending += key
        val file = model.sources[p.src].file
        val srcPage = p.srcPage
        val cacheKey = baseKey(p, w)
        scope.launch {
            val bmp = withContext(dispatcher) {
                if (closed) null else runCatching { renderWhole(file, srcPage, w) }.getOrNull()
            }
            pending -= key
            if (bmp != null) {
                thumbCache.put(cacheKey, bmp)
                onThumbReady?.invoke()
            }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        tiles.clear()
        CoroutineScope(dispatcher).launch {
            for ((fd, r) in renderers.values) {
                runCatching { r.close() }
                runCatching { fd.close() }
            }
            renderers.clear()
        }
    }
}
