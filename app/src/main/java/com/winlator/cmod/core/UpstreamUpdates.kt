package com.winlator.cmod.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.winlator.cmod.components.ComponentDownloads
import com.winlator.cmod.ui.AppInfoDialog
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
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
        show(activity, onDismiss = {})
    }

    internal data class AvailableUpdate(val development: Boolean, val artifact: ActionsUpdates.Artifact? = null,
                                        val manifest: JSONObject? = null) {
        val title: String get() = artifact?.title?.ifBlank { "Development build #" + artifact.build.number }
            ?: "Version " + manifest?.optString("versionName").orEmpty()
        val description: String get() = artifact?.let {
            "Development · Build #" + it.build.number + " · " + it.build.sha.take(7) +
                " · " + (it.downloadSize / 1_000_000) + " MB"
        } ?: listOf("Stable", manifest?.optString("notes").orEmpty().take(600)).filter { it.isNotBlank() }.joinToString("\n")
    }

    internal suspend fun available(activity: Activity, development: Boolean): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            if (development) {
                val installed = ActionsUpdates.installedBuild(activity)
                // A CI build can update in place; non-CI builds need a higher versionCode.
                val next = ActionsUpdates.latest(installed, installed?.let { ApkPatcher.sha256(File(activity.applicationInfo.sourceDir)) })
                next?.let { AvailableUpdate(true, artifact = it) }
            } else {
                val next = JSONObject(ComponentDownloads.downloadString(MANIFEST) ?: error("Could not check for updates"))
                val current = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                check(next.getString("packageName") == activity.packageName)
                if (next.getLong("versionCode") > current && next.optString("apkUrl").startsWith("https://"))
                    AvailableUpdate(false, manifest = next) else null
            }
        }

    internal fun show(activity: Activity, onDismiss: () -> Unit, forceDevelopment: Boolean = false,
                      initial: AvailableUpdate? = null) {
        AppInfoDialog.show(activity, onDismiss) { close -> UpdatePage(activity, close, forceDevelopment, initial) }
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

    @Composable private fun UpdatePage(activity: Activity, close: () -> Unit, forceDevelopment: Boolean, initial: AvailableUpdate?) {
        val scope = rememberCoroutineScope()
        val preferences = remember { PreferenceManager.getDefaultSharedPreferences(activity) }
        var development by remember { mutableStateOf(initial?.development ?: (forceDevelopment || preferences.getBoolean("app_update_development", false) || ActionsUpdateService.state.value.active)) }
        val installedBuild = remember { ActionsUpdates.installedBuild(activity) }
        val serviceState by ActionsUpdateService.state.collectAsState()
        val transfer = serviceState.takeIf { it.artifact?.build?.let { next -> installedBuild?.let { next.newerThan(it) } ?: true } == true }
        val transferActive = transfer?.active == true
        var artifact by remember { mutableStateOf<ActionsUpdates.Artifact?>(initial?.artifact) }
        val selectedArtifact = if (transferActive) transfer?.artifact else artifact ?: transfer?.artifact
        val ready = transfer?.ready?.takeIf { it.isFile && transfer.artifact == selectedArtifact }
        var info by remember { mutableStateOf<JSONObject?>(initial?.manifest) }
        var status by remember { mutableStateOf(initial?.manifest?.let { "${it.optString("versionName")} is available.\n${it.optString("notes")}" }.orEmpty()) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        val cancelled = remember { AtomicBoolean(false) }
        DisposableEffect(Unit) { onDispose { cancelled.set(true) } }
        fun checkUpdate() {
            if (transferActive) return
            busy = true; info = null; artifact = null; progress = 0f; status = "Checking…"
            scope.launch {
                try {
                    if (development) {
                        val next = withContext(Dispatchers.IO) { ActionsUpdates.latest(installedBuild, installedBuild?.let { ApkPatcher.sha256(File(activity.applicationInfo.sourceDir)) }) }
                        artifact = next
                        status = if (next == null) "No newer development build is available." else
                            ""
                        return@launch
                    }
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
                                    patched = if (ContentApkDelta.matches(patch)) ContentApkDelta.apply(old, patch, out, cancelled) { n, total -> report(n.toFloat() / total) }
                                    else ApkPatcher.apply(old, patch, out, cancelled, onProgress = { n, total -> report(n.toFloat() / total) })
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
        fun updateDevelopment(next: ActionsUpdates.Artifact) {
            status = ""
            ActionsUpdateService.begin(activity, next)
        }
        fun installReady(apk: File) {
            try {
                install(activity, apk)
                status = "Confirm the Android update. Containers and settings are kept."
            } catch (e: Exception) { status = e.message ?: "Could not install update" }
        }
        fun selectChannel(dev: Boolean) {
            development = dev
            preferences.edit().putBoolean("app_update_development", dev).apply()
            info = null; artifact = null; status = ""
        }
        androidx.activity.compose.BackHandler { if (!busy) close() }
        val version = remember { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty() }
        val downloading = busy && info != null
        val locked = busy || transferActive
        val devStatus = if (development && transfer?.artifact == selectedArtifact) transfer?.message.orEmpty() else ""
        Column(Modifier.padding(20.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(uiStr(R.string.app_info_updates), style = MaterialTheme.typography.titleLarge)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(uiStr(R.string.app_info_installed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(version, style = MaterialTheme.typography.headlineMedium)
                    installedBuild?.let {
                        Text("Build #" + it.number + " · " + it.sha.take(7),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiStr(R.string.app_info_channel), style = MaterialTheme.typography.titleSmall)
                    // Unequal weights and no automatic leading check icon leave room for Development.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !development, onClick = { selectChannel(false) },
                            modifier = Modifier.weight(0.85f),
                            shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = !locked,
                            icon = {}) {
                            Text(uiStr(R.string.app_info_stable), style = MaterialTheme.typography.labelMedium)
                        }
                        SegmentedButton(selected = development, onClick = { selectChannel(true) },
                            modifier = Modifier.weight(1.15f),
                            shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = !locked,
                            icon = {}) {
                            Text(uiStr(R.string.app_info_development), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(if (development) uiStr(R.string.app_info_dev_description) +
                        "\n" + ActionsUpdates.sourceBranch(installedBuild) else uiStr(R.string.app_info_stable_description),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (development && selectedArtifact != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Update available", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selectedArtifact.title.ifBlank { "Development build #" + selectedArtifact.build.number },
                            style = MaterialTheme.typography.titleMedium)
                        Text("Build #" + selectedArtifact.build.number + " · " + selectedArtifact.build.sha.take(7) +
                            " · " + (selectedArtifact.downloadSize / 1_000_000) + " MB" + if (selectedArtifact.delta != null) " · Incremental" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (status.isNotBlank() || busy || (development && devStatus.isNotBlank())) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
                            if (development && devStatus.isNotBlank())
                                Text(devStatus, style = MaterialTheme.typography.bodyMedium)
                            if (development && transferActive && transfer != null) {
                                if (transfer.done >= transfer.total) LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                                Text(transfer.timing + " · " + (transfer.fraction * 100).toInt() + "%",
                                    style = MaterialTheme.typography.bodySmall)
                            } else if (downloading) {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Text((progress * 100).toInt().toString() + "%", modifier = Modifier.align(Alignment.End),
                                    style = MaterialTheme.typography.labelSmall)
                            } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (development && ready != null && !locked) {
                Button(onClick = { installReady(ready) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install update")
                }
            } else if (development && selectedArtifact != null && !locked) {
                Button(onClick = { updateDevelopment(selectedArtifact) }, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.app_info_download_install))
                }
            } else if (info != null && !locked) {
                Button(onClick = { info?.let { update(it) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.app_info_download_install))
                }
            } else if (!transferActive) {
                Button(onClick = ::checkUpdate, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.app_info_check_updates))
                }
            }
            if (transferActive) {
                TextButton(onClick = { ActionsUpdateService.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.cancel))
                }
            }
            TextButton(onClick = close, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(uiStr(R.string.app_info_close))
            }
            if (!locked && ((development && selectedArtifact != null) || info != null)) {
                TextButton(onClick = ::checkUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.app_info_check_updates))
                }
            }
        }
    }
}
