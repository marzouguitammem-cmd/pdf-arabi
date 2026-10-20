package com.tammem.pdfarabi.word

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.io.PdfMaker
import com.tammem.pdfarabi.io.Storage
import com.tammem.pdfarabi.model.Origin
import com.tammem.pdfarabi.ui.SettingsActivity
import com.tammem.pdfarabi.ui.SwatchView
import com.tammem.pdfarabi.ui.askText
import com.tammem.pdfarabi.ui.attrColor
import com.tammem.pdfarabi.ui.dp
import com.tammem.pdfarabi.ui.hideKeyboard
import com.tammem.pdfarabi.ui.makeIconChip
import com.tammem.pdfarabi.ui.makeTextChip
import com.tammem.pdfarabi.ui.setChipSelected
import com.tammem.pdfarabi.ui.showKeyboard
import com.tammem.pdfarabi.ui.textString
import com.tammem.pdfarabi.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** Reads and edits Word (.docx) documents. */
class WordActivity : AppCompatActivity() {

    companion object {
        const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val EXTRA_PATH = "path"
        const val EXTRA_WORK = "work"
        const val EXTRA_TITLE = "title"

        val FONTS = listOf("Traditional Arabic", "Simplified Arabic", "Arial", "Times New Roman", "Sakkal Majalla",
            "Arabic Typesetting", "Tahoma", "Calibri", "Andalus", "Aref Ruqaa", "Amiri", "Cairo", "Courier New")
        val SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72)
        val TEXT_COLORS = listOf("000000", "1F3864", "2E74B5", "C00000", "FF0000", "00B050", "ED7D31", "7030A0", "7F7F7F", "843C0C", "FFC000", "FFFFFF")
        val HIGHLIGHTS = linkedMapOf("yellow" to "#FFFF00", "green" to "#00FF00", "cyan" to "#00FFFF", "magenta" to "#FF00FF",
            "red" to "#FF0000", "blue" to "#0000FF", "lightGray" to "#C0C0C0", "darkYellow" to "#808000")
        val CELL_COLORS = listOf("FFF2CC", "DDEBF7", "E2EFDA", "FCE4D6", "EDEDED", "D9E1F2", "F8CBAD", "C6E0B4", "FFFF00", "BFBFBF")
        val SYMBOLS = listOf("✓", "✗", "☐", "☑", "★", "☆", "●", "○", "■", "□", "▲", "►", "◄", "←", "→", "↑", "↓", "⇐", "⇒",
            "×", "÷", "±", "≤", "≥", "≠", "≈", "∞", "√", "π", "°", "%", "‰", "€", "«", "»", "،", "؛", "؟", "…", "☺", "♥", "✍", "✂", "☎")
    }

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var replaceRow: LinearLayout
    private lateinit var replaceInput: EditText
    private lateinit var topProgress: LinearProgressIndicator
    private lateinit var web: WebView
    private lateinit var editFab: ExtendedFloatingActionButton
    private lateinit var formatBar: LinearLayout
    private lateinit var ctxScroll: HorizontalScrollView
    private lateinit var ctxRow: LinearLayout
    private lateinit var ctxDivider: View
    private lateinit var wtools: LinearLayout
    private lateinit var busy: View
    private lateinit var busyText: TextView

    private var pkg: DocxPackage? = null
    private var conv: Converted? = null
    private var origin: Origin = Origin.None
    private var title = ""
    private var docKey: String? = null
    private var stamp = ""
    private var ready = false
    private var editMode = false
    private var dirty = false
    private var imageSelected = false
    private var state = JSONObject()
    private var pendingDraft: String? = null
    private val results = HashMap<String, (String) -> Unit>()
    private var tokenSeq = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastInsets: WindowInsetsCompat? = null
    private val toggles = HashMap<String, ImageView>()
    private var view = "page"

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insertImage(uri)
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word)
        root = findViewById(R.id.root)
        column = findViewById(R.id.column)
        topBar = findViewById(R.id.topBar)
        toolbar = findViewById(R.id.toolbar)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        replaceRow = findViewById(R.id.replaceRow)
        replaceInput = findViewById(R.id.replaceInput)
        topProgress = findViewById(R.id.topProgress)
        web = findViewById(R.id.web)
        editFab = findViewById(R.id.editFab)
        formatBar = findViewById(R.id.formatBar)
        ctxScroll = findViewById(R.id.ctxScroll)
        ctxRow = findViewById(R.id.ctxRow)
        ctxDivider = findViewById(R.id.ctxDivider)
        wtools = findViewById(R.id.wtools)
        busy = findViewById(R.id.busy)
        busyText = findViewById(R.id.busyText)
        view = Prefs.wordView

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            lastInsets = insets
            applyInsets()
            insets
        }
        toolbar.setNavigationOnClickListener { onBack() }
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }
        editFab.setOnClickListener { enterEdit() }
        setupWeb()
        setupFind()
        buildTools()
        buildMenu()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        load()
    }

    override fun onPause() {
        super.onPause()
        if (ready && dirty) saveDraft()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("Bridge")
        web.destroy()
        super.onDestroy()
    }

    private fun applyInsets() {
        val insets = lastInsets ?: return
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        topBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
        if (formatBar.visibility == View.VISIBLE) {
            column.updatePadding(left = bars.left, right = bars.right, bottom = if (ime > 0) ime else 0)
            formatBar.updatePadding(bottom = if (ime > 0) 0 else bars.bottom)
        } else {
            column.updatePadding(left = bars.left, right = bars.right, bottom = max(ime, bars.bottom))
        }
    }

    // ------------------------------------------------------------------ web view

    private val bridge = object {
        @JavascriptInterface
        fun onReady() = runOnUiThread { onEditorReady() }

        @JavascriptInterface
        fun onState(json: String) = runOnUiThread {
            state = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            updateToolState()
        }

        @JavascriptInterface
        fun onChange() = runOnUiThread {
            if (!dirty) { dirty = true; updateTitle() }
            scheduleDraft()
        }

        @JavascriptInterface
        fun onResult(token: String, value: String) = runOnUiThread { onJsResult(token, value) }

        @JavascriptInterface
        fun onImage(v: String) = runOnUiThread {
            imageSelected = v == "1"
            updateContext()
        }

        @JavascriptInterface
        fun onLink(url: String) = runOnUiThread { openLink(url) }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.domStorageEnabled = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = false
        s.textZoom = 100
        s.mediaPlaybackRequiresUserGesture = true
        web.isVerticalScrollBarEnabled = true
        web.setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
        web.addJavascriptInterface(bridge, "Bridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(request.url) ?: super.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js" -> "application/javascript"
        "css" -> "text/css"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun serve(uri: Uri): WebResourceResponse? {
        if (uri.host != "doc.local") return null
        val path = uri.path ?: return notFound()
        return try {
            when {
                path.startsWith("/editor/") || path.startsWith("/fonts/") ->
                    WebResourceResponse(mimeOf(path), "utf-8", assets.open(path.removePrefix("/")))
                path.startsWith("/m/") -> {
                    val name = Uri.decode(path.removePrefix("/m/"))
                    val bytes = pkg?.entries?.get(name) ?: return notFound()
                    WebResourceResponse(mimeOf(name), null, ByteArrayInputStream(bytes))
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun js(code: String) {
        if (!ready) return
        web.evaluateJavascript(code, null)
    }

    private fun q(v: Any?): String = when (v) {
        null -> "null"
        is String -> JSONObject.quote(v)
        is Boolean, is Number -> v.toString()
        else -> JSONObject.quote(v.toString())
    }

    /** Calls E.[fn](args) in the page. */
    private fun call(fn: String, vararg args: Any?) = js("E.$fn(${args.joinToString(",") { q(it) }})")

    private fun request(fn: String, cb: (String) -> Unit) {
        val token = "t${++tokenSeq}"
        results[token] = cb
        js("E.$fn(${q(token)})")
    }

    private fun onJsResult(token: String, value: String) {
        when (token) {
            "find" -> {
                val o = runCatching { JSONObject(value) }.getOrNull() ?: return
                val n = o.optInt("n")
                findCount.text = if (n == 0) "0" else "${o.optInt("i") + 1}/$n"
            }
            "replaced" -> {
                toast(getString(R.string.w_replaced_n, value.toIntOrNull() ?: 0))
                runFind()
            }
            else -> results.remove(token)?.invoke(value)
        }
    }

    // ------------------------------------------------------------------ loading

    private fun wordStrings() = WordStrings(
        getString(R.string.word_page_break),
        mapOf(
            "Normal" to getString(R.string.style_normal), "Title" to getString(R.string.style_title),
            "Subtitle" to getString(R.string.style_subtitle), "Heading1" to getString(R.string.style_h1),
            "Heading2" to getString(R.string.style_h2), "Heading3" to getString(R.string.style_h3),
            "Heading4" to getString(R.string.style_h4), "ListParagraph" to getString(R.string.style_list_para)
        ),
        getString(R.string.word_chart), getString(R.string.word_shape), getString(R.string.word_equation)
    )

    private class Source(val bytes: ByteArray, val title: String, val origin: Origin, val key: String?, val stamp: String)

    private fun readSource(): Source {
        intent.getStringExtra(EXTRA_WORK)?.let { work ->
            val f = File(work)
            return Source(f.readBytes(), intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.untitled), Origin.None, null, "")
        }
        intent.getStringExtra(EXTRA_PATH)?.let { p ->
            val f = File(p)
            return Source(f.readBytes(), f.name, Origin.Path(p), p, "${f.length()}:${f.lastModified()}")
        }
        @Suppress("DEPRECATION")
        val uri = intent.data ?: (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)) ?: error("nothing to open")
        if (uri.scheme == "file") {
            val f = File(uri.path ?: error("bad path"))
            return Source(f.readBytes(), f.name, Origin.Path(f.path), f.path, "${f.length()}:${f.lastModified()}")
        }
        val real = Storage.realPath(this, uri)
        if (real != null && File(real).canRead() && File(real).canWrite()) {
            val f = File(real)
            return Source(f.readBytes(), f.name, Origin.Path(real), real, "${f.length()}:${f.lastModified()}")
        }
        Storage.takePermission(this, uri)
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read")
        val name = Storage.displayName(this, uri) ?: "document.docx"
        return Source(bytes, name, Origin.Content(uri, Storage.canWriteUri(this, uri)), uri.toString(), bytes.size.toString())
    }

    private fun isOldDoc(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() && bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    private fun load() {
        showBusy(getString(R.string.opening))
        lifecycleScope.launch {
            try {
                val src = withContext(Dispatchers.IO) { readSource() }
                if (isOldDoc(src.bytes)) {
                    hideBusy()
                    MaterialAlertDialogBuilder(this@WordActivity)
                        .setTitle(getString(R.string.word_doc_old))
                        .setMessage(getString(R.string.word_doc_old_msg))
                        .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                    return@launch
                }
                val strings = wordStrings()
                val p = withContext(Dispatchers.IO) { DocxPackage.read(ByteArrayInputStream(src.bytes)) }
                val c = withContext(Dispatchers.Default) { DocxReader(p, strings).convert(view) }
                pkg = p
                conv = c
                origin = src.origin
                title = src.title
                docKey = src.key
                stamp = src.stamp
                if (src.origin == Origin.None) dirty = true
                docKey?.let { key ->
                    val draft = withContext(Dispatchers.IO) { readDraft(key) }
                    if (draft != null && draft.optString("stamp") == stamp && askRestore(draft.optLong("time"))) {
                        pendingDraft = draft.optString("html")
                    }
                    Prefs.touchRecent(key, title, src.bytes.size.toLong())
                }
                updateTitle()
                web.loadDataWithBaseURL("https://doc.local/", c.html, "text/html", "utf-8", null)
            } catch (e: Exception) {
                hideBusy()
                MaterialAlertDialogBuilder(this@WordActivity)
                    .setTitle(getString(R.string.open_failed_title))
                    .setMessage(getString(R.string.word_open_failed, e.message ?: ""))
                    .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private suspend fun askRestore(time: Long): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        var done = false
        val t = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(time))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.draft_title))
            .setMessage(getString(R.string.draft_msg, t))
            .setPositiveButton(getString(R.string.draft_restore)) { _, _ -> done = true; cont.resume(true) }
            .setNegativeButton(getString(R.string.draft_discard)) { _, _ -> done = true; cont.resume(false) }
            .setOnDismissListener { if (!done) { done = true; cont.resume(false) } }
            .show()
    }

    private fun onEditorReady() {
        ready = true
        hideBusy()
        call("setView", view)
        pendingDraft?.let {
            call("setHtml", it)
            pendingDraft = null
            dirty = true
            updateTitle()
        }
        if (origin == Origin.None) enterEdit() else {
            editFab.visibility = View.VISIBLE
            editFab.show()
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun updateTitle() {
        toolbar.title = Storage.baseName(title)
        toolbar.subtitle = if (dirty) getString(R.string.unsaved_sub) else null
    }

    private fun showBusy(msg: String) {
        busyText.text = msg
        busy.visibility = View.VISIBLE
    }

    private fun hideBusy() {
        busy.visibility = View.GONE
    }

    private object M {
        const val UNDO = 1; const val REDO = 2; const val SAVE = 3; const val SAVE_AS = 4; const val SHARE = 5
        const val PDF = 6; const val FIND = 7; const val COUNT = 8; const val VIEW = 9; const val INFO = 10; const val SETTINGS = 11
    }

    private fun buildMenu() {
        val m: Menu = toolbar.menu
        m.clear()
        if (editMode) {
            m.add(0, M.UNDO, 0, getString(R.string.undo)).setIcon(R.drawable.ic_undo).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.add(0, M.REDO, 1, getString(R.string.redo)).setIcon(R.drawable.ic_redo).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.add(0, M.SAVE, 2, getString(R.string.save)).setIcon(R.drawable.ic_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            updateUndoIcons()
        } else {
            m.add(0, M.FIND, 0, getString(R.string.search)).setIcon(R.drawable.ic_search).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            if (dirty) m.add(0, M.SAVE, 1, getString(R.string.save)).setIcon(R.drawable.ic_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            else m.add(0, M.SHARE, 1, getString(R.string.share)).setIcon(R.drawable.ic_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        var o = 10
        if (!editMode && !dirty) m.add(0, M.SAVE, o++, getString(R.string.save))
        m.add(0, M.SAVE_AS, o++, getString(R.string.save_copy))
        if (editMode || dirty) m.add(0, M.SHARE, o++, getString(R.string.w_share_docx))
        m.add(0, M.PDF, o++, getString(R.string.w_export_pdf))
        if (editMode) m.add(0, M.FIND, o++, getString(R.string.w_show_replace))
        m.add(0, M.COUNT, o++, getString(R.string.w_word_count))
        m.add(0, M.VIEW, o++, getString(if (view == "page") R.string.w_view_mobile else R.string.w_view_page))
        m.add(0, M.INFO, o++, getString(R.string.file_info))
        m.add(0, M.SETTINGS, o, getString(R.string.settings))
    }

    private fun updateUndoIcons() {
        val m = toolbar.menu
        m.findItem(M.UNDO)?.let { it.isEnabled = state.optBoolean("u"); it.icon?.mutate()?.alpha = if (it.isEnabled) 255 else 90 }
        m.findItem(M.REDO)?.let { it.isEnabled = state.optBoolean("r"); it.icon?.mutate()?.alpha = if (it.isEnabled) 255 else 90 }
    }

    private fun onMenu(id: Int) {
        if (!ready) return
        when (id) {
            M.UNDO -> call("undo")
            M.REDO -> call("redo")
            M.SAVE -> save()
            M.SAVE_AS -> saveAs(copy = true)
            M.SHARE -> share()
            M.PDF -> exportPdf()
            M.FIND -> openFind(editMode)
            M.COUNT -> wordCount()
            M.VIEW -> {
                view = if (view == "page") "mobile" else "page"
                Prefs.wordView = view
                call("setView", view)
                buildMenu()
            }
            M.INFO -> info()
            M.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun info() {
        val where = when (val o = origin) {
            is Origin.Path -> o.path
            is Origin.Content -> Storage.displayName(this, o.uri) ?: o.uri.toString()
            Origin.None -> getString(R.string.new_unsaved)
        }
        val size = pkg?.entries?.values?.sumOf { it.size.toLong() } ?: 0L
        MaterialAlertDialogBuilder(this)
            .setTitle(Storage.baseName(title))
            .setMessage(getString(R.string.w_info_msg, where, formatSize(size)))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun formatSize(b: Long): String = when {
        b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
        b >= 1024 -> String.format(Locale.US, "%d KB", b / 1024)
        else -> "$b B"
    }

    // ------------------------------------------------------------------ edit mode & tool bar

    private fun enterEdit() {
        if (!ready) return
        editMode = true
        editFab.hide()
        formatBar.visibility = View.VISIBLE
        call("setEditable", true)
        buildMenu()
        applyInsets()
    }

    private fun exitEdit() {
        editMode = false
        call("setEditable", false)
        hideKeyboard(web)
        formatBar.visibility = View.GONE
        editFab.show()
        buildMenu()
        applyInsets()
    }

    private fun toolBtn(icon: Int, desc: String, key: String? = null, onClick: () -> Unit): ImageView {
        val v = makeIconChip(this, icon, desc, onClick)
        val s = dp(44f)
        v.layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(2f) }
        v.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        if (key != null) toggles[key] = v
        wtools.addView(v)
        return v
    }

    private fun sep() {
        wtools.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(24f)).apply { marginStart = dp(4f); marginEnd = dp(4f) }
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorOutlineVariant))
        })
    }

    private fun buildTools() {
        wtools.removeAllViews()
        toggles.clear()
        toolBtn(R.drawable.ic_check, getString(R.string.w_read)) { exitEdit() }
        sep()
        toolBtn(R.drawable.ic_format_text, getString(R.string.w_text)) { textSheet() }
        toolBtn(R.drawable.ic_bold, getString(R.string.w_bold), "b") { call("toggle", "b") }
        toolBtn(R.drawable.ic_italic, getString(R.string.w_italic), "i") { call("toggle", "i") }
        toolBtn(R.drawable.ic_underline, getString(R.string.w_underline), "u") { call("toggle", "u") }
        toolBtn(R.drawable.ic_color_text, getString(R.string.w_color), "c") { colorSheet(false) }
        toolBtn(R.drawable.ic_highlight, getString(R.string.w_highlight), "hl") { colorSheet(true) }
        sep()
        toolBtn(R.drawable.ic_align_right, getString(R.string.w_align), "al") { paragraphSheet() }
        toolBtn(R.drawable.ic_list_bullet, getString(R.string.w_bullets), "lb") { call("toggleList", "bullet") }
        toolBtn(R.drawable.ic_list_number, getString(R.string.w_numbers), "ln") { call("toggleList", "decimal") }
        toolBtn(R.drawable.ic_indent_less, getString(R.string.w_indent_less)) { call("indent", -1) }
        toolBtn(R.drawable.ic_indent_more, getString(R.string.w_indent_more)) { call("indent", 1) }
        toolBtn(R.drawable.ic_dir, getString(R.string.w_direction)) {
            call("setDir", if (state.optString("dir") == "rtl") "ltr" else "rtl")
        }
        sep()
        toolBtn(R.drawable.ic_styles, getString(R.string.w_styles)) { stylesSheet() }
        toolBtn(R.drawable.ic_add, getString(R.string.w_insert)) { insertSheet() }
        toolBtn(R.drawable.ic_search, getString(R.string.w_find)) { openFind(true) }
    }

    private fun updateToolState() {
        toggles["b"]?.setChipSelected(state.optBoolean("b"))
        toggles["i"]?.setChipSelected(state.optBoolean("i"))
        toggles["u"]?.setChipSelected(state.optBoolean("ul"))
        toggles["lb"]?.setChipSelected(state.optString("list") == "bullet")
        toggles["ln"]?.setChipSelected(state.optString("list") == "decimal" || state.optString("list") == "abjad")
        val rtl = state.optString("dir") == "rtl"
        toggles["al"]?.setImageResource(when (state.optString("al")) {
            "center" -> R.drawable.ic_align_center
            "justify" -> R.drawable.ic_count
            "end" -> if (rtl) R.drawable.ic_align_left else R.drawable.ic_align_right
            else -> if (rtl) R.drawable.ic_align_right else R.drawable.ic_align_left
        })
        val c = state.optString("c")
        toggles["c"]?.imageTintList = ColorStateList.valueOf(
            if (c.length == 6) Color.parseColor("#$c") else attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        updateUndoIcons()
        updateContext()
    }

    private var ctxKind = ""

    private fun updateContext() {
        val kind = when {
            !editMode -> ""
            imageSelected || state.optBoolean("img") -> "img"
            state.optBoolean("tbl") -> "tbl"
            else -> ""
        }
        if (kind == ctxKind) return
        ctxKind = kind
        ctxRow.removeAllViews()
        when (kind) {
            "tbl" -> {
                fun chip(text: Int, fn: String, vararg args: Any?) = ctxRow.addView(makeTextChip(this, getString(text)) { call(fn, *args) })
                chip(R.string.w_row_before, "tableRow", false)
                chip(R.string.w_row_after, "tableRow", true)
                chip(R.string.w_col_before, "tableCol", false)
                chip(R.string.w_col_after, "tableCol", true)
                ctxRow.addView(makeTextChip(this, getString(R.string.w_cell_color)) { cellColorSheet() })
                chip(R.string.w_del_row, "tableDelRow")
                chip(R.string.w_del_col, "tableDelCol")
                chip(R.string.w_table_dir, "tableDir")
                chip(R.string.w_del_table, "tableDelete")
            }
            "img" -> {
                listOf(25, 50, 75, 100).forEach { pct ->
                    ctxRow.addView(makeTextChip(this, "$pct%") { call("imageWidth", pct / 100.0) })
                }
                ctxRow.addView(makeTextChip(this, getString(R.string.w_img_delete)) { call("imageDelete") })
            }
        }
        val show = kind.isNotEmpty()
        ctxScroll.visibility = if (show) View.VISIBLE else View.GONE
        ctxDivider.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ sheets

    private fun sheet(build: (LinearLayout, BottomSheetDialog) -> Unit) {
        val d = BottomSheetDialog(this)
        val c = d.context
        val scroll = androidx.core.widget.NestedScrollView(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(16f), c.dp(4f), c.dp(16f), c.dp(20f))
        }
        scroll.addView(box)
        build(box, d)
        d.setContentView(scroll)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.window?.setDimAmount(0.15f)
        d.setOnDismissListener { if (editMode) { web.requestFocus(); call("focus") } }
        d.show()
    }

    private fun label(box: LinearLayout, text: String) {
        box.addView(TextView(box.context).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            typeface = Fonts.get("tajawal").bold
            setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
            setPadding(0, dp(12f), 0, dp(6f))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
    }

    private fun hScroll(box: LinearLayout, fill: (LinearLayout) -> Unit) {
        val sv = HorizontalScrollView(box.context).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(box.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fill(row)
        sv.addView(row)
        box.addView(sv)
    }

    private fun swatch(row: LinearLayout, hex: String?, selected: Boolean, onPick: () -> Unit) {
        val v = SwatchView(row.context, if (hex == null) Color.WHITE else Color.parseColor(hex))
        v.isSelected = selected
        if (hex == null) {
            v.contentDescription = getString(R.string.w_no_color)
            v.foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2f), Color.RED)
                setColor(Color.TRANSPARENT)
            }
        }
        v.setOnClickListener { onPick() }
        row.addView(v)
    }

    private fun textSheet() = sheet { box, d ->
        label(box, getString(R.string.w_font))
        val current = state.optString("f")
        val fonts = LinkedHashSet<String>()
        if (current.isNotEmpty()) fonts += current
        fonts += FONTS
        conv?.fonts?.let { fonts += it }
        val group = ChipGroup(box.context).apply { isSingleLine = true; chipSpacingHorizontal = dp(6f) }
        fonts.forEach { f ->
            group.addView(Chip(box.context).apply {
                text = f
                isCheckable = true
                isChecked = f == current
                typeface = Fonts.typeface(Fonts.guess(f, true), false)
                textSize = 15f
                setOnClickListener { call("setFont", f); d.dismiss() }
            })
        }
        val sv = HorizontalScrollView(box.context).apply { isHorizontalScrollBarEnabled = false; addView(group) }
        box.addView(sv)

        label(box, getString(R.string.w_size) + " — " + fmtSize(state.optDouble("sz", 0.0)))
        hScroll(box) { row ->
            row.addView(makeIconChip(this, R.drawable.ic_minus, getString(R.string.w_size)) { call("growSize", -1) })
            row.addView(makeIconChip(this, R.drawable.ic_add, getString(R.string.w_size)) { call("growSize", 1) })
            SIZES.forEach { s ->
                val chip = makeTextChip(this, s.toString()) { call("setSize", s) }
                if (kotlin.math.abs(state.optDouble("sz", 0.0) - s) < 0.01) chip.setBackgroundResource(R.drawable.bg_tool_selected)
                row.addView(chip)
            }
        }

        label(box, getString(R.string.w_text))
        hScroll(box) { row ->
            fun tg(icon: Int, desc: Int, on: Boolean, fn: () -> Unit) {
                val v = makeIconChip(this, icon, getString(desc)) { fn(); d.dismiss() }
                v.setChipSelected(on)
                row.addView(v)
            }
            tg(R.drawable.ic_bold, R.string.w_bold, state.optBoolean("b")) { call("toggle", "b") }
            tg(R.drawable.ic_italic, R.string.w_italic, state.optBoolean("i")) { call("toggle", "i") }
            tg(R.drawable.ic_underline, R.string.w_underline, state.optBoolean("ul")) { call("toggle", "u") }
            tg(R.drawable.ic_strike, R.string.w_strike, state.optBoolean("s")) { call("toggle", "s") }
            tg(R.drawable.ic_sup, R.string.w_sup, state.optString("va") == "sup") { call("vertAlign", "sup") }
            tg(R.drawable.ic_sub, R.string.w_sub, state.optString("va") == "sub") { call("vertAlign", "sub") }
        }

        label(box, getString(R.string.w_color))
        hScroll(box) { row ->
            swatch(row, null, state.optString("c").isEmpty()) { call("setColor", ""); d.dismiss() }
            TEXT_COLORS.forEach { c -> swatch(row, "#$c", state.optString("c").equals(c, true)) { call("setColor", c); d.dismiss() } }
        }
        label(box, getString(R.string.w_highlight))
        hScroll(box) { row ->
            swatch(row, null, state.optString("hl").isEmpty()) { call("setHighlight", ""); d.dismiss() }
            HIGHLIGHTS.forEach { (name, hex) -> swatch(row, hex, state.optString("hl") == name) { call("setHighlight", name); d.dismiss() } }
        }
        box.addView(MaterialButton(box.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.w_clear_format)
            setOnClickListener { call("clearFormat"); d.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
    }

    private fun fmtSize(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun colorSheet(highlight: Boolean) = sheet { box, d ->
        label(box, getString(if (highlight) R.string.w_highlight else R.string.w_color))
        val row = LinearLayout(box.context).apply { orientation = LinearLayout.HORIZONTAL }
        if (highlight) {
            swatch(row, null, state.optString("hl").isEmpty()) { call("setHighlight", ""); d.dismiss() }
            HIGHLIGHTS.forEach { (name, hex) -> swatch(row, hex, state.optString("hl") == name) { call("setHighlight", name); d.dismiss() } }
        } else {
            swatch(row, null, state.optString("c").isEmpty()) { call("setColor", ""); d.dismiss() }
            TEXT_COLORS.forEach { c -> swatch(row, "#$c", state.optString("c").equals(c, true)) { call("setColor", c); d.dismiss() } }
        }
        val sv = HorizontalScrollView(box.context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        box.addView(sv)
    }

    private fun cellColorSheet() = sheet { box, d ->
        label(box, getString(R.string.w_cell_color))
        hScroll(box) { row ->
            swatch(row, null, state.optString("bg").isEmpty()) { call("cellBg", ""); d.dismiss() }
            CELL_COLORS.forEach { c -> swatch(row, "#$c", state.optString("bg").equals(c, true)) { call("cellBg", c); d.dismiss() } }
        }
    }

    private fun paragraphSheet() = sheet { box, d ->
        val rtl = state.optString("dir") == "rtl"
        label(box, getString(R.string.w_align))
        hScroll(box) { row ->
            fun al(icon: Int, desc: Int, v: String) {
                val b = makeIconChip(this, icon, getString(desc)) { call("setAlign", v); d.dismiss() }
                b.setChipSelected(state.optString("al") == v)
                row.addView(b)
            }
            if (rtl) {
                al(R.drawable.ic_align_right, R.string.w_align_start, "start")
                al(R.drawable.ic_align_center, R.string.w_align_center, "center")
                al(R.drawable.ic_align_left, R.string.w_align_end, "end")
            } else {
                al(R.drawable.ic_align_left, R.string.w_align_start, "start")
                al(R.drawable.ic_align_center, R.string.w_align_center, "center")
                al(R.drawable.ic_align_right, R.string.w_align_end, "end")
            }
            al(R.drawable.ic_count, R.string.w_align_justify, "justify")
        }
        label(box, getString(R.string.w_direction))
        hScroll(box) { row ->
            val r = makeTextChip(this, getString(R.string.w_rtl)) { call("setDir", "rtl"); d.dismiss() }
            r.setBackgroundResource(if (rtl) R.drawable.bg_tool_selected else R.drawable.bg_round_surface)
            val l = makeTextChip(this, getString(R.string.w_ltr)) { call("setDir", "ltr"); d.dismiss() }
            l.setBackgroundResource(if (!rtl) R.drawable.bg_tool_selected else R.drawable.bg_round_surface)
            row.addView(r)
            row.addView(l)
        }
        label(box, getString(R.string.w_line_spacing))
        hScroll(box) { row ->
            val cur = state.optString("ls").substringBefore(',').toIntOrNull()
            listOf(1.0, 1.15, 1.5, 2.0, 2.5, 3.0).forEach { m ->
                val chip = makeTextChip(this, String.format(Locale.US, "%s", if (m % 1.0 == 0.0) m.toInt().toString() else m.toString())) { call("setLineSpacing", m); d.dismiss() }
                if (cur != null && kotlin.math.abs(cur - m * 240) < 2) chip.setBackgroundResource(R.drawable.bg_tool_selected)
                row.addView(chip)
            }
        }
        label(box, getString(R.string.w_space_before))
        hScroll(box) { row ->
            val cur = state.optString("sb").toIntOrNull() ?: 0
            listOf(0, 6, 12, 18, 24).forEach { pt ->
                val chip = makeTextChip(this, "$pt pt") { call("setSpacing", "before", pt); d.dismiss() }
                if (cur == pt * 20) chip.setBackgroundResource(R.drawable.bg_tool_selected)
                row.addView(chip)
            }
        }
        label(box, getString(R.string.w_space_after))
        hScroll(box) { row ->
            val cur = state.optString("sa").toIntOrNull() ?: 0
            listOf(0, 6, 8, 10, 12, 18, 24).forEach { pt ->
                val chip = makeTextChip(this, "$pt pt") { call("setSpacing", "after", pt); d.dismiss() }
                if (cur == pt * 20) chip.setBackgroundResource(R.drawable.bg_tool_selected)
                row.addView(chip)
            }
        }
        label(box, getString(R.string.w_indent))
        hScroll(box) { row ->
            row.addView(makeIconChip(this, R.drawable.ic_indent_less, getString(R.string.w_indent_less)) { call("indent", -1) })
            row.addView(makeIconChip(this, R.drawable.ic_indent_more, getString(R.string.w_indent_more)) { call("indent", 1) })
        }
        label(box, getString(R.string.w_lists))
        hScroll(box) { row ->
            val list = state.optString("list")
            fun li(text: Int, kind: String) {
                val chip = makeTextChip(this, getString(text)) { call("toggleList", kind); d.dismiss() }
                if (list == kind) chip.setBackgroundResource(R.drawable.bg_tool_selected)
                row.addView(chip)
            }
            li(R.string.w_bullets, "bullet")
            li(R.string.w_numbers, "decimal")
            li(R.string.w_abjad, "abjad")
        }
    }

    private fun stylesSheet() = sheet { box, d ->
        label(box, getString(R.string.w_styles))
        val cur = state.optString("st")
        val styles = conv?.quickStyles ?: emptyList()
        val defaults = listOf("Title", "Heading1", "Heading2", "Heading3")
        val all = LinkedHashMap<String, String>()
        styles.forEach { all[it.first] = it.second }
        defaults.forEach { id -> if (!all.containsKey(id)) all[id] = wordStrings().styleNames[id] ?: id }
        for ((id, name) in all) {
            val tv = TextView(box.context).apply {
                text = name
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                typeface = when (id) {
                    "Title", "Heading1", "Heading2", "Heading3", "Heading4" -> Fonts.get("amiri").bold
                    else -> Fonts.get("amiri").regular
                }
                textSize = when (id) { "Title" -> 24f; "Heading1" -> 21f; "Heading2" -> 19f; "Heading3" -> 17f; else -> 16f }
                setBackgroundResource(if (id == cur) R.drawable.bg_tool_selected else R.drawable.bg_round_surface)
                setOnClickListener { call("setStyle", id); d.dismiss() }
            }
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        }
    }

    private fun insertSheet() = sheet { box, d ->
        label(box, getString(R.string.w_insert_table))
        var rows = 3
        var cols = 3
        val row = LinearLayout(box.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun stepper(lbl: Int, get: () -> Int, set: (Int) -> Unit, maxV: Int): LinearLayout {
            val l = LinearLayout(box.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val value = TextView(box.context).apply {
                text = get().toString(); minWidth = dp(28f); gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            }
            l.addView(TextView(box.context).apply { text = getString(lbl); setPadding(dp(4f), 0, dp(4f), 0) })
            l.addView(makeIconChip(this, R.drawable.ic_minus, getString(lbl)) { set(max(1, get() - 1)); value.text = get().toString() })
            l.addView(value)
            l.addView(makeIconChip(this, R.drawable.ic_add, getString(lbl)) { set(min(maxV, get() + 1)); value.text = get().toString() })
            return l
        }
        row.addView(stepper(R.string.w_rows, { rows }, { rows = it }, 60))
        row.addView(stepper(R.string.w_cols, { cols }, { cols = it }, 12))
        box.addView(HorizontalScrollView(box.context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.addView(MaterialButton(box.context).apply {
            text = getString(R.string.w_insert_btn)
            setIconResource(R.drawable.ic_table)
            setOnClickListener { call("insertTable", rows, cols); d.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })

        label(box, getString(R.string.w_insert))
        val grid = ChipGroup(box.context).apply { chipSpacingHorizontal = dp(8f) }
        fun action(text: Int, icon: Int, fn: () -> Unit) {
            grid.addView(Chip(box.context).apply {
                this.text = getString(text)
                setChipIconResource(icon)
                isChipIconVisible = true
                chipIconTint = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setOnClickListener { d.dismiss(); fn() }
            })
        }
        action(R.string.w_insert_image, R.drawable.ic_image) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        action(R.string.w_insert_pb, R.drawable.ic_pagebreak) { call("insertPageBreak") }
        action(R.string.w_insert_date, R.drawable.ic_date) {
            call("insertText", SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()))
        }
        action(R.string.w_insert_dots, R.drawable.ic_minus) { call("insertText", "………………………………………………") }
        action(R.string.w_insert_box, R.drawable.ic_check_circle) { call("insertText", "☐ ") }
        action(R.string.w_insert_link, R.drawable.ic_link) { askLink() }
        box.addView(grid)

        label(box, getString(R.string.w_symbols))
        val sym = ChipGroup(box.context).apply { chipSpacingHorizontal = dp(4f); chipSpacingVertical = dp(0f) }
        SYMBOLS.forEach { s ->
            sym.addView(Chip(box.context).apply {
                text = s
                textSize = 18f
                setOnClickListener { call("insertText", s) }
            })
        }
        box.addView(sym)
    }

    private fun askLink() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(8f), dp(22f), 0) }
        fun field(hint: Int, type: Int): TextInputEditText {
            val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = getString(hint) }
            val e = TextInputEditText(til.context).apply {
                inputType = type; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            til.addView(e)
            box.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            return e
        }
        val text = field(R.string.w_link_text, InputType.TYPE_CLASS_TEXT)
        val url = field(R.string.w_link_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.w_insert_link))
            .setView(box)
            .setPositiveButton(getString(R.string.w_insert_btn)) { _, _ ->
                var u = url.textString().trim()
                if (u.isNotEmpty()) {
                    if (!u.contains("://") && !u.startsWith("mailto:")) u = "https://$u"
                    call("insertLink", text.textString().trim().ifEmpty { u }, u)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        showKeyboard(text)
    }

    // ------------------------------------------------------------------ images

    private fun insertImage(uri: Uri) {
        val p = pkg ?: return
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = PdfMaker.loadImage(this@WordActivity, uri, 1800) ?: error("image")
                    val png = bmp.hasAlpha()
                    val bos = ByteArrayOutputStream()
                    bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, bos)
                    val dir = if (p.mainDir.isEmpty()) "media" else p.mainDir + "/media"
                    var n = 1
                    var name: String
                    do { name = "$dir/pa_img${n++}.${if (png) "png" else "jpeg"}" } while (p.entries.containsKey(name))
                    p.entries[name] = bos.toByteArray()
                    val w = bmp.width
                    val h = bmp.height
                    bmp.recycle()
                    Triple(name, w, h)
                }.getOrNull()
            }
            if (res == null) { toast(getString(R.string.image_failed)); return@launch }
            val (name, w, h) = res
            val cw = (conv?.contentWidthTwips ?: 9000) / 15
            val dw = min(cw, w)
            val dh = (h.toDouble() * dw / w).toInt()
            call("insertImage", "/m/$name", name, dw, dh)
        }
    }

    private fun openLink(url: String) {
        if (url.startsWith("#")) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ------------------------------------------------------------------ find & replace

    private val findRunnable = Runnable { runFind() }

    private fun setupFind() {
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
        findViewById<View>(R.id.findNext).setOnClickListener { call("findStep", 1) }
        findViewById<View>(R.id.findPrev).setOnClickListener { call("findStep", -1) }
        findViewById<View>(R.id.findReplaceToggle).setOnClickListener {
            replaceRow.visibility = if (replaceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.replaceOne).setOnClickListener {
            if (!editMode) enterEdit()
            call("replaceCur", replaceInput.textString())
            handler.postDelayed({ runFind() }, 120)
        }
        findViewById<View>(R.id.replaceAll).setOnClickListener {
            if (!editMode) enterEdit()
            val q = findInput.textString()
            if (q.isNotEmpty()) call("replaceAll", q, replaceInput.textString(), false)
        }
        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runFind(); hideKeyboard(findInput); true } else false
        }
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(findRunnable)
                handler.postDelayed(findRunnable, 400)
            }
        })
    }

    private fun runFind() = call("find", findInput.textString(), false)

    private fun openFind(withReplace: Boolean) {
        findBar.visibility = View.VISIBLE
        replaceRow.visibility = if (withReplace) View.VISIBLE else View.GONE
        showKeyboard(findInput)
    }

    private fun closeFind() {
        handler.removeCallbacks(findRunnable)
        hideKeyboard(findInput)
        findBar.visibility = View.GONE
        findCount.text = ""
        call("findClear")
    }

    private fun wordCount() {
        if (!ready) return
        web.evaluateJavascript("E.count()") { v ->
            val o = runCatching { JSONObject(JSONArray("[$v]").getString(0)) }.getOrNull() ?: return@evaluateJavascript
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.w_word_count))
                .setMessage(getString(R.string.w_count_msg, o.optInt("w"), o.optInt("c"), o.optInt("n"), o.optInt("p"), o.optInt("pg")))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }
    }

    // ------------------------------------------------------------------ saving

    /** Asks the page for its content and builds a .docx file from it. */
    private fun buildDocx(cb: (File) -> Unit) {
        val p = pkg ?: return
        val c = conv ?: return
        showBusy(getString(R.string.w_saving))
        request("serialize") { json ->
            lifecycleScope.launch {
                val out = File(Storage.outDir(this@WordActivity), Storage.sanitize(Storage.baseName(title)) + ".docx")
                val r = withContext(Dispatchers.Default) {
                    runCatching {
                        val entries = DocxWriter(p, c).build(JSONObject(json))
                        out.delete()
                        DocxPackage(entries).write(out)
                        out
                    }
                }
                hideBusy()
                r.onSuccess(cb).onFailure { e ->
                    MaterialAlertDialogBuilder(this@WordActivity)
                        .setTitle(getString(R.string.error_title))
                        .setMessage(getString(R.string.w_save_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }
        }
    }

    private fun canOverwrite(): Boolean = when (val o = origin) {
        is Origin.Path -> File(o.path).let { it.exists() && it.canWrite() }
        is Origin.Content -> o.writable
        Origin.None -> false
    }

    private fun save(after: (() -> Unit)? = null) {
        if (!canOverwrite()) { saveAs(false, after); return }
        buildDocx { out ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Storage.writeBack(this@WordActivity, out, origin) }.isSuccess }
                if (!ok) { saveAs(false, after); return@launch }
                markSaved(out)
                toast(getString(R.string.saved))
                after?.invoke()
            }
        }
    }

    private fun saveAs(copy: Boolean, after: (() -> Unit)? = null) {
        val base = Storage.baseName(title)
        val suggested = when {
            copy -> "$base (${getString(R.string.copy_suffix)})"
            origin == Origin.None -> base
            else -> "$base (${getString(R.string.edited_suffix)})"
        }
        askText(getString(if (copy) R.string.save_copy_title else R.string.save_file), suggested, getString(R.string.file_name)) { name ->
            buildDocx { out ->
                lifecycleScope.launch {
                    val o = withContext(Dispatchers.IO) { runCatching { Storage.saveNew(this@WordActivity, out, name, "docx", MIME) }.getOrNull() }
                    if (o == null) { toast(getString(R.string.error_title)); return@launch }
                    if (!copy) {
                        deleteDraft()
                        origin = o
                        title = Storage.withExt(Storage.sanitize(name), "docx")
                        docKey = o.key
                        markSaved(out)
                    }
                    o.key?.let { Prefs.touchRecent(it, Storage.withExt(Storage.sanitize(name), "docx"), out.length()) }
                    toast(getString(if (copy) R.string.copy_saved_in else R.string.saved_in, Storage.describe(o)), true)
                    after?.invoke()
                }
            }
        }
    }

    private fun markSaved(out: File) {
        dirty = false
        deleteDraft()
        docKey?.let { Prefs.touchRecent(it, title, out.length()) }
        (origin as? Origin.Path)?.let { stamp = "${File(it.path).length()}:${File(it.path).lastModified()}" }
        updateTitle()
        buildMenu()
    }

    private fun share() {
        buildDocx { out ->
            val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        }
    }

    private fun exportPdf() {
        if (!ready) return
        val name = Storage.baseName(title)
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val landscape = (conv?.let { c -> c.finalSectPr?.let { s -> Ox.child(s, "pgSz")?.let { Ox.a(it, "orient") == "landscape" } } } ) == true
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4)
            .build()
        pm.print(name, web.createPrintDocumentAdapter(name), attrs)
    }

    // ------------------------------------------------------------------ drafts

    private val draftRunnable = Runnable { saveDraft() }

    private fun scheduleDraft() {
        if (docKey == null) return
        handler.removeCallbacks(draftRunnable)
        handler.postDelayed(draftRunnable, 4000)
    }

    private fun draftFile(key: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(Storage.draftsDir(this), "word-" + md.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun saveDraft() {
        val key = docKey ?: return
        if (!dirty || !ready) return
        val s = stamp
        request("html") { html ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    draftFile(key).writeText(JSONObject().put("html", html).put("stamp", s).put("time", System.currentTimeMillis()).toString())
                }
            }
        }
    }

    private fun readDraft(key: String): JSONObject? = runCatching {
        val f = draftFile(key)
        if (!f.exists()) null else JSONObject(f.readText())
    }.getOrNull()

    private fun deleteDraft() {
        handler.removeCallbacks(draftRunnable)
        docKey?.let { draftFile(it).delete() }
    }

    // ------------------------------------------------------------------ back

    private fun onBack() {
        when {
            busy.visibility == View.VISIBLE -> return
            findBar.visibility == View.VISIBLE -> closeFind()
            editMode -> exitEdit()
            dirty && ready -> askSaveOnExit()
            else -> finish()
        }
    }

    private fun askSaveOnExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save_changes_q))
            .setMessage(getString(R.string.save_changes_msg, Storage.baseName(title)))
            .setPositiveButton(getString(R.string.save_btn)) { _, _ -> save { finish() } }
            .setNegativeButton(getString(R.string.dont_save)) { _, _ -> deleteDraft(); dirty = false; finish() }
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }
}
