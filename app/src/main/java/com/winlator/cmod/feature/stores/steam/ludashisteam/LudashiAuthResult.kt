package com.winlator.cmod.feature.stores.steam.ludashisteam


data class LudashiAuthResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String,
    val accountName: String,
    val refreshToken: String,
    val accessToken: String,
    val newGuardData: String,
    val steamId: Long,
    val hadRemoteInteraction: Boolean,
    val agreementSessionUrl: String,
)
