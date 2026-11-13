package com.winlator.cmod.stores

import org.junit.Assert.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ItchConnectorTest {
    @Test fun sessionAndPurchaseCookiesStayScopedToTheirHostsAndPaths() {
        val root = Cookie.Builder().name("itchio").value("session").domain("itch.io").path("/").secure().build()
        val purchase = Cookie.Builder().name("purchase").value("owned").hostOnlyDomain("author.itch.io").path("/game").secure().build()
        val cookies = listOf(root, purchase)
        assertEquals("itchio=session; purchase=owned", ItchCookies.headerFor(cookies, "https://author.itch.io/game/file/1".toHttpUrl()))
        assertEquals("itchio=session", ItchCookies.headerFor(cookies, "https://other.itch.io/game".toHttpUrl()))
        assertEquals("", ItchCookies.headerFor(cookies, "https://cdn.itch.zone/file.zip".toHttpUrl()))
        assertEquals("", ItchCookies.headerFor(cookies, "https://itch.io.evil.test/file".toHttpUrl()))
    }
    @Test fun purchasedGamesPreserveTitlesArtworkAndPagination() {
        val html = """<div class="game_row" data-game_id="1"><a class="thumb_link" href="https://author.itch.io/my-game"><img data-lazy_src="https://img.itch.zone/game.png"></a><div class="game_title"><a href="https://author.itch.io/my-game">Game &amp; Friends</a></div></div><a href="/my-purchases?page=2">Next</a>"""
        val page = ItchConnector.parseOwned(html, "https://itch.io/my-purchases")
        assertEquals("Game & Friends", page.games.single().title)
        assertEquals("https://img.itch.zone/game.png", page.games.single().image)
        assertEquals("https://itch.io/my-purchases?page=2", page.next)
        assertEquals("https://author.itch.io/my-game", page.games.single().id)
    }
    @Test fun emptyLibraryDoesNotImportSiteNavigationAsGames() {
        val page = ItchConnector.parseOwned("""<a href="https://itch.io/games">Games</a><a href="https://author.itch.io/">Author</a>""", "https://itch.io/my-purchases")
        assertTrue(page.games.isEmpty())
        assertNull(page.next)
    }
    @Test fun windowsUploadsExcludeLinuxAndroidAndUnrelatedFileFormats() {
        val html = """<meta name="csrf_token" value="session-token"><div class="upload"><a data-upload_id="1">Download</a><div class="upload_name"><strong class="name">game-win.zip</strong><span>Windows build</span></div><span class="icon-windows8"></span><span class="file_size">10 MB</span></div><div class="upload"><a data-upload_id="2">Download</a><div class="upload_name"><strong>game-linux.zip</strong></div><span class="icon-tux"></span></div><div class="upload"><a data-upload_id="3">Download</a><div class="upload_name"><strong>game.apk</strong></div></div>"""
        val uploads = ItchConnector.parseUploads(html)
        assertEquals(listOf("1"), uploads.map { it.id })
        assertEquals("game-win.zip", uploads.single().name)
        assertEquals(10L * 1024 * 1024, uploads.single().size)
        assertEquals("session-token", ItchConnector.csrf(html))
    }
    @Test fun rejectsNonItchAndPurchaseSubpageUrls() {
        assertEquals("https://author.itch.io/game", ItchConnector.gameUrl("https://author.itch.io/game/"))
        for (url in listOf("https://itch.io.evil.test/game", "http://author.itch.io/game", "https://author.itch.io/game/purchase", "https://evil.test/game"))
            assertThrows(IllegalArgumentException::class.java) { ItchConnector.gameUrl(url) }
    }
}
