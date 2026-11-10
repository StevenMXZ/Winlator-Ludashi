package com.winlator.cmod.steam

import android.graphics.Bitmap
import com.winlator.cmod.ui.library.LibraryImageCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamLibraryThumbnailTest {
    @Test fun thumbnailDecodeIsBoundedAndDoesNotReplaceFullSizeArtwork() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "large-library-image.png")
        val source = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        val thumbnail = LibraryImageCache.loadThumbnail(file.path)!!
        assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 512)
        assertSame(thumbnail, LibraryImageCache.loadThumbnail(file.path))
        assertNotEquals(LibraryImageCache.keyFor(file.path), LibraryImageCache.thumbnailKeyFor(file.path))
    }
}
