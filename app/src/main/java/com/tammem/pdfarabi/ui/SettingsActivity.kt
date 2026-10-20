package com.tammem.pdfarabi.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.tammem.pdfarabi.BuildConfig
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.io.Storage

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.swRemove).apply {
            isChecked = Prefs.removeOriginalText
            setOnCheckedChangeListener { _, c -> Prefs.removeOriginalText = c }
        }
        findViewById<MaterialSwitch>(R.id.swScreen).apply {
            isChecked = Prefs.keepScreenOn
            setOnCheckedChangeListener { _, c -> Prefs.keepScreenOn = c }
        }
        findViewById<MaterialSwitch>(R.id.swNight).apply {
            isChecked = Prefs.nightReading
            setOnCheckedChangeListener { _, c -> Prefs.nightReading = c }
        }
        findViewById<TextInputEditText>(R.id.teacherName).apply {
            setText(Prefs.teacherName)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.teacherName = s?.toString() ?: "" }
            })
        }
        val lang = findViewById<MaterialButtonToggleGroup>(R.id.langGroup)
        val curLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        lang.check(if (curLang.startsWith("fr")) R.id.langFr else R.id.langAr)
        lang.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val tag = if (id == R.id.langFr) "fr" else "ar-TN"
            val now = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (!now.startsWith(tag.substring(0, 2))) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
        val group = findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        group.check(
            when (Prefs.themeMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.themeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != Prefs.themeMode) {
                Prefs.themeMode = mode
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
        findViewById<TextView>(R.id.saveInfo).text = getString(R.string.set_save_info, Storage.FOLDER)
        findViewById<TextView>(R.id.version).text = getString(R.string.version, getString(R.string.app_name), BuildConfig.VERSION_NAME)
    }
}
