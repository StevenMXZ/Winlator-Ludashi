package com.winlator.cmod.steam

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamAchievementsTest {
    @Test fun resetAchievementStaysLockedDespiteOldUnlockTimestamp() {
        val raw = JSONObject("""{"schema":{"stats":{"4":{"bits":{"0":{"name":"FIRST","display":{"name":"First","desc":"Finish"}},"1":{"name":"SECRET","display":{"name":"Spoiler","desc":"Secret detail","hidden":"1"}}}}}},"stats":[{"id":4,"value":1}],"achievementBlocks":[{"achievementId":4,"unlockTimes":[123,456]}]}""")
        val list = SteamUserData.parseAchievements(raw, 42)
        val first = list.first { it.key == "FIRST" }
        assertTrue(first.unlocked); assertEquals(123L, first.unlockedAt)
        val reset = list.first { it.key == "SECRET" }
        assertFalse(reset.unlocked); assertEquals(0L, reset.unlockedAt)
        assertEquals("Hidden achievement", reset.title)
        assertFalse(reset.description.contains("Secret detail"))
    }
    @Test fun highBitAndNestedLocalizedSchemaAreDecodedWithoutLosingUnlockState() {
        val raw = JSONObject("""{"schema":{"GameStats":{"stats":{"12":{"bits":{"31":{"name":"FINAL","display":{"name":{"english":"Final","german":"Ende"},"desc":{"english":"Finish it"},"icon":"earned.jpg","icon_gray":"locked.jpg"}}}}}}},"stats":[{"id":12,"value":2147483648}],"achievementBlocks":[]}""")
        val achievement = SteamUserData.parseAchievements(raw, 42).single()
        assertTrue(achievement.unlocked)
        assertEquals("Final", achievement.title)
        assertEquals("Finish it", achievement.description)
        assertTrue(achievement.icon.endsWith("/42/earned.jpg"))
    }
    @Test fun gamesWithNoAchievementBitsProduceNoInventedEntries() {
        val raw = JSONObject("""{"schema":{"stats":{"1":{"type":"1","name":"Score"}}},"stats":[{"id":1,"value":999}]}""")
        assertTrue(SteamUserData.parseAchievements(raw, 42).isEmpty())
    }
}
