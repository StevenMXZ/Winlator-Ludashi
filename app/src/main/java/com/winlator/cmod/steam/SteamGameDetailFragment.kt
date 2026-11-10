package com.winlator.cmod.steam

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.winlator.cmod.MainActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.library.*
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog
import java.io.File

class SteamGameDetailFragment : Fragment() {
    companion object {
        fun newInstance(appId: Int) = SteamGameDetailFragment().apply { arguments = Bundle().apply { putInt("appId", appId) } }
    }
    private var configuring = false
    private var preparing = false
    private val appId get() = requireArguments().getInt("appId")
    private fun notice(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val title = SteamGames.info(context, appId).optJSONObject("common")?.optString("name").orEmpty().ifBlank { "Steam $appId" }
        SteamLibrary.requestArtwork(context, "steam://$appId", "banner")
        return GameDetailComposeHost.createSteam(context, title, File(context.cacheDir, "steam-art/$appId-banner.jpg").path, appId, object : SteamDetailCallbacks {
            override fun onPlay() {
                val shortcut = SteamGames.shortcut(context, appId)
                if (shortcut == null || !SteamGames.configured(context, appId)) { setupPlay(true); return }
                if (!SteamSettings.enabled(context) || (SteamSettings.mode(context, appId) == "RealSteam" && SteamCredentials.load(context) == null)) { notice(uiStr(R.string.steam_ui_enable_signin)); return }
                if (SteamNativeCloud.busy) { notice(uiStr(R.string.steam_native_cloud_busy)); return }
                if (preparing || SteamSession.gameActive) return
                preparing = true
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        SteamLaunch.prepareConfigured(context, shortcut, SteamSettings.mode(context, appId))
                    } }
                    preparing = false
                    if (isAdded) result.onSuccess { prepared ->
                        startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", prepared.container.id)
                            .putExtra("shortcut_path", prepared.file.path).putExtra("shortcut_name", title))
                    }.onFailure { notice(it.message.orEmpty()) }
                }.invokeOnCompletion { preparing = false }
            }
            override fun onSteamExecutable() { setupPlay(false) }
            override fun onSteamCloud() {
                viewLifecycleOwner.lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: SteamGames.draft(context, appId, ContainerManager(context).containers.firstOrNull() ?: error(uiStr(R.string.steam_ui_create_container)))
                    } }
                    found.onSuccess { SteamCloud.showNative(this@SteamGameDetailFragment, appId, it) }.onFailure { notice(it.message.orEmpty()) }
                }
            }
            override fun onSteamMode(mode: String) {
                if (preparing || SteamSession.gameActive) return
                SteamSettings.setMode(context, appId, mode)
                SteamGames.changed()
            }
            override fun onConfigure() {
                if (SteamSession.gameActive) { notice(uiStr(R.string.steam_ui_exit_configure)); return }
                if (configuring) return
                configuring = true
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: run {
                            val containers = ContainerManager(context).containers
                            check(containers.isNotEmpty()) { uiStr(R.string.steam_ui_create_container) }
                            val preferred = context.getSharedPreferences("steam-settings", 0).getInt("steam-container-$appId", -1)
                            val container = containers.firstOrNull { it.id == preferred } ?: containers.first()
                            SteamGames.draft(context, appId, container)
                        }
                    } }
                    if (isAdded) result.onSuccess { ShortcutSettingsComposeDialog.show(this@SteamGameDetailFragment, it) }
                        .onFailure { notice(it.message.orEmpty()) }
                }.invokeOnCompletion { configuring = false }
            }
            override fun onArguments() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice(uiStr(R.string.steam_ui_choose_container))
                startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id))
            }
            override fun onSaves() {
                viewLifecycleOwner.lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: SteamGames.draft(context, appId, ContainerManager(context).containers.firstOrNull() ?: error(uiStr(R.string.steam_ui_create_container)))
                    } }
                    found.onSuccess { shortcut ->
                        if (SteamSettings.mode(context, appId) == "RealSteam") SteamCloud.showStatus(this@SteamGameDetailFragment, appId, shortcut)
                        else GameSavesComposeDialog.show(this@SteamGameDetailFragment, shortcut)
                    }.onFailure { notice(it.message.orEmpty()) }
                }
            }
            override fun onFavorite(favorite: Boolean) {
                context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$appId", favorite).apply(); SteamGames.changed()
            }
            override fun onHome() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice(uiStr(R.string.steam_ui_configure_game_first))
                if (SteamGames.configured(context, appId)) HomeShortcuts.pin(requireActivity(), shortcut) else notice(uiStr(R.string.steam_ui_configure_game_first))
            }
            override fun onRemove() {
                android.app.AlertDialog.Builder(context).setTitle(uiStr(R.string.steam_ui_uninstall_title))
                    .setMessage(uiStr(R.string.steam_ui_uninstall_confirm, title))
                    .setNegativeButton(uiStr(R.string.steam_ui_cancel), null).setPositiveButton(uiStr(R.string.steam_ui_uninstall)) { _, _ ->
                        SteamSession.work {
                            runCatching { SteamGames.uninstall(context, appId) }.onSuccess {
                                activity?.runOnUiThread { SteamGames.changed(); if (isAdded) { if (activity is MainActivity) parentFragmentManager.popBackStack() else activity?.finish() } }
                            }.onFailure { failure -> activity?.runOnUiThread { if (isAdded) notice(failure.message.orEmpty()) } }
                        }
                    }.show()
            }
        })
    }
    private fun setupPlay(play: Boolean) {
        if (SteamSession.gameActive || preparing) return
        val root = requireActivity().findViewById<android.view.ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>("steam-play-setup") != null) return
        val view = androidx.compose.ui.platform.ComposeView(requireContext()).apply { tag = "steam-play-setup" }
        val dismiss = { view.disposeComposition(); root.removeView(view) }
        view.setContent { com.winlator.cmod.ui.theme.WinZTheme {
            SteamPlaySetup(appId, play, done = { shortcut ->
                dismiss()
                if (play) startActivity(Intent(requireContext(), XServerDisplayActivity::class.java)
                    .putExtra("container_id", shortcut.container.id).putExtra("shortcut_path", shortcut.file.path)
                    .putExtra("shortcut_name", SteamGames.displayName(shortcut)))
            }, dismiss = dismiss)
        } }
        root.addView(view)
    }
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.apply { setDetailMode(true); setBottomNavigationVisible(false); setMainToolbarVisible(false) }
    }
}
