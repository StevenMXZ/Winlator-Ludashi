package com.winlator.cmod.steam

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
    private val appId get() = requireArguments().getInt("appId")
    private fun notice(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val title = SteamGames.info(context, appId).optJSONObject("common")?.optString("name").orEmpty().ifBlank { "Steam $appId" }
        SteamLibrary.requestArtwork(context, "steam://$appId", "banner")
        return GameDetailComposeHost.createSteam(context, title, File(context.cacheDir, "steam-art/$appId-banner.jpg").path, appId, object : GameDetailCallbacks {
            override fun onPlay() {
                val shortcut = SteamGames.shortcut(context, appId)
                if (shortcut == null || !SteamGames.configured(context, appId)) { notice("Configure the launch method and executable first"); return }
                if (!SteamLaunch.canLaunch(context, shortcut)) { notice("Enable Steam and sign in before playing"); return }
                startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id)
                    .putExtra("shortcut_path", shortcut.file.path).putExtra("shortcut_name", title))
            }
            override fun onConfigure() {
                if (SteamSession.gameActive) { notice("Exit the game before configuring"); return }
                if (configuring) return
                configuring = true
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        SteamGames.shortcut(context, appId) ?: run {
                            val containers = ContainerManager(context).containers
                            check(containers.isNotEmpty()) { "Create a container first" }
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
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice("Choose a container in Configure first")
                startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id))
            }
            override fun onSaves() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice("Configure the game first")
                GameSavesComposeDialog.show(this@SteamGameDetailFragment, shortcut)
            }
            override fun onFavorite(favorite: Boolean) {
                context.getSharedPreferences("steam-settings", 0).edit().putBoolean("favorite-$appId", favorite).apply(); SteamGames.changed()
            }
            override fun onHome() {
                val shortcut = SteamGames.shortcut(context, appId) ?: return notice("Configure the game first")
                if (SteamGames.configured(context, appId)) HomeShortcuts.pin(requireActivity(), shortcut) else notice("Configure the game first")
            }
            override fun onRemove() {
                android.app.AlertDialog.Builder(context).setTitle("Uninstall Steam game?")
                    .setMessage("Remove $title and its downloaded files from this device? The game remains in your Steam account.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Uninstall") { _, _ ->
                        SteamSession.work {
                            runCatching { SteamGames.uninstall(context, appId) }.onSuccess {
                                activity?.runOnUiThread { SteamGames.changed(); if (isAdded) { if (activity is MainActivity) parentFragmentManager.popBackStack() else activity?.finish() } }
                            }.onFailure { failure -> activity?.runOnUiThread { if (isAdded) notice(failure.message.orEmpty()) } }
                        }
                    }.show()
            }
        })
    }
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.apply { setDetailMode(true); setBottomNavigationVisible(false); setMainToolbarVisible(false) }
    }
}
