package com.tammem.pdfarabi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tammem.pdfarabi.model.Annot
import com.tammem.pdfarabi.render.AnnotPainter

/** A page thumbnail (with the user's additions), rotated like the page. */
class ThumbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var rot = 0
    private var baseW = 595f
    private var baseH = 842f
    private var annots: List<Annot> = emptyList()
    private val painter = AnnotPainter(false)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val border = Paint().apply { color = 0x33000000; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val white = Paint().apply { color = Color.WHITE }
    private val m = Matrix()
    var marked = false
        set(v) { field = v; invalidate() }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f).toFloat()
        color = context.attrColor(androidx.appcompat.R.attr.colorPrimary)
    }

    fun set(bmp: Bitmap?, rotation: Int, w: Float, h: Float, a: List<Annot>) {
        val sizeChanged = rot != rotation || baseW != w || baseH != h
        bitmap = bmp
        rot = rotation
        baseW = w
        baseH = h
        annots = a
        if (sizeChanged) requestLayout()
        invalidate()
    }

    private val visualW get() = if (rot % 180 == 0) baseW else baseH
    private val visualH get() = if (rot % 180 == 0) baseH else baseW

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * visualH / visualW).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), white)
        val k = width / visualW
        m.reset()
        when (rot) {
            90 -> { m.setRotate(90f); m.postTranslate(baseH, 0f) }
            180 -> { m.setRotate(180f); m.postTranslate(baseW, baseH) }
            270 -> { m.setRotate(270f); m.postTranslate(0f, baseW) }
        }
        m.postScale(k, k)
        canvas.save()
        canvas.concat(m)
        bitmap?.let {
            canvas.save()
            canvas.scale(baseW / it.width, baseH / it.height)
            canvas.drawBitmap(it, 0f, 0f, bmpPaint)
            canvas.restore()
        }
        canvas.clipRect(0f, 0f, baseW, baseH)
        annots.forEach { painter.draw(canvas, it) }
        canvas.restore()
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, border)
        if (marked) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), markPaint)
    }
}
