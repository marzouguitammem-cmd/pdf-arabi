package com.tammem.pdfarabi

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tammem.pdfarabi.io.Storage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.concurrent.thread

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        PDFBoxResourceLoader.init(applicationContext)

        // The whole interface is Arabic and right-to-left, whatever the phone language is.
        val wanted = Prefs.uiLang
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!current.startsWith(wanted.substring(0, 2))) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(wanted))
        }
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode)

        thread(name = "cleanup", isDaemon = true) {
            runCatching { Storage.cleanupWorkFiles(this) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
