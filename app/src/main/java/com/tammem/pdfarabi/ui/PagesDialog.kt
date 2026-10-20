package com.tammem.pdfarabi.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.model.Cmd
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.model.RotatePages
import com.tammem.pdfarabi.model.SetPages
import com.tammem.pdfarabi.render.RenderEngine
import java.util.Collections
import kotlin.math.max

/** Full-screen page manager: reorder by dragging, rotate, delete, duplicate, insert, extract. */
class PagesDialog(
    private val act: AppCompatActivity,
    private val model: DocModel,
    private val engine: RenderEngine,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun goTo(index: Int)
        fun commit(cmd: Cmd)
        fun insertBlank(after: Int)
        fun insertPdf(after: Int)
        fun insertImages(after: Int)
        fun scan(after: Int)
        fun extract(pages: List<PageItem>)
    }

    private val dialog = Dialog(act, R.style.Theme_PdfArabi_FullScreenDialog)
    private val items = ArrayList<PageItem>()
    private val selected = LinkedHashSet<Long>()
    private lateinit var grid: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var selBar: LinearLayout
    private val adapter = Adapter()
    private var thumbW = 240
    private var dragStart: List<PageItem>? = null

    val isShowing: Boolean get() = dialog.isShowing

    @SuppressLint("NotifyDataSetChanged")
    fun show() {
        val v = LayoutInflater.from(dialog.context).inflate(R.layout.dialog_pages, null)
        dialog.setContentView(v)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
        }
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        toolbar = v.findViewById(R.id.pagesToolbar)
        grid = v.findViewById(R.id.grid)
        selBar = v.findViewById(R.id.selBar)
        toolbar.setNavigationOnClickListener { dialog.dismiss() }
        buildMenu(toolbar.menu)
        toolbar.setOnMenuItemClickListener { onMenu(it) }

        val widthDp = act.resources.configuration.screenWidthDp
        val cols = max(2, widthDp / 125)
        val screenW = act.resources.displayMetrics.widthPixels
        thumbW = ((screenW / cols / 64) * 64).coerceIn(128, 640)
        grid.layoutManager = GridLayoutManager(dialog.context, cols)
        grid.adapter = adapter
        touchHelper.attachToRecyclerView(grid)
        buildSelBar()
        engine.onThumbReady = { adapter.notifyDataSetChanged() }
        dialog.setOnDismissListener { engine.onThumbReady = null }
        refresh()
        dialog.show()
        val cur = cbCurrent()
        if (cur > 0) grid.scrollToPosition(cur)
    }

    var currentPage: () -> Int = { 0 }
    private fun cbCurrent() = currentPage()

    fun dismiss() = dialog.dismiss()

    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        items.clear()
        items.addAll(model.pages)
        selected.retainAll(items.map { it.id }.toSet())
        if (::toolbar.isInitialized) {
            toolbar.title = act.getString(R.string.pages_count, items.size)
            adapter.notifyDataSetChanged()
            updateSelBar()
        }
    }

    private fun buildMenu(m: Menu) {
        m.add(0, 1, 0, act.getString(R.string.blank_page)).setIcon(R.drawable.ic_note_add).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 2, 1, act.getString(R.string.add_pdf)).setIcon(R.drawable.ic_pdf).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 3, 2, act.getString(R.string.add_images)).setIcon(R.drawable.ic_photos).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 4, 3, act.getString(R.string.tool_scan)).setIcon(R.drawable.ic_scan).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    /** Insert position: after the last selected page, or at the end. */
    private fun insertAfter(): Int {
        if (selected.isEmpty()) return items.size - 1
        return items.indexOfLast { it.id in selected }
    }

    private fun onMenu(item: MenuItem): Boolean {
        val after = insertAfter()
        when (item.itemId) {
            1 -> cb.insertBlank(after)
            2 -> cb.insertPdf(after)
            3 -> cb.insertImages(after)
            4 -> cb.scan(after)
        }
        return true
    }

    private fun buildSelBar() {
        selBar.removeAllViews()
        fun add(icon: Int, label: String, action: () -> Unit) {
            val b = makeToolButton(dialog.context, icon, label, action)
            selBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(R.drawable.ic_rotate, act.getString(R.string.rotate)) { rotate(90) }
        add(R.drawable.ic_rotate_left, act.getString(R.string.rotate_left)) { rotate(-90) }
        add(R.drawable.ic_copy, act.getString(R.string.copy)) { duplicate() }
        add(R.drawable.ic_extract, act.getString(R.string.extract)) { cb.extract(items.filter { it.id in selected }) }
        add(R.drawable.ic_delete, act.getString(R.string.delete)) { delete() }
        add(R.drawable.ic_select_all, act.getString(R.string.all)) { toggleAll() }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleAll() {
        if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.id }) }
        adapter.notifyDataSetChanged()
        updateSelBar()
    }

    private fun updateSelBar() {
        selBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        toolbar.subtitle = if (selected.isEmpty()) null else act.getString(R.string.selected_n, selected.size)
    }

    private fun rotate(delta: Int) {
        if (selected.isEmpty()) return
        cb.commit(RotatePages(selected.toList(), delta))
    }

    private fun duplicate() {
        if (selected.isEmpty()) return
        val before = model.pages.toList()
        val after = ArrayList<PageItem>()
        for (p in before) {
            after += p
            if (p.id in selected) after += p.copyAsNew()
        }
        cb.commit(SetPages(before, after))
    }

    private fun delete() {
        if (selected.isEmpty()) return
        if (selected.size >= items.size) {
            act.toast(act.getString(R.string.keep_one_page))
            return
        }
        act.confirm(act.getString(R.string.delete_pages_q, selected.size), act.getString(R.string.undo_possible), act.getString(R.string.delete)) {
            val before = model.pages.toList()
            val after = before.filter { it.id !in selected }
            selected.clear()
            cb.commit(SetPages(before, after))
        }
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
    ) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            if (dragStart == null) dragStart = items.toList()
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        @SuppressLint("NotifyDataSetChanged")
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            val start = dragStart
            dragStart = null
            if (start != null && start.map { it.id } != items.map { it.id }) {
                cb.commit(SetPages(start, items.toList()))
            } else {
                adapter.notifyDataSetChanged()
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.alpha = 0.75f
        }
    })

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ThumbView = v.findViewById(R.id.thumb)
        val num: TextView = v.findViewById(R.id.num)
        val check: ImageView = v.findViewById(R.id.check)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumb, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = items[position]
            val bmp = engine.thumb(p, thumbW)
            if (bmp == null) engine.requestThumb(p, thumbW)
            h.thumb.set(bmp, p.rot, model.baseW(p), model.baseH(p), p.annots.toList())
            h.num.text = "${position + 1}"
            val sel = p.id in selected
            h.thumb.marked = sel
            val c = h.itemView.context
            h.check.imageTintList = ColorStateList.valueOf(
                if (sel) c.attrColor(androidx.appcompat.R.attr.colorPrimary)
                else c.attrColor(com.google.android.material.R.attr.colorOutline)
            )
            h.check.alpha = if (sel) 1f else 0.6f
            h.check.setOnClickListener {
                if (p.id in selected) selected.remove(p.id) else selected.add(p.id)
                notifyItemChanged(h.bindingAdapterPosition)
                updateSelBar()
            }
            h.itemView.setOnClickListener {
                if (selected.isNotEmpty()) {
                    if (p.id in selected) selected.remove(p.id) else selected.add(p.id)
                    notifyItemChanged(h.bindingAdapterPosition)
                    updateSelBar()
                } else {
                    cb.goTo(h.bindingAdapterPosition)
                    dialog.dismiss()
                }
            }
        }
    }
}
