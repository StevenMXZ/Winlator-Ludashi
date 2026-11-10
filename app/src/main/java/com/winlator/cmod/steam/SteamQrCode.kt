package com.winlator.cmod.steam

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun encodeSteamQr(url: String): Bitmap {
    require(url.startsWith("https://s.team/q/")) { "Invalid Steam QR challenge" }
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 4))
    return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        setPixels(pixels, 0, 512, 0, 0, 512, 512)
    }
}

@Composable internal fun SteamQrCode(url: String, qrSize: Dp = 240.dp) {
    val bitmap by produceState<Bitmap?>(null, url) {
        value = null
        if (url.isNotBlank()) value = withContext(Dispatchers.Default) { runCatching { encodeSteamQr(url) }.getOrNull() }
    }
    Box(Modifier.padding(top = 4.dp).size(qrSize).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)).padding(14.dp).background(Color.White),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.steam_login_qr), Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.size(32.dp))
    }
}
