package com.tammem.pdfarabi.text

import com.tammem.pdfarabi.model.DocModel
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Read-only PDFBox documents used for text extraction, on their own thread. */
class PdfBox(private val model: DocModel) {
    companion object {
        val dispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "pdfbox") }.asCoroutineDispatcher()

        fun load(file: java.io.File, password: String? = null): PDDocument =
            PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly())
    }

    private val docs = HashMap<String, PDDocument>()

    /** Must be called on [dispatcher]. */
    fun doc(srcIndex: Int): PDDocument {
        val f = model.sources[srcIndex].file
        return docs.getOrPut(f.path) { load(f) }
    }

    /** Must be called on [dispatcher]. */
    fun closeAll() {
        docs.values.forEach { runCatching { it.close() } }
        docs.clear()
    }
}
