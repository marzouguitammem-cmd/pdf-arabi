package com.tammem.pdfarabi.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Creates new PDFs: from pictures, blank/lined/squared pages. */
object PdfMaker {
    const val A4_W = 595.28f
    const val A4_H = 841.89f

    const val STYLE_BLANK = 0
    const val STYLE_LINES = 1
    const val STYLE_GRID = 2
    const val STYLE_SEYES = 3

    /** Decodes a picture at a reasonable size with the camera orientation applied. */
    fun loadImage(ctx: Context, uri: Uri, maxSide: Int = 2400): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = cr.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        var out = bmp
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
        }
        val longSide = max(out.width, out.height)
        if (longSide > maxSide) {
            val f = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(out, max(1, (out.width * f).toInt()), max(1, (out.height * f).toInt()), true)
            if (scaled !== out) out.recycle()
            out = scaled
        }
        return out
    }

    /** Saves a picture for an image annotation (JPEG, in the app's private storage). */
    fun storeImage(ctx: Context, uri: Uri): File? {
        val bmp = loadImage(ctx, uri, 2000) ?: return null
        val f = File(Storage.imagesDir(ctx), "img-${System.currentTimeMillis()}.${if (bmp.hasAlpha()) "png" else "jpg"}")
        FileOutputStream(f).use {
            if (bmp.hasAlpha()) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        bmp.recycle()
        return f
    }

    /** One A4 page per picture (portrait or landscape to match the picture). */
    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: File, onProgress: (Int) -> Unit = {}) {
        PDDocument().use { doc ->
            uris.forEachIndexed { i, uri ->
                val bmp = loadImage(ctx, uri) ?: return@forEachIndexed
                val bytes = ByteArrayOutputStream().use { bos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                    bos.toByteArray()
                }
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) A4_H else A4_W
                val ph = if (landscape) A4_W else A4_H
                val page = PDPage(PDRectangle(pw, ph))
                doc.addPage(page)
                val img = JPEGFactory.createFromByteArray(doc, bytes)
                val margin = 14f
                val f = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val w = bmp.width * f
                val h = bmp.height * f
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, (pw - w) / 2f, (ph - h) / 2f, w, h)
                }
                bmp.recycle()
                onProgress(i + 1)
            }
            if (doc.numberOfPages == 0) error("no images")
            doc.save(out)
        }
    }

    /** Blank pages for writing: plain, lined, 5 mm squares, or French Seyès ruling. */
    fun blank(out: File, count: Int, style: Int, width: Float = A4_W, height: Float = A4_H) {
        PDDocument().use { doc ->
            repeat(max(1, count)) {
                val page = PDPage(PDRectangle(width, height))
                doc.addPage(page)
                if (style != STYLE_BLANK) {
                    PDPageContentStream(doc, page).use { cs -> drawRuling(cs, style, width, height) }
                }
            }
            doc.save(out)
        }
    }

    private fun drawRuling(cs: PDPageContentStream, style: Int, w: Float, h: Float) {
        val mm = 72f / 25.4f
        when (style) {
            STYLE_LINES -> {
                cs.setStrokingColor(0.62f, 0.75f, 0.9f)
                cs.setLineWidth(0.6f)
                var y = h - 70f
                while (y > 40f) {
                    cs.moveTo(36f, y); cs.lineTo(w - 36f, y); y -= 24f
                }
                cs.stroke()
                cs.setStrokingColor(0.9f, 0.45f, 0.45f)
                cs.setLineWidth(0.8f)
                cs.moveTo(w - 62f, 30f); cs.lineTo(w - 62f, h - 30f)
                cs.stroke()
            }
            STYLE_GRID -> {
                val step = 5f * mm
                cs.setStrokingColor(0.72f, 0.8f, 0.9f)
                cs.setLineWidth(0.35f)
                var x = 20f
                while (x < w - 18f) { cs.moveTo(x, 20f); cs.lineTo(x, h - 20f); x += step }
                var y = 20f
                while (y < h - 18f) { cs.moveTo(20f, y); cs.lineTo(w - 20f, y); y += step }
                cs.stroke()
            }
            STYLE_SEYES -> {
                val big = 8f * mm
                val small = 2f * mm
                val top = h - 40f
                val bottom = 40f
                cs.setStrokingColor(0.78f, 0.82f, 0.95f)
                cs.setLineWidth(0.3f)
                var y = top
                var i = 0
                while (y > bottom) {
                    if (i % 4 != 0) { cs.moveTo(24f, y); cs.lineTo(w - 24f, y) }
                    y -= small; i++
                }
                cs.stroke()
                cs.setStrokingColor(0.5f, 0.55f, 0.85f)
                cs.setLineWidth(0.6f)
                y = top
                while (y > bottom) { cs.moveTo(24f, y); cs.lineTo(w - 24f, y); y -= big }
                var x = 24f + big * 3
                while (x < w - 24f) { cs.moveTo(x, bottom); cs.lineTo(x, top); x += big }
                cs.stroke()
                cs.setStrokingColor(0.9f, 0.4f, 0.45f)
                cs.setLineWidth(0.8f)
                cs.moveTo(24f + big * 3 - 1f, bottom); cs.lineTo(24f + big * 3 - 1f, top)
                cs.stroke()
            }
        }
    }
}
