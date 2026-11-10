package com.winlator.cmod.ui.containers

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.winlator.cmod.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContainerStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun countsContainerFilesWithoutFollowingSharedDriveLinks() {
        val root = temp.newFolder("container")
        val drive = File(root, ".wine/drive_c").apply { mkdirs() }
        File(drive, "game.dat").writeBytes(ByteArray(123))
        val cache = File(root, ".cache").apply { mkdirs() }
        File(cache, "shader").writeBytes(ByteArray(45))
        File(root, "metadata").writeBytes(ByteArray(7))
        val outside = temp.newFolder("shared")
        File(outside, "other-game").writeBytes(ByteArray(1000))
        Files.createSymbolicLink(File(drive, "shared").toPath(), outside.toPath())
        assertEquals(StorageUsage(123, 45, 175), ContainerStorageUsage.measure(root))
    }

    @Test fun missingRootReturnsZeroUsage() {
        assertEquals(StorageUsage(0, 0, 0), ContainerStorageUsage.measure(File(temp.root, "missing")))
    }

    @Test fun closingStorageReleasesOverlayAndItsComposition() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup().visible()
        try {
            val activity = controller.get()
            activity.setContentView(android.widget.FrameLayout(activity))
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val containerRoot = temp.newFolder("close-test")
            ContainerStorageDialog.show(activity, "Container-1", containerRoot)
            val host = root.findViewWithTag<ComposeView>(ContainerStorageDialog.VIEW_TAG)
            assertNotNull(host)
            host.createComposition()
            assertTrue(host.hasComposition)
            ContainerStorageDialog.dismiss(activity)
            assertNull(root.findViewWithTag<ComposeView>(ContainerStorageDialog.VIEW_TAG))
            assertFalse(host.hasComposition)
            // A subsequent open must create a usable overlay, rather than leave input blocked.
            ContainerStorageDialog.show(activity, "Container-1", containerRoot)
            assertNotNull(root.findViewWithTag<ComposeView>(ContainerStorageDialog.VIEW_TAG))
            ContainerStorageDialog.dismiss(activity)
        } finally { controller.pause().stop().destroy() }
    }
}
