package com.tammem.pdfarabi.text

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.pdfbox.pdmodel.font.encoding.MacRomanEncoding
import com.tom_roush.pdfbox.pdmodel.font.encoding.WinAnsiEncoding
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Decoder for the old "AXt" Arabic fonts (QuarkXPress Arabic XT) still found in many school
 * books. These fonts put Arabic letter shapes in the Latin code positions, so a normal PDF
 * reader shows rubbish like «ádÉ°SôdG» instead of «الرسالة».
 *
 * The layout is regular:
 *  - 0x41–0x72 = the Arabic letters and harakat in ISO-8859-6 order (ء…غ, ف…ي, ً…ْ), using the
 *    initial form for joining letters and the isolated form for the others;
 *  - 0x80–0xC9 = the other forms of each letter (medial, final, isolated) in alphabetical order;
 *  - hamza / madda are separate zero-width marks put on alef or waw;
 *  - seen, sheen, sad and dad are drawn as a head plus a shared tail;
 *  - a few lam ligatures (لا لم لج لح لخ لى) and ير / ين.
 */
object LegacyArabic {

    /** Ink that belongs to the neighbouring letter (tails, kashida, unknown pieces). */
    const val PART = "‍"

    private const val MADDA = "ٓ"
    private const val HAMZA_ABOVE = "ٔ"
    private const val HAMZA_BELOW = "ٕ"

    private val table = arrayOfNulls<String>(256)

    init {
        "ء${MADDA}${HAMZA_ABOVE}${HAMZA_ABOVE}${HAMZA_BELOW}ئابةتثجحخدذرزسشصضطظعغ".forEachIndexed { i, c -> table[0x41 + i] = c.toString() }
        "فقكلمنهوىيًٌٍَُِّْ".forEachIndexed { i, c -> table[0x61 + i] = c.toString() }
        for (c in 0x73..0x7A) table[c] = PART
        table[0x2C] = "،"
        table[0x3B] = "؛"
        table[0x3F] = "؟"
        table[0x60] = "لا"
        fun g(start: Int, count: Int, s: String) { for (c in start until start + count) table[c] = s }
        g(0x80, 3, "ئ"); g(0x83, 1, "ا"); g(0x84, 3, "ب"); g(0x87, 1, "ة"); g(0x88, 3, "ت"); g(0x8B, 3, "ث")
        g(0x8E, 3, "ج"); g(0x91, 3, "ح"); g(0x94, 3, "خ"); g(0x97, 1, "د"); g(0x98, 1, "ذ"); g(0x99, 2, "ر")
        g(0x9B, 2, "ز"); g(0x9D, 1, "س"); g(0x9E, 1, "ش"); g(0x9F, 1, "ص"); g(0xA0, 1, "ض"); g(0xA1, 2, PART)
        g(0xA3, 3, "ط"); g(0xA6, 3, "ظ"); g(0xA9, 3, "ع"); g(0xAC, 3, "غ"); g(0xAF, 3, "ف"); g(0xB2, 3, "ق")
        g(0xB5, 3, "ك"); g(0xB8, 3, "ل"); g(0xBB, 3, "م"); g(0xBE, 3, "ن"); g(0xC1, 3, "ه"); g(0xC4, 1, "و")
        g(0xC5, 2, "ى"); g(0xC7, 3, "ي"); g(0xCA, 1, " "); g(0xD0, 2, PART); g(0xD4, 2, "لا"); g(0xD6, 1, "لج")
        g(0xD7, 1, "لح"); g(0xD8, 1, "لخ"); g(0xD9, 1, "لم"); g(0xDB, 1, "لى"); g(0xDC, 1, "لي"); g(0xEE, 1, "لا")
        g(0xF1, 1, "ير"); g(0xF2, 1, "ين")
        // Digits and the remaining ASCII punctuation keep their meaning.
        for (c in 0x20..0x40) if (table[c] == null) table[c] = c.toChar().toString()
        for (c in "[]()/-.+=*%!\"':".map { it.code }) if (table[c] == null) table[c] = c.toChar().toString()
    }

