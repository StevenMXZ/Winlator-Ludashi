package com.winlator.cmod.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.winlator.cmod.components.ComponentDownloads
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in checks. Patches save bandwidth; Android still installs a normally signed APK. */
object UpstreamUpdates {
    const val MANIFEST = "https://raw.githubusercontent.com/Other-backup/winlator-imagefs-v2/upstream-updates/update.json"

    @JvmStatic fun show(activity: Activity) {
        val dialog = ComponentDialog(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(ComposeView(activity).apply { setContent { WinZTheme { UpdatePage(activity, dialog::dismiss) } } })
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.toSet()

    @Suppress("DEPRECATION")
    internal fun validateApk(activity: Activity, apk: File, manifest: JSONObject) {
        check(apk.length() == manifest.getLong("size")) { "APK size does not match release" }
        check(ApkPatcher.sha256(apk).equals(manifest.getString("sha256"), true)) { "APK checksum does not match release" }
        val pm = activity.packageManager
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val next = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        check(next.packageName == activity.packageName) { "This APK is for a different app variant" }
        check(next.longVersionCode == manifest.getLong("versionCode") && next.longVersionCode > installed.longVersionCode) { "APK version does not match this update" }
        check(signers(installed).isNotEmpty() && signers(next) == signers(installed)) {
            "This APK uses a different signing key. It cannot update this installation. Export your containers before changing installation."
        }
    }

    private fun install(activity: Activity, apk: File) {
        check(!ContainerSessionGate.anyBusy()) { "Close Wine sessions and component operations before installing the update" }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            error("Allow app installation, then tap Update again")
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".tileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Composable private fun UpdatePage(activity: Activity, close: () -> Unit) {
        val scope = rememberCoroutineScope()
        var info by remember { mutableStateOf<JSONObject?>(null) }
        var status by remember { mutableStateOf("Check for an upstream update.") }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        val cancelled = remember { AtomicBoolean(false) }
        DisposableEffect(Unit) { onDispose { cancelled.set(true) } }
        fun checkUpdate() {
            busy = true; info = null; status = "Checking…"
            scope.launch {
                try {
                    val next = withContext(Dispatchers.IO) {
                        JSONObject(ComponentDownloads.downloadString(MANIFEST) ?: error("Could not check for updates"))
                    }
                    val current = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                    check(next.getString("packageName") == activity.packageName) { "No update channel for this app variant" }
                    if (next.getLong("versionCode") > current) {
                        check(next.optString("apkUrl").startsWith("https://")) { "This release is not available yet" }
                        info = next; status = "${next.getString("versionName")} is available.\n${next.optString("notes")}"
                    } else status = "You are up to date."
                } catch (e: Exception) { status = e.message ?: "Update check failed" }
                finally { busy = false }
            }
        }
        fun update(next: JSONObject) {
            if (ContainerSessionGate.anyBusy()) { status = "Close Wine sessions and component operations before updating."; return }
            busy = true; cancelled.set(false); progress = 0f
            scope.launch {
                try {
                    val apk = withContext(Dispatchers.IO) {
                        val directory = File(activity.externalCacheDir ?: activity.cacheDir, "upstream-update").apply { mkdirs() }
                        val out = File(directory, "update.apk")
                        out.delete()
                        fun report(value: Float) { scope.launch { progress = value.coerceIn(0f, 1f) } }
                        val old = File(activity.applicationInfo.sourceDir)
                        val patchInfo = next.optJSONObject("patches")?.optJSONObject(ApkPatcher.sha256(old))
                        var patched = false
                        if (patchInfo != null && patchInfo.optLong("size") in 1 until next.getLong("size")) {
                            scope.launch { status = "Downloading small update…" }
                            val patch = File(directory, "update.bldp")
                            try {
                                if (ComponentDownloads.downloadFile(patchInfo.getString("url"), patch, ::report) &&
                                    ApkPatcher.sha256(patch).equals(patchInfo.getString("sha256"), true)) {
                                    scope.launch { status = "Rebuilding update…" }
                                    patched = ApkPatcher.apply(old, patch, out, cancelled, onProgress = { n, total -> report(n.toFloat() / total) })
                                }
                            } catch (e: Exception) { android.util.Log.w("UpstreamUpdates", "Small update failed; using full APK", e) }
                            finally { patch.delete() }
                        }
                        check(!cancelled.get()) { "Cancelled" }
                        if (!patched) {
                            out.delete(); scope.launch { status = "Downloading full update…" }
                            check(ComponentDownloads.downloadFile(next.getString("apkUrl"), out, ::report)) { "APK download failed" }
                        }
                        check(!cancelled.get()) { "Cancelled" }
                        validateApk(activity, out, next)
                        out
                    }
                    install(activity, apk)
                    status = "Confirm the Android update. Containers and settings are kept."
                } catch (e: Exception) { status = e.message ?: "Update failed" }
                finally { busy = false }
            }
        }
        androidx.activity.compose.BackHandler { if (!busy) close() }
        Surface {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("App Updates", style = MaterialTheme.typography.titleLarge)
                Text(status)
                if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                info?.let { next -> Button(onClick = { update(next) }, enabled = !busy) { Text("Update") } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::checkUpdate, enabled = !busy) { Text("Check for Updates") }
                    TextButton(onClick = close, enabled = !busy) { Text("Close") }
                }
            }
        }
    }
}
