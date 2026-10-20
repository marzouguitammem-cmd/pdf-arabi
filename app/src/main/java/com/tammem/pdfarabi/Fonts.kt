package com.tammem.pdfarabi

import android.graphics.Typeface

/** Fonts bundled with the app (Arabic shaping is done by Android, so letters always join correctly). */
object Fonts {

    class Face(val id: String, val labelRes: Int, regular: () -> Typeface, bold: () -> Typeface) {
        val regular: Typeface by lazy(regular)
        val bold: Typeface by lazy(bold)
    }

    private fun asset(name: String): Typeface =
        try {
            Typeface.createFromAsset(App.instance.assets, "fonts/$name")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }

    val all: List<Face> = listOf(
        Face("amiri", R.string.f_naskh, { asset("Amiri-Regular.ttf") }, { asset("Amiri-Bold.ttf") }),
        Face("tajawal", R.string.f_modern, { asset("Tajawal-Regular.ttf") }, { asset("Tajawal-Bold.ttf") }),
        Face("ruqaa", R.string.f_ruqaa, { asset("ArefRuqaa-Regular.ttf") }, { asset("ArefRuqaa-Bold.ttf") }),
        Face("system", R.string.f_system, { Typeface.DEFAULT }, { Typeface.DEFAULT_BOLD }),
        Face("serif", R.string.f_serif, { Typeface.SERIF }, { Typeface.create(Typeface.SERIF, Typeface.BOLD) })
    )

    fun get(id: String): Face = all.firstOrNull { it.id == id } ?: all[0]

    fun typeface(id: String, bold: Boolean): Typeface = get(id).let { if (bold) it.bold else it.regular }

    /** Picks the closest bundled font for a font name found inside a PDF. */
    fun guess(pdfFontName: String?, rtl: Boolean): String {
        val n = (pdfFontName ?: "").lowercase()
        val naskh = listOf("tradition", "amiri", "times", "naskh", "typesetting", "majalla", "scheherazade",
            "lotus", "badr", "mitra", "nazanin", "simplified", "andalus", "uthman", "noorehira")
        val sans = listOf("arial", "tahoma", "segoe", "calibri", "verdana", "helvetica", "sans", "cairo",
            "tajawal", "droid", "dubai", "roboto", "kufi", "almarai", "janna", "hacen", "neo")
        val ruqaa = listOf("ruqaa", "ruqa", "diwani", "hand", "script", "kids")
        return when {
            ruqaa.any { n.contains(it) } -> "ruqaa"
            naskh.any { n.contains(it) } -> if (!rtl && n.contains("times")) "serif" else "amiri"
            sans.any { n.contains(it) } -> if (rtl) "tajawal" else "system"
            rtl -> "amiri"
            else -> "serif"
        }
    }

    fun looksBold(pdfFontName: String?): Boolean {
        val n = (pdfFontName ?: "").lowercase()
        return listOf("bold", "black", "heavy", "semibold", ",bd", "-bd").any { n.contains(it) }
    }
}
