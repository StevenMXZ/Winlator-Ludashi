package com.winlator.cmod.gog

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GogTransfersTest {
    @Test fun restoresInterruptedTransfersAsPausedWithArtworkAndRetryMetadata() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val game = GogGame("1234", "A Game", "https://images.test/cover.jpg")
        GogTransfers.save(context, game, "downloading", 20, 100, 7, "{\"files\":[]}")
        val restored = GogTransfers.load(context).getValue(game.id)
        assertEquals("paused", restored.getString("status"))
        assertEquals(game.image, restored.getString("image"))
        assertEquals(7, restored.getInt("container"))
        assertEquals(20L, restored.getLong("done"))
        assertTrue(restored.getString("installer").contains("files"))
        GogTransfers.save(context, game, "completed", 100, 100, 7, "{}")
        assertEquals("completed", GogTransfers.load(context).getValue(game.id).getString("status"))
    }    @Test fun malformedHistoryDoesNotHideValidTransfers() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "gog-transfers").mkdirs()
        java.io.File(context.filesDir, "gog-transfers/broken.json").writeText("{}")
        val game = GogGame("7890", "Game", "")
        GogTransfers.save(context, game, "failed", 1, 5, 1, "{}", "network")
        val restored = GogTransfers.load(context)
        assertFalse(restored.containsKey("broken"))
        assertEquals("network", restored.getValue(game.id).getString("error"))
    }

}
