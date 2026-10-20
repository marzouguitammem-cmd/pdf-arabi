package com.tammem.pdfarabi.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.tammem.pdfarabi.model.Origin
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Turns whatever was opened (file, link from another app, several files to merge…) into working copies. */
object DocOpener {
    const val EXTRA_PATH = "path"
    const val EXTRA_URIS = "uris"
    const val EXTRA_WORK = "work"
    const val EXTRA_TITLE = "title"

    class Prepared(
        val files: List<File>,
        val title: String,
        val origin: Origin,
        /** Key for "recent files" and unsaved-edit drafts (null for new/merged documents). */
        val key: String?,
        /** Size + date of the original, to tell whether a draft still matches it. */
        val stamp: String,
        val size: Long
    )

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun uriList(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        else intent.getParcelableArrayListExtra(EXTRA_URIS)

    fun prepare(ctx: Context, intent: Intent): Prepared {
        intent.getStringExtra(EXTRA_WORK)?.let { work ->
            val f = File(work)
            val path = intent.getStringExtra(EXTRA_PATH)
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "ملف جديد"
            return if (path != null) Prepared(listOf(f), title, Origin.Path(path), path, stampOf(File(path)), f.length())
            else Prepared(listOf(f), title, Origin.None, null, "", f.length())
        }
        uriList(intent)?.let { uris ->
            val files = uris.map { u ->
                val dest = Storage.newWorkFile(ctx)
                Storage.copyUri(ctx, u, dest)
                dest
            }
            return Prepared(files, intent.getStringExtra(EXTRA_TITLE) ?: "ملف مدمج", Origin.None, null, "", files.sumOf { it.length() })
        }
        intent.getStringExtra(EXTRA_PATH)?.let { return fromPath(ctx, it) }
        val uri = intent.data ?: streamUri(intent) ?: error("nothing to open")
        if (uri.scheme == "file") return fromPath(ctx, uri.path ?: error("bad path"))
        return fromUri(ctx, uri)
    }

    fun stampOf(f: File) = "${f.length()}:${f.lastModified()}"

    fun fromPath(ctx: Context, path: String): Prepared {
        val src = File(path)
        val dest = Storage.newWorkFile(ctx)
        src.copyTo(dest, overwrite = true)
        return Prepared(listOf(dest), src.name, Origin.Path(path), path, stampOf(src), src.length())
    }

    fun fromUri(ctx: Context, uri: Uri): Prepared {
        val real = Storage.realPath(ctx, uri)
        if (real != null && File(real).canRead()) {
            val p = fromPath(ctx, real)
            if (File(real).canWrite()) return p
        }
        Storage.takePermission(ctx, uri)
        val dest = Storage.newWorkFile(ctx)
        Storage.copyUri(ctx, uri, dest)
        val name = Storage.displayName(ctx, uri) ?: "ملف.pdf"
        val writable = Storage.canWriteUri(ctx, uri)
        val size = dest.length()
        return Prepared(listOf(dest), name, Origin.Content(uri, writable), uri.toString(), "$size", size)
    }

    /** Removes the password of a protected file (into a new working copy). Throws on a wrong password. */
    fun decrypt(ctx: Context, file: File, password: String): File {
        val out = Storage.newWorkFile(ctx)
        PDDocument.load(file, password).use { d ->
            d.isAllSecurityToBeRemoved = true
            d.save(out)
        }
        return out
    }
}
