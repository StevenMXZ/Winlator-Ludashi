package com.winlator.cmod.steam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Refresh tokens stay in private storage encrypted with an Android Keystore key. */
object SteamCredentials {
    private const val ALIAS = "ludashi-steam"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(context: Context, account: String, token: String, id: Long) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = JSONObject().put("account", account).put("token", token).put("id", id).toString().toByteArray()
        val file = File(context.noBackupFilesDir, "steam-login")
        val temp = File(context.noBackupFilesDir, "steam-login.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal(bytes))
        check(temp.renameTo(file)) { "Could not save Steam login" }
    }
    fun load(context: Context): JSONObject? = runCatching {
        val bytes = File(context.noBackupFilesDir, "steam-login").readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }.getOrNull()
    fun clear(context: Context) { File(context.noBackupFilesDir, "steam-login").delete() }
}
