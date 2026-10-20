package com.tammem.pdfarabi.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.Signature
import com.tammem.pdfarabi.Stamp
import com.tammem.pdfarabi.io.DocOpener
import com.tammem.pdfarabi.io.Drafts
import com.tammem.pdfarabi.io.Exporter
import com.tammem.pdfarabi.io.PdfMaker
import com.tammem.pdfarabi.io.Storage
import com.tammem.pdfarabi.model.ALIGN_CENTER
import com.tammem.pdfarabi.model.ALIGN_LEFT
import com.tammem.pdfarabi.model.ALIGN_RIGHT
import com.tammem.pdfarabi.model.AddAnnot
import com.tammem.pdfarabi.model.Annot
import com.tammem.pdfarabi.model.Batch
import com.tammem.pdfarabi.model.Cmd
import com.tammem.pdfarabi.model.CoverAnnot
import com.tammem.pdfarabi.model.DocModel
import com.tammem.pdfarabi.model.Ids
import com.tammem.pdfarabi.model.ImageAnnot
import com.tammem.pdfarabi.model.MarkAnnot
import com.tammem.pdfarabi.model.MarkKind
import com.tammem.pdfarabi.model.Origin
import com.tammem.pdfarabi.model.PageItem
import com.tammem.pdfarabi.model.PdfSource
import com.tammem.pdfarabi.model.RemoveAnnot
import com.tammem.pdfarabi.model.ReplaceAnnot
import com.tammem.pdfarabi.model.SetPages
import com.tammem.pdfarabi.model.ShapeKind
import com.tammem.pdfarabi.model.SignAnnot
import com.tammem.pdfarabi.model.TextAnnot
import com.tammem.pdfarabi.model.rotated90
import com.tammem.pdfarabi.render.RenderEngine
import com.tammem.pdfarabi.render.TextBlocks
import com.tammem.pdfarabi.text.Norm
import com.tammem.pdfarabi.text.PageText
import com.tammem.pdfarabi.text.PdfBox
import com.tammem.pdfarabi.text.RegionAnalyzer
import com.tammem.pdfarabi.text.Selection
import com.tammem.pdfarabi.view.PdfView
import com.tammem.pdfarabi.view.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

class EditorActivity : AppCompatActivity(), PdfView.Host {

    private lateinit var root: FrameLayout
    private lateinit var pdfView: PdfView
    private lateinit var topBar: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var topProgress: LinearProgressIndicator
    private lateinit var pageBadge: TextView
    private lateinit var editFab: ExtendedFloatingActionButton
    private lateinit var editPanel: LinearLayout
    private lateinit var options: LinearLayout
    private lateinit var tools: LinearLayout
    private lateinit var busy: View
    private lateinit var busyText: TextView

    private val model = DocModel()
    private lateinit var engine: RenderEngine
    private lateinit var pdfbox: PdfBox
    private val textCache = HashMap<String, PageText>()
    private val handler = Handler(Looper.getMainLooper())

    private var loaded = false
    private var editMode = false
    private var docKey: String? = null
    private var originalStamp = ""
    private var barsHidden = false
    private var navBottom = 0
    private val toolButtons = LinkedHashMap<Tool, LinearLayout>()

    private var markKind = MarkKind.values().getOrElse(Prefs.markKind) { MarkKind.CHECK }
    private var markColor = Prefs.markColor
    private var markSize = Prefs.markSize
    private var stamp: Stamp = com.tammem.pdfarabi.ui.StampPresets.everything().getOrElse(Prefs.stampIndex) { StampPresets.all[0] }
    private var signature: Signature? = Prefs.signatures().firstOrNull { it.id == Prefs.signatureId } ?: Prefs.signatures().lastOrNull()

    private var replaceJob: Job? = null
    private var searchJob: Job? = null
    private var hits = ArrayList<Pair<Long, RectF>>()
    private var hitIndex = -1
    private var pagesDialog: PagesDialog? = null
    private var insertAfterIndex = -1