    /** Codes that practically never appear in French/English text but are frequent in AXt Arabic. */
    private val telltale = BooleanArray(256).also { t ->
        val codes = listOf(0x80, 0x81, 0x83, 0x84, 0x85, 0x86, 0x87, 0x8A, 0x8B, 0x8C, 0x92, 0x93, 0x96, 0x97, 0x98, 0x9A,
            0x9B, 0x9C, 0xA0, 0xA2, 0xA3, 0xA6, 0xA7, 0xA8, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB2, 0xB3,
            0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF, 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5,
            0xC6, 0xD6, 0xD7, 0xD8, 0xD9, 0xDB, 0xEE, 0xF1, 0xF2)
        codes.forEach { t[it] = true }
    }

    /** Unicode text PDFBox produces for each MacRoman code, back to the code. */
    private val macRoman: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        val agl = GlyphList.getAdobeGlyphList()
        for ((code, name) in MacRomanEncoding.INSTANCE.codeToNameMap) {
            val u = runCatching { agl.toUnicode(name) }.getOrNull() ?: continue
            if (!m.containsKey(u)) m[u] = code
        }
        m["µ"] = 0xB5
        m["μ"] = 0xB5
        m["€"] = 0xDB
        m[" "] = 0xCA
        m
    }

    fun isMark(s: String): Boolean = s.length == 1 && s[0] in 'ً'..'ٕ'

    /** For hamza / madda marks: the letter they sit on. */
    fun preferredBase(s: String, code: Int): Char? = when {
        code == 0x44 -> 'و'
        s == HAMZA_ABOVE || s == HAMZA_BELOW || s == MADDA -> 'ا'
        else -> null
    }

    /** A hamza/madda that found no letter to sit on becomes a full letter. */
    fun standalone(s: String, code: Int): String = when {
        code == 0x44 -> "ؤ"
        s == HAMZA_ABOVE -> "أ"
        s == HAMZA_BELOW -> "إ"
        s == MADDA -> "آ"
        else -> ""
    }

    /** Glyph position code of one extracted character. */
    fun codeOf(tp: TextPosition): Int? {
        val enc = (tp.font as? PDSimpleFont)?.encoding
        val codes = tp.characterCodes
        if (enc is WinAnsiEncoding && codes != null && codes.size == 1) return codes[0].takeIf { it in 0..255 }
        val u = tp.unicode
        if (!u.isNullOrEmpty()) {
            macRoman[u]?.let { return it }
            if (u.length == 1 && u[0].code in 0x20..0x7E) return u[0].code
        }
        return codes?.singleOrNull()?.takeIf { it in 0..255 }
    }

    /** Decoded text of a glyph position. */
    fun decode(code: Int): String = table.getOrNull(code) ?: PART

    private val namedOther = listOf("symbol", "math", "extra", "wingding", "dingbat", "zapf", "webding")

    fun hasLegacyName(font: PDFont?): Boolean {
        val n = runCatching { font?.name }.getOrNull()?.lowercase() ?: return false
        return n.contains("axt")
    }

    /** Decides from the font name and the characters it draws whether a font is an old AXt Arabic font. */
    fun detect(font: PDFont?, sample: List<TextPosition>): Boolean {
        if (hasLegacyName(font)) return true
        val name = runCatching { font?.name }.getOrNull()?.lowercase() ?: ""
        if (namedOther.any { name.contains(it) }) return false
        var total = 0
        var tell = 0
        var ascii = 0
        var frequent = 0
        for (tp in sample) {
            val u = tp.unicode ?: continue
            if (u.isBlank()) continue
            if (u.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC }) return false
            val c = codeOf(tp) ?: continue
            total++
            if (telltale[c]) tell++
            if (c in 0x41..0x5A || c in 0x61..0x7A) ascii++
            if (c == 0x47 || c == 0x64 || c == 0x83 || c == 0x87) frequent++
        }
        if (total < 8) return false
        return tell >= total * 0.12f && ascii >= total * 0.2f && frequent >= total * 0.08f
    }

    /** True when a string looks like undecoded legacy-font rubbish. */
    fun looksGarbled(s: String): Boolean {
        var total = 0
        var tell = 0
        for (ch in s) {
            if (ch.isWhitespace()) continue
            total++
            val c = macRoman[ch.toString()] ?: continue
            if (telltale[c]) tell++
        }
        return total >= 4 && tell >= total * 0.3f
    }
}
