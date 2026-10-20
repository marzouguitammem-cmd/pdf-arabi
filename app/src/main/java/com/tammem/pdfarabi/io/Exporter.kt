package com.tammem.pdfarabi.io

import android.graphics.pdf.PdfDocument
import com.tammem.pdfarabi.model.CoverAnnot
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.render.AnnotPainter
import com.tammem.pdfarabi.text.PdfBox
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Writes the document: original pages (reordered/rotated/merged as the user arranged them) with
 * everything the user added drawn on top as real vector content (text stays sharp, Arabic is
 * shaped by Android's text engine before it is written).
 */
object Exporter {

    class Options(
        val removeText: Boolean,
        val password: String? = null,
        /** Only these pages (e.g. "extract pages"); null = whole document. */
        val pages: List<PageItem>? = null
    )

    private class Geometry(val r: FloatArray, val dw: Float, val dh: Float, val sx: Float, val sy: Float)

    /** Display (bottom-left origin, points) -> PDF user space, for the page's own /Rotate and crop box. */
    private fun displayToUser(page: PDPage): Pair<FloatArray, FloatArray> {
        val rot = ((page.rotation % 360) + 360) % 360
        val crop = page.cropBox
        val w = crop.width
        val h = crop.height
        val llx = crop.lowerLeftX
        val lly = crop.lowerLeftY
        val m = when (rot) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + w, lly)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + w, lly + h)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + h)
            else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
        }
        val dims = if (rot % 180 == 0) floatArrayOf(w, h) else floatArrayOf(h, w)
        return m to dims
    }

    private fun apply(m: FloatArray, x: Float, y: Float) =
        floatArrayOf(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])

    fun export(model: DocModel, out: File, opt: Options, tmpDir: File) {
        val pages = opt.pages ?: model.pages.toList()
        require(pages.isNotEmpty()) { "no pages" }
        val docs = HashMap<Int, PDDocument>()
        var overlayDoc: PDDocument? = null
        var target: PDDocument? = null
        var overlayFile: File? = null
        try {
            for (s in pages.map { it.src }.distinct()) {
                val d = PdfBox.load(model.sources[s].file)
                if (d.isEncrypted) d.isAllSecurityToBeRemoved = true
                docs[s] = d
            }
            val inPlace = opt.pages == null && model.isOriginalStructure()
            val tgt = if (inPlace) docs.getValue(0) else PDDocument()
            target = tgt
            val targetPages = ArrayList<PDPage>(pages.size)
            for ((k, item) in pages.withIndex()) {
                if (inPlace) {
                    targetPages += tgt.getPage(k)
                } else {
                    val src = docs.getValue(item.src).getPage(item.srcPage)
                    val imp = tgt.importPage(src)
                    if (!imp.cosObject.containsKey(COSName.RESOURCES) && src.resources != null) imp.resources = src.resources
                    targetPages += imp
                }
            }

            // Page geometry, true removal of replaced text, and rotation.
            val geo = ArrayList<Geometry>(pages.size)
            for ((k, item) in pages.withIndex()) {
                val page = targetPages[k]
                val (r, dims) = displayToUser(page)
                val dw = dims[0]
                val dh = dims[1]
                val bw = model.baseW(item)
                val bh = model.baseH(item)
                val g = Geometry(r, dw, dh, dw / bw, dh / bh)
                geo += g
                if (opt.removeText) {
                    val rects = item.annots.filterIsInstance<CoverAnnot>().filter { it.removeText }.map { c ->
                        val x0 = c.l * g.sx
                        val x1 = c.r * g.sx
                        val y0 = dh - c.b * g.sy
                        val y1 = dh - c.t * g.sy
                        val p = listOf(apply(r, x0, y0), apply(r, x1, y0), apply(r, x0, y1), apply(r, x1, y1))
                        Redactor.URect(p.minOf { it[0] }, p.minOf { it[1] }, p.maxOf { it[0] }, p.maxOf { it[1] })
                    }
                    if (rects.isNotEmpty()) runCatching { Redactor.redact(tgt, page, rects) }
                }
                if (item.rot != 0) {
                    val orig = ((page.rotation % 360) + 360) % 360
                    page.rotation = (orig + item.rot) % 360
                }
            }

            // Everything the user added, drawn by Android into an overlay PDF, then stamped on each page.
            val withAnnots = pages.indices.filter { pages[it].annots.isNotEmpty() }
            if (withAnnots.isNotEmpty()) {
                val pdf = PdfDocument()
                val painter = AnnotPainter(true)
                val heightsInt = IntArray(withAnnots.size)
                for ((n, k) in withAnnots.withIndex()) {
                    val item = pages[k]
                    val bw = model.baseW(item)
                    val bh = model.baseH(item)
                    val wi = max(1, ceil(bw.toDouble()).toInt())
                    val hi = max(1, ceil(bh.toDouble()).toInt())
                    heightsInt[n] = hi
                    val info = PdfDocument.PageInfo.Builder(wi, hi, n + 1).create()
                    val pg = pdf.startPage(info)
                    val c = pg.canvas
                    c.clipRect(0f, 0f, bw, bh)
                    for (a in item.annots) painter.draw(c, a)
                    pdf.finishPage(pg)
                }
                val of = File(tmpDir, "overlay-${System.nanoTime()}.pdf")
                overlayFile = of
                FileOutputStream(of).use { pdf.writeTo(it) }
                pdf.close()
                val od = PdfBox.load(of)
                overlayDoc = od
                val layer = LayerUtility(tgt)
                for ((n, k) in withAnnots.withIndex()) {
                    val form = layer.importPageAsForm(od, n)
                    val g = geo[k]
                    val r = g.r
                    PDPageContentStream(tgt, targetPages[k], PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(r[0], r[1], r[2], r[3], r[4], r[5]))
                        cs.transform(Matrix(g.sx, 0f, 0f, g.sy, 0f, g.dh - heightsInt[n] * g.sy))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                    }
                }
            }

            val pw = opt.password
            if (!pw.isNullOrEmpty()) {
                tgt.isAllSecurityToBeRemoved = false
                val policy = StandardProtectionPolicy(pw, pw, AccessPermission())
                policy.encryptionKeyLength = 256
                tgt.protect(policy)
            }
            out.parentFile?.mkdirs()
            tgt.save(out)
        } finally {
            runCatching { overlayDoc?.close() }
            val t = target
            if (t != null && !docs.values.any { it === t }) runCatching { t.close() }
            docs.values.forEach { runCatching { it.close() } }
            overlayFile?.delete()
        }
    }

    /** Small helper used by search/replace: page size of a source page the way PDFBox sees it. */
    fun clampRect(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)
}
