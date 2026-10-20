package com.tammem.pdfarabi.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A PDF shown in the home screen lists. */
data class PdfFile(
    val key: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String,
    val path: String?,
    val uri: Uri?
) {
    val isWord: Boolean get() = name.lowercase().let { it.endsWith(".docx") || it.endsWith(".doc") } || key.lowercase().endsWith(".docx")
}

object FileRepo {

    @Suppress("DEPRECATION")
    fun listAll(ctx: Context): List<PdfFile> {
        val out = LinkedHashMap<String, PdfFile>()
        val col = MediaStore.Files.FileColumns.DATA
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            col
        )
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val sel = "$mime = ? OR $col LIKE ? OR $mime = ? OR $col LIKE ?"
        val args = arrayOf("application/pdf", "%.pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "%.docx")
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"), proj, sel, args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(4) ?: continue
                    val f = File(path)
                    if (!f.isFile) continue
                    if (path.contains("/Android/data/") || path.contains("/.")) continue
                    val name = c.getString(1) ?: f.name
                    out[path] = PdfFile(path, name, f.length(), f.lastModified(), folderLabel(ctx, f), path, null)
                }
            }
        }
        return out.values.sortedByDescending { it.modified }
    }

    private fun folderLabel(ctx: Context, f: File): String {
        val parent = f.parentFile ?: return ""
        val root = Environment.getExternalStorageDirectory().path
        return when {
            parent.path == root -> ctx.getString(R.string.folder_storage)
            parent.name.equals("Download", true) -> ctx.getString(R.string.folder_downloads)
            parent.path.contains("WhatsApp", true) -> ctx.getString(R.string.folder_whatsapp)
            parent.path.contains("Telegram", true) -> ctx.getString(R.string.folder_telegram)
            parent.name.equals("Documents", true) -> ctx.getString(R.string.folder_documents)
            else -> parent.name
        }
    }

    fun recents(ctx: Context): List<PdfFile> = Prefs.recents().mapNotNull { r ->
        if (r.key.startsWith("/")) {
            val f = File(r.key)
            if (!f.isFile) null else PdfFile(r.key, f.name, f.length(), r.time, folderLabel(ctx, f), r.key, null)
        } else {
            val uri = Uri.parse(r.key)
            PdfFile(r.key, r.name, r.size, r.time, "", null, uri)
        }
    }

    fun favorites(ctx: Context, known: List<PdfFile>): List<PdfFile> {
        val favs = Prefs.favorites()
        val byKey = known.associateBy { it.key }
        return favs.mapNotNull { k ->
            byKey[k] ?: if (k.startsWith("/")) {
                val f = File(k)
                if (f.isFile) PdfFile(k, f.name, f.length(), f.lastModified(), folderLabel(ctx, f), k, null) else null
            } else PdfFile(k, Prefs.recents().firstOrNull { it.key == k }?.name ?: ctx.getString(R.string.file_word), 0, 0, "", null, Uri.parse(k))
        }.sortedBy { it.name.lowercase() }
    }
}

/** First-page thumbnails for the file lists (memory + disk cache). */
object FileThumbs {
    private val mem = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun load(ctx: Context, f: PdfFile, iv: ImageView) {
        val key = "${f.key}#${f.modified}#${f.size}"
        iv.tag = key
        if (f.isWord) {
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iv.setImageResource(com.tammem.pdfarabi.R.drawable.ic_word)
            return
        }
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        mem.get(key)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        val app = ctx.applicationContext
        pool.execute {
            val bmp = runCatching { loadOrRender(app, f, key) }.getOrNull() ?: return@execute
            mem.put(key, bmp)
            main.post { if (iv.tag == key) iv.setImageBitmap(bmp) }
        }
    }

    private fun loadOrRender(ctx: Context, f: PdfFile, key: String): Bitmap? {
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val disk = File(dir, hash(key) + ".jpg")
        if (disk.exists()) android.graphics.BitmapFactory.decodeFile(disk.path)?.let { return it }
        val fd: ParcelFileDescriptor = when {
            f.path != null -> ParcelFileDescriptor.open(File(f.path), ParcelFileDescriptor.MODE_READ_ONLY)
            f.uri != null -> ctx.contentResolver.openFileDescriptor(f.uri, "r") ?: return null
            else -> return null
        }
        val bmp = PdfRenderer(fd).use { r ->
            if (r.pageCount == 0) return null
            val p = r.openPage(0)
            val w = 160
            val h = (w.toFloat() * p.height / p.width).toInt().coerceIn(1, 480)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            b
        }
        runCatching { FileOutputStream(disk).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        return bmp
    }

    fun forget(key: String) {
        mem.snapshot().keys.filter { it.startsWith("$key#") }.forEach { mem.remove(it) }
    }
}
