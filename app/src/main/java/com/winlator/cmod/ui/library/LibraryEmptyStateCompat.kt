package com.winlator.cmod.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

@Composable
internal fun LibraryRootWithoutEmptyDescription(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks,
    showAddGames: MutableState<Boolean>,
    steamFilterAvailable: Boolean
) {
    LibraryRoot(items, grid, query, selectedShortcutPath, callbacks, showAddGames, steamFilterAvailable)
}
