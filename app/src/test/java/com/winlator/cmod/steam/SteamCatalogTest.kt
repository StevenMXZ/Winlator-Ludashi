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

    @Test fun estimatesOnlyLicensedRegionalDepotsAndDeduplicatesPackages() {
        val depots = listOf(SteamCatalog.Depot(1, 11, 57), SteamCatalog.Depot(2, 22, 63),
            SteamCatalog.Depot(3, 33, 1))
        val library = JSONObject("""{"packages":[
            {"pics_fetched":true,"depot_ids":[1,3]},
            {"pics_fetched":true,"depot_ids":[3,99]}
        ]}""")
        assertEquals(58L, SteamCatalog.estimatedSize(depots, library))
        val japanese = JSONObject("""{"packages":[{"pics_fetched":true,"depot_ids":[2,3]}]}""")
        assertEquals(64L, SteamCatalog.estimatedSize(depots, japanese))
    }

    @Test fun doesNotGuessAlternativeSizesWhileLicenseMetadataIsMissing() {
        val depots = listOf(SteamCatalog.Depot(1, 11, 57), SteamCatalog.Depot(2, 22, 63))
        assertNull(SteamCatalog.estimatedSize(depots, JSONObject()))
        assertNull(SteamCatalog.estimatedSize(depots, JSONObject("""{"packages":[
            {"pics_fetched":true,"depot_ids":[1]},
            {"pics_fetched":false,"depot_ids":[]}
        ]}""")))
        assertEquals(57L, SteamCatalog.estimatedSize(depots.take(1), JSONObject()))
        assertNull(SteamCatalog.estimatedSize(listOf(SteamCatalog.Depot(1, 11)), JSONObject()))
    }

    @Test fun selectsOnlyOwnedAndEnabledDlcAlongsideBaseDepots() {
        val info = JSONObject("""{"extended":{"listofdlc":"101,102,103,104"},"depots":{
            "1":{"manifests":{"public":{"gid":"11","size":"100"}}},
            "2":{"dlcappid":"101","manifests":{"public":{"gid":"22","size":"20"}}},
            "3":{"dlcappid":"102","manifests":{"public":{"gid":"33","size":"30"}}},
            "4":{"dlcappid":"103","manifests":{"public":{"gid":"44","size":"40"}}},
            "5":{"dlcappid":"101","config":{"language":"french"},"manifests":{"public":"55"}}
        }}""")
        val library = JSONObject("""{"owned_apps":[{"id":100},{"id":101},{"id":102},{"id":104}],
          "packages":[{"pics_fetched":true,"depot_ids":[1,2,3]}]}""")
        assertTrue(SteamCatalog.packageMetadataReady(library))
        assertFalse(SteamCatalog.packageMetadataReady(JSONObject()))
        assertEquals(setOf(101,102,103,104), SteamCatalog.dlcIds(info))
        assertEquals(setOf(101,102), SteamCatalog.selectedDlc(info, library, emptySet()))
        val selected = SteamCatalog.selectedDlc(info, library, setOf(102))
        val depots = SteamCatalog.depots(info, "public", "english", selected)
        assertEquals(listOf(1,2), depots.map { it.id })
        assertEquals(120L, SteamCatalog.estimatedSize(depots, library))
        assertEquals(listOf(1), SteamCatalog.depots(info).map { it.id })
    }

    @Test fun keepsManifestRecordsForDeselectedInstalledDlc() {
        val previous = JSONObject("""{"depots":{"1":{},"2":{"dlcappid":"101"},"3":{"dlcappid":"102"}},
            "ludashiInstalledDepots":"1:11:0,2:22:0,3:33:0"}""")
        val attempted = listOf(SteamCatalog.Depot(1, 12), SteamCatalog.Depot(2, 23))
        assertEquals(setOf(101,102), SteamCatalog.installedDlcIds(previous))
        assertEquals("1:12:0,2:23:0,3:33:0", SteamCatalog.installedManifestList(previous, attempted, attempted))
        assertEquals("1:12:0,3:33:0", SteamCatalog.installedManifestList(previous, attempted, attempted.take(1)))
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
