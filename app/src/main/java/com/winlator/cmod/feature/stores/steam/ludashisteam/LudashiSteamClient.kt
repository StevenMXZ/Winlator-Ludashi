// JNI symbols depend on this package path and class name.
package com.winlator.cmod.feature.stores.steam.ludashisteam

import timber.log.Timber

// JVM-side entry point to the native ludashi-steam-client library.
object LudashiSteamClient {

    @Volatile
    private var loaded: Boolean = false

    // Load libludashisteam.so once; failed loads can be retried.
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("ludashisteam")
            loaded = true
            Timber.tag(TAG).i("libludashisteam.so loaded, native version = %s", nativeVersion())
        }
    }

    // Native library semver string.
    fun version(): String {
        ensureLoaded()
        return nativeVersion()
    }

    @JvmStatic
    private external fun nativeVersion(): String

    private const val TAG = "LudashiSteamClient"
}
