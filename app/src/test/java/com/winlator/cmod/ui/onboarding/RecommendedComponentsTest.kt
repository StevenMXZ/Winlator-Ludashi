package com.winlator.cmod.ui.onboarding

import org.junit.Assert.*
import org.junit.Test

class RecommendedComponentsTest {
    private fun item(id: String, type: String, name: String, recommended: Boolean = false) =
        OnboardingComponent(id, type, name, false, recommended, true)

    @Test fun recommendsNewestProtonInsteadOfStaleCatalogWine() {
        val catalog = listOf(
            item("wine9", "Wine", "wine-9.20-x86_64", true),
            item("p10", "Proton", "Proton 10.0-5 arm64ec"),
            item("p11", "Proton", "Proton 11.0-1 arm64ec"),
            item("legacy", "Proton", "Proton 10 arm64ec (Legacy)"),
            item("dxvk3", "DXVK", "3.1-arm64ec-gplasync"),
            item("dxvk2", "DXVK", "2.7.1", true)
        )
        assertEquals(setOf("p11", "dxvk3"), recommendedComponentIds(catalog))
    }

    @Test fun prefersProton9Arm64ecOverLegacyAndNewerPackages() {
        val catalog = listOf(
            item("p9", "Proton", "Proton 9 arm64ec"),
            item("p9x86", "Proton", "Proton 9 x86_64"),
            item("p10", "Proton", "Proton 10 arm64ec (Legacy)", true),
            item("p11", "Proton", "Proton 11.0-7 arm64ec")
        )
        assertEquals(setOf("p9"), recommendedComponentIds(catalog))
    }

    @Test fun displayOrderKeepsRecommendationsAndInstalledPriority() {
        val catalog = listOf(
            item("old", "DXVK", "1.9.4").copy(installed = true),
            item("v2", "DXVK", "2.7.1"),
            item("v3a", "DXVK", "3.1-a6xx"),
            item("v3b", "DXVK", "3.1-arm64ec-gplasync"),
            item("p9", "Proton", "Proton 9 arm64ec"),
            item("p11", "Proton", "Proton 11.0-7 arm64ec")
        )
        val recommended = recommendedComponentIds(catalog)
        assertEquals(setOf("v3b", "p9"), recommended)
        assertEquals(listOf("old", "v3a", "v3b", "v2"),
            visibleComponentRows(catalog, "DXVK", recommended).map { it.id })
        assertEquals(listOf("v3b", "p9"),
            visibleComponentRows(catalog, "Recommended", recommended).map { it.id })
        assertEquals(listOf("p11", "p9"),
            visibleComponentRows(catalog, "Wine & Proton", recommended).map { it.id })
    }

    @Test fun displayOrderHandlesFexDatesAndCompactBox64Versions() {
        val catalog = listOf(
            item("fex2024", "FEXCore", "241214"),
            item("fex2026", "FEXCore", "2611"),
            item("box36", "Box64", "036fix"),
            item("box44", "Box64", "0.4.4"),
            item("box35", "Box64", "0.3.5")
        )
        assertEquals(listOf("fex2026", "fex2024"),
            visibleComponentRows(catalog, "FEXCore", emptySet()).map { it.id })
        assertEquals(listOf("box44", "box36", "box35"),
            visibleComponentRows(catalog, "Box64", emptySet()).map { it.id })
    }

    @Test fun fallsBackToNewestWineWhenNoProtonIsAvailable() {
        val catalog = listOf(item("old", "Wine", "wine-9.20-x86_64", true), item("new", "Wine", "wine-10.15-arm64ec"))
        assertEquals(setOf("new"), recommendedComponentIds(catalog))
    }
}
