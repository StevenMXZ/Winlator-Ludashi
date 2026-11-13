package com.winlator.cmod.storage

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal enum class StorageCategory { GAMES, COMPONENTS, CONTAINERS, DOWNLOADS, CACHE, BACKUPS, LOGS, SYSTEM, OTHER }
internal data class StorageTarget(val file: File, val category: StorageCategory, val title: String, val source: String = "", val id: String = "", val removable: Boolean = false)
internal data class StorageEntry(val target: StorageTarget, val bytes: Long)
internal data class StorageSnapshot(val entries: List<StorageEntry>, val unreadable: Int = 0) {
    val total get() = entries.sumOf { it.bytes }
    fun bytes(category: StorageCategory) = entries.filter { it.target.category == category }.sumOf { it.bytes }
}
internal object StorageScanner {
    fun measure(roots: List<File>, targets: List<StorageTarget>, check: () -> Unit = {}): StorageSnapshot {
        val byPath = targets.filterNot { Files.isSymbolicLink(it.file.toPath()) }.associateBy { it.file.canonicalFile.toPath() }
        val normalized = roots.filter { it.exists() && !Files.isSymbolicLink(it.toPath()) }.map { it.canonicalFile.toPath() }.distinct().sortedBy { it.nameCount }
        val walks = mutableListOf<Path>()
        normalized.forEach { path -> if (walks.none { path.startsWith(it) }) walks.add(path) }
        val sizes = mutableMapOf<StorageTarget, Long>()
        val seen = mutableSetOf<Any>()
        var unreadable = 0
        for (root in walks) {
            check()
            val inherited = generateSequence(root) { it.parent }.firstNotNullOfOrNull { byPath[it] }
            val stack = java.util.ArrayDeque<StorageTarget>()
            inherited?.let { stack.add(it) }
            val fallback = StorageTarget(root.toFile(), StorageCategory.OTHER, root.fileName.toString())
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        check(); stack.addLast(byPath[dir] ?: stack.peekLast() ?: fallback)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        check()
                        if (attrs.isRegularFile && (attrs.fileKey() == null || seen.add(attrs.fileKey()))) {
                            val target = byPath[file] ?: stack.peekLast() ?: fallback
                            sizes[target] = (sizes[target] ?: 0) + attrs.size()
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        if (error != null) unreadable++
                        stack.removeLast(); return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult { check(); unreadable++; return FileVisitResult.CONTINUE }
                })
            } catch (_: IOException) { unreadable++ }
        }
        return StorageSnapshot(sizes.map { StorageEntry(it.key, it.value) }.sortedByDescending { it.bytes }, unreadable)
    }
    fun clear(root: File, protected: List<File> = emptyList(), check: () -> Unit = {}) {
        require(!Files.isSymbolicLink(root.toPath())) { "Unsafe cleanup directory" }
        val base = root.canonicalFile.toPath()
        if (!Files.exists(base)) return
        val keep = protected.map { it.canonicalFile.toPath() }
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check()
                return if (keep.any { dir.startsWith(it) }) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(); if (keep.none { file.startsWith(it) }) Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                check()
                if (dir != base && keep.none { it.startsWith(dir) || dir.startsWith(it) }) Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
