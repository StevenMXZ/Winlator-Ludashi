package com.winlator.cmod.ui.settings

internal enum class DxvkAsyncMode { NONE, ASYNC, GPL_ASYNC }

internal fun dxvkAsyncMode(version: String): DxvkAsyncMode = when {
    version.contains("gplasync", ignoreCase = true) -> DxvkAsyncMode.GPL_ASYNC
    version.contains("async", ignoreCase = true) -> DxvkAsyncMode.ASYNC
    else -> DxvkAsyncMode.NONE
}

internal fun envValue(raw: String, key: String): String? = raw
    .split(' ')
    .firstOrNull { it.startsWith("$key=") }
    ?.substringAfter('=')

internal fun envPut(raw: String, key: String, value: String?): String {
    val prefix = "$key="
    val items = raw.split(' ').filter(String::isNotBlank).filterNot { it.startsWith(prefix) }.toMutableList()
    if (!value.isNullOrBlank()) items.add("$key=${value.replace(" ", "")}")
    return items.joinToString(" ")
}

internal fun cleanContainerEnvironment(raw: String): String = envPut(raw, "TU_DEBUG", null)

internal fun isTurnipDriver(version: String): Boolean {
    val value = version.lowercase()
    return value.contains("turnip") || value.startsWith("tu-") || value.startsWith("mesa-turnip")
}
