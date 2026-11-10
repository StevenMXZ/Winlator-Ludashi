package com.winlator.cmod.ui

import android.content.Context
import androidx.annotation.StringRes

// Access to string resources from anywhere in the UI code - composables, click handlers, plain
// helper functions, list builders - through the application context. stringResource() only works
// inside composition, and a lot of the app's text is built outside it (toasts, confirm dialogs,
// option lists), so the screens use this one call everywhere instead of two different ones.
//
// Installed by WinlatorApp.onCreate, i.e. before any activity or view exists. The app is
// restarted by the system on a locale change (no locale in the activities' configChanges), so the
// application context always resolves the current language.
object UiStrings {
    @Volatile
    private var context: Context? = null

    @JvmStatic
    fun install(app: Context) {
        context = app.applicationContext
    }

    @JvmStatic
    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = context ?: error("UiStrings.install() was not called")
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}

fun uiStr(@StringRes id: Int, vararg args: Any?): String = UiStrings.get(id, *args)
