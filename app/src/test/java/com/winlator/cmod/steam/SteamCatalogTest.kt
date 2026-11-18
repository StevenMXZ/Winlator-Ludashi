package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.nio.file.Files
import com.winlator.cmod.core.StringUtils

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamCatalogTest {
    @Test fun selectsWindowsEnglishBaseContentAndPreservesUnsignedManifestIds() {
        val info = JSONObject("""{"depots":{
          "1":{"manifests":{"public":{"gid":"18446744073709551615"}}},
          "2":{"config":{"oslist":"windows","language":"english"},"manifests":{"public":"42"}},
          "3":{"config":{"oslist":"linux"},"manifests":{"public":"43"}},
          "4":{"config":{"language":"french"},"manifests":{"public":"44"}},
          "5":{"dlcappid":"99","manifests":{"public":"45"}},
          "6":{"manifests":{"beta":"46"}}, "branches":{}
        }}""")
        val selected = SteamCatalog.depots(info)
        assertEquals(listOf(1, 2), selected.map { it.id })
        assertEquals(listOf(-1L, 42L), selected.map { it.manifest })
    }
    @Test fun resolvesSharedDepotsGenericallyBeforeGameFiles() {
        val info = JSONObject("""{"depots":{
          "91":{"manifests":{"beta":{"gid":"91"}}},
          "92":{"depotfromapp":"800","sharedinstall":"2"},
          "93":{"depotfromapp":"800","config":{"language":"french"}},
          "94":{"depotfromapp":"800","config":{"oslist":"linux"}},
          "95":{"depotfromapp":"900","sharedinstall":"1"},
          "96":{"depotfromapp":"800","config":{"lowviolence":"1"}},
          "branches":{"beta":{}}
        }}""")
        val source = JSONObject("""{"depots":{
          "92":{"manifests":{"public":{"gid":"18446744073709551615","size":"42"}}},
          "93":{"depotfromapp":"801"}
        }}""")
        val secondSource = JSONObject("""{"depots":{"93":{"manifests":{"beta":"93"}}}}""")
        val requested = mutableListOf<Int>()

        val depots = SteamCatalog.depots(info, "beta", "french") { appId ->
            requested.add(appId)
            when (appId) {
                800 -> source
                801 -> secondSource
                else -> error("Unexpected lookup for app $appId")
            }
        }

        assertEquals(listOf(800, 801), requested)
        assertEquals(listOf(
            SteamCatalog.Depot(92, -1, 42, 800, "public"),
            SteamCatalog.Depot(93, 93, 0, 801, "beta"),
            SteamCatalog.Depot(91, 91, 0, 0, "beta"),
        ), depots)
    }

    @Test fun rejectsCircularSharedDepotReferences() {
        val info = JSONObject("""{"depots":{"92":{"depotfromapp":"800","sharedinstall":"2"}}}""")
        assertThrows(IllegalStateException::class.java) { SteamCatalog.depots(info) { info } }
    }

    @Test fun rejectsEmptySelection() {
        assertThrows(IllegalArgumentException::class.java) { SteamCatalog.depots(JSONObject("""{"depots":{}}""")) }
    }
    @Test fun rejectsUnsafeInstallNames() {
        for (name in listOf("..", ".", "", "../escape", "a\\b", "line\nbreak", "a\"b")) {
            val info = JSONObject().put("config", JSONObject().put("installdir", name))
            assertThrows(IllegalArgumentException::class.java) { SteamCatalog.installName(info) }
        }
    }
    @Test fun acceptsNamesWithSpaces() {
        assertEquals("Game Name", SteamCatalog.installName(JSONObject("""{"config":{"installdir":"Game Name"}}""")))
    }
    @Test fun acceptsOnlyExistingExecutablesInsideInstallation() {
        val root = Files.createTempDirectory("steam-catalog").toFile()
        try {
            root.resolve("bin").mkdirs(); root.resolve("bin/game.exe").writeText("")
            val info = JSONObject("""{"config":{"launch":{
              "0":{"executable":"bin\\game.exe","config":{"oslist":"windows"}},
              "1":{"executable":"../outside.exe"},
              "2":{"executable":"missing.exe"},
              "3":{"executable":"bin/game.exe","config":{"oslist":"linux"}}
            }}}""")
            assertEquals(listOf("bin/game.exe"), SteamCatalog.executables(info, root))
        } finally { root.deleteRecursively() }
    }
    @Test fun desktopPathSurvivesLudashiUnescape() {
        val path = "C:\\Program Files (x86)\\Steam\\steam.exe"
        assertEquals(path, StringUtils.unescape(SteamCatalog.desktopExecPath(path)))
    }
}
