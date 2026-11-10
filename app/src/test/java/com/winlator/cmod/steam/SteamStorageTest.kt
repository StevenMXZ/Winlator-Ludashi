package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [com.winlator.cmod.container.AudioDriverMigrationTest.GpuShadow::class])
class SteamStorageTest {
    @Test fun changingDefaultKeepsInstalledAndPausedGamesAtTheirOriginalLocation() {
        val context = RuntimeEnvironment.getApplication()
        val first = File(context.cacheDir, "library-one").apply { mkdirs() }
        SteamStorage.setRoot(context, first)
        val dir = SteamStorage.download(context, 701, "Game One")
        File(dir, "save.dat").writeText("keep")
        val record = SteamStorage.record(JSONObject("""{"config":{"installdir":"Game One"}}"""), dir)
        SteamStorage.setRoot(context, File(context.cacheDir, "library-two"))
        assertEquals(dir, SteamStorage.directory(context, record))
        assertEquals(dir, SteamStorage.download(context, 701, "Game One"))
        assertEquals(File(context.cacheDir, "library-two/LudashiSteam/New Game").canonicalFile, SteamStorage.download(context, 702, "New Game"))
        assertEquals("keep", File(dir, "save.dat").readText())
    }
    @Test fun legacyPartialDownloadKeepsItsChunksAfterChangingDefault() {
        val context = RuntimeEnvironment.getApplication()
        val partial = File(context.filesDir, "steam-library/common/Partial").apply { mkdirs() }
        File(partial, "chunk.bin").writeText("verified chunk")
        SteamStorage.setRoot(context, File(context.cacheDir, "new-default"))
        assertEquals(partial.canonicalFile, SteamStorage.download(context, 704, "Partial"))
        assertEquals("verified chunk", File(partial, "chunk.bin").readText())
    }
    @Test fun uninstallUsesRecordedExternalLocationAndPreservesLibrarySiblings() {
        val context = RuntimeEnvironment.getApplication()
        SteamStorage.setRoot(context, File(context.cacheDir, "uninstall-external"))
        val game = SteamStorage.download(context, 705, "Remove Me")
        File(game, "game.exe").writeText("exe")
        val sibling = File(game.parentFile, "keep.dat").apply { writeText("keep") }
        val metadata = File(context.filesDir, "steam-library/705.json").apply { parentFile!!.mkdirs() }
        metadata.writeText(SteamStorage.record(JSONObject("""{"config":{"installdir":"Remove Me"}}"""), game).toString())
        SteamStorage.setRoot(context, null)
        SteamGames.uninstall(context, 705)
        assertFalse(game.exists())
        assertFalse(metadata.exists())
        assertEquals("keep", sibling.readText())
    }
    @Test fun rejectsTraversalAndRelativeCustomRoots() {
        val context = RuntimeEnvironment.getApplication()
        assertThrows(IllegalArgumentException::class.java) { SteamStorage.setRoot(context, File("relative")) }
        assertThrows(IllegalArgumentException::class.java) { SteamStorage.download(context, 703, "../outside") }
    }
    @Test fun legacyGamesRemainInThePrivateLibraryAfterChoosingExternalFolder() {
        val context = RuntimeEnvironment.getApplication()
        SteamStorage.setRoot(context, File(context.cacheDir, "external"))
        assertEquals(File(context.filesDir, "steam-library/common/Legacy").canonicalFile,
            SteamStorage.directory(context, JSONObject("""{"config":{"installdir":"Legacy"}}""")))
    }
    @Test fun seedingNeverRelocksLocalAchievementsOrReplacesTheirEarnedTime() {
        val local = JSONObject("""{"LOCAL":{"earned":true,"earned_time":10},"BOTH":{"earned":1,"earned_time":20}}""")
        fun a(key: String, earned: Boolean) = SteamUserData.Achievement(key, key, "", earned, 30, "")
        SteamAchievements.mergeEarned(local, listOf(a("LOCAL", false), a("BOTH", true), a("REMOTE", true)))
        assertTrue(local.getJSONObject("LOCAL").getBoolean("earned"))
        assertEquals(20, local.getJSONObject("BOTH").getInt("earned_time"))
        assertEquals(30, local.getJSONObject("REMOTE").getInt("earned_time"))
    }
    @Test fun friendIdsStay64BitAndMissingNamesDoNotExposeAccountIds() {
        val f = SteamSocial.friend(JSONObject("""{"sid":"76561198000000001","name":"Nick","state":1,"app":440}"""))!!
        assertEquals(76561198000000001L, f.id)
        assertEquals("Nick", f.name)
        assertNull(SteamSocial.friend(JSONObject("""{"sid":"0"}""")))
    }
    @Test fun cachedSelfAndOtherRequestedPersonasAreNotDisplayedAsFriends() {
        val people = org.json.JSONArray("""[{"sid":"76561198000000001","name":"Self"},{"sid":"76561198000000002","name":"Friend"},{"sid":"76561198000000003","name":"Other"}]""")
        assertEquals(listOf("Friend"), SteamSocial.roster(people, setOf(76561198000000002L)).map { it.name })
    }

}
