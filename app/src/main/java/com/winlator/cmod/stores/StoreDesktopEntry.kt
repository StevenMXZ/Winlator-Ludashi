package com.winlator.cmod.stores

internal fun updateStoreDesktop(text: String, section: String, values: Map<String, String>): String {
    val lines = text.lines().toMutableList()
    val heading = "[$section]"
    var start = lines.indexOfFirst { it.trim() == heading }
    if (start < 0) { lines.add(heading); start = lines.lastIndex }
    val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith('[') } ?: lines.size
    val kept = lines.subList(start + 1, end).filter { it.substringBefore('=').trim() !in values.keys }
    return (lines.take(start + 1) + values.map { (key, value) -> "$key=$value" } + kept + lines.drop(end)).joinToString("\n")
}
