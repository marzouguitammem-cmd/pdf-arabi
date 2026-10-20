package com.tammem.pdfarabi.io

import android.content.Context
import android.net.Uri
import com.tammem.pdfarabi.model.Annot
import com.tammem.pdfarabi.model.CoverAnnot
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.ImageAnnot
import com.tammem.pdfarabi.model.InkAnnot
import com.tammem.pdfarabi.model.MarkAnnot
import com.tammem.pdfarabi.model.MarkKind
import com.tammem.pdfarabi.model.Origin
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.model.PdfSource
import com.tammem.pdfarabi.model.ShapeAnnot
import com.tammem.pdfarabi.model.ShapeKind
import com.tammem.pdfarabi.model.SignAnnot
import com.tammem.pdfarabi.model.TextAnnot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Unsaved edits are kept on disk while you work, so nothing is lost if the phone kills the app.
 */
object Drafts {

    class Draft(
        val title: String,
        val origin: Origin,
        val password: String?,
        val sources: List<File>,
        val pages: List<PageItem>,
        val originalStamp: String,
        val time: Long
    )

    private fun fileFor(ctx: Context, key: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val hex = md.joinToString("") { "%02x".format(it) }
        return File(Storage.draftsDir(ctx), "$hex.json")
    }

    private fun floats(a: FloatArray): JSONArray = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
    private fun floatsOf(j: JSONArray?): FloatArray = if (j == null) FloatArray(0) else FloatArray(j.length()) { j.optDouble(it).toFloat() }
    private fun f(o: JSONObject, k: String) = o.optDouble(k, 0.0).toFloat()

    fun annotToJson(a: Annot): JSONObject {
        val o = JSONObject().put("id", a.id)
        when (a) {
            is InkAnnot -> o.put("t", "ink").put("pts", floats(a.pts)).put("c", a.color).put("w", a.width.toDouble()).put("hl", a.highlighter)
            is ShapeAnnot -> o.put("t", "shape").put("k", a.kind.name).put("x1", a.x1.toDouble()).put("y1", a.y1.toDouble())
                .put("x2", a.x2.toDouble()).put("y2", a.y2.toDouble()).put("c", a.color).put("w", a.width.toDouble()).put("fill", a.fill)
            is TextAnnot -> o.put("t", "text").put("cx", a.cx.toDouble()).put("cy", a.cy.toDouble()).put("rot", a.rot.toDouble())
                .put("text", a.text).put("font", a.font).put("size", a.size.toDouble()).put("c", a.color).put("bold", a.bold)
                .put("align", a.align).put("bg", a.bg).put("border", a.border).put("wrap", a.wrap.toDouble())
            is CoverAnnot -> o.put("t", "cover").put("l", a.l.toDouble()).put("tp", a.t.toDouble()).put("r", a.r.toDouble())
                .put("b", a.b.toDouble()).put("c", a.color).put("rm", a.removeText)
            is ImageAnnot -> o.put("t", "image").put("cx", a.cx.toDouble()).put("cy", a.cy.toDouble()).put("w", a.boxW.toDouble())
                .put("h", a.boxH.toDouble()).put("rot", a.rot.toDouble()).put("path", a.path)
            is SignAnnot -> {
                val strokes = JSONArray()
                a.strokes.forEach { strokes.put(floats(it)) }
                o.put("t", "sign").put("cx", a.cx.toDouble()).put("cy", a.cy.toDouble()).put("w", a.boxW.toDouble())
                    .put("h", a.boxH.toDouble()).put("rot", a.rot.toDouble()).put("strokes", strokes).put("c", a.color)
                    .put("pen", a.penRel.toDouble())
            }
            is MarkAnnot -> o.put("t", "mark").put("cx", a.cx.toDouble()).put("cy", a.cy.toDouble()).put("size", a.size.toDouble())
                .put("rot", a.rot.toDouble()).put("k", a.kind.name).put("c", a.color)
        }
        return o
    }

