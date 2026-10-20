package com.tammem.pdfarabi.word

import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A .docx file held in memory: every part by name, in the original order. */
class DocxPackage(val entries: LinkedHashMap<String, ByteArray>) {

    class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    /** Path of the main document part (normally word/document.xml). */
    val mainPart: String by lazy {
        val rels = entries["_rels/.rels"]?.let { runCatching { parseRels(it) }.getOrNull() } ?: emptyList()
        val off = rels.firstOrNull { it.type.endsWith("/officeDocument") }?.target?.trimStart('/')
        if (off != null && entries.containsKey(off)) off else "word/document.xml"
    }

    val mainDir: String get() = mainPart.substringBeforeLast('/', "")

    fun relsPathFor(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val name = part.substringAfterLast('/')
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
    }

    fun rels(part: String): List<Rel> =
        entries[relsPathFor(part)]?.let { runCatching { parseRels(it) }.getOrNull() } ?: emptyList()

    /** Resolves a relationship target relative to the part's folder. */
    fun resolve(part: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val base = part.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (base.isNotEmpty()) base.removeAt(base.size - 1)
                else -> base += seg
            }
        }
        return base.joinToString("/")
    }

    fun partFor(type: String): String? {
        val r = rels(mainPart).firstOrNull { it.type == type } ?: return null
        val p = resolve(mainPart, r.target)
        return if (entries.containsKey(p)) p else null
    }

    fun bytes(path: String): ByteArray? = entries[path]

    fun write(out: File) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            val order = entries.keys.sortedBy { if (it == "[Content_Types].xml") 0 else 1 }
            for (name in order) {
                val e = ZipEntry(name)
                z.putNextEntry(e)
                z.write(entries.getValue(name))
                z.closeEntry()
            }
        }
    }

    companion object {
        fun read(input: InputStream): DocxPackage {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.trimStart('/').replace('\\', '/')
                    val bos = ByteArrayOutputStream()
                    z.copyTo(bos, 64 * 1024)
                    map[name] = bos.toByteArray()
                }
            }
            if (map.isEmpty()) error("not a docx")
            return DocxPackage(map)
        }

        fun parseRels(bytes: ByteArray): List<Rel> {
            val doc = Ox.parse(bytes)
            val out = ArrayList<Rel>()
            val list = doc.getElementsByTagNameNS(Ox.PKG_REL, "Relationship")
            for (i in 0 until list.length) {
                val e = list.item(i) as Element
                out += Rel(e.getAttribute("Id"), e.getAttribute("Type"), e.getAttribute("Target"),
                    e.getAttribute("TargetMode").equals("External", true))
            }
            return out
        }
    }
}
