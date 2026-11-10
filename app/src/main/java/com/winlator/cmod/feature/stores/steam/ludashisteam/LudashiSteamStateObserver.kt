package com.winlator.cmod.feature.stores.steam.ludashisteam


interface LudashiSteamStateObserver {
    fun onStateChanged(state: Int)
    fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray)
}
