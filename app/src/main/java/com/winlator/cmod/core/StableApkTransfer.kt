package com.winlator.cmod.core

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stages stable APKs in private cache, without touching a running container. */
internal object StableApkTransfer {
    private const val MAX = 2L * 1024 * 1024 * 1024
    private const val RESERVE = 64L * 1024 * 1024
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()

    fun download(context: Context, update: UpstreamUpdates.AvailableUpdate, cancelled: AtomicBoolean,
                 progress: (Long, Long) -> Unit, status: (String) -> Unit): File {
        val manifest = checkNotNull(update.manifest)
        val size = manifest.getLong("size")
        val digest = manifest.getString("sha256")
        check(size in 1..MAX && digestPattern.matches(digest))
        check(manifest.getString("packageName") == context.packageName)
        val directory = File(context.cacheDir, "upstream-update").apply { mkdirs() }
        val parts = File(directory, "stable-parts").apply { mkdirs() }
        val work = File(parts, digest.lowercase()).apply { mkdirs() }
        parts.listFiles()?.filter { it != work }?.forEach { it.deleteRecursively() }
        val apk = File(directory, "stable-update.apk")
        apk.delete()
        var success = false
        fun active() = check(!cancelled.get()) { "Cancelled" }
        fun transfer(url: String, expected: Long, hash: String, label: String): File {
            check(url.startsWith("https://") && expected in 1..MAX && digestPattern.matches(hash))
            val staging = File(work, label + "-" + hash.lowercase()).apply { mkdirs() }
            val file = File(staging, "download")
            check(directory.usableSpace + (staging.listFiles()?.sumOf { it.length() } ?: 0L) >=
                expected * 2 + RESERVE) { "Not enough free space for the update" }
            ActionsArtifactTransfer.download(client, url, expected, staging, file, cancelled, progress)
            active()
            if (!ApkPatcher.sha256(file).equals(hash, true)) {
                staging.deleteRecursively()
                error("Update checksum mismatch")
            }
            return file
        }
        try {
            active()
            val old = File(context.applicationInfo.sourceDir)
            val patchInfo = update.patch
            if (patchInfo != null && patchInfo.optLong("size") in 1 until size) {
                try {
                    status("Downloading update…")
                    val patch = transfer(patchInfo.getString("url"), patchInfo.getLong("size"),
                        patchInfo.getString("sha256"), "patch")
                    check(directory.usableSpace >= size + RESERVE) { "Not enough free space to rebuild APK" }
                    status("Rebuilding APK…")
                    val rebuilt = if (ContentApkDelta.matches(patch))
                        ContentApkDelta.apply(old, patch, apk, cancelled) { _, _ -> active() }
                    else ApkPatcher.apply(old, patch, apk, cancelled, onProgress = { _, _ -> active() })
                    check(rebuilt)
                    status("Verifying APK…")
                    UpstreamUpdates.validateApk(context, apk, manifest)
                    active()
                    success = true
                    return apk
                } catch (e: Exception) {
                    active()
                    android.util.Log.w("StableApkTransfer", "Patch failed; using full APK", e)
                    apk.delete()
                    work.listFiles()?.filter { it.name.startsWith("patch-") }?.forEach { it.deleteRecursively() }
                }
            }
            status("Downloading full update…")
            val file = transfer(manifest.getString("apkUrl"), size, digest, "full")
            check(file.renameTo(apk)) { "Could not stage APK" }
            status("Verifying APK…")
            UpstreamUpdates.validateApk(context, apk, manifest)
            active()
            success = true
            return apk
        } finally {
            if (success) work.deleteRecursively() else apk.delete()
        }
    }
}
