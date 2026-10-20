package com.tammem.pdfarabi

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

/** A file the user opened recently. [key] is a file path or a content:// address. */
data class RecentFile(val key: String, val name: String, val time: Long, val page: Int, val size: Long)

/** A teacher stamp («ممتاز», «العدد: … / 20» …). */
data class Stamp(val text: String, val color: Int, val border: Boolean, val font: String = "ruqaa", val bold: Boolean = true)

/** A saved hand signature: strokes in 0..1 coordinates of a box with the given aspect (width / height). */
data class Signature(val id: Long, val aspect: Float, val strokes: List<FloatArray>, val color: Int)

object Prefs {
    private val sp: SharedPreferences by lazy {
        App.instance.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun float(key: String, def: Float) = sp.getFloat(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def

    var themeMode: Int
        get() = int("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(v) = sp.edit().putInt("theme", v).apply()

    var keepScreenOn: Boolean
        get() = bool("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    var removeOriginalText: Boolean
        get() = bool("remove_original", true)
        set(v) = sp.edit().putBoolean("remove_original", v).apply()

    var nightReading: Boolean
        get() = bool("night_reading", false)
        set(v) = sp.edit().putBoolean("night_reading", v).apply()

    var teacherName: String
        get() = str("teacher_name", "")
        set(v) = sp.edit().putString("teacher_name", v).apply()

    var permissionAsked: Boolean
        get() = bool("perm_asked", false)
        set(v) = sp.edit().putBoolean("perm_asked", v).apply()

    /** Interface language tag ("ar-TN" or "fr"). */
    var uiLang: String
        get() = str("ui_lang", "ar-TN")
        set(v) = sp.edit().putString("ui_lang", v).apply()

    var wordView: String
        get() = str("word_view", "page")
        set(v) = sp.edit().putString("word_view", v).apply()

    var homeTab: Int
        get() = int("home_tab", 0)
        set(v) = sp.edit().putInt("home_tab", v).apply()

    // ---- Last used tool styles ----
    var penColor: Int
        get() = int("pen_color", 0xFFD32F2F.toInt())
        set(v) = sp.edit().putInt("pen_color", v).apply()
    var penWidth: Float
        get() = float("pen_width", 1.6f)
        set(v) = sp.edit().putFloat("pen_width", v).apply()
    var hlColor: Int
        get() = int("hl_color", 0x66FFEB3B)
        set(v) = sp.edit().putInt("hl_color", v).apply()
    var hlWidth: Float
        get() = float("hl_width", 12f)
        set(v) = sp.edit().putFloat("hl_width", v).apply()
    var shapeColor: Int
        get() = int("shape_color", 0xFFD32F2F.toInt())
        set(v) = sp.edit().putInt("shape_color", v).apply()
    var shapeWidth: Float
        get() = float("shape_width", 1.6f)
        set(v) = sp.edit().putFloat("shape_width", v).apply()
    var shapeKind: Int
        get() = int("shape_kind", 0)
        set(v) = sp.edit().putInt("shape_kind", v).apply()
    var markKind: Int
        get() = int("mark_kind", 0)
        set(v) = sp.edit().putInt("mark_kind", v).apply()
    var markColor: Int
        get() = int("mark_color", 0xFFD32F2F.toInt())
        set(v) = sp.edit().putInt("mark_color", v).apply()
    var markSize: Float
        get() = float("mark_size", 22f)
        set(v) = sp.edit().putFloat("mark_size", v).apply()
    var textColor: Int
        get() = int("text_color", 0xFF1A237E.toInt())
        set(v) = sp.edit().putInt("text_color", v).apply()
    var textFont: String
        get() = str("text_font", "amiri")
        set(v) = sp.edit().putString("text_font", v).apply()
    var textSize: Float
        get() = float("text_size", 16f)
        set(v) = sp.edit().putFloat("text_size", v).apply()
    var stampIndex: Int
        get() = int("stamp_index", 0)
        set(v) = sp.edit().putInt("stamp_index", v).apply()
    var signatureId: Long
        get() = sp.getLong("signature_id", 0L)
        set(v) = sp.edit().putLong("signature_id", v).apply()

    // ---- Recent files ----
    fun recents(): List<RecentFile> {
        val arr = runCatching { JSONArray(str("recents", "[]")) }.getOrDefault(JSONArray())
        val out = ArrayList<RecentFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += RecentFile(
                o.optString("key"), o.optString("name"), o.optLong("time"),
                o.optInt("page"), o.optLong("size")
            )
        }
        return out
    }

    private fun saveRecents(list: List<RecentFile>) {
        val arr = JSONArray()
        list.take(60).forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name).put("time", it.time)
                .put("page", it.page).put("size", it.size))
        }
        sp.edit().putString("recents", arr.toString()).apply()
    }

