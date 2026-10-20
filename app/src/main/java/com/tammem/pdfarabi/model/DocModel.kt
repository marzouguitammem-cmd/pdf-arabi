package com.tammem.pdfarabi.model

import android.net.Uri
import java.io.File

/** One PDF file the document takes pages from (a private working copy). */
class PdfSource(val file: File) {
    var pageCount = 0
    /** Page sizes in points, as displayed (the PDF's own /Rotate already applied). */
    var widths = FloatArray(0)
    var heights = FloatArray(0)
}

/** A page of the document: which source page it shows, the extra rotation, and what was added on it. */
class PageItem(
    val id: Long,
    val src: Int,
    val srcPage: Int,
    var rot: Int = 0,
    val annots: MutableList<Annot> = ArrayList()
) {
    fun copyAsNew(): PageItem = PageItem(Ids.new(), src, srcPage, rot, annots.map { it.withId(Ids.new()) }.toMutableList())
}

/** Where "save" writes to. */
sealed class Origin {
    data class Path(val path: String) : Origin()
    data class Content(val uri: Uri, val writable: Boolean) : Origin()
    object None : Origin()

    val key: String?
        get() = when (this) {
            is Path -> path
            is Content -> uri.toString()
            None -> null
        }
}

class DocModel {
    val sources = ArrayList<PdfSource>()
    val pages = ArrayList<PageItem>()
    var title: String = "ملف"
    var origin: Origin = Origin.None
    /** Password of the original file, re-applied when saving. */
    var password: String? = null
    val history = History(this)

    interface Listener {
        /** [structure] = pages were added/removed/reordered/rotated (layout must be recomputed). */
        fun onModelChanged(structure: Boolean)
    }

    private val listeners = ArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    fun notifyChanged(structure: Boolean) { listeners.toList().forEach { it.onModelChanged(structure) } }

    fun baseW(p: PageItem): Float = sources[p.src].widths.getOrElse(p.srcPage) { 595f }
    fun baseH(p: PageItem): Float = sources[p.src].heights.getOrElse(p.srcPage) { 842f }
    fun visualW(p: PageItem): Float = if (p.rot % 180 == 0) baseW(p) else baseH(p)
    fun visualH(p: PageItem): Float = if (p.rot % 180 == 0) baseH(p) else baseW(p)

    fun indexOfPage(id: Long): Int = pages.indexOfFirst { it.id == id }
    fun page(id: Long): PageItem? = pages.firstOrNull { it.id == id }

    fun hasAnnotations(): Boolean = pages.any { it.annots.isNotEmpty() }

    /** True when pages are exactly the single source's pages, in order. */
    fun isOriginalStructure(): Boolean {
        if (sources.size != 1) return false
        val s = sources[0]
        if (pages.size != s.pageCount) return false
        pages.forEachIndexed { i, p -> if (p.src != 0 || p.srcPage != i) return false }
        return true
    }
}

/** An undoable change. */
interface Cmd {
    val structural: Boolean get() = false
    fun apply(m: DocModel)
    fun revert(m: DocModel)
}

class AddAnnot(private val pageId: Long, private val a: Annot) : Cmd {
    private var index = -1
    override fun apply(m: DocModel) {
        val p = m.page(pageId) ?: return
        if (index < 0 || index > p.annots.size) index = p.annots.size
        p.annots.add(index, a)
    }
    override fun revert(m: DocModel) {
        m.page(pageId)?.annots?.removeAll { it === a }
    }
}

class RemoveAnnot(private val pageId: Long, private val a: Annot) : Cmd {
    private var index = -1
    override fun apply(m: DocModel) {
        val p = m.page(pageId) ?: return
        index = p.annots.indexOfFirst { it === a }
        if (index >= 0) p.annots.removeAt(index)
    }
    override fun revert(m: DocModel) {
        val p = m.page(pageId) ?: return
        if (index < 0) return
        p.annots.add(index.coerceAtMost(p.annots.size), a)
    }
}

class ReplaceAnnot(private val pageId: Long, private val old: Annot, private val new: Annot) : Cmd {
    override fun apply(m: DocModel) = swap(m, old, new)
    override fun revert(m: DocModel) = swap(m, new, old)
    private fun swap(m: DocModel, from: Annot, to: Annot) {
        val p = m.page(pageId) ?: return
        val i = p.annots.indexOfFirst { it === from }
        if (i >= 0) p.annots[i] = to
    }
}

class Batch(private val cmds: List<Cmd>) : Cmd {
    override val structural: Boolean get() = cmds.any { it.structural }
    override fun apply(m: DocModel) = cmds.forEach { it.apply(m) }
    override fun revert(m: DocModel) = cmds.asReversed().forEach { it.revert(m) }
}

/** Replaces the whole page list (reorder, delete, insert, duplicate). */
class SetPages(private val before: List<PageItem>, private val after: List<PageItem>) : Cmd {
    override val structural: Boolean get() = true
    override fun apply(m: DocModel) { m.pages.clear(); m.pages.addAll(after) }
    override fun revert(m: DocModel) { m.pages.clear(); m.pages.addAll(before) }
}

class RotatePages(private val ids: List<Long>, private val delta: Int) : Cmd {
    override val structural: Boolean get() = true
    override fun apply(m: DocModel) = ids.forEach { id -> m.page(id)?.let { it.rot = ((it.rot + delta) % 360 + 360) % 360 } }
    override fun revert(m: DocModel) = ids.forEach { id -> m.page(id)?.let { it.rot = ((it.rot - delta) % 360 + 360) % 360 } }
}

class History(private val model: DocModel) {
    private val undo = ArrayList<Cmd>()
    private val redo = ArrayList<Cmd>()
    /** Size of the undo stack when the file was last saved; -1 when that state can't be reached any more. */
    private var cleanAt = 0

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val dirty get() = undo.size != cleanAt

    fun push(c: Cmd) {
        c.apply(model)
        if (undo.size < cleanAt) cleanAt = -1
        undo += c
        redo.clear()
        model.notifyChanged(c.structural)
    }

    fun undo(): Boolean {
        val c = undo.removeLastOrNull() ?: return false
        c.revert(model)
        redo += c
        model.notifyChanged(c.structural)
        return true
    }

    fun redo(): Boolean {
        val c = redo.removeLastOrNull() ?: return false
        c.apply(model)
        undo += c
        model.notifyChanged(c.structural)
        return true
    }

    fun markClean() { cleanAt = undo.size; model.notifyChanged(false) }
    fun markDirty() { cleanAt = -1; model.notifyChanged(false) }

    fun clear() { undo.clear(); redo.clear(); cleanAt = 0 }
}
