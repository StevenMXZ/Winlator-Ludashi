package com.winlator.cmod.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

// Access to string resources from anywhere in the UI code - composables, click handlers, plain
// helper functions, list builders - through the application context. stringResource() only works
// inside composition, and a lot of the app's text is built outside it (toasts, confirm dialogs,
// option lists), so the screens use this one call everywhere instead of two different ones.
//
// AppCompat locales also apply to text built outside an Activity on Android 12 and earlier.
object UiStrings {
    @Volatile
    private var context: Context? = null
    @Volatile private var observedLocales: LocaleList? = null
    private data class LocalizedContext(
        val systemLocales: LocaleList,
        val context: Context
    )
    @Volatile private var localized: LocalizedContext? = null

    @JvmStatic
    fun install(app: Context) {
        val application = app.applicationContext
        val locales = app.resources.configuration.locales
        if (context !== application || observedLocales != locales) {
            synchronized(this) {
                context = application
                observedLocales = locales
                localized = null
            }
        }
    }

    @JvmStatic
    fun invalidate() {
        localized = null
    }

    @JvmStatic
    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = context ?: error("UiStrings.install() was not called")
        val systemLocales = Resources.getSystem().configuration.locales
        val cached = localized
        val translated = if (cached != null && cached.systemLocales == systemLocales) {
            cached.context
        } else {
            synchronized(this) {
                val current = localized
                if (current != null && current.systemLocales == systemLocales) {
                    current.context
                } else {
                    val appLocales = AppCompatDelegate.getApplicationLocales()
                    val config = Configuration(c.resources.configuration)
                    config.setLocales(if (appLocales.isEmpty) systemLocales else
                        LocaleList.forLanguageTags(appLocales.toLanguageTags()))
                    val updated = c.createConfigurationContext(config)
                    localized = LocalizedContext(systemLocales, updated)
                    updated
                }
            }
        }
        return if (args.isEmpty()) translated.getString(id) else translated.getString(id, *args)
    }
}

fun uiStr(@StringRes id: Int, vararg args: Any?): String = UiStrings.get(id, *args)
