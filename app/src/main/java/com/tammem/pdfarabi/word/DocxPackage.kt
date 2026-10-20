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
            if (map.keys.none { it.endsWith(".rels") }) error("not a Word document")
            fromStrict(map)
            return DocxPackage(map)
        }

        private const val P = "http://purl.oclc.org/ooxml/"
        private const val T = "http://schemas.openxmlformats.org/"

        /** Strict Open XML namespaces and their transitional twins (specific names before their prefixes). */
        private val STRICT = listOf(
            "${P}officeDocument/relationships/metadata/core-properties" to "${T}package/2006/relationships/metadata/core-properties",
            "${P}officeDocument/relationships/metadata/thumbnail" to "${T}package/2006/relationships/metadata/thumbnail",
            "${P}officeDocument/relationships/extendedProperties" to "${T}officeDocument/2006/relationships/extended-properties",
            "${P}officeDocument/relationships/customProperties" to "${T}officeDocument/2006/relationships/custom-properties",
            "${P}officeDocument/relationships" to "${T}officeDocument/2006/relationships",
            "${P}officeDocument/extendedProperties" to "${T}officeDocument/2006/extended-properties",
            "${P}officeDocument/customProperties" to "${T}officeDocument/2006/custom-properties",
            "${P}officeDocument/docPropsVTypes" to "${T}officeDocument/2006/docPropsVTypes",
            "${P}officeDocument/sharedTypes" to "${T}officeDocument/2006/sharedTypes",
            "${P}officeDocument/bibliography" to "${T}officeDocument/2006/bibliography",
            "${P}officeDocument/math" to "${T}officeDocument/2006/math",
            "${P}wordprocessingml/main" to "${T}wordprocessingml/2006/main",
            "${P}drawingml/wordprocessingDrawing" to "${T}drawingml/2006/wordprocessingDrawing",
            "${P}drawingml/main" to "${T}drawingml/2006/main",
            "${P}drawingml/picture" to "${T}drawingml/2006/picture",
            "${P}drawingml/chart" to "${T}drawingml/2006/chart",
            "${P}drawingml/diagram" to "${T}drawingml/2006/diagram",
            "${P}drawingml/lockedCanvas" to "${T}drawingml/2006/lockedCanvas",
        )

        /**
         * Word can save "Strict Open XML" files, which use other namespace names. They are turned into the
         * usual (transitional) names when the file is opened, so the whole app reads them and Word reopens the save.
         */
        private fun fromStrict(map: LinkedHashMap<String, ByteArray>) {
            val rootRels = map["_rels/.rels"]?.toString(Charsets.UTF_8) ?: return
            if (!rootRels.contains(P)) return
            for ((name, bytes) in map.entries.toList()) {
                val low = name.lowercase()
                if (!(low.endsWith(".xml") || low.endsWith(".rels"))) continue
                var t = bytes.toString(Charsets.UTF_8)
                if (!t.contains(P) && !t.contains("conformance=")) continue
                for ((a, b) in STRICT) t = t.replace(a, b)
                t = t.replace(Regex("""\s(\w+:)?conformance="strict""""), "")
                map[name] = t.toByteArray(Charsets.UTF_8)
            }
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
