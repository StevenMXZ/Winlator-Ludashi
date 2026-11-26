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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.ui.text.style.TextDecoration
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Opt-in checks. Patches save bandwidth; Android still installs a normally signed APK. */
object UpstreamUpdates {
    const val MANIFEST = "https://raw.githubusercontent.com/Other-backup/winlator-imagefs-v2/upstream-updates/update.json"

    @JvmStatic fun show(activity: Activity) {
        show(activity, onDismiss = {})
    }

    internal fun sizeText(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1e6)

    internal data class AvailableUpdate(val development: Boolean, val artifact: ActionsUpdates.Artifact? = null,
                                        val manifest: JSONObject? = null, val patch: JSONObject? = null) {
        val title: String get() = artifact?.title?.ifBlank { "Development build #" + artifact.build.number }
            ?: "Version " + manifest?.optString("versionName").orEmpty()
        val description: String get() = artifact?.let {
            "Development · Build #" + it.build.number + " · " + it.build.sha.take(7)
        } ?: "Stable"
        val fullSize: Long get() = artifact?.size ?: manifest?.optLong("size") ?: 0L
        val downloadSize: Long get() = artifact?.downloadSize ?: patch?.optLong("size") ?: fullSize
        val key: String get() = artifact?.let { "dev:" + it.build.branch + ":" + it.build.number + ":" + it.build.attempt + ":" + it.build.sha }
            ?: "stable:" + manifest?.optLong("versionCode") + ":" + manifest?.optString("sha256")
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
                    AvailableUpdate(false, manifest = next, patch = next.optJSONObject("patches")
                        ?.optJSONObject(ApkPatcher.sha256(File(activity.applicationInfo.sourceDir)))
                        ?.takeIf { it.optLong("size") in 1 until next.optLong("size") &&
                            it.optString("url").startsWith("https://") &&
                            it.optString("sha256").matches(Regex("[0-9a-fA-F]{64}")) }) else null
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
    internal fun validateApk(activity: android.content.Context, apk: File, manifest: JSONObject) {
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
        check(!ContainerSessionGate.anyBusy() && !ComponentDownloads.busy) { "Close Wine sessions and component operations before installing the update" }
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
        val installedBuild = remember { ActionsUpdates.installedBuild(activity) }
        val installedCode = remember { activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode }
        val serviceState by ActionsUpdateService.state.collectAsState()
        val transfer = serviceState.takeIf { state ->
            state.update?.artifact?.build?.let { next -> installedBuild?.let { next.newerThan(it) } ?: true } == true ||
                (state.update?.manifest?.optLong("versionCode") ?: 0L) > installedCode
        }
        var development by remember {
            mutableStateOf(transfer?.update?.development ?: initial?.development ?:
                (forceDevelopment || preferences.getBoolean("app_update_development", false)))
        }
        var found by remember { mutableStateOf(initial) }
        var checked by remember { mutableStateOf(initial != null) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val active = serviceState.active
        val next = if (active) transfer?.update else found ?: transfer?.update?.takeIf { !checked }
        val shown = next?.takeIf { it.development == development }
        val matching = transfer?.takeIf { shown != null && it.update?.key == shown.key }
        val ready = matching?.ready?.takeIf { it.isFile }
        val locked = busy || active
        fun checkUpdate() {
            if (locked) return
            busy = true; checked = true; found = null; status = "Checking…"
            scope.launch {
                try {
                    found = available(activity, development)
                    status = if (found == null) "You are up to date." else ""
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { status = e.message ?: "Update check failed" }
                finally { busy = false }
            }
        }
        fun selectChannel(dev: Boolean) {
            development = dev
            preferences.edit().putBoolean("app_update_development", dev).apply()
            found = null; checked = false; status = ""
        }
        androidx.activity.compose.BackHandler { if (!busy) close() }
        val version = remember { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty() }
        Column(Modifier.padding(20.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(uiStr(R.string.app_info_updates), style = MaterialTheme.typography.titleLarge)
                if (!active) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(uiStr(R.string.app_info_installed), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(version, style = MaterialTheme.typography.headlineMedium)
                        installedBuild?.let {
                            Text("Build #" + it.number + " · " + it.sha.take(7),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !development, onClick = { selectChannel(false) },
                            modifier = Modifier.weight(0.85f), shape = SegmentedButtonDefaults.itemShape(0, 2),
                            enabled = !locked, icon = {}) { Text(uiStr(R.string.app_info_stable)) }
                        SegmentedButton(selected = development, onClick = { selectChannel(true) },
                            modifier = Modifier.weight(1.15f), shape = SegmentedButtonDefaults.itemShape(1, 2),
                            enabled = !locked, icon = {}) { Text(uiStr(R.string.app_info_development)) }
                    }
                }
                if (shown != null) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(if (ready != null) "Ready to install" else if (active) "Downloading update" else "Update available",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Text(shown.title, style = MaterialTheme.typography.bodyLarge)
                            Text(shown.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!active && ready == null && shown.downloadSize > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(sizeText(shown.downloadSize), style = MaterialTheme.typography.headlineSmall)
                                    if (shown.downloadSize < shown.fullSize) Text(sizeText(shown.fullSize),
                                        textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            shown.manifest?.optString("notes")?.takeIf { it.isNotBlank() && !active }?.let {
                                Text(it.take(1200), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (active && matching != null) {
                                Text(matching.message, style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text((matching.fraction * 100).toInt().toString() + "%", style = MaterialTheme.typography.headlineMedium)
                                    if (matching.speed > 0) Text(sizeText(matching.speed) + "/s", style = MaterialTheme.typography.bodySmall)
                                }
                                if (matching.total <= 0 || matching.done >= matching.total)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { matching.fraction }, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(sizeText(matching.done) + " / " + sizeText(matching.total), style = MaterialTheme.typography.bodySmall)
                                    if (matching.speed > 0 && matching.total > matching.done) {
                                        val seconds = (matching.total - matching.done) / matching.speed
                                        Text(if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s left" else "${seconds}s left",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            } else if (matching != null && matching.message.isNotBlank()) {
                                Text(matching.message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            if (active) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ActionsUpdateService.cancel() }, modifier = Modifier.weight(1f)) { Text(uiStr(R.string.cancel)) }
                    Button(onClick = close, modifier = Modifier.weight(1f)) { Text("Minimize") }
                }
                Text("Download continues in the background.", modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (ready != null) {
                    Button(onClick = {
                        try {
                            install(activity, ready)
                            status = "Confirm the Android update. Containers and settings are kept."
                        } catch (e: Exception) { status = e.message ?: "Could not install update" }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Install update") }
                } else if (shown != null) {
                    Button(onClick = { status = ""; ActionsUpdateService.begin(activity, shown) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Download update") }
                } else {
                    Button(onClick = ::checkUpdate, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.app_info_check_updates)) }
                }
                if (shown != null && !busy) TextButton(onClick = ::checkUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text(uiStr(R.string.app_info_check_updates))
                }
                TextButton(onClick = close, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.app_info_close)) }
            }
        }
    }
}
