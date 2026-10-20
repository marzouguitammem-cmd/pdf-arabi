package com.tammem.pdfarabi.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.LruCache
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.model.ALIGN_LEFT
import com.tammem.pdfarabi.model.ALIGN_RIGHT
import com.tammem.pdfarabi.model.TextAnnot
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Laid-out text of a text annotation: one StaticLayout per paragraph so each gets its own direction. */
class TextBlock(
    private val layouts: List<StaticLayout>,
    val textW: Float,
    val textH: Float,
    val pad: Float,
    val firstBaseline: Float
) {
    val boxW: Float get() = textW + pad * 2
    val boxH: Float get() = textH + pad * 2

    /** Draws the text with its top-left corner at (pad, pad) of the current canvas origin. */
    fun draw(c: Canvas) {
        c.save()
        c.translate(pad, pad)
        for (l in layouts) {
            l.draw(c)
            c.translate(0f, l.height.toFloat())
        }
        c.restore()
    }
}

object TextBlocks {
    private val cache = LruCache<String, TextBlock>(300)

    private fun key(a: TextAnnot) =
        "${a.text}\u0001${a.font}\u0001${a.size}\u0001${a.bold}\u0001${a.align}\u0001${a.wrap}\u0001${a.color}\u0001${a.bg != 0 || a.border}"

    /** Cached layout, for the UI thread. */
    fun get(a: TextAnnot): TextBlock {
        val k = key(a)
        cache.get(k)?.let { return it }
        val b = build(a)
        cache.put(k, b)
        return b
    }

    fun isRtl(s: CharSequence): Boolean? {
        for (ch in s) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return null
    }

    fun paintFor(a: TextAnnot): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = Fonts.typeface(a.font, a.bold)
            textSize = a.size
            color = a.color
        }

    /** Fresh layout (safe to use on a background thread). */
    fun build(a: TextAnnot): TextBlock {
        val paint = paintFor(a)
        val text = if (a.text.isEmpty()) " " else a.text
        val paras = text.split('\n')
        val globalRtl = isRtl(text) ?: (a.align == ALIGN_RIGHT)
        var desired = 0f
        for (p in paras) desired = max(desired, Layout.getDesiredWidth(p, paint))
        val maxW = if (a.wrap > 0f) a.wrap else Float.MAX_VALUE
        val width = ceil(min(desired, maxW).toDouble()).toInt().coerceAtLeast(max(2, (a.size * 0.4f).toInt())) + 1
        val layouts = paras.map { p ->
            val rtl = isRtl(p) ?: globalRtl
            val alignment = when (a.align) {
                ALIGN_RIGHT -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
                ALIGN_LEFT -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                else -> Layout.Alignment.ALIGN_CENTER
            }
            StaticLayout.Builder.obtain(p, 0, p.length, paint, width)
                .setAlignment(alignment)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
        }
        var h = 0f
        for (l in layouts) h += l.height
        val pad = if (a.bg != 0 || a.border) a.size * 0.28f else a.size * 0.06f
        val firstBaseline = pad + (layouts.firstOrNull()?.getLineBaseline(0)?.toFloat() ?: a.size)
        return TextBlock(layouts, width.toFloat(), h, pad, firstBaseline)
    }
}
