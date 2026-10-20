package com.tammem.pdfarabi.text

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Looks at the rendered pixels around some text to find the exact background colour (so a cover
 * blends in), the text colour, and the tight box of the ink — leaving table lines untouched.
 */
object RegionAnalyzer {

    class Result(val cover: RectF, val bg: Int, val fg: Int, val inkHeight: Float, val foundInk: Boolean)

    private fun median(h: IntArray, count: Int): Int {
        var acc = 0
        val half = count / 2
        for (i in 0 until 256) {
            acc += h[i]
            if (acc > half) return i
        }
        return 255
    }

    /**
     * [bmp] shows page area [region] (points) at [scale] px/pt. [limit] (points) is where the text
     * to cover can be; ink outside it is ignored.
     */
    fun analyze(bmp: Bitmap, region: RectF, scale: Float, limit: RectF): Result {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // Background = median colour of the outer frame.
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        var n = 0
        val frame = max(1, min(3, min(w, h) / 6))
        fun addBg(c: Int) { hr[Color.red(c)]++; hg[Color.green(c)]++; hb[Color.blue(c)]++; n++ }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y < frame || y >= h - frame || x < frame || x >= w - frame) addBg(px[y * w + x])
            }
        }
        val bgR = median(hr, n)
        val bgG = median(hg, n)
        val bgB = median(hb, n)
        val bg = Color.rgb(bgR, bgG, bgB)

        // Limit box in pixels.
        val lx0 = ((limit.left - region.left) * scale).toInt().coerceIn(0, w - 1)
        val ly0 = ((limit.top - region.top) * scale).toInt().coerceIn(0, h - 1)
        val lx1 = ((limit.right - region.left) * scale).toInt().coerceIn(lx0 + 1, w)
        val ly1 = ((limit.bottom - region.top) * scale).toInt().coerceIn(ly0 + 1, h)
        val lw = lx1 - lx0
        val lh = ly1 - ly0

        val thr = 52
        fun dist(c: Int) = max(abs(Color.red(c) - bgR), max(abs(Color.green(c) - bgG), abs(Color.blue(c) - bgB)))

        // Rows / columns that are almost fully inked are ruling lines (tables, underlines): skip them.
        val rowInk = IntArray(h)
        val colInk = IntArray(w)
        for (y in ly0 until ly1) for (x in lx0 until lx1) {
            if (dist(px[y * w + x]) > thr) { rowInk[y]++; colInk[x]++ }
        }
        val ruleRow = BooleanArray(h) { it in ly0 until ly1 && rowInk[it] > lw * 0.8f }
        val ruleCol = BooleanArray(w) { it in lx0 until lx1 && colInk[it] > lh * 0.8f }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        val inkR = IntArray(256)
        val inkG = IntArray(256)
        val inkB = IntArray(256)
        var inkN = 0
        val strongCut = thr + 60
        for (y in ly0 until ly1) {
            if (ruleRow[y]) continue
            for (x in lx0 until lx1) {
                if (ruleCol[x]) continue
                val c = px[y * w + x]
                val d = dist(c)
                if (d > thr) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (d > strongCut) {
                        inkR[Color.red(c)]++; inkG[Color.green(c)]++; inkB[Color.blue(c)]++; inkN++
                    }
                }
            }
        }

        if (maxX < 0) {
            return Result(RectF(limit), bg, Color.BLACK, limit.height(), false)
        }

        val fg = if (inkN > 0) Color.rgb(median(inkR, inkN), median(inkG, inkN), median(inkB, inkN)) else Color.BLACK
        val padPx = max(1.5f, scale * 0.8f)
        val cover = RectF(
            region.left + (minX - padPx) / scale,
            region.top + (minY - padPx) / scale,
            region.left + (maxX + 1 + padPx) / scale,
            region.top + (maxY + 1 + padPx) / scale
        )
        return Result(cover, bg, fg, (maxY - minY + 1) / scale, true)
    }
}
