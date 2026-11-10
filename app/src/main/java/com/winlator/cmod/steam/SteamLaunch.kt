package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.EnvVars
import com.winlator.cmod.core.TarCompressorUtils
import com.winlator.cmod.core.WineRegistryEditor
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Adapts Ludashi's resident Steam launcher to Ludashi's existing Wine launch path. */
object SteamLaunch {
    private const val ROOT = "C:\\Program Files (x86)\\Steam"
    fun createShortcut(context: Context, container: Container, id: Int, info: JSONObject, exe: String): File {
        check(com.winlator.cmod.core.ContainerSessionGate.edit(container.id)) { "Exit the container before preparing Steam" }
        try { return stageShortcut(context, container, id, info, exe) }
        finally { com.winlator.cmod.core.ContainerSessionGate.edited(container.id) }
    }
    private fun stageShortcut(context: Context, container: Container, id: Int, info: JSONObject, exe: String): File {
        check(SteamSettings.enabled(context)) { "Steam is disabled" }
        require(id > 0)
        val installName = SteamCatalog.installName(info)
        val game = SteamStorage.directory(context, info)
        require(File(game, exe).canonicalFile.toPath().startsWith(game.toPath()))
        require(File(game, exe).isFile)
        val mode = SteamSettings.mode(context, id)
        check(!SteamSession.gameActive) { "Exit the running Steam game before changing launch mode" }
        SteamGoldberg.restore(game)
        if (mode != "RealSteam") {
            if (mode == "Goldberg") SteamGoldberg.prepare(context, game, id)
            val link = File(container.rootDir, ".wine/drive_c/LudashiGames/$id")
            link.parentFile?.mkdirs()
            if (!Files.exists(link.toPath())) Files.createSymbolicLink(link.toPath(), game.toPath())
            check(link.canonicalFile == game) { "Game path differs from the configured installation" }
            return writeShortcut(container, id, "C:\\LudashiGames\\$id\\${exe.replace('/', '\\')}", "", mode)
        }
        val prefix = File(container.rootDir, ".wine")
        val steam = File(prefix, "drive_c/Program Files (x86)/Steam")
        require(!Files.isSymbolicLink(steam.toPath())) { "Use a container without an existing Steam installation" }
        val stamp = File(steam, ".ludashi-steam")
        require(!File(steam, "steam.exe").exists() || stamp.exists()) { "Use a container without an existing Steam installation" }
        steam.mkdirs()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!stamp.isFile || stamp.readText() != version) {
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                "ludashisteam/bionic/valve-steam-x86_64.tzst", steam)) { "Steam client extraction failed" }
            for (name in listOf("steam.exe", "steamservice.exe", "steamservice.dll", "service_current_versions.vdf", "service_minimum_versions.vdf")) {
                val dest = if (name == "steam.exe") File(steam, name) else File(steam, "bin/$name")
                dest.parentFile?.mkdirs()
                context.assets.open("ludashisteam/bionic/$name").use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
            val service = File(prefix, "drive_c/Program Files (x86)/Common Files/Steam/steamservice.exe")
            service.parentFile?.mkdirs(); File(steam, "bin/steamservice.exe").copyTo(service, true)
            for ((folder, deps) in listOf("system32" to listOf("tier0_s64.dll", "vstdlib_s64.dll"), "syswow64" to listOf("tier0_s.dll", "vstdlib_s.dll"))) {
                for (dep in deps) File(steam, dep).takeIf { it.isFile }?.copyTo(File(prefix, "drive_c/windows/$folder/$dep").apply { parentFile?.mkdirs() }, true)
            }
            stamp.writeText(version)
        }
        val link = File(steam, "steamapps/common/$installName")
        link.parentFile?.mkdirs()
        if (!Files.exists(link.toPath())) Files.createSymbolicLink(link.toPath(), game.toPath())
        check(link.canonicalFile == game) { "A different installation already occupies $installName" }
        fun vdf(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
        File(steam, "steamapps/appmanifest_$id.acf").writeText("\"AppState\"\n{\n\"appid\" \"$id\"\n\"Universe\" \"1\"\n\"name\" \"${vdf(info.optJSONObject("common")?.optString("name") ?: installName)}\"\n\"StateFlags\" \"4\"\n\"installdir\" \"${vdf(installName)}\"\n}\n")
        val spec = File(steam, "ludashi-game-$id.txt")
        spec.writeText("$ROOT\\steamapps\\common\\$installName\\${exe.replace('/', '\\')}\n$id\n")
        WineRegistryEditor(File(prefix, "user.reg")).use { registry ->
            registry.setCreateKeyIfNotExist(true)
            registry.setStringValue("Software\\Valve\\Steam", "SteamPath", ROOT)
            registry.setStringValue("Software\\Valve\\Steam", "SteamExe", "$ROOT\\steam.exe")
        }
        return writeShortcut(container, id, "$ROOT\\steam.exe", "\"$ROOT\\${spec.name}\"", mode)
    }
    private fun writeShortcut(container: Container, id: Int, target: String, args: String, mode: String): File {
        container.desktopDir.mkdirs()
        val file = container.desktopDir.listFiles()?.firstOrNull {
            it.extension == "desktop" && Shortcut(container, it).getExtra("steamAppId") == id.toString()
        } ?: File(container.desktopDir, "Steam-$id.desktop")
        val previous = if (file.isFile) Shortcut(container, file) else null
        val effectiveArgs = if (mode != "RealSteam" && previous?.getExtra("steamLaunchMode") == mode) previous.getExtra("execArgs") else args
        val exec = "Exec=wine ${SteamCatalog.desktopExecPath(target)}"
        if (file.isFile) file.writeText(file.readLines().joinToString("\n") { if (it.startsWith("Exec=")) exec else it } + "\n")
        else file.writeText("[Desktop Entry]\nName=Steam $id\n$exec\nType=Application\n\n[Extra Data]\n")
        Shortcut(container, file).apply {
            putExtra("steamAppId", id.toString()); putExtra("steamLaunchMode", mode); putExtra("execArgs", effectiveArgs); saveData()
        }
        return file
    }
    /** Rebuild the launch target when the Play selector changes the saved mode. */
    fun prepareConfigured(context: Context, shortcut: Shortcut, mode: String): Shortcut {
        val id = shortcut.getExtra("steamAppId").toInt()
        val exe = shortcut.getExtra("steamExecutable")
        check(exe.isNotBlank() && shortcut.getExtra("steamConfigured") == "1") { "Configure the game executable first" }
        SteamSettings.setMode(context, id, mode)
        if (shortcut.getExtra("steamLaunchMode") == mode) return shortcut
        val file = createShortcut(context, shortcut.container, id, SteamGames.info(context, id), exe)
        return Shortcut(shortcut.container, file).apply {
            putExtra("steamExecutable", exe); putExtra("steamConfigured", "1"); saveData()
            SteamGames.changed()
        }
    }
    @JvmStatic fun canLaunch(context: Context, shortcut: Shortcut?): Boolean {
        if (shortcut?.getExtra("steamAppId")?.toIntOrNull() == null) return true
        return shortcut.getExtra("steamConfigured") == "1" && !SteamNativeCloud.busy && shortcut.getExtra("steamLaunchMode", "RealSteam") == SteamSettings.mode(context, shortcut.getExtra("steamAppId").toInt()) && SteamSettings.enabled(context) && (shortcut.getExtra("steamLaunchMode", "RealSteam") != "RealSteam" || SteamCredentials.load(context) != null)
    }
    @JvmStatic fun apply(context: Context, shortcut: Shortcut?, env: EnvVars) {
        val owner = context as? com.winlator.cmod.XServerDisplayActivity
        val id = shortcut?.getExtra("steamAppId")?.toIntOrNull() ?: run {
            if (owner != null && SteamSession.pauseForCustomGame(context)) owner.markSteamSessionPaused()
            return
        }
        check(shortcut?.getExtra("steamConfigured") == "1") { "Configure the Steam launch method and executable before playing" }
        SteamSession.beginGame(context)
        owner?.markSteamSessionPaused()
        SteamAchievements.begin(context, shortcut!!)
        val game = SteamGames.directory(context, id)
        // Also covers pinned/home shortcuts and a different container sharing this install.
        SteamGoldberg.restore(game)
        if (shortcut!!.getExtra("steamLaunchMode") == "Goldberg") SteamGoldberg.prepare(context, game, id)
        if (shortcut.getExtra("steamLaunchMode", "RealSteam") != "RealSteam") { SteamSocial.status.value = com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.steam_friends_empty); return }
        val saved = SteamCredentials.load(context) ?: error("Sign in to Steam before launching this game")
        env.put("LUDASHI_STEAM_TOKEN", saved.getString("token"))
        env.put("LUDASHI_STEAM_USERNAME", saved.getString("account"))
        env.put("LUDASHI_STEAM_STEAMID", saved.getLong("id"))
        env.put("LUDASHI_STEAM_APPID", id)
        val info = JSONObject(File(context.filesDir, "steam-library/$id.json").readText())
        if (SteamSocial.enabled(context) || SteamAchievements.enabled(context)) SteamSocial.beginGuest(context, env, id)
        SteamCloud.begin(context, shortcut)
        env.put("LUDASHI_STEAM_AGENT_CLOUD", "1")
        env.put("LUDASHI_STEAM_LANGUAGE", "english")
        env.put("LUDASHI_STEAM_APP_NAME", info.optJSONObject("common")?.optString("name").orEmpty())
        env.put("LUDASHI_STEAM_BUILD_ID", info.optJSONObject("depots")?.optJSONObject("branches")?.optJSONObject("public")?.optString("buildid", "0") ?: "0")
        env.put("LUDASHI_STEAM_DEPOTS", SteamCatalog.depots(info).joinToString(",") { "${it.id}:${it.manifest.toULong()}:0" })
        env.put("SteamAppId", id)
        env.put("SteamGameId", id)
        env.put("PROTON_DISABLE_LSTEAMCLIENT", "1")
        val overrides = env.get("WINEDLLOVERRIDES")
        env.put("WINEDLLOVERRIDES", overrides + if (overrides.isEmpty()) "lsteamclient=" else ";lsteamclient=")
    }
}
