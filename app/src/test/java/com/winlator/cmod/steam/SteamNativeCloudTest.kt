package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamNativeCloudTest {
    private fun roots(): Map<String, File> {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "cloud-paths").apply { mkdirs() }
        return mapOf("steamuserdata" to File(dir, "remote"), "winappdatalocallow" to File(dir, "LocalLow"), "root" to File(dir, "home"))
    }
    @Test fun resolvesRemoteAndWindowsPathsWithoutStartingWine() {
        val r = roots()
        assertEquals(File(r.getValue("steamuserdata"), "slot1.sav").canonicalFile, SteamNativeCloud.resolve("remote/slot1.sav", r, 76561198000000001L))
        assertEquals(File(r.getValue("winappdatalocallow"), "Studio/Game/76561198000000001/save.dat").canonicalFile,
            SteamNativeCloud.resolve("%WinAppDataLocalLow%/Studio/Game/{64BitSteamID}/save.dat", r, 76561198000000001L))
        assertEquals(File(r.getValue("root"), "Game/save").canonicalFile, SteamNativeCloud.resolve("ROOT_MOD/Game/save", r, 1))
    }
    @Test fun rejectsTraversalUnknownPlatformsAndUnexpandedRoots() {
        val r = roots()
        listOf("../file", "%WinAppDataLocalLow%/../../bad", "/etc/passwd", "C:/Windows/file", "%LinuxHome%/.save", "%WinAppDataLocalLow%/{UnknownID}/file").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { SteamNativeCloud.resolve(it, r, 1) }
        }
    }
    @Test fun refusesSymlinkEscapesInsideTheSaveDirectory() {
        val r = roots(); val base = r.getValue("steamuserdata").apply { mkdirs() }
        val outside = File(base.parentFile, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(base, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { SteamNativeCloud.resolve("link/save.dat", r, 1) }
    }
    @Test fun socialIsOffByDefaultAndExplicitChoiceIsPreserved() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        context.getSharedPreferences("steam-settings", 0).edit().remove("social").commit()
        assertFalse(SteamSocial.enabled(context))
        context.getSharedPreferences("steam-settings", 0).edit().putBoolean("social", true).commit()
        assertTrue(SteamSocial.enabled(context))
    }
}
