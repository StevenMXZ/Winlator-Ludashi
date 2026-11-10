package com.winlator.cmod.ui.library

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

// Decoded Library artwork (covers, banners, game icons), keyed by path + last-modified time so a
// replaced file (new custom icon, re-downloaded cover) is never served stale.
//
// Why: tiles decoded their image asynchronously and showed the shortcut's original exe icon
// until the decode finished — so a game with a custom icon flashed the original one for a few
// frames on every app start and every time it scrolled/filtered back into view (All ↔
// Favorites). With the cache, a tile that has been decoded before starts with the right bitmap,
// and LibraryScreenController pre-decodes the (small) game icons on its loader thread before
// publishing, so even the first frame is correct.
object LibraryImageCache {
    private val cache = object : LruCache<String, Bitmap>(
        // KB; 1/8 of the app heap.
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    // Stat only (cheap); null if the file doesn't exist.
    @JvmStatic
    fun keyFor(path: String?): String? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile) return null
        return path + "|" + file.lastModified()
    }

    @JvmStatic
    fun peek(key: String?): Bitmap? = key?.let { cache.get(it) }

    // Nothing in the Library is ever drawn larger than the screen, and the largest use is a
    // full-screen crop (backdrops). A picture is therefore decoded at a power-of-two reduction as
    // long as its SHORT side still covers the screen's LONG side afterwards - so a crop fills the
    // screen without upscaling in either orientation. Downloaded covers/banners are far below that
    // and decode exactly as before; this only catches very large pictures the user picked (a
    // 48 MP photo decodes to ~190 MB, 1/4 of that after one halving), which otherwise could not
    // even fit the cache (1/8 of the heap) and were decoded again on every view.
    private fun decodeForScreen(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return BitmapFactory.decodeFile(path)
        val metrics = Resources.getSystem().displayMetrics
        val target = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sample * 2) >= target) {
            sample *= 2
        }
        if (sample == 1) return BitmapFactory.decodeFile(path)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // Decodes (off the main thread!) and caches; returns the cached bitmap when present.
    @JvmStatic
    fun load(path: String?): Bitmap? {
        val key = keyFor(path) ?: return null
        cache.get(key)?.let { return it }
        val bitmap = runCatching { decodeForScreen(path!!) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