    fun touchRecent(key: String, name: String, size: Long) {
        val list = recents().toMutableList()
        val old = list.firstOrNull { it.key == key }
        list.removeAll { it.key == key }
        list.add(0, RecentFile(key, name, System.currentTimeMillis(), old?.page ?: 0, size))
        saveRecents(list)
    }

    fun setLastPage(key: String, page: Int) {
        val list = recents().map { if (it.key == key) it.copy(page = page) else it }
        saveRecents(list)
    }

    fun lastPage(key: String): Int = recents().firstOrNull { it.key == key }?.page ?: 0

    fun removeRecent(key: String) = saveRecents(recents().filterNot { it.key == key })

    fun renameRecent(oldKey: String, newKey: String, newName: String) =
        saveRecents(recents().map { if (it.key == oldKey) it.copy(key = newKey, name = newName) else it })

    // ---- Favorites ----
    fun favorites(): Set<String> = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun isFavorite(key: String) = favorites().contains(key)

    fun toggleFavorite(key: String): Boolean {
        val s = favorites().toMutableSet()
        val now = if (s.contains(key)) { s.remove(key); false } else { s.add(key); true }
        sp.edit().putStringSet("favorites", s).apply()
        return now
    }

    // ---- Custom stamps ----
    fun customStamps(): List<Stamp> {
        val arr = runCatching { JSONArray(str("stamps", "[]")) }.getOrDefault(JSONArray())
        val out = ArrayList<Stamp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Stamp(o.optString("text"), o.optInt("color"), o.optBoolean("border", true),
                o.optString("font", "ruqaa"), o.optBoolean("bold", true))
        }
        return out
    }

    fun saveCustomStamps(list: List<Stamp>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("text", it.text).put("color", it.color).put("border", it.border)
                .put("font", it.font).put("bold", it.bold))
        }
        sp.edit().putString("stamps", arr.toString()).apply()
    }

    // ---- Signatures ----
    fun signatures(): List<Signature> {
        val arr = runCatching { JSONArray(str("signatures", "[]")) }.getOrDefault(JSONArray())
        val out = ArrayList<Signature>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val strokesArr = o.optJSONArray("strokes") ?: continue
            val strokes = ArrayList<FloatArray>()
            for (s in 0 until strokesArr.length()) {
                val pts = strokesArr.optJSONArray(s) ?: continue
                strokes += FloatArray(pts.length()) { pts.optDouble(it).toFloat() }
            }
            out += Signature(o.optLong("id"), o.optDouble("aspect", 3.0).toFloat(), strokes,
                o.optInt("color", 0xFF0D2A6B.toInt()))
        }
        return out
    }

    fun saveSignatures(list: List<Signature>) {
        val arr = JSONArray()
        list.forEach { sig ->
            val strokes = JSONArray()
            sig.strokes.forEach { pts ->
                val a = JSONArray()
                pts.forEach { a.put(it.toDouble()) }
                strokes.put(a)
            }
            arr.put(JSONObject().put("id", sig.id).put("aspect", sig.aspect.toDouble())
                .put("strokes", strokes).put("color", sig.color))
        }
        sp.edit().putString("signatures", arr.toString()).apply()
    }
}
