package com.winlator.cmod.stores

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object StoreVault {
    private val memory = mutableMapOf<LudashiStore, String>()
    private fun key(store: LudashiStore): SecretKey {
        val alias = "ludashi-${store.id}-account"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(context: Context, store: LudashiStore, account: JSONObject) {
        val text = account.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(store)) }
        val file = File(context.noBackupFilesDir, "${store.id}-account")
        val pending = File(file.parentFile, file.name + ".pending")
        pending.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray()))
        check(pending.renameTo(file)) { "Cannot save store account" }
        memory[store] = text
    }
    @Synchronized fun load(context: Context, store: LudashiStore): JSONObject? {
        memory[store]?.let { return JSONObject(it) }
        return runCatching {
            val bytes = File(context.noBackupFilesDir, "${store.id}-account").readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(store), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            val text = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            JSONObject(text).also { memory[store] = text }
        }.getOrNull()
    }
    @Synchronized fun clear(context: Context, store: LudashiStore) {
        memory.remove(store); File(context.noBackupFilesDir, "${store.id}-account").delete()
    }
}
