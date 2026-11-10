package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.uiStr
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.FileUtils
import android.os.Environment
import java.io.File

// The launcher (home screen) icon of a library game: pin it, find out whether it is there, take it
// away again. Shared by the Library tile menu and the game page. A shortcut is identified on the
// home screen by the "uuid" the .desktop file carries.
object HomeShortcuts {
    enum class PinResult { Unsupported, Requested, Restored }

    @JvmStatic
    fun buildInfo(
        context: Context,
        shortLabel: String,
        longLabel: String,
        containerId: Int,
        shortcutPath: String,
        icon: Icon,
        uuid: String
    ): ShortcutInfo {
        val intent = Intent(context, XServerDisplayActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.putExtra("container_id", containerId)
        intent.putExtra("shortcut_path", shortcutPath)
        return ShortcutInfo.Builder(context, uuid)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    // True while a usable (not disabled) pinned icon for this game exists.
    @JvmStatic
    fun isPinned(context: Context, shortcut: Shortcut): Boolean {
        val uuid = shortcut.getExtra("uuid")
        if (uuid.isEmpty()) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return try {
            manager.pinnedShortcuts.any { it.id == uuid && it.isEnabled }
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun pin(activity: Activity, shortcut: Shortcut): PinResult {
        val manager = activity.getSystemService(ShortcutManager::class.java) ?: return PinResult.Unsupported
        if (!manager.isRequestPinShortcutSupported) return PinResult.Unsupported

        shortcut.genUUID()
        val uuid = shortcut.getExtra("uuid")
        val info = buildInfo(
            activity, shortcut.name, shortcut.name, shortcut.container.id, shortcut.file.path,
            Icon.createWithBitmap(fitIcon(manager, pickIcon(activity, shortcut))), uuid
        )

        // An icon that was switched off here is still on the home screen: switch it back on
        // instead of asking the launcher for a second copy.
        val existing = try {
            manager.pinnedShortcuts.firstOrNull { it.id == uuid }
        } catch (e: Exception) {
            null
        }
        if (existing != null) {
            try {
                manager.enableShortcuts(listOf(uuid))
                manager.updateShortcuts(listOf(info))
                return PinResult.Restored
            } catch (e: Exception) {
                // fall through to a normal pin request
            }
        }
        return if (manager.requestPinShortcut(info, null)) PinResult.Requested else PinResult.Unsupported
    }

    // After the shortcut moved (container switch): the pinned icon's intent still names the old
    // container and .desktop, so it is pointed at the new ones (same id). No-op when not pinned.
    @JvmStatic
    fun updatePinned(context: Context, shortcut: Shortcut) {
        if (!isPinned(context, shortcut)) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        try {
            val info = buildInfo(
                context, shortcut.name, shortcut.name, shortcut.container.id, shortcut.file.path,
                Icon.createWithBitmap(fitIcon(manager, pickIcon(context, shortcut))), shortcut.getExtra("uuid")
            )
            manager.updateShortcuts(listOf(info))
        } catch (e: Exception) {
            android.util.Log.w("HomeShortcuts", uiStr(R.string.ui_could_not_update_the_pinned_shortcut), e)
        }
    }

    // Pinned icons can't be deleted by an app, only switched off (the launcher greys them out).
    @JvmStatic
    fun unpin(context: Context, shortcut: Shortcut): Boolean {
        val uuid = shortcut.getExtra("uuid")
        if (uuid.isEmpty()) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return try {
            val pinned = manager.pinnedShortcuts.any { it.id == uuid }
            if (pinned) {
                manager.disableShortcuts(listOf(uuid), context.getString(R.string.shortcut_not_available))
                manager.removeDynamicShortcuts(listOf(uuid))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.removeLongLivedShortcuts(listOf(uuid))
            }
            pinned
        } catch (e: Exception) {
            false
        }
    }

    // The icon the user chose (NAME.user.png) first, then the one extracted from the exe.
    private fun pickIcon(context: Context, shortcut: Shortcut): Bitmap {
        val base = FileUtils.getBasename(shortcut.file.path)
        val root = File(Environment.getExternalStorageDirectory(), "Winlator")
        // Same priority/crop as Ludashi's existing library home-screen action.
        for ((relative, crop) in listOf("icons/$base.user.png" to false, "covers/$base.png" to true, "banners/$base.png" to true, "icons/$base.png" to false)) {
            val bitmap = LibraryImageCache.load(File(root, relative).path) ?: continue
            if (!crop) return bitmap
            val size = minOf(bitmap.width, bitmap.height)
            return Bitmap.createBitmap(bitmap, (bitmap.width-size)/2, (bitmap.height-size)/2, size, size)
        }
        return shortcut.icon ?: BitmapFactory.decodeResource(context.resources, R.drawable.icon_wine)
    }

    private fun fitIcon(manager: ShortcutManager, bmp: Bitmap): Bitmap {
        val maxSide = maxOf(manager.iconMaxWidth, manager.iconMaxHeight)
        val longSide = maxOf(bmp.width, bmp.height)
        if (maxSide > 0 && longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            return Bitmap.createScaledBitmap(
                bmp,
                maxOf(1, Math.round(bmp.width * scale)),
                maxOf(1, Math.round(bmp.height * scale)),
                true
            )
        }
        return bmp
    }
}
