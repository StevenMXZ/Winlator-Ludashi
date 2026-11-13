package com.winlator.cmod.stores

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ItchCatalogTest {
    @Test fun emptyOwnedKeysIsAValidAccountWithoutPurchasedGames() {
        assertEquals(0, ItchApi.ownedRows(JSONObject("""{"page":1,"perPage":500,"ownedKeys":null}""")).length())
        assertEquals(0, ItchApi.ownedRows(JSONObject("""{"page":1,"owned_keys":[]}""")).length())
        assertEquals(0, ItchApi.ownedRows(JSONObject("{}")).length())
        assertThrows(IllegalStateException::class.java) { ItchApi.ownedRows(JSONObject("""{"unexpected":"response"}""")) }
    }
    @Test fun officialCamelCaseMetadataRetainsArtworkAndOwnership() {
        val rows = org.json.JSONArray("""[{"id":123,"game":{"id":456,"title":"Game","url":"https://author.itch.io/game","coverUrl":"https://img.itch.zone/cover.png","shortText":"Description","user":{"displayName":"Developer"}}}]""")
        val game = ItchApi.games(rows).single()
        assertEquals("https://img.itch.zone/cover.png", game.image)
        assertEquals("Description", game.metadata.getJSONObject("information").getString("description"))
        assertEquals("Developer", game.metadata.getJSONObject("information").getString("developer"))
        assertTrue(game.metadata.getBoolean("owned"))
        assertTrue(game.metadata.getBoolean("free"))
    }
    @Test fun publicFeedsPreserveFreeAndPaidPricesWithoutImportingOtherHosts() {
        val xml = """<?xml version="1.0"?><rss version="2.0"><channel>
            <item><link>https://author.itch.io/game</link><title>Game [Free] [Windows]</title><plainTitle>Game &amp; Friends</plainTitle><price>${'$'}0.00</price><imageurl>https://img.itch.zone/game.png</imageurl><description><![CDATA[Find a clover!<img src="example.png"/>]]></description></item>
            <item><link>https://author.itch.io/paid</link><title>Paid game [Windows]</title><price>${'$'}5.00</price></item>
            <item><link>https://example.test/game</link><title>Invalid host</title></item>
            </channel></rss>"""
        val games = ItchCatalog.parse(xml)
        assertEquals(2, games.size)
        assertEquals("Game & Friends", games[0].title)
        assertEquals("https://img.itch.zone/game.png", games[0].image)
        assertTrue(games[0].metadata.getBoolean("free"))
        assertEquals("Find a clover!", games[0].metadata.getJSONObject("information").getString("description"))
        assertFalse(games[1].metadata.getBoolean("free"))
        assertEquals("$5.00", games[1].metadata.getString("price"))
        assertThrows(IllegalArgumentException::class.java) { ItchCatalog.parse("<html>Security check</html>") }
    }
    @Test fun discoveryFiltersUseWindowsFeedsAndSafeTagPaths() {
        assertEquals("https://itch.io/games/price-free/platform-windows/tag-pixel-art.xml?page=2", ItchCatalog.url(ItchBrowseQuery(price = "price-free", tag = "Pixel art"), 2))
        assertThrows(IllegalArgumentException::class.java) { ItchCatalog.url(ItchBrowseQuery(sort = "../../profile"), 1) }
        assertThrows(IllegalArgumentException::class.java) { ItchCatalog.url(ItchBrowseQuery(), 0) }
    }
    @Test fun resolvesTheDocumentedGameIdRegardlessOfMetaAttributeOrder() {
        assertEquals("5157608", ItchApi.pageId("""<meta content="games/5157608" name="itch:path"/>"""))
        assertEquals("123", ItchApi.pageId("""<meta name="itch:path" content="games/123"/>"""))
        assertNull(ItchApi.pageId("""<meta name="itch:path" content="users/123"/>"""))
    }
}
