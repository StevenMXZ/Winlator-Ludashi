package com.winlator.cmod.ui.toast

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Remembers which Activity is currently in the foreground so that [WinToast] can still show its
 * Compose toast when it is called with a non-Activity Context (BroadcastReceiver, background
 * helpers, application context). Installed once from MainActivity.
 */
object ForegroundActivityTracker {
    private var currentRef: WeakReference<Activity>? = null
    private var installed = false

    val current: Activity?
        get() = currentRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    @JvmStatic
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentRef?.get() === activity) currentRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentRef?.get() === activity) currentRef = null
            }
        })
    }
}
