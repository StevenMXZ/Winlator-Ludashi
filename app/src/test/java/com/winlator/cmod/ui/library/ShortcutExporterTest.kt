package com.winlator.cmod.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutExporterTest {
    @Test fun steamExportPreservesGameSettingsAndWritesOneContainerId() {
        val lines = listOf("[Desktop Entry]", "Exec=wine steam.exe", "container_id:2",
            "[Extra Data]", "steamAppId=10", "steamConfigured=1", "execArgs=-game cstrike", "container_id:3")
        assertEquals(listOf("[Desktop Entry]", "Exec=wine steam.exe",
            "[Extra Data]", "steamAppId=10", "steamConfigured=1", "execArgs=-game cstrike", "container_id:9"),
            ShortcutExporter.desktopContents(lines, 9).trimEnd().lines())
    }
}