    // ------------------------------------------------------------------ results

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) insertImage(uri)
    }
    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) insertPdfs(uris)
    }
    private val pickImagesForPages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { uris ->
        if (uris.isNotEmpty()) insertImagePages(uris)
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        val pdf = r?.pdf?.uri
        if (pdf != null) insertPdfs(listOf(pdf))
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        root = findViewById(R.id.root)
        pdfView = findViewById(R.id.pdfView)
        topBar = findViewById(R.id.topBar)
        toolbar = findViewById(R.id.toolbar)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchCount = findViewById(R.id.searchCount)
        topProgress = findViewById(R.id.topProgress)
        pageBadge = findViewById(R.id.pageBadge)
        editFab = findViewById(R.id.editFab)
        editPanel = findViewById(R.id.editPanel)
        options = findViewById(R.id.options)
        tools = findViewById(R.id.tools)
        busy = findViewById(R.id.busy)
        busyText = findViewById(R.id.busyText)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(b.left, b.top, b.right, 0)
            editPanel.setPadding(b.left, 0, b.right, b.bottom)
            navBottom = b.bottom
            (editFab.layoutParams as FrameLayout.LayoutParams).let {
                it.bottomMargin = dp(16f) + b.bottom
                it.marginEnd = dp(16f) + max(b.left, b.right)
                editFab.layoutParams = it
            }
            updateInsets()
            insets
        }
        topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInsets() }
        editPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInsets() }

        toolbar.setNavigationOnClickListener { onBack() }
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }
        editFab.setOnClickListener { enterEditMode() }

        engine = RenderEngine(model)
        pdfbox = PdfBox(model)
        pdfView.host = this
        pdfView.nightMode = Prefs.nightReading
        applyToolStyles()
        buildTools()
        setupSearch()

        model.addListener(object : DocModel.Listener {
            override fun onModelChanged(structure: Boolean) {
                updateTitle()
                buildMenu()
                pagesDialog?.let { if (it.isShowing) it.refresh() }
                if (structure) updatePageBadge(pdfView.currentPage(), model.pages.size)
                scheduleDraft()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildMenu()
        load(intent)
    }

    override fun onPause() {
        super.onPause()
        if (loaded) {
            docKey?.let { Prefs.setLastPage(it, pdfView.currentPage()) }
            if (model.history.dirty) saveDraftNow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        engine.close()
        val box = pdfbox
        CoroutineScope(PdfBox.dispatcher).launch { box.closeAll() }
    }

    // ------------------------------------------------------------------ loading

    private fun load(intent: Intent) {
        showBusy(getString(R.string.opening))
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { DocOpener.prepare(this@EditorActivity, intent) }
                docKey = prepared.key
                originalStamp = prepared.stamp
                var restored = false
                val key = prepared.key
                if (key != null) {
                    val draft = withContext(Dispatchers.IO) { Drafts.load(this@EditorActivity, key) }
                    if (draft != null && draft.originalStamp == prepared.stamp && askRestore(draft.time)) {
                        Drafts.applyTo(draft, model)
                        restored = true
                    }
                }
                if (!restored) {
                    model.sources.clear()
                    prepared.files.forEach { model.sources += PdfSource(it) }
                    model.title = prepared.title
                    model.origin = prepared.origin
                }
                for (i in model.sources.indices) {
                    if (!openSource(i)) { finish(); return@launch }
                }
                if (!restored) {
                    model.pages.clear()
                    model.sources.forEachIndexed { si, s ->
                        for (p in 0 until s.pageCount) model.pages += PageItem(Ids.new(), si, p)
                    }
                }
                model.history.clear()
                if (restored || prepared.origin == Origin.None) model.history.markDirty()
                if (model.pages.isEmpty()) error("empty")
                pdfView.setDocument(model, engine)
                key?.let {
                    val last = Prefs.lastPage(it)
                    if (last > 0) pdfView.scrollToPage(last)
                    Prefs.touchRecent(it, prepared.title, prepared.size)
                }
                loaded = true
                hideBusy()
                updateTitle()
                buildMenu()
                updatePageBadge(pdfView.currentPage(), model.pages.size)
            } catch (e: Exception) {
                hideBusy()
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(getString(R.string.open_failed_title))
                    .setMessage(getString(R.string.open_failed_msg, e.message ?: ""))
                    .setPositiveButton(getString(R.string.ok)) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /** Opens source [i] for display; asks for the password if needed. False = user gave up. */
    private suspend fun openSource(i: Int): Boolean {
        var src = model.sources[i]
        while (true) {
            try {
                engine.openSource(src)
                return true
            } catch (e: SecurityException) {
                val pw = askPassword() ?: return false
                val dec = withContext(Dispatchers.IO) {
                    runCatching { DocOpener.decrypt(this@EditorActivity, src.file, pw) }.getOrNull()
                }
                if (dec == null) {
                    toast(getString(R.string.wrong_password))
                    continue
                }
                src = PdfSource(dec)
                model.sources[i] = src
                model.password = pw
            }
        }
    }

    private suspend fun askPassword(): String? = suspendCancellableCoroutine { cont ->
        var done = false
        val til = com.google.android.material.textfield.TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.password)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        til.addView(input)
        val wrap = FrameLayout(this).apply { setPadding(dp(22f), dp(8f), dp(22f), 0); addView(til) }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.protected_title))
            .setView(wrap)
            .setPositiveButton(getString(R.string.unlock)) { _, _ -> done = true; cont.resume(input.text?.toString() ?: "") }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> done = true; cont.resume(null) }
            .setOnDismissListener { if (!done) { done = true; cont.resume(null) } }
            .create()
        d.setOnShowListener { showKeyboard(input) }
        d.show()
    }

    private suspend fun askRestore(time: Long): Boolean = suspendCancellableCoroutine { cont ->
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

    // ------------------------------------------------------------------ chrome

    private fun updateInsets() {
        pdfView.insetTop = if (barsHidden) 0f else topBar.height.toFloat()
        pdfView.insetBottom = if (editMode) editPanel.height.toFloat() else navBottom.toFloat()
        val lp = pageBadge.layoutParams as FrameLayout.LayoutParams
        val wantMargin = (if (editMode) editPanel.height else navBottom) + dp(14f)
        if (lp.bottomMargin != wantMargin) {
            lp.bottomMargin = wantMargin
            pageBadge.layoutParams = lp
        }
    }

    private fun updateTitle() {
        toolbar.title = Storage.baseName(model.title)
        toolbar.subtitle = if (model.history.dirty) getString(R.string.unsaved_sub) else null
    }

    private val hideBadge = Runnable { pageBadge.animate().alpha(0f).setDuration(300).start() }

    private fun updatePageBadge(index: Int, count: Int) {
        if (count <= 0) return
        pageBadge.text = "${index + 1} / $count"
        pageBadge.animate().cancel()
        pageBadge.alpha = 1f
        handler.removeCallbacks(hideBadge)
        handler.postDelayed(hideBadge, 2500)
    }

    private fun showBusy(msg: String) {
        busyText.text = msg
        busy.visibility = View.VISIBLE
    }

    private fun hideBusy() {
        busy.visibility = View.GONE
    }

    private fun setBarsHidden(hide: Boolean) {
        if (barsHidden == hide) return
        barsHidden = hide
        val ctrl = WindowInsetsControllerCompat(window, root)
        if (hide) {
            topBar.animate().translationY(-topBar.height.toFloat()).setDuration(180).start()
            editFab.hide()
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            topBar.animate().translationY(0f).setDuration(180).start()
            if (!editMode) editFab.show()
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        updateInsets()
    }

    // ------------------------------------------------------------------ menu

    private object M {
        const val UNDO = 1; const val REDO = 2; const val SAVE = 3; const val SAVE_COPY = 4; const val SHARE = 5
        const val PRINT = 6; const val SEARCH = 7; const val PAGES = 8; const val GOTO = 9; const val IMAGES = 10
        const val PROTECT = 11; const val NIGHT = 12; const val SETTINGS = 13; const val FIT = 14; const val INFO = 15
    }

    private fun buildMenu() {
        val m: Menu = toolbar.menu
        m.clear()
        val dirty = model.history.dirty
        if (editMode) {
            m.add(0, M.UNDO, 0, getString(R.string.undo)).setIcon(R.drawable.ic_undo).setEnabled(model.history.canUndo)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.add(0, M.REDO, 1, getString(R.string.redo)).setIcon(R.drawable.ic_redo).setEnabled(model.history.canRedo)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.add(0, M.SAVE, 2, getString(R.string.save)).setIcon(R.drawable.ic_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.findItem(M.UNDO).icon?.mutate()?.alpha = if (model.history.canUndo) 255 else 90
            m.findItem(M.REDO).icon?.mutate()?.alpha = if (model.history.canRedo) 255 else 90
        } else {
            m.add(0, M.SEARCH, 0, getString(R.string.search)).setIcon(R.drawable.ic_search).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            m.add(0, M.PAGES, 1, getString(R.string.pages)).setIcon(R.drawable.ic_grid).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            if (dirty) m.add(0, M.SAVE, 2, getString(R.string.save)).setIcon(R.drawable.ic_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            else m.add(0, M.SHARE, 2, getString(R.string.share)).setIcon(R.drawable.ic_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        var o = 10
        if (!editMode && !dirty) m.add(0, M.SAVE, o++, getString(R.string.save))
        m.add(0, M.SAVE_COPY, o++, getString(R.string.save_copy))
        if (editMode || dirty) m.add(0, M.SHARE, o++, getString(R.string.share_whatsapp))
        m.add(0, M.PRINT, o++, getString(R.string.print))
        if (editMode) {
            m.add(0, M.SEARCH, o++, getString(R.string.search_file))
            m.add(0, M.PAGES, o++, getString(R.string.pages))
        }
        m.add(0, M.GOTO, o++, getString(R.string.goto_page))
        m.add(0, M.FIT, o++, getString(R.string.fit_page))
        m.add(0, M.IMAGES, o++, getString(R.string.pages_as_images))
        m.add(0, M.PROTECT, o++, getString(R.string.protect))
        m.add(0, M.NIGHT, o++, getString(R.string.night_reading)).setCheckable(true).setChecked(Prefs.nightReading)
        m.add(0, M.INFO, o++, getString(R.string.file_info))
        m.add(0, M.SETTINGS, o, getString(R.string.settings))
    }

    private fun onMenu(id: Int) {
        if (!loaded) return
        when (id) {
            M.UNDO -> { pdfView.select(null, null); model.history.undo() }
            M.REDO -> { pdfView.select(null, null); model.history.redo() }
            M.SAVE -> save()
            M.SAVE_COPY -> saveCopy()
            M.SHARE -> share()
            M.PRINT -> print()
            M.SEARCH -> openSearch()
            M.PAGES -> openPages()
            M.GOTO -> askText(getString(R.string.goto_title), "", getString(R.string.goto_hint, model.pages.size)) { t ->
                t.trim().toIntOrNull()?.let { pdfView.scrollToPage(it - 1) }
            }
            M.FIT -> pdfView.resetZoom()
            M.IMAGES -> exportImages()
            M.PROTECT -> protect()
            M.NIGHT -> {
                Prefs.nightReading = !Prefs.nightReading
                pdfView.nightMode = Prefs.nightReading
                buildMenu()
            }
            M.INFO -> showInfo()
            M.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showInfo() {
        val where = when (val o = model.origin) {
            is Origin.Path -> o.path
            is Origin.Content -> Storage.displayName(this, o.uri) ?: o.uri.toString()
            Origin.None -> getString(R.string.new_unsaved)
        }
        val size = model.sources.sumOf { it.file.length() }
        MaterialAlertDialogBuilder(this)
            .setTitle(Storage.baseName(model.title))
            .setMessage(getString(R.string.info_msg, model.pages.size, formatSize(size), where))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    // ------------------------------------------------------------------ edit mode & tools

    private fun enterEditMode() {
        if (!loaded) return
        editMode = true
        setBarsHidden(false)
        editFab.hide()
        editPanel.visibility = View.VISIBLE
        setTool(Tool.SELECT)
        buildMenu()
        editPanel.post { updateInsets() }
    }

    private fun exitEditMode() {
        editMode = false
        pdfView.tool = Tool.READ
        pdfView.clearPreview()
        editPanel.visibility = View.GONE
        editFab.show()
        buildMenu()
        updateInsets()
    }

    private fun buildTools() {
        tools.removeAllViews()
        toolButtons.clear()
        fun add(icon: Int, label: String, tool: Tool?, action: (() -> Unit)? = null) {
            val b = makeToolButton(this, icon, label) {
                if (action != null) action() else if (tool != null) setTool(tool)
            }
            tools.addView(b)
            if (tool != null) toolButtons[tool] = b
        }
        add(R.drawable.ic_book, getString(R.string.t_read), null) { exitEditMode() }
        add(R.drawable.ic_select, getString(R.string.t_move), Tool.SELECT)
        add(R.drawable.ic_replace, getString(R.string.t_replace), Tool.REPLACE)
        add(R.drawable.ic_text, getString(R.string.t_text), Tool.TEXT)
        add(R.drawable.ic_draw, getString(R.string.t_pen), Tool.PEN)
        add(R.drawable.ic_highlight, getString(R.string.t_highlight), Tool.HIGHLIGHT)
        add(R.drawable.ic_marks, getString(R.string.t_marks), Tool.MARK)
        add(R.drawable.ic_stamp, getString(R.string.t_stamps), Tool.STAMP)
        add(R.drawable.ic_signature, getString(R.string.t_sign), Tool.SIGN)
        add(R.drawable.ic_image, getString(R.string.t_image), null) {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        add(R.drawable.ic_shapes, getString(R.string.t_shapes), Tool.SHAPE)
        add(R.drawable.ic_eraser, getString(R.string.t_eraser), Tool.ERASER)
        add(R.drawable.ic_grid, getString(R.string.pages), null) { openPages() }
    }

    private fun setTool(t: Tool) {
        if (t != Tool.SELECT) pdfView.select(null, null)
        pdfView.tool = t
        toolButtons.forEach { (tool, b) -> b.setToolSelected(tool == t) }
        buildOptions()
        if (t == Tool.SIGN && signature == null) {
            showSignaturePad(this) { s -> signature = s; Prefs.signatureId = s.id; buildOptions() }
        }
    }

    private fun applyToolStyles() {
        pdfView.penColor = Prefs.penColor
        pdfView.penWidth = Prefs.penWidth
        pdfView.hlColor = Prefs.hlColor
        pdfView.hlWidth = Prefs.hlWidth
        pdfView.shapeColor = Prefs.shapeColor
        pdfView.shapeWidth = Prefs.shapeWidth
        pdfView.shapeKind = ShapeKind.values().getOrElse(Prefs.shapeKind) { ShapeKind.RECT }
    }

    private fun hint(text: String) {
        options.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            typeface = Fonts.get("tajawal").regular
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(6f), 0, dp(6f), 0)
        })
    }

    private fun widthChips(values: FloatArray, labels: Array<String>, current: Float, onPick: (Float) -> Unit) {
        val chips = ArrayList<TextView>()
        values.forEachIndexed { i, w ->
            val c = makeTextChip(this, labels[i]) {
                chips.forEach { it.setTextChipSelected(false) }
                chips[i].setTextChipSelected(true)
                onPick(w)
            }
            c.setTextChipSelected(kotlin.math.abs(current - w) < 0.01f)
            chips += c
            options.addView(c)
        }
    }

    private fun buildOptions() {
        options.removeAllViews()
        val sel = pdfView.selection()
        if (pdfView.tool == Tool.SELECT && sel != null) {
            buildSelectionOptions(sel.first, sel.second)
            return
        }
        when (pdfView.tool) {
            Tool.SELECT -> hint(getString(R.string.h_select))
            Tool.REPLACE -> hint(getString(R.string.h_replace))
            Tool.TEXT -> {
                hint(getString(R.string.h_text))
                options.addView(divider(this))
                options.addSwatches(Palette.text.copyOf(8), Prefs.textColor) { Prefs.textColor = it }
            }
            Tool.PEN -> {
                options.addSwatches(Palette.pen, pdfView.penColor) { pdfView.penColor = it; Prefs.penColor = it }
                options.addView(divider(this))
                widthChips(floatArrayOf(0.8f, 1.6f, 3f), arrayOf(getString(R.string.w_thin), getString(R.string.w_medium), getString(R.string.w_thick)), pdfView.penWidth) {
                    pdfView.penWidth = it; Prefs.penWidth = it
                }
                hint(getString(R.string.h_two_fingers))
            }
            Tool.HIGHLIGHT -> {
                options.addSwatches(Palette.highlight, pdfView.hlColor) { pdfView.hlColor = it; Prefs.hlColor = it }
                options.addView(divider(this))
                widthChips(floatArrayOf(8f, 12f, 18f), arrayOf(getString(R.string.w_thin), getString(R.string.w_medium), getString(R.string.w_thick)), pdfView.hlWidth) {
                    pdfView.hlWidth = it; Prefs.hlWidth = it
                }
                hint(getString(R.string.h_straight))
            }
            Tool.ERASER -> hint(getString(R.string.h_eraser))
            Tool.SHAPE -> {
                val kinds = listOf(
                    ShapeKind.RECT to R.drawable.ic_shape_rect, ShapeKind.OVAL to R.drawable.ic_shape_oval,
                    ShapeKind.LINE to R.drawable.ic_shape_line, ShapeKind.ARROW to R.drawable.ic_shape_arrow
                )
                val views = ArrayList<android.widget.ImageView>()
                kinds.forEach { (k, icon) ->
                    val v = makeIconChip(this, icon, k.name) {
                        pdfView.shapeKind = k; Prefs.shapeKind = k.ordinal
                        views.forEachIndexed { i, iv -> iv.setChipSelected(kinds[i].first == k) }
                    }
                    v.setChipSelected(pdfView.shapeKind == k)
                    views += v
                    options.addView(v)
                }
                options.addView(divider(this))
                options.addSwatches(Palette.pen, pdfView.shapeColor) { pdfView.shapeColor = it; Prefs.shapeColor = it }
                options.addView(divider(this))
                widthChips(floatArrayOf(0.8f, 1.6f, 3f), arrayOf(getString(R.string.w_thin), getString(R.string.w_medium), getString(R.string.w_thick)), pdfView.shapeWidth) {
                    pdfView.shapeWidth = it; Prefs.shapeWidth = it
                }
            }
            Tool.MARK -> {
                val kinds = listOf(
                    MarkKind.CHECK to R.drawable.ic_mark_check, MarkKind.CROSS to R.drawable.ic_mark_cross,
                    MarkKind.STAR to R.drawable.ic_mark_star
                )
                val views = ArrayList<View>()
                kinds.forEach { (k, icon) ->
                    val v = makeIconChip(this, icon, k.name) {
                        markKind = k; Prefs.markKind = k.ordinal
                        views.forEachIndexed { i, iv ->
                            if (iv is android.widget.ImageView) iv.setChipSelected(i < kinds.size && kinds[i].first == k)
                            if (iv is TextView) iv.setTextChipSelected(false)
                        }
                    }
                    v.setChipSelected(markKind == k)
                    views += v
                    options.addView(v)
                }
                val q = makeTextChip(this, "؟") {
                    markKind = MarkKind.QUESTION; Prefs.markKind = MarkKind.QUESTION.ordinal
                    views.forEach { iv -> if (iv is android.widget.ImageView) iv.setChipSelected(false) }
                    (views.last() as TextView).setTextChipSelected(true)
                }
                q.setTextChipSelected(markKind == MarkKind.QUESTION)
                views += q
                options.addView(q)
                options.addView(divider(this))
                options.addSwatches(intArrayOf(0xFFD32F2F.toInt(), 0xFF2E7D32.toInt(), 0xFF1565C0.toInt(), 0xFF000000.toInt()), markColor) {
                    markColor = it; Prefs.markColor = it
                }
                options.addView(divider(this))
                widthChips(floatArrayOf(14f, 22f, 34f), arrayOf(getString(R.string.s_small), getString(R.string.w_medium), getString(R.string.s_large)), markSize) {
                    markSize = it; Prefs.markSize = it
                }
            }
            Tool.STAMP -> {
                val chip = makeTextChip(this, StampPresets.resolve(stamp.text).replace('\n', ' ')) { chooseStamp() }
                chip.typeface = Fonts.typeface(stamp.font, stamp.bold)
                chip.setTextColor(stamp.color)
                options.addView(chip)
                options.addView(makeTextChip(this, getString(R.string.change_stamp)) { chooseStamp() })
                hint(getString(R.string.h_stamp))
            }
            Tool.SIGN -> {
                options.addView(makeTextChip(this, getString(R.string.choose_signature)) { chooseSignature() })
                hint(if (signature == null) getString(R.string.h_sign_first) else getString(R.string.h_sign))
            }
            Tool.READ -> {}
        }
    }

    private fun chooseStamp() {
        showStampsSheet(this) { s ->
            stamp = s
            Prefs.stampIndex = StampPresets.everything().indexOf(s).coerceAtLeast(0)
            buildOptions()
        }
    }

    private fun chooseSignature() {
        showSignaturesSheet(this) { s ->
            signature = s
            Prefs.signatureId = s.id
            buildOptions()
        }
    }

    private fun buildSelectionOptions(page: PageItem, a: Annot) {
        if (a is TextAnnot) {
            options.addView(makeTextChip(this, getString(R.string.edit_text)) { onEditText(model.indexOfPage(page.id), a) })
        }
        if (a.canRecolor && a !is CoverAnnot) {
            options.addView(divider(this))
            val colors = if (a is com.tammem.pdfarabi.model.InkAnnot && a.highlighter) Palette.highlight else Palette.pen
            options.addSwatches(colors, a.color) { c ->
                val cur = pdfView.selection()?.second ?: return@addSwatches
                val n = cur.withColor(c)
                commit(ReplaceAnnot(page.id, cur, n))
                pdfView.select(page.id, n)
            }
        }
        options.addView(divider(this))
        if (a is com.tammem.pdfarabi.model.Boxed) {
            options.addView(makeIconChip(this, R.drawable.ic_rotate, getString(R.string.rotate)) {
                val cur = pdfView.selection()?.second ?: return@makeIconChip
                val n = cur.rotated90()
                commit(ReplaceAnnot(page.id, cur, n))
                pdfView.select(page.id, n)
            })
        }
        options.addView(makeIconChip(this, R.drawable.ic_copy, getString(R.string.copy)) {
            val cur = pdfView.selection()?.second ?: return@makeIconChip
            val n = cur.withId(Ids.new()).translated(12f, 12f)
            commit(AddAnnot(page.id, n))
            pdfView.select(page.id, n)
        })
        options.addView(makeIconChip(this, R.drawable.ic_delete, getString(R.string.delete)) {
            val cur = pdfView.selection()?.second ?: return@makeIconChip
            pdfView.select(null, null)
            commit(RemoveAnnot(page.id, cur))
        })
        options.addView(makeIconChip(this, R.drawable.ic_close, getString(R.string.deselect)) { pdfView.select(null, null) })
    }

    // ------------------------------------------------------------------ PdfView.Host

    override fun onPageChanged(index: Int, count: Int) = updatePageBadge(index, count)

    override fun onTapEmpty() {
        if (!editMode) setBarsHidden(!barsHidden)
    }

    override fun onCommit(cmd: Cmd) = commit(cmd)

    private fun commit(cmd: Cmd) {
        model.history.push(cmd)
    }

    override fun onSelectionChanged() {
        if (editMode) buildOptions()
    }

    /** Rotation that makes something upright on screen for this page. */
    private fun uprightRot(p: PageItem) = ((360 - p.rot) % 360).toFloat()

    override fun onPlace(pageIndex: Int, x: Float, y: Float) {
        val p = model.pages.getOrNull(pageIndex) ?: return
        when (pdfView.tool) {
            Tool.TEXT -> addText(pageIndex, x, y)
            Tool.MARK -> commit(AddAnnot(p.id, MarkAnnot(Ids.new(), x, y, markSize, uprightRot(p), markKind, markColor)))
            Tool.STAMP -> {
                val big = stamp.text.contains("العدد") || stamp.text.contains("{date}")
                val t = TextAnnot(
                    Ids.new(), x, y, uprightRot(p), StampPresets.resolve(stamp.text), stamp.font,
                    if (big) 15f else 18f, stamp.color, stamp.bold, ALIGN_CENTER, 0, stamp.border
                )
                commit(AddAnnot(p.id, t))
            }
            Tool.SIGN -> {
                val s = signature
                if (s == null) {
                    showSignaturePad(this) { ns -> signature = ns; Prefs.signatureId = ns.id; buildOptions() }
                    return
                }
                val w = min(model.visualW(p) * 0.28f, 170f)
                val h = w / s.aspect.coerceIn(0.4f, 8f)
                val a = SignAnnot(Ids.new(), x, y, w, h, uprightRot(p), s.strokes, s.color, 0.03f)
                commit(AddAnnot(p.id, a))
                setTool(Tool.SELECT)
                pdfView.select(p.id, a)
            }
            else -> {}
        }
    }

    private fun addText(pageIndex: Int, x: Float, y: Float) {
        val p = model.pages[pageIndex]
        val rot = uprightRot(p)
        var proto = TextAnnot(
            Ids.new(), x, y, rot, "", Prefs.textFont, Prefs.textSize, Prefs.textColor, false, ALIGN_RIGHT, 0, false,
            wrap = model.visualW(p) * 0.9f
        )
        if (rot == 0f) {
            val b = TextBlocks.get(proto)
            proto = proto.copy(cx = x - b.boxW / 2f, cy = y - b.firstBaseline + b.boxH / 2f)
        }
        pdfView.ensureVisible(pageIndex, RectF(x - 10f, y - 20f, x + 10f, y + 10f), 0.22f)
        TextEditSheet(this, proto, TextEditSheet.Mode.ADD,
            onPreview = { t, _ -> pdfView.setPreview(p.id, listOf(t)) },
            onDone = { t, _ ->
                pdfView.clearPreview()
                if (t.text.isNotBlank()) {
                    commit(AddAnnot(p.id, t))
                    Prefs.textFont = t.font
                    Prefs.textSize = t.size
                    Prefs.textColor = t.color
                    setTool(Tool.SELECT)
                    pdfView.select(p.id, t)
                }
            },
            onCancel = { pdfView.clearPreview() }
        ).show()
    }

    override fun onEditText(pageIndex: Int, a: TextAnnot) {
        val p = model.pages.getOrNull(pageIndex) ?: return
        pdfView.ensureVisible(pageIndex, a.bounds(), 0.18f)
        TextEditSheet(this, a, TextEditSheet.Mode.EDIT,
            onPreview = { t, _ -> pdfView.setPreview(p.id, listOf(t), a.id) },
            onDone = { t, _ ->
                pdfView.clearPreview()
                val cur = p.annots.firstOrNull { it.id == a.id } ?: return@TextEditSheet
                if (t.text.isBlank()) {
                    pdfView.select(null, null)
                    commit(RemoveAnnot(p.id, cur))
                } else {
                    commit(ReplaceAnnot(p.id, cur, t))
                    pdfView.select(p.id, t)
                }
            },
            onCancel = { pdfView.clearPreview() }
        ).show()
    }

    override fun onReplaceTap(pageIndex: Int, x: Float, y: Float) = startReplace(pageIndex, x, y, null, false)

    override fun onReplaceRect(pageIndex: Int, rect: RectF) = startReplace(pageIndex, 0f, 0f, rect, false)

    private suspend fun pageText(item: PageItem): PageText {
        val key = "${model.sources[item.src].file.path}:${item.srcPage}"
        textCache[key]?.let { return it }
        val w = model.baseW(item)
        val h = model.baseH(item)
        val box = pdfbox
        val pt = withContext(PdfBox.dispatcher) {
            runCatching { PageText.extract(box.doc(item.src), item.srcPage, w, h) }.getOrDefault(PageText.EMPTY)
        }
        textCache[key] = pt
        return pt
    }

    /**
     * Replace text: find the words, read the exact colours under them, cover them and type the
     * new text in a matching font and size — with live preview.
     */
    private fun startReplace(pageIndex: Int, x: Float, y: Float, rect: RectF?, wholeLine: Boolean) {
        val item = model.pages.getOrNull(pageIndex) ?: return
        replaceJob?.cancel()
        replaceJob = lifecycleScope.launch {
            topProgress.visibility = View.VISIBLE
            try {
                val pt = pageText(item)
                val sel: Selection? = when {
                    rect != null -> pt.inRect(rect)
                    wholeLine -> pt.lineSelectionAt(x, y)
                    else -> pt.wordAt(x, y)
                }
                if (sel == null && rect == null) {
                    toast(if (pt.isEmpty) getString(R.string.image_page_hint)
                    else getString(R.string.no_text_here))
                    return@launch
                }
                val bw = model.baseW(item)
                val bh = model.baseH(item)
                val hasText = sel != null
                val limit: RectF
                var size: Float
                val rtl: Boolean
                val prefill: String
                val fontId: String
                val bold: Boolean
                if (sel != null) {
                    val b = sel.box
                    size = sel.size
                    limit = RectF(b.left - size * 0.12f, b.top - size * 0.08f, b.right + size * 0.12f, b.bottom + size * 0.1f)
                    rtl = sel.rtl || com.tammem.pdfarabi.text.LegacyArabic.looksGarbled(sel.text)
                    prefill = if (com.tammem.pdfarabi.text.LegacyArabic.looksGarbled(sel.text)) {
                        toast(getString(R.string.old_font), true)
                        ""
                    } else sel.text
                    fontId = Fonts.guess(sel.font, rtl)
                    bold = Fonts.looksBold(sel.font)
                } else {
                    limit = RectF(rect)
                    size = 0f
                    rtl = true
                    prefill = ""
                    fontId = "amiri"
                    bold = false
                }
                limit.intersect(0f, 0f, bw, bh)
                val pad = max(limit.height() * 0.5f, 6f)
                val region = RectF(limit.left - pad, limit.top - pad, limit.right + pad, limit.bottom + pad)
                region.intersect(0f, 0f, bw, bh)
                val scale = (1600f / max(region.width(), region.height())).coerceIn(2f, 8f)
                val bmp: Bitmap? = engine.renderRegion(item, region, scale)
                val an = if (bmp != null) withContext(Dispatchers.Default) { RegionAnalyzer.analyze(bmp, region, scale, limit) }
                else RegionAnalyzer.Result(RectF(limit), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), limit.height(), false)
                if (size <= 0f) size = (an.inkHeight / 1.35f).coerceIn(6f, 48f)
                val c = an.cover
                if (sel != null) {
                    // never leave the original glyph area uncovered
                    c.union(sel.box.left, sel.box.top + (sel.box.height() * 0.15f), sel.box.right, sel.box.bottom - (sel.box.height() * 0.15f))
                }
                val cover = CoverAnnot(Ids.new(), c.left, c.top, c.right, c.bottom, an.bg, hasText && Prefs.removeOriginalText)
                val align = if (rtl) ALIGN_RIGHT else ALIGN_LEFT
                var proto = TextAnnot(Ids.new(), 0f, 0f, 0f, prefill, fontId, size, an.fg, bold, align, 0, false, 0f)
                val block = TextBlocks.get(proto)
                val top = if (sel != null && sel.lineCount == 1) sel.baseline - block.firstBaseline
                else c.centerY() - block.boxH / 2f
                val edgeR = sel?.box?.right ?: (c.right - 1f)
                val edgeL = sel?.box?.left ?: (c.left + 1f)
                val cx = if (rtl) edgeR + block.pad - block.boxW / 2f else edgeL - block.pad + block.boxW / 2f
                proto = proto.copy(cx = cx, cy = top + block.boxH / 2f)
                topProgress.visibility = View.GONE

                pdfView.ensureVisible(pageIndex, RectF(c), 0.14f)
                pdfView.setPreview(item.id, listOf(cover, proto))
                val extras = ArrayList<Pair<String, () -> Unit>>()
                if (sel != null && rect == null) {
                    if (!wholeLine) extras += getString(R.string.whole_line) to { pdfView.clearPreview(); startReplace(pageIndex, x, y, null, true) }
                    else extras += getString(R.string.word_only) to { pdfView.clearPreview(); startReplace(pageIndex, x, y, null, false) }
                }
                TextEditSheet(this@EditorActivity, proto, TextEditSheet.Mode.REPLACE,
                    onPreview = { t, rm -> pdfView.setPreview(item.id, listOf(cover.copy(removeText = rm && hasText), t)) },
                    onDone = { t, rm ->
                        pdfView.clearPreview()
                        val cmds = ArrayList<Cmd>()
                        cmds += AddAnnot(item.id, cover.copy(removeText = rm && hasText))
                        if (t.text.isNotBlank()) cmds += AddAnnot(item.id, t)
                        commit(Batch(cmds))
                        if (hasText) Prefs.removeOriginalText = rm
                    },
                    onCancel = { pdfView.clearPreview() },
                    allowRemove = hasText,
                    extras = extras
                ).show(Prefs.removeOriginalText)
            } finally {
                topProgress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------ images & pages

    private fun insertImage(uri: Uri) {
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { PdfMaker.storeImage(this@EditorActivity, uri) }.getOrNull() }
            if (f == null) { toast(getString(R.string.image_failed)); return@launch }
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            val i = pdfView.currentPage()
            val p = model.pages.getOrNull(i) ?: return@launch
            val c = pdfView.visibleCenter(i)
            val aspect = if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1.4f
            var w = model.visualW(p) * 0.5f
            var h = w / aspect
            if (h > model.visualH(p) * 0.5f) { h = model.visualH(p) * 0.5f; w = h * aspect }
            val a = ImageAnnot(Ids.new(), c[0], c[1], w, h, uprightRot(p), f.path)
            commit(AddAnnot(p.id, a))
            setTool(Tool.SELECT)
            pdfView.select(p.id, a)
            toast(getString(R.string.image_hint))
        }
    }

    private fun openPages() {
        if (!loaded) return
        val d = PagesDialog(this, model, engine, object : PagesDialog.Callbacks {
            override fun goTo(index: Int) = pdfView.scrollToPage(index)
            override fun commit(cmd: Cmd) = this@EditorActivity.commit(cmd)
            override fun insertBlank(after: Int) = askBlankPage(after)
            override fun insertPdf(after: Int) { insertAfterIndex = after; pickPdfs.launch(arrayOf("application/pdf")) }
            override fun insertImages(after: Int) {
                insertAfterIndex = after
                pickImagesForPages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            override fun scan(after: Int) { insertAfterIndex = after; startScan() }
            override fun extract(pages: List<PageItem>) = extractPages(pages)
        })
        d.currentPage = { pdfView.currentPage() }
        pagesDialog = d
        d.show()
    }

    private fun insertPositionOrCurrent(): Int {
        val a = insertAfterIndex
        insertAfterIndex = -1
        return if (a >= 0) a else pdfView.currentPage()
    }

    /** Adds a whole PDF file after page [after]. */
    private suspend fun addSourcePages(file: File, after: Int): Boolean {
        val idx = model.sources.size
        model.sources += PdfSource(file)
        if (!openSource(idx)) return false
        val s = model.sources[idx]
        val before = model.pages.toList()
        val newPages = (0 until s.pageCount).map { PageItem(Ids.new(), idx, it) }
        val pos = (after + 1).coerceIn(0, before.size)
        val after2 = before.subList(0, pos) + newPages + before.subList(pos, before.size)
        commit(SetPages(before, after2))
        return true
    }

    private fun insertPdfs(uris: List<Uri>) {
        val after = insertPositionOrCurrent()
        showBusy(getString(R.string.adding_pages))
        lifecycleScope.launch {
            var pos = after
            for (u in uris) {
                val f = withContext(Dispatchers.IO) {
                    runCatching { Storage.newWorkFile(this@EditorActivity).also { Storage.copyUri(this@EditorActivity, u, it) } }.getOrNull()
                } ?: continue
                val count = model.pages.size
                if (addSourcePages(f, pos)) pos += model.pages.size - count
            }
            hideBusy()
        }
    }

    private fun insertImagePages(uris: List<Uri>) {
        val after = insertPositionOrCurrent()
        showBusy(getString(R.string.preparing_images))
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val out = Storage.newWorkFile(this@EditorActivity)
                    PdfMaker.imagesToPdf(this@EditorActivity, uris, out)
                    out
                }.getOrNull()
            }
            if (f != null) addSourcePages(f, after) else toast(getString(R.string.images_failed))
            hideBusy()
        }
    }

    private fun askBlankPage(after: Int) {
        val names = arrayOf(getString(R.string.ps_blank), getString(R.string.ps_lined), getString(R.string.ps_grid), getString(R.string.ps_seyes))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_page))
            .setItems(names) { _, which ->
                lifecycleScope.launch {
                    val ref = model.pages.getOrNull(after) ?: model.pages.lastOrNull()
                    val w = ref?.let { model.visualW(it) } ?: PdfMaker.A4_W
                    val h = ref?.let { model.visualH(it) } ?: PdfMaker.A4_H
                    val f = withContext(Dispatchers.IO) {
                        val out = Storage.newWorkFile(this@EditorActivity)
                        PdfMaker.blank(out, 1, which, w, h)
                        out
                    }
                    addSourcePages(f, after)
                }
            }
            .show()
    }

    private fun startScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { toast(getString(R.string.scanner_failed), true) }
    }

    private fun extractPages(pages: List<PageItem>) {
        if (pages.isEmpty()) return
        askText(getString(R.string.extract_title, pages.size), "${Storage.baseName(model.title)} - ${getString(R.string.extract_suffix)}", getString(R.string.new_file_name)) { name ->
            exportThen(Exporter.Options(true, null, pages), getString(R.string.preparing)) { out ->
                val origin = withContext(Dispatchers.IO) { Storage.saveNew(this@EditorActivity, out, name) }
                savedSnack(getString(R.string.saved_in, Storage.describe(origin)), origin)
            }
        }
    }

    // ------------------------------------------------------------------ saving / sharing

    private fun exportThen(opt: Exporter.Options, msg: String, block: suspend (File) -> Unit) {
        showBusy(msg)
        lifecycleScope.launch {
            val out = File(Storage.outDir(this@EditorActivity), "${Storage.sanitize(Storage.baseName(model.title))}.pdf")
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    out.delete()
                    Exporter.export(model, out, opt, Storage.tmpDir(this@EditorActivity))
                }
            }
            if (r.isFailure) {
                hideBusy()
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(getString(R.string.error_title))
                    .setMessage(getString(R.string.prepare_failed, r.exceptionOrNull()?.message ?: ""))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
                return@launch
            }
            try {
                block(out)
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(getString(R.string.error_title))
                    .setMessage(e.message ?: "")
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            } finally {
                hideBusy()
            }
        }
    }

    private fun canOverwrite(): Boolean = when (val o = model.origin) {
        is Origin.Path -> File(o.path).let { it.exists() && it.canWrite() }
        is Origin.Content -> o.writable
        Origin.None -> false
    }

    private fun save(after: (() -> Unit)? = null) {
        if (!canOverwrite()) { saveAs(after); return }
        exportThen(Exporter.Options(true, model.password), getString(R.string.saving)) { out ->
            withContext(Dispatchers.IO) { Storage.writeBack(this@EditorActivity, out, model.origin) }
            model.history.markClean()
            deleteDraft()
            docKey?.let { Prefs.touchRecent(it, model.title, out.length()) }
            toast(getString(R.string.saved))
            after?.invoke()
        }
    }

    /** First save of a new/read-only file: creates the file and keeps working on it. */
    private fun saveAs(after: (() -> Unit)? = null) {
        val suggested = if (model.origin == Origin.None) Storage.baseName(model.title) else "${Storage.baseName(model.title)} (${getString(R.string.edited_suffix)})"
        askText(getString(R.string.save_file), suggested, getString(R.string.file_name)) { name ->
            exportThen(Exporter.Options(true, model.password), getString(R.string.saving)) { out ->
                val origin = withContext(Dispatchers.IO) { Storage.saveNew(this@EditorActivity, out, name) }
                deleteDraft()
                model.origin = origin
                model.title = Storage.withPdf(Storage.sanitize(name))
                docKey = origin.key
                docKey?.let { Prefs.touchRecent(it, model.title, out.length()) }
                model.history.markClean()
                savedSnack(getString(R.string.saved_in, Storage.describe(origin)), null)
                after?.invoke()
            }
        }
    }

    private fun saveCopy() {
        askText(getString(R.string.save_copy_title), "${Storage.baseName(model.title)} (${getString(R.string.copy_suffix)})", getString(R.string.copy_name)) { name ->
            exportThen(Exporter.Options(true, model.password), getString(R.string.saving_copy)) { out ->
                val origin = withContext(Dispatchers.IO) { Storage.saveNew(this@EditorActivity, out, name) }
                origin.key?.let { Prefs.touchRecent(it, Storage.withPdf(Storage.sanitize(name)), out.length()) }
                savedSnack(getString(R.string.copy_saved_in, Storage.describe(origin)), origin)
            }
        }
    }

    private fun savedSnack(msg: String, openable: Origin?) {
        val sb = Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
        if (openable != null) {
            sb.setAction(getString(R.string.open_it)) {
                val i = Intent(this, EditorActivity::class.java)
                when (openable) {
                    is Origin.Path -> i.putExtra(DocOpener.EXTRA_PATH, openable.path)
                    is Origin.Content -> i.data = openable.uri
                    Origin.None -> return@setAction
                }
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(i)
            }
        }
        if (editMode) sb.anchorView = editPanel
        sb.show()
    }

    private fun share() {
        exportThen(Exporter.Options(true, model.password), getString(R.string.preparing)) { out ->
            val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        }
    }

    private fun print() {
        exportThen(Exporter.Options(true, null), getString(R.string.preparing_print)) { out ->
            val pm = getSystemService(PRINT_SERVICE) as PrintManager
            val name = Storage.baseName(model.title)
            pm.print(name, object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal?, callback: PrintDocumentAdapter.LayoutResultCallback?, extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
                    val info = PrintDocumentInfo.Builder("$name.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(model.pages.size)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
                    cancellationSignal: android.os.CancellationSignal?, callback: PrintDocumentAdapter.WriteResultCallback?
                ) {
                    try {
                        FileInputStream(out).use { i ->
                            FileOutputStream(destination!!.fileDescriptor).use { o -> i.copyTo(o) }
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }, null)
        }
    }

    private fun protect() {
        askText(getString(R.string.protect), "", getString(R.string.new_password), password = true) { pw ->
            if (pw.length < 3) { toast(getString(R.string.password_short)); return@askText }
            askText(getString(R.string.repeat_password), "", getString(R.string.password), password = true) { again ->
                if (again != pw) { toast(getString(R.string.password_mismatch)); return@askText }
                askText(getString(R.string.protected_name), "${Storage.baseName(model.title)} (${getString(R.string.protected_suffix)})", getString(R.string.file_name)) { name ->
                    exportThen(Exporter.Options(true, pw), getString(R.string.protecting)) { out ->
                        val origin = withContext(Dispatchers.IO) { Storage.saveNew(this@EditorActivity, out, name) }
                        savedSnack(getString(R.string.protected_saved, Storage.describe(origin)), null)
                    }
                }
            }
        }
    }

    private fun exportImages() {
        exportThen(Exporter.Options(true, null), getString(R.string.to_images)) { out ->
            val folder = Storage.sanitize(Storage.baseName(model.title))
            val count = withContext(Dispatchers.IO) { renderToImages(out, folder) }
            savedSnack(getString(R.string.images_saved, count, "Pictures/${Storage.FOLDER}/$folder"), null)
        }
    }

    private fun renderToImages(pdf: File, folder: String): Int {
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var n = 0
        PdfRenderer(fd).use { r ->
            for (i in 0 until r.pageCount) {
                val pg = r.openPage(i)
                val w = 1654
                val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                pg.close()
                val name = String.format(Locale.US, "page_%02d.jpg", i + 1)
                saveImage(bmp, folder, name)
                bmp.recycle()
                n++
            }
        }
        return n
    }

    private fun saveImage(bmp: Bitmap, folder: String, name: String) {
        if (Storage.hasAllFilesAccess(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "${Storage.FOLDER}/$folder")
            dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            Storage.scan(this, f.path)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Storage.FOLDER + "/" + folder)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    // ------------------------------------------------------------------ drafts

    private val draftRunnable = Runnable { saveDraftNow() }

    private fun scheduleDraft() {
        if (!loaded || docKey == null) return
        handler.removeCallbacks(draftRunnable)
        if (model.history.dirty) handler.postDelayed(draftRunnable, 2000) else deleteDraft()
    }

    private fun saveDraftNow() {
        val key = docKey ?: return
        if (!model.history.dirty) return
        val stamp = originalStamp
        runCatching { Drafts.save(this, key, model, stamp) }
    }

    private fun deleteDraft() {
        handler.removeCallbacks(draftRunnable)
        docKey?.let { Drafts.delete(this, it) }
    }

    // ------------------------------------------------------------------ search

    @SuppressLint("SetTextI18n")
    private fun setupSearch() {
        findViewById<View>(R.id.searchClose).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.searchNext).setOnClickListener { stepHit(1) }
        findViewById<View>(R.id.searchPrev).setOnClickListener { stepHit(-1) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchRunnable)
                runSearch(searchInput.textString())
                hideKeyboard(searchInput)
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 550)
            }
        })
    }

    private val searchRunnable = Runnable { runSearch(searchInput.textString()) }

    private fun openSearch() {
        if (!loaded) return
        searchBar.visibility = View.VISIBLE
        toolbar.visibility = View.GONE
        showKeyboard(searchInput)
    }

    private fun closeSearch() {
        searchJob?.cancel()
        handler.removeCallbacks(searchRunnable)
        hideKeyboard(searchInput)
        searchBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        topProgress.visibility = View.GONE
        hits.clear()
        hitIndex = -1
        searchCount.text = ""
        pdfView.setSearchHits(emptyMap())
        pdfView.setCurrentHit(-1, null)
    }

    @SuppressLint("SetTextI18n")
    private fun runSearch(q: String) {
        searchJob?.cancel()
        val nq = Norm.of(q)
        if (nq.isEmpty()) {
            hits.clear(); hitIndex = -1; searchCount.text = ""
            pdfView.setSearchHits(emptyMap()); pdfView.setCurrentHit(-1, null)
            return
        }
        searchJob = lifecycleScope.launch {
            topProgress.visibility = View.VISIBLE
            val found = LinkedHashMap<Long, List<RectF>>()
            val flat = ArrayList<Pair<Long, RectF>>()
            var anyText = false
            val pages = model.pages.toList()
            for ((i, item) in pages.withIndex()) {
                val pt = pageText(item)
                if (!pt.isEmpty) anyText = true
                val r = pt.find(nq)
                if (r.isNotEmpty()) {
                    found[item.id] = r
                    r.forEach { flat += item.id to it }
                }
                if (i % 4 == 3) {
                    searchCount.text = "${flat.size}…"
                    pdfView.setSearchHits(HashMap(found))
                }
            }
            topProgress.visibility = View.GONE
            hits = flat
            pdfView.setSearchHits(found)
            if (flat.isEmpty()) {
                searchCount.text = "0"
                pdfView.setCurrentHit(-1, null)
                if (!anyText) toast(getString(R.string.scanned_no_text), true)
                else toast(getString(R.string.not_found, q))
            } else {
                val cur = pdfView.currentPage()
                hitIndex = flat.indexOfFirst { model.indexOfPage(it.first) >= cur }.let { if (it < 0) 0 else it }
                showHit()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showHit() {
        val h = hits.getOrNull(hitIndex) ?: return
        pdfView.setCurrentHit(h.first, h.second)
        pdfView.ensureVisible(model.indexOfPage(h.first), h.second, 0.3f)
        searchCount.text = "${hitIndex + 1}/${hits.size}"
    }

    private fun stepHit(d: Int) {
        if (hits.isEmpty()) return
        hitIndex = ((hitIndex + d) % hits.size + hits.size) % hits.size
        showHit()
    }

    // ------------------------------------------------------------------ back

    private fun onBack() {
        when {
            busy.visibility == View.VISIBLE -> return
            searchBar.visibility == View.VISIBLE -> closeSearch()
            editMode && pdfView.selection() != null -> pdfView.select(null, null)
            editMode -> exitEditMode()
            loaded && model.history.dirty -> askSaveOnExit()
            else -> finish()
        }
    }

    private fun askSaveOnExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save_changes_q))
            .setMessage(getString(R.string.save_changes_msg, Storage.baseName(model.title)))
            .setPositiveButton(getString(R.string.save_btn)) { _, _ -> save { finish() } }
            .setNegativeButton(getString(R.string.dont_save)) { _, _ -> deleteDraft(); model.history.markClean(); finish() }
            .setNeutralButton(getString(R.string.cancel), null)
            .show()
    }

    companion object {
        fun formatSize(b: Long): String = when {
            b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
            b >= 1024 -> String.format(Locale.US, "%d KB", b / 1024)
            else -> "$b B"
        }
    }
}
