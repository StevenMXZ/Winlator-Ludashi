package com.winlator.cmod.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ActionsUpdatesTest {
    private val sha = "a".repeat(40)
    private val old = ActionsUpdates.Build("5.0", sha, 117, 1)

    @Test fun commitTitleAndRanges() {
        assertEquals("Fix updater", ActionsUpdates.commitTitle("Fix updater\n\nDetails"))
        assertEquals("", ActionsUpdates.commitTitle(null))
        val ranges = ActionsArtifactTransfer.spans(11, 4)
        assertEquals(11L, ranges.sumOf { it.length })
        assertEquals(0L, ranges.first().start)
        assertEquals(10L, ranges.last().end)
        assertTrue(ranges.zipWithNext().all { (a,b) -> a.end + 1 == b.start })
        assertTrue(ActionsArtifactTransfer.rangeMatches("bytes 2-5/11", 2, 5, 11))
        assertFalse(ActionsArtifactTransfer.rangeMatches("bytes 0-5/11", 2, 5, 11))
    }

    @Test fun compactDeltaRequiresExactBaseAndArtifactMetadata() {
        val base = "b".repeat(64)
        fun annotation(message: String, title: String = "Ludashi delta metadata") =
            ActionsUpdates.DeltaMetadata(title, "notice", message)
        fun matches(vararg values: ActionsUpdates.DeltaMetadata) = ActionsUpdates.matchesDeltaMetadata(
            values.toList(), 100, 2, 55, base)
        val valid = annotation("v1:100:2:55:$base")
        assertTrue(matches(valid))
        assertFalse(matches())
        assertFalse(matches(annotation("v1:100:1:55:$base")))
        assertFalse(matches(annotation("v1:100:2:56:$base")))
        assertFalse(matches(annotation("v1:101:2:55:$base")))
        assertFalse(matches(annotation("v1:100:2:55:" + "c".repeat(64))))
        assertFalse(matches(annotation("v1:100:2:55:$base", "Other notice")))
        assertFalse(matches(valid, valid))
    }

    @Test fun signedBuildIdentityAndOrdering() {
        assertEquals(old, ActionsUpdates.Build.parse("v1:5.0:" + sha + ":117:1"))
        assertNull(ActionsUpdates.Build.parse("local"))
        assertNull(ActionsUpdates.Build.parse("v1:other:" + sha + ":117:1"))
        assertNull(ActionsUpdates.Build.parse("v1:5.0:" + sha + ":0:1"))
        assertFalse(old.newerThan(old))
        assertTrue(old.copy(attempt = 2).newerThan(old))
        assertTrue(old.copy(number = 118).newerThan(old))
        assertFalse(old.copy(number = 116, attempt = 10).newerThan(old))
        assertEquals("5.0", ActionsUpdates.sourceBranch(old))
        assertEquals(ActionsUpdates.TEST_BRANCH, ActionsUpdates.sourceBranch(old.copy(branch = ActionsUpdates.TEST_BRANCH)))
    }

    @Test fun neverOfferDowngradeOrUnknownEqualVersion() {
        val next = old.copy(number = 118)
        assertTrue(ActionsUpdates.acceptableVersion(67, 67, old, next))
        assertFalse(ActionsUpdates.acceptableVersion(67, 66, old, next))
        assertFalse(ActionsUpdates.acceptableVersion(67, 67, old, old))
        assertFalse(ActionsUpdates.acceptableVersion(67, 67, null, next))
        assertTrue(ActionsUpdates.acceptableVersion(67, 68, null, next))
        assertFalse(ActionsUpdates.acceptableVersion(67, 68, old, next.copy(branch = ActionsUpdates.TEST_BRANCH)))
    }

    @Test fun artifactExtractionRejectsExtraFilesTraversalSizeAndCancellation() {
        val dir = Files.createTempDirectory("actions-updates").toFile()
        try {
            val zip = File(dir, "artifact.zip")
            val apk = File(dir, "update.apk")
            fun archive(vararg names: String) {
                ZipOutputStream(zip.outputStream()).use { out ->
                    names.forEach { name ->
                        out.putNextEntry(ZipEntry(name))
                        out.write(byteArrayOf(1, 2, 3))
                        out.closeEntry()
                    }
                }
            }
            archive("app-release.apk")
            ActionsUpdates.extract(zip, apk, 3, AtomicBoolean(false))
            assertArrayEquals(byteArrayOf(1, 2, 3), apk.readBytes())
            fun rejected(limit: Long, cancelled: Boolean) {
                assertTrue(runCatching { ActionsUpdates.extract(zip, apk, limit, AtomicBoolean(cancelled)) }.isFailure)
                assertFalse(apk.exists())
            }
            rejected(2, false)
            rejected(3, true)
            archive("../app-release.apk"); rejected(3, false)
            archive("app-release.apk", "extra"); rejected(3, false)
        } finally { dir.deleteRecursively() }
    }
}
