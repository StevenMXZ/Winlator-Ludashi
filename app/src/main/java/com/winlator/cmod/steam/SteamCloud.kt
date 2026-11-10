package com.winlator.cmod.steam

import android.content.Context
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.library.GameSavesComposeDialog
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import android.os.FileObserver
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Bridge to the bundled Valve client's AutoCloud, never a second CM connection. */
object SteamCloud {
    internal data class Report(val id: Int, val phase: String, val state: Int, val transfers: Int) {
        val synchronized get() = state == 2
    }
    internal fun parse(text: String): Report? = runCatching {
        val fields = text.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        Report(fields.getValue("appid").toInt().also { require(it > 0) }, fields.getValue("phase").also { require(it in listOf("launch", "exit")) },
            fields.getValue("state").toInt(), fields.getValue("transfers").toInt().also { require(it >= 0) })
    }.getOrNull()
    private fun drive(shortcut: Shortcut) = File(shortcut.container.rootDir, ".wine/drive_c")
    private fun cached(context: Context, id: Int, shortcut: Shortcut): File {
        val user = SteamCredentials.load(context)?.optLong("id") ?: 0L
        return File(context.filesDir, "steam-cloud/$user/${shortcut.container.id}-$id.txt")
    }
    fun begin(context: Context, shortcut: Shortcut) {
        val root = drive(shortcut)
        listOf("ludashi-steam-cloud-status.txt", "ludashi-steam-cloud-conflict.txt", "ludashi-steam-cloud-conflict-answer.txt", "ludashi-steam-shutdown-complete.txt", "ludashi-steam-launcher-ready.txt", "ludashi-launcher.shutdown")
            .forEach { File(root, it).delete() }
        val activity = context as? ComponentActivity ?: return
        val id = shortcut.getExtra("steamAppId").toInt()
        val destination = cached(context, id, shortcut)
        activity.runOnUiThread {
            activity.lifecycleScope.launch {
                val events = Channel<Unit>(Channel.CONFLATED)
                @Suppress("DEPRECATION")
                val watcher = object : FileObserver(root.path, CLOSE_WRITE or MOVED_TO) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == "ludashi-steam-cloud-status.txt" || path == "ludashi-steam-cloud-conflict.txt") events.trySend(Unit)
                    }
                }
                val resumed = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) events.trySend(Unit) }
                watcher.startWatching()
                activity.lifecycle.addObserver(resumed)
                events.trySend(Unit)
                var previous = ""
                var persisted = ""
                var promptOpen = false
                try { for (ignored in events) {
                    val (reportText, conflict) = withContext(Dispatchers.IO) {
                        val text = runCatching { File(root, "ludashi-steam-cloud-status.txt").readText() }.getOrDefault("")
                        if (text != persisted && parse(text)?.id == id) runCatching {
                            destination.parentFile?.mkdirs()
                            destination.writeText(text)
                        }.onFailure { android.util.Log.w("SteamCloud", "Cannot cache sync result", it) }
                        text to runCatching { File(root, "ludashi-steam-cloud-conflict.txt").readLines().contains("appid=$id") }.getOrDefault(false)
                    }
                    persisted = reportText
                    if (!conflict) promptOpen = false
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        if (conflict && !promptOpen) {
                            promptOpen = true
                            panel(activity, "steam-cloud-conflict") { close ->
                                fun answer(choice: String) {
                                    activity.lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) { runCatching {
                                            val temp = File(root, "ludashi-steam-cloud-conflict-answer.tmp")
                                            temp.writeText(choice)
                                            check(temp.renameTo(File(root, "ludashi-steam-cloud-conflict-answer.txt")))
                                        } }
                                        result.onSuccess { close() }.onFailure { android.widget.Toast.makeText(activity, uiStr(R.string.steam_cloud_pending), android.widget.Toast.LENGTH_LONG).show() }
                                    }
                                }
                                AlertDialog(onDismissRequest = { answer("cancel") }, title = { Text(uiStr(R.string.steam_cloud_conflict)) },
                                    text = { Text(uiStr(R.string.steam_cloud_conflict_help)) },
                                    confirmButton = { TextButton(onClick = { answer("cloud") }) { Text(uiStr(R.string.steam_cloud_use_remote)) } },
                                    dismissButton = { TextButton(onClick = { answer("local") }) { Text(uiStr(R.string.steam_cloud_keep_local)) } })
                            }
                        }
                        if (reportText != previous && parse(reportText)?.let { it.id == id && it.phase == "launch" && it.state !in listOf(0, 2, 3, 5, 7) } == true) {
                            panel(activity, "steam-cloud-error") { close ->
                                val leave = { close(); (activity as? com.winlator.cmod.XServerDisplayActivity)?.exitSteamLaunchFailure(); Unit }
                                AlertDialog(onDismissRequest = leave, title = { Text(uiStr(R.string.steam_cloud_saves)) },
                                    text = { Text(uiStr(R.string.steam_cloud_failed)) }, confirmButton = { TextButton(onClick = leave) { Text(uiStr(R.string.ui_back)) } })
                            }
                        }
                        previous = reportText
                    }
                } } finally {
                    watcher.stopWatching()
                    activity.lifecycle.removeObserver(resumed)
                    events.close()
                }
            }
        }
    }
    /** Called on a worker BEFORE Wine/network/audio are torn down. Bound all waits. */
    @JvmStatic fun awaitShutdown(context: Context, shortcut: Shortcut?) {
        if (shortcut == null || shortcut.getExtra("steamAppId").isBlank() || shortcut.getExtra("steamLaunchMode") != "RealSteam") return
        val root = drive(shortcut)
        if (!File(root, "ludashi-steam-launcher-ready.txt").isFile) return
        runCatching {
            File(root, "ludashi-launcher.shutdown").writeText("shutdown")
            val deadline = android.os.SystemClock.elapsedRealtime() + 75000
            while (!File(root, "ludashi-steam-shutdown-complete.txt").exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            val status = File(root, "ludashi-steam-cloud-status.txt")
            val id = shortcut.getExtra("steamAppId").toInt()
            if (status.isFile && parse(status.readText())?.id == id) {
                val target = cached(context, id, shortcut)
                target.parentFile?.mkdirs()
                if (File(root, "ludashi-steam-shutdown-complete.txt").isFile) status.copyTo(target, true)
                else target.writeText("appid=$id\nphase=exit\nstate=-1\ntransfers=0\n")
            }
        }.onFailure { android.util.Log.w("SteamCloud", "Graceful shutdown did not complete", it) }
    }
    fun showStatus(fragment: Fragment, id: Int, shortcut: Shortcut) {
        panel(fragment.requireActivity(), "steam-cloud-status") { close ->
            SteamNativeCloudPanel(id, shortcut.container, close, onBackup = {
                close()
                GameSavesComposeDialog.show(fragment, shortcut)
            })
        }
    }
    fun showNative(fragment: Fragment, id: Int, shortcut: Shortcut) {
        panel(fragment.requireActivity(), "steam-native-cloud") { close -> SteamNativeCloudPanel(id, shortcut.container, close) }
    }
    private fun panel(activity: ComponentActivity, tag: String, content: @Composable (() -> Unit) -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<android.view.View>(tag) != null) return
        val view = ComposeView(activity).apply { this.tag = tag; setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
        val close = { view.disposeComposition(); root.removeView(view) }
        view.setContent { WinZTheme { content(close) } }
        root.addView(view)
    }
}
