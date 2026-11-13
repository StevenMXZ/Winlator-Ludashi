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

    @Test fun fallsBackToNewestWineWhenNoProtonIsAvailable() {
        val catalog = listOf(item("old", "Wine", "wine-9.20-x86_64", true), item("new", "Wine", "wine-10.15-arm64ec"))
        assertEquals(setOf("new"), recommendedComponentIds(catalog))
    }
}
