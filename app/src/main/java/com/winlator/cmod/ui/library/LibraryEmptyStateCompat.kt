package com.winlator.cmod.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

@Composable
internal fun LibraryRootWithoutEmptyDescription(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks
) {
    LibraryRoot(items, grid, query, selectedShortcutPath, callbacks)
}
