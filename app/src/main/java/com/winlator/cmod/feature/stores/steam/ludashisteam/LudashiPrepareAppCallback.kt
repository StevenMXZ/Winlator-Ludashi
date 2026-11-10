package com.winlator.cmod.feature.stores.steam.ludashisteam


fun interface LudashiPrepareAppCallback {
    fun onPrepareResult(ok: Boolean, error: String)
}
