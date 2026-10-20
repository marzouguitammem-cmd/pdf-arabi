package com.tammem.pdfarabi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.io.DocOpener
import com.tammem.pdfarabi.io.FileRepo
import com.tammem.pdfarabi.io.FileThumbs
import com.tammem.pdfarabi.io.PdfFile
import com.tammem.pdfarabi.io.PdfMaker
import com.tammem.pdfarabi.io.Storage
import com.tammem.pdfarabi.model.Origin
import com.tammem.pdfarabi.text.Norm
import com.tammem.pdfarabi.word.BlankDocx
import com.tammem.pdfarabi.word.WordActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var fab: ExtendedFloatingActionButton

    private var all: List<PdfFile> = emptyList()
    private var recents: List<PdfFile> = emptyList()
    private var query = ""
    private val adapter = FilesAdapter()

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Storage.takePermission(this, uri)
            openUri(uri)
        }
    }
    private val mergeDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) {
            if (uris.isNotEmpty()) toast(getString(R.string.pick_two))
            return@registerForActivityResult
        }
        val i = Intent(this, EditorActivity::class.java)
        i.putParcelableArrayListExtra(DocOpener.EXTRA_URIS, ArrayList(uris))
        i.putExtra(DocOpener.EXTRA_TITLE, getString(R.string.merged_title))
        startActivity(i)
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(80)) { uris ->
        if (uris.isNotEmpty()) imagesToPdf(uris)
    }
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        val pdf = r?.pdf?.uri ?: return@registerForActivityResult
        saveScanned(pdf)
    }
    private val legacyPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        refresh = findViewById(R.id.refresh)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        fab = findViewById(R.id.fabOpen)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            list.updatePadding(left = b.left, right = b.right, bottom = dp(104f) + b.bottom)
            (fab.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.bottomMargin = dp(16f) + b.bottom
                it.marginEnd = dp(16f) + b.right
                fab.layoutParams = it
            }
            insets
        }

        setupMenu()
        setupTools()
        listOf(getString(R.string.tab_recent), getString(R.string.tab_all), getString(R.string.tab_fav)).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        tabs.getTabAt(Prefs.homeTab.coerceIn(0, 2))?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { Prefs.homeTab = tab.position; render() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { list.smoothScrollToPosition(0) }
        })

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) fab.shrink() else if (dy < -6) fab.extend()
            }
        })
        refresh.setOnRefreshListener { reload() }
        fab.setOnClickListener { openDoc.launch(arrayOf("application/pdf", WordActivity.MIME, "application/msword")) }

        if (!Storage.hasAllFilesAccess(this) && !Prefs.permissionAsked) {
            Prefs.permissionAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_body)
                .setPositiveButton(R.string.perm_button) { _, _ -> requestAccess() }
                .setNegativeButton(getString(R.string.not_now), null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setupMenu() {
        val search = toolbar.menu.add(0, 1, 0, getString(R.string.search_files)).setIcon(R.drawable.ic_search)
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val sv = SearchView(this)
        sv.queryHint = getString(R.string.search_files)
        sv.maxWidth = Int.MAX_VALUE
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean { query = q ?: ""; render(); return true }
            override fun onQueryTextChange(q: String?): Boolean { query = q ?: ""; render(); return true }
        })
        search.actionView = sv
        search.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean { query = ""; render(); return true }
        })
        toolbar.menu.add(0, 2, 1, getString(R.string.settings)).setIcon(R.drawable.ic_settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == 2) startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
    }

    private fun setupTools() {
        val row = findViewById<LinearLayout>(R.id.toolsRow)
        fun add(icon: Int, label: String, tint: Int, action: () -> Unit) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_tool_card, row, false)
            card.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(icon)
                imageTintList = if (icon == R.drawable.ic_word) null else ColorStateList.valueOf(tint)
            }
            card.findViewById<FrameLayout>(R.id.iconBg).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((tint and 0x00FFFFFF) or 0x22000000)
            }
            card.findViewById<TextView>(R.id.label).text = label
            card.setOnClickListener { action() }
            row.addView(card)
        }
        add(R.drawable.ic_scan, getString(R.string.tool_scan), 0xFFD84315.toInt()) { startScan() }
        add(R.drawable.ic_photos, getString(R.string.tool_images), 0xFF2E7D32.toInt()) {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        add(R.drawable.ic_merge, getString(R.string.tool_merge), 0xFF1565C0.toInt()) { mergeDocs.launch(arrayOf("application/pdf")) }
        add(R.drawable.ic_word, getString(R.string.word_new), 0xFF2B579A.toInt()) { newWordDocument() }
        add(R.drawable.ic_note_add, getString(R.string.tool_new), 0xFF6A1B9A.toInt()) { newDocument() }
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        lifecycleScope.launch {
            val hasAccess = Storage.hasAllFilesAccess(this@HomeActivity)
            val (a, r) = withContext(Dispatchers.IO) {
                val a = if (hasAccess) FileRepo.listAll(this@HomeActivity) else emptyList()
                a to FileRepo.recents(this@HomeActivity)
            }
            all = a
            recents = r
            refresh.isRefreshing = false
            render()
        }
    }

    private fun render() {
        val tab = tabs.selectedTabPosition.coerceAtLeast(0)
        val hasAccess = Storage.hasAllFilesAccess(this)
        var rows: List<PdfFile> = when (tab) {
            0 -> recents
            1 -> all
            else -> FileRepo.favorites(this, all + recents)
        }
        if (query.isNotBlank()) {
            val q = Norm.of(query)
            rows = rows.filter { Norm.of(it.name).contains(q) }
        }
        val showPerm = !hasAccess && (tab == 1 || (tab == 0 && rows.isEmpty()))
        adapter.submit(rows, showPerm, tab)
        val isEmpty = rows.isEmpty() && !showPerm
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        emptyText.text = when {
            query.isNotBlank() -> getString(R.string.no_name_match)
            tab == 0 -> getString(R.string.empty_recent)
            tab == 1 -> getString(R.string.empty_all)
            else -> getString(R.string.empty_fav)
        }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            legacyPerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    // ---------------------------------------------------------------- open & actions

    private fun openFile(f: PdfFile) {
        if (f.path != null) {
            val target = if (f.isWord) WordActivity::class.java else EditorActivity::class.java
            startActivity(Intent(this, target).putExtra(DocOpener.EXTRA_PATH, f.path))
        } else if (f.uri != null) {
            openUri(f.uri, f.isWord)
        }
    }

    private fun isWordUri(uri: Uri): Boolean {
        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: ""
        if (type == WordActivity.MIME || type == "application/msword") return true
        val name = (Storage.displayName(this, uri) ?: uri.lastPathSegment ?: "").lowercase()
        return name.endsWith(".docx") || name.endsWith(".doc")
    }

    private fun openUri(uri: Uri, word: Boolean? = null) {
        val isWord = word ?: isWordUri(uri)
        val i = Intent(this, if (isWord) WordActivity::class.java else EditorActivity::class.java)
        i.data = uri
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivity(i)
    }

    private fun openOrigin(o: Origin) {
        when (o) {
            is Origin.Path -> startActivity(Intent(this, EditorActivity::class.java).putExtra(DocOpener.EXTRA_PATH, o.path))
            is Origin.Content -> openUri(o.uri)
            Origin.None -> {}
        }
    }

    private fun progress(msg: String): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24f), dp(22f), dp(24f), dp(22f))
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(CircularProgressIndicator(this@HomeActivity).apply { isIndeterminate = true })
            addView(TextView(this@HomeActivity).apply {
                text = msg
                setPadding(dp(18f), 0, 0, 0)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
        }
        return MaterialAlertDialogBuilder(this).setView(box).setCancelable(false).show()
    }

    private fun startScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(80)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast(getString(R.string.scanner_failed), true) }
    }

    private fun saveScanned(pdf: Uri) {
        val dlg = progress(getString(R.string.saving_scan))
        lifecycleScope.launch {
            val origin = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(Storage.tmpDir(this@HomeActivity), "scan.pdf")
                    Storage.copyUri(this@HomeActivity, pdf, tmp)
                    Storage.saveNew(this@HomeActivity, tmp, "${getString(R.string.scan_name)} ${Storage.stamp()}").also { tmp.delete() }
                }.getOrNull()
            }
            dlg.dismiss()
            if (origin == null) toast(getString(R.string.scan_failed)) else openOrigin(origin)
        }
    }

    private fun imagesToPdf(uris: List<Uri>) {
        val dlg = progress(getString(R.string.converting_images, uris.size))
        lifecycleScope.launch {
            val origin = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(Storage.tmpDir(this@HomeActivity), "images.pdf")
                    PdfMaker.imagesToPdf(this@HomeActivity, uris, tmp)
                    Storage.saveNew(this@HomeActivity, tmp, "${getString(R.string.images_name)} ${Storage.stamp()}").also { tmp.delete() }
                }.getOrNull()
            }
            dlg.dismiss()
            if (origin == null) toast(getString(R.string.convert_failed)) else openOrigin(origin)
        }
    }

    private fun newDocument() {
        val styles = arrayOf(getString(R.string.ns_blank), getString(R.string.ps_lined), getString(R.string.ps_grid), getString(R.string.ps_seyes))
        var style = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_pdf))
            .setSingleChoiceItems(styles, 0) { _, which -> style = which }
            .setPositiveButton(getString(R.string.next)) { _, _ ->
                val counts = arrayOf("1", "2", "5", "10")
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.how_many_pages))
                    .setItems(counts) { _, which ->
                        lifecycleScope.launch {
                            val f = withContext(Dispatchers.IO) {
                                val out = Storage.newWorkFile(this@HomeActivity)
                                PdfMaker.blank(out, counts[which].toInt(), style)
                                out
                            }
                            startActivity(Intent(this@HomeActivity, EditorActivity::class.java)
                                .putExtra(DocOpener.EXTRA_WORK, f.path)
                                .putExtra(DocOpener.EXTRA_TITLE, getString(R.string.untitled)))
                        }
                    }
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun newWordDocument() {
        val items = arrayOf(getString(R.string.word_dir_rtl), getString(R.string.word_dir_ltr))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.word_new_dir_title))
            .setItems(items) { _, which ->
                lifecycleScope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val out = File(Storage.workDir(this@HomeActivity), "new-${System.currentTimeMillis()}.docx")
                        BlankDocx.create(out, which == 0)
                        out
                    }
                    startActivity(Intent(this@HomeActivity, WordActivity::class.java)
                        .putExtra(WordActivity.EXTRA_WORK, f.path)
                        .putExtra(WordActivity.EXTRA_TITLE, getString(R.string.untitled)))
                }
            }
            .show()
    }

    private fun share(f: PdfFile) {
        val uri = when {
            f.path != null -> runCatching { FileProvider.getUriForFile(this, "$packageName.files", File(f.path)) }.getOrNull()
            else -> f.uri
        } ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (f.isWord) WordActivity.MIME else "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    private fun rename(f: PdfFile) {
        val path = f.path ?: return
        askText(getString(R.string.rename), Storage.baseName(f.name), getString(R.string.new_name)) { name ->
            val src = File(path)
            val dest = File(src.parentFile, Storage.withPdf(Storage.sanitize(name)))
            if (dest.exists()) { toast(getString(R.string.name_exists)); return@askText }
            if (src.renameTo(dest)) {
                Prefs.renameRecent(path, dest.path, dest.name)
                if (Prefs.isFavorite(path)) { Prefs.toggleFavorite(path); Prefs.toggleFavorite(dest.path) }
                Storage.scan(this, src.path)
                Storage.scan(this, dest.path)
                reload()
            } else toast(getString(R.string.rename_failed))
        }
    }

    private fun delete(f: PdfFile) {
        val path = f.path ?: return
        confirm(getString(R.string.delete_file_q), getString(R.string.delete_file_msg, f.name), getString(R.string.delete)) {
            if (File(path).delete()) {
                Prefs.removeRecent(path)
                Storage.scan(this, path)
                reload()
            } else toast(getString(R.string.delete_failed))
        }
    }

    private fun showMenu(anchor: View, f: PdfFile, tab: Int) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, getString(R.string.share))
        if (f.path != null && Storage.hasAllFilesAccess(this)) {
            pm.menu.add(0, 2, 1, getString(R.string.rename))
            pm.menu.add(0, 4, 3, getString(R.string.delete_file))
        }
        if (tab == 0) pm.menu.add(0, 3, 2, getString(R.string.remove_from_list))
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> share(f)
                2 -> rename(f)
                3 -> { Prefs.removeRecent(f.key); reload() }
                4 -> delete(f)
            }
            true
        }
        pm.show()
    }

    // ---------------------------------------------------------------- adapter

    private inner class FilesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<PdfFile> = emptyList()
        private var perm = false
        private var tab = 0

        @SuppressLint("NotifyDataSetChanged")
        fun submit(r: List<PdfFile>, showPerm: Boolean, t: Int) {
            rows = r
            perm = showPerm
            tab = t
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size + if (perm) 1 else 0
        override fun getItemViewType(position: Int) = if (perm && position == 0) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 1) object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_perm_card, parent, false)) {}
            else object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_file, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 1) {
                h.itemView.findViewById<View>(R.id.grant).setOnClickListener { requestAccess() }
                return
            }
            val f = rows[position - if (perm) 1 else 0]
            val v = h.itemView
            v.findViewById<TextView>(R.id.name).text = Storage.baseName(f.name)
            val parts = ArrayList<String>()
            if (f.size > 0) parts += EditorActivity.formatSize(f.size)
            if (f.modified > 0) parts += DateUtils.getRelativeTimeSpanString(f.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            if (f.folder.isNotEmpty()) parts += f.folder
            v.findViewById<TextView>(R.id.meta).text = parts.joinToString(" • ")
            FileThumbs.load(this@HomeActivity, f, v.findViewById(R.id.thumb))
            val star = v.findViewById<ImageButton>(R.id.star)
            fun paintStar() {
                val fav = Prefs.isFavorite(f.key)
                star.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
                star.imageTintList = ColorStateList.valueOf(
                    if (fav) 0xFFF9A825.toInt() else attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            paintStar()
            star.setOnClickListener {
                Prefs.toggleFavorite(f.key)
                if (tab == 2) render() else paintStar()
            }
            v.findViewById<ImageButton>(R.id.more).setOnClickListener { showMenu(it, f, tab) }
            v.setOnClickListener { openFile(f) }
        }
    }
}
