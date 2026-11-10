package com.winlator.cmod.components

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class ComponentRestoreTest {
    private fun prefix(): File = Files.createTempDirectory("component-restore").toFile().also {
        File(it, ".wine/drive_c/windows/system32").mkdirs()
        for (name in listOf("user.reg", "system.reg")) File(it, ".wine/$name").writeText("WINE REGISTRY Version 2\n")
    }
    private fun place(root: File, component: String, content: String) {
        val stage = Files.createTempDirectory("component-stage").toFile()
        File(stage, "test.dll").writeText(content)
        val journal = ComponentUndo.open(root, component)
        journal.place(stage, File(root, ".wine/drive_c/windows/system32"))
        journal.close()
        stage.deleteRecursively()
    }
    @Test fun overlappingComponentsRestoreTheOriginalInEitherOrder() {
        for (order in listOf(listOf("first", "second"), listOf("second", "first"))) {
            val root = prefix()
            val file = File(root, ".wine/drive_c/windows/system32/test.dll")
            file.writeText("base")
            place(root, "first", "aaaa"); place(root, "second", "bbbb")
            ComponentUndo.revert(root, order.first())
            assertEquals(if (order.first() == "first") "bbbb" else "aaaa", file.readText())
            ComponentUndo.revert(root, order.last())
            assertEquals("base", file.readText())
            root.deleteRecursively()
        }
    }
    @Test fun sameSizeSameTimestampUserChangeIsPreserved() {
        val root = prefix()
        place(root, "runtime", "aaaa")
        assertTrue(ComponentUndo.keepsLegacyComponent(root, org.json.JSONArray("[\"test\"]")))
        assertFalse(ComponentUndo.keepsLegacyComponent(root, org.json.JSONArray("[\"unrelated\"]")))
        val file = File(root, ".wine/drive_c/windows/system32/test.dll")
        val time = file.lastModified()
        file.writeText("user"); file.setLastModified(time)
        assertTrue(ComponentUndo.revert(root, "runtime").isNotEmpty())
        assertEquals("user", file.readText())
        root.deleteRecursively()
    }

    @Test fun registryValuesAreRestoredAndLaterUserEditsAreKept() {
        val root = prefix()
        val path = File(root, ".wine/user.reg")
        val key = "Software\\LudashiRestoreTest"
        com.winlator.cmod.components.offline.WineRegistryFile(path).apply {
            put(key, "existing", "\"existing\"=\"before\""); save()
        }
        val journal = ComponentUndo.open(root, "registry")
        com.winlator.cmod.components.offline.WineRegistryFile(path).apply {
            put(key, "existing", "\"existing\"=\"component\""); put(key, "added", "\"added\"=\"component\""); save()
        }
        journal.close()
        com.winlator.cmod.components.offline.WineRegistryFile(path).apply {
            put(key, "added", "\"added\"=\"user\""); save()
        }
        assertTrue(ComponentUndo.revert(root, "registry").isNotEmpty())
        val restored = com.winlator.cmod.components.offline.WineRegistryFile(path)
        assertEquals("\"existing\"=\"before\"", restored.get(key, "existing"))
        assertEquals("\"added\"=\"user\"", restored.get(key, "added"))
        root.deleteRecursively()
    }
}
