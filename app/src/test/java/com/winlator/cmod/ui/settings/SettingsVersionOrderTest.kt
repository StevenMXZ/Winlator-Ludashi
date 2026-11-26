package com.winlator.cmod.ui.settings

import com.winlator.cmod.contents.ContentProfile.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsVersionOrderTest {
    @Test fun installedVersionsFirstAndNewestWithinEachGroup() {
        val versions = listOf("1.10.3", "2.3.1", "2.7.1", "3.1-a6xx", "3.1-arm64ec-gplasync")
        val installed = setOf("1.10.3", "2.7.1")
        assertEquals(
            listOf("2.7.1", "1.10.3", "3.1-a6xx", "3.1-arm64ec-gplasync", "2.3.1"),
            orderSettingsVersions(ContentType.CONTENT_TYPE_DXVK, versions, installed)
        )
    }

    @Test fun keepsVkd3dNoneAtTopAndPreservesSelectedCustomVersion() {
        val versions = listOf("None", "2.8", "2.14.1", "proton-3.0b-2763dd2", "2.13-custom")
        assertEquals(
            listOf("None", "2.14.1", "2.8", "proton-3.0b-2763dd2", "2.13-custom"),
            orderSettingsVersions(ContentType.CONTENT_TYPE_VKD3D, versions, setOf("None", "2.8", "2.14.1"))
        )
    }

    @Test fun handlesFexDatesAndBothBox64Variants() {
        assertEquals(listOf("2611", "2608", "241214"),
            orderSettingsVersions(ContentType.CONTENT_TYPE_FEXCORE, listOf("241214", "2608", "2611"), emptySet()))
        for (type in listOf(ContentType.CONTENT_TYPE_BOX64, ContentType.CONTENT_TYPE_WOWBOX64)) {
            assertEquals(listOf("0.4.4", "036fix", "0.3.5"),
                orderSettingsVersions(type, listOf("0.3.5", "036fix", "0.4.4"), emptySet()))
        }
    }
}
