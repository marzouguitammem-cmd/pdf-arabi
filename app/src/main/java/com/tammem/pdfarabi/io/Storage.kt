package com.tammem.pdfarabi.io

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.tammem.pdfarabi.model.Origin
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Storage {
    const val FOLDER = "محرّر PDF"

    fun workDir(ctx: Context) = File(ctx.filesDir, "work").apply { mkdirs() }
    fun imagesDir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }
    fun draftsDir(ctx: Context) = File(ctx.filesDir, "drafts").apply { mkdirs() }
    fun outDir(ctx: Context) = File(ctx.cacheDir, "out").apply { mkdirs() }
    fun tmpDir(ctx: Context) = File(ctx.cacheDir, "tmp").apply { mkdirs() }

    fun newWorkFile(ctx: Context): File =
        File(workDir(ctx), "w-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}.pdf")

    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun saveDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())

    fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim()
        return clean.ifEmpty { "ملف" }
    }

    fun withPdf(name: String): String = if (name.lowercase().endsWith(".pdf")) name else "$name.pdf"

    fun baseName(name: String): String = name.removeSuffix(".pdf").removeSuffix(".PDF")

    fun uniqueFile(dir: File, fileName: String): File {
        var f = File(dir, fileName)
        if (!f.exists()) return f
        val base = baseName(fileName)
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i).pdf")
            i++
        }
        return f
    }

    fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** Real file path behind a content address, when the app is allowed to write it directly. */
    fun realPath(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (!hasAllFilesAccess(ctx)) return null
        val p = runCatching {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        if (p != null && File(p).isFile) return p
        // Files app / Downloads documents: "primary:Documents/x.pdf"
        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (docId.startsWith("primary:")) {
            val f = File(Environment.getExternalStorageDirectory(), docId.removePrefix("primary:"))
            if (f.isFile) return f.path
        }
        if (docId.startsWith("raw:")) {
            val f = File(docId.removePrefix("raw:"))
            if (f.isFile) return f.path
        }
        return null
    }

    fun copyUri(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("cannot open")
        input.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } }
    }

    fun canWriteUri(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)

    fun takePermission(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.recoverCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun scan(ctx: Context, path: String) {
        runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf("application/pdf"), null) }
    }

    /** Overwrites [origin] with [src]. */
    fun writeBack(ctx: Context, src: File, origin: Origin) {
        when (origin) {
            is Origin.Path -> {
                val f = File(origin.path)
                src.inputStream().use { i -> FileOutputStream(f, false).use { o -> i.copyTo(o, 64 * 1024) } }
                scan(ctx, f.path)
            }
            is Origin.Content -> {
                val os = runCatching { ctx.contentResolver.openOutputStream(origin.uri, "wt") }.getOrNull()
                    ?: ctx.contentResolver.openOutputStream(origin.uri, "w")
                    ?: error("cannot write")
                os.use { o -> src.inputStream().use { i -> i.copyTo(o, 64 * 1024) } }
            }
            Origin.None -> error("no origin")
        }
    }

    /** Saves a new file in Documents/محرّر PDF and returns where it went. */
    fun saveNew(ctx: Context, src: File, wantedName: String): Origin {
        val name = withPdf(sanitize(wantedName))
        if (hasAllFilesAccess(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val dir = saveDir().apply { mkdirs() }
                val f = uniqueFile(dir, name)
                src.inputStream().use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 64 * 1024) } }
                scan(ctx, f.path)
                return Origin.Path(f.path)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("cannot create")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { i -> i.copyTo(o, 64 * 1024) } }
                ?: error("cannot write")
            return Origin.Content(uri, true)
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), FOLDER).apply { mkdirs() }
        val f = uniqueFile(dir, name)
        src.copyTo(f, overwrite = true)
        return Origin.Path(f.path)
    }

    /** Human readable place of a saved file. */
    fun describe(origin: Origin): String = when (origin) {
        is Origin.Path -> origin.path.removePrefix(Environment.getExternalStorageDirectory().path + "/")
        is Origin.Content -> "Documents/$FOLDER"
        Origin.None -> ""
    }

    fun cleanupWorkFiles(ctx: Context) {
        val referenced = HashSet<String>()
        draftsDir(ctx).listFiles()?.forEach { f -> runCatching { referenced.add(f.readText()) } }
        val all = referenced.joinToString("\n")
        val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        workDir(ctx).listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff && !all.contains(f.name)) f.delete()
        }
        outDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        tmpDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
