package com.winlator.cmod.stores

import java.io.File
import java.util.Locale

internal object StoreExecutables {
    fun helper(path: String): Boolean {
        val parts = path.replace('\\', '/').lowercase(Locale.ROOT).split('/')
        val name = parts.last()
        return parts.dropLast(1).any { it in setOf("__redist", "_redist", "redist", "redistributables", "support", "prerequisites") } ||
            Regex("^(unins|uninstall|unitycrashhandler|crashreport|crashhandler|vc_redist|vcredist|dxsetup|scriptinterpreter|dotnetfx)").containsMatchIn(name)
    }
    fun ordered(paths: List<String>, title: String): List<String> {
        val normalized = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return paths.distinct().sortedWith(compareBy<String> { helper(it) }.thenByDescending {
            File(it).nameWithoutExtension.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") == normalized
        }.thenBy { it.count { char -> char == '/' || char == '\\' } }.thenBy { it.lowercase(Locale.ROOT) })
    }
    fun default(paths: List<String>, title: String): String {
        val eligible = ordered(paths, title).filterNot(::helper)
        val normalized = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return eligible.firstOrNull { File(it).nameWithoutExtension.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") == normalized }
            ?: eligible.singleOrNull().orEmpty()
    }
}