    fun annotFromJson(o: JSONObject): Annot? {
        val id = o.optLong("id")
        return when (o.optString("t")) {
            "ink" -> InkAnnot(id, floatsOf(o.optJSONArray("pts")), o.optInt("c"), f(o, "w"), o.optBoolean("hl"))
            "shape" -> ShapeAnnot(id, runCatching { ShapeKind.valueOf(o.optString("k")) }.getOrDefault(ShapeKind.RECT),
                f(o, "x1"), f(o, "y1"), f(o, "x2"), f(o, "y2"), o.optInt("c"), f(o, "w"), o.optInt("fill"))
            "text" -> TextAnnot(id, f(o, "cx"), f(o, "cy"), f(o, "rot"), o.optString("text"), o.optString("font", "amiri"),
                f(o, "size"), o.optInt("c"), o.optBoolean("bold"), o.optInt("align"), o.optInt("bg"),
                o.optBoolean("border"), f(o, "wrap"))
            "cover" -> CoverAnnot(id, f(o, "l"), f(o, "tp"), f(o, "r"), f(o, "b"), o.optInt("c"), o.optBoolean("rm"))
            "image" -> ImageAnnot(id, f(o, "cx"), f(o, "cy"), f(o, "w"), f(o, "h"), f(o, "rot"), o.optString("path"))
            "sign" -> {
                val arr = o.optJSONArray("strokes") ?: JSONArray()
                val strokes = (0 until arr.length()).map { floatsOf(arr.optJSONArray(it)) }
                SignAnnot(id, f(o, "cx"), f(o, "cy"), f(o, "w"), f(o, "h"), f(o, "rot"), strokes, o.optInt("c"), f(o, "pen"))
            }
            "mark" -> MarkAnnot(id, f(o, "cx"), f(o, "cy"), f(o, "size"), f(o, "rot"),
                runCatching { MarkKind.valueOf(o.optString("k")) }.getOrDefault(MarkKind.CHECK), o.optInt("c"))
            else -> null
        }
    }

    private fun originToJson(o: Origin): JSONObject = when (o) {
        is Origin.Path -> JSONObject().put("type", "path").put("v", o.path)
        is Origin.Content -> JSONObject().put("type", "uri").put("v", o.uri.toString()).put("w", o.writable)
        Origin.None -> JSONObject().put("type", "none")
    }

    private fun originFromJson(o: JSONObject?): Origin = when (o?.optString("type")) {
        "path" -> Origin.Path(o.optString("v"))
        "uri" -> Origin.Content(Uri.parse(o.optString("v")), o.optBoolean("w"))
        else -> Origin.None
    }

    fun save(ctx: Context, key: String, model: DocModel, originalStamp: String) {
        val o = JSONObject()
        o.put("title", model.title)
        o.put("origin", originToJson(model.origin))
        model.password?.let { o.put("pw", it) }
        o.put("stamp", originalStamp)
        o.put("time", System.currentTimeMillis())
        o.put("sources", JSONArray().also { a -> model.sources.forEach { a.put(it.file.path) } })
        val pages = JSONArray()
        for (p in model.pages) {
            val annots = JSONArray()
            p.annots.forEach { annots.put(annotToJson(it)) }
            pages.put(JSONObject().put("id", p.id).put("src", p.src).put("sp", p.srcPage).put("rot", p.rot).put("a", annots))
        }
        o.put("pages", pages)
        val f = fileFor(ctx, key)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun load(ctx: Context, key: String): Draft? = runCatching {
        val f = fileFor(ctx, key)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val srcArr = o.getJSONArray("sources")
        val sources = (0 until srcArr.length()).map { File(srcArr.getString(it)) }
        if (sources.any { !it.exists() }) return null
        val pagesArr = o.getJSONArray("pages")
        val pages = ArrayList<PageItem>()
        for (i in 0 until pagesArr.length()) {
            val po = pagesArr.getJSONObject(i)
            val aa = po.optJSONArray("a") ?: JSONArray()
            val annots = ArrayList<Annot>()
            for (k in 0 until aa.length()) annotFromJson(aa.getJSONObject(k))?.let { annots += it }
            pages += PageItem(po.getLong("id"), po.getInt("src"), po.getInt("sp"), po.optInt("rot"), annots)
        }
        Draft(
            o.optString("title"), originFromJson(o.optJSONObject("origin")),
            if (o.has("pw")) o.optString("pw") else null, sources, pages, o.optString("stamp"), o.optLong("time")
        )
    }.getOrNull()

    fun delete(ctx: Context, key: String) {
        fileFor(ctx, key).delete()
    }

    fun has(ctx: Context, key: String) = fileFor(ctx, key).exists()

    /** Fills a model from a draft (sources still need opening). */
    fun applyTo(d: Draft, m: DocModel) {
        m.sources.clear()
        d.sources.forEach { m.sources += PdfSource(it) }
        m.pages.clear()
        m.pages.addAll(d.pages)
        m.title = d.title
        m.origin = d.origin
        m.password = d.password
    }
}
