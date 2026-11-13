package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.uiStr
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.ExpandMore
import android.graphics.Bitmap
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.steam.SteamActivity
import com.winlator.cmod.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()

@Composable
internal fun LibraryRoot(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    cb: LibraryCallbacks,
    showAddGames: MutableState<Boolean>,
    steamFilterAvailable: Boolean
) {
    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.All.name) }
    val selectedFilter = LibraryFilter.valueOf(filterName)
    val filter = if (selectedFilter == LibraryFilter.Steam) LibraryFilter.All else selectedFilter
    val context = LocalContext.current
    val storePrefs = remember(context) { context.getSharedPreferences("library_compose_state", 0) }
    var storeMask by rememberSaveable { mutableStateOf(storePrefs.getInt("store-mask", 31)) }
    val changeStores: (Int) -> Unit = { mask -> storeMask = mask; storePrefs.edit().putInt("store-mask", mask).apply() }
    LaunchedEffect(Unit) {
        if (storePrefs.getInt("store-mask-version", 0) < 2) {
            if (storeMask == 7) changeStores(31)
            storePrefs.edit().putInt("store-mask-version", 2).apply()
        }
        if (filterName == LibraryFilter.Steam.name) {
            filterName = LibraryFilter.All.name
            changeStores(if (steamFilterAvailable) 2 else 31)
        }
    }
    val visible = remember(items, filter, query, storeMask) {
        val source = filterLibraryStores(filterLibraryItems(items, filter), storeMask)
        if (query.isBlank()) source else source.filter { it.name.contains(query, true) }
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity, landscape) {
        activity?.setBottomNavigationVisible(!landscape)
        activity?.setMainToolbarVisible(!landscape)
        onDispose {
            activity?.setBottomNavigationVisible(true)
            activity?.setMainToolbarVisible(true)
        }
    }

    if (showAddGames.value) {
        ModalBottomSheet(
            onDismissRequest = { showAddGames.value = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LibraryAddGamesChoice("Stores", Icons.Outlined.Storefront) {
                    showAddGames.value = false
                    activity?.startActivity(Intent(activity, com.winlator.cmod.steam.StoresActivity::class.java))
                }
                LibraryAddGamesChoice("File Manager", Icons.Outlined.FolderOpen) {
                    showAddGames.value = false
                    activity?.navigateToMainDestination(R.id.main_menu_file_manager)
                }
            }
        }
    }

    if (landscape && visible.isNotEmpty() && !grid) {
        var menu by remember { mutableStateOf<LibraryItem?>(null) }
        LandscapePagerCore(
            items = visible,
            selectedShortcutPath = selectedShortcutPath,
            callbacks = cb,
            header = {
                LibraryLandscapeHeader(
                    activity = activity,
                    grid = grid,
                    onArtwork = true,
                    onAddGames = { showAddGames.value = true },
                    onGridViewChanged = cb::onGridViewChanged
                )
                Spacer(Modifier.height(7.dp))
                LibraryFilters(filter, { filterName = it.name }, storeMask, changeStores)
            },
            footerActions = { item ->
                IconButton(onClick = { menu = item }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_more_options), tint = Color.White) }
            }
        )
        menu?.let { LibraryItemMenuCompat(it, cb) { menu = null } }
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp)) {
        if (landscape) {
            LibraryLandscapeHeader(
                activity = activity,
                grid = grid,
                onArtwork = false,
                onAddGames = { showAddGames.value = true },
                onGridViewChanged = cb::onGridViewChanged
            )
            Spacer(Modifier.height(7.dp))
        }
        LibraryFilters(filter, { filterName = it.name }, storeMask, changeStores)
        if (visible.isEmpty()) {
            if (query.isNotBlank() || (filter != LibraryFilter.All && items.isNotEmpty())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) uiStr(R.string.ui_no_games_match_your_search) else uiStr(R.string.ui_no_games_in_this_section),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { showAddGames.value = true },
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(34.dp)) }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(uiStr(R.string.ui_add_games), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (grid) 172.dp else 360.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    if (grid) CoverArtworkCard(item, cb) else CompactArtworkCard(item, cb)
                }
            }
        }
    }
}

@Composable
private fun LibraryLandscapeHeader(
    activity: MainActivity?,
    grid: Boolean,
    onArtwork: Boolean,
    onAddGames: () -> Unit,
    onGridViewChanged: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            uiStr(R.string.library),
            color = if (onArtwork) Color.White else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        LibraryTopIcon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, false) {
            onGridViewChanged(!grid)
        }
        LibraryTopIcon(Icons.Outlined.Add, false, onAddGames)
        LibraryTopIcon(Icons.Outlined.Home, true) {}
        LibraryTopIcon(Icons.Outlined.Dns, false) { activity?.navigateToMainDestination(R.id.main_menu_containers) }
        LibraryTopIcon(Icons.Outlined.SportsEsports, false) { activity?.navigateToMainDestination(R.id.main_menu_input_controls) }
        LibraryTopIcon(Icons.Outlined.Settings, false) { activity?.navigateToMainDestination(R.id.main_menu_settings) }
        LibraryOrientationMenu(activity)
    }
}

@Composable
private fun LibraryOrientationMenu(activity: MainActivity?) {
    var expanded by remember { mutableStateOf(false) }
    var orientationRevision by remember { mutableStateOf(0) }
    val orientationState = remember(activity, orientationRevision) {
        Triple(
            activity?.isOrientationLocked ?: false,
            activity?.isVerticalModeEnabled ?: false,
            activity?.isHorizontalModeEnabled ?: false
        )
    }

    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.padding(horizontal = 3.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_orientation), null, Modifier.size(23.dp), tint = Color.White.copy(alpha = .68f))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OrientationToggleMenuItem(uiStr(R.string.ui_lock_screen_orientation), orientationState.first) {
                activity?.toggleOrientationLock()
                orientationRevision++
            }
            OrientationToggleMenuItem(uiStr(R.string.ui_vertical_mode), orientationState.second) {
                activity?.toggleVerticalMode()
                orientationRevision++
            }
            OrientationToggleMenuItem(uiStr(R.string.ui_horizontal_mode), orientationState.third) {
                activity?.toggleHorizontalMode()
                orientationRevision++
            }
        }
    }
}

@Composable
private fun OrientationToggleMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        onClick = onClick
    )
}

@Composable
private fun LibraryTopIcon(icon: ImageVector, selected: Boolean, click: () -> Unit) {
    val whiteTheme = MaterialTheme.colorScheme.background.luminance() > .65f
    val background = if (whiteTheme) Color.Black.copy(if (selected) .90f else .78f)
    else if (selected) Color.White.copy(.16f) else Color.Transparent
    val content = if (whiteTheme) Color.White else Color.White.copy(if (selected) 1f else .68f)
    Surface(
        onClick = click,
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = content
    ) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(23.dp)) } }
}

@Composable
private fun LibraryAddGamesChoice(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(25.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun LibraryStoreLabel(item: LibraryItem, onArtwork: Boolean) {
    val gog = item.shortcutPath.startsWith("gog://")
    val steam = item.shortcutPath.startsWith("steam://")
    val extra = com.winlator.cmod.stores.LudashiStore.fromPath(item.shortcutPath)
    val color = if (onArtwork) Color.White.copy(.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    if (!gog && !steam && extra == null) { Text(item.containerName, color = color, maxLines = 1); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (gog) Color(0xFF654080).copy(.75f) else MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(if (gog) R.drawable.ic_gog else extra?.icon ?: R.drawable.ic_steam), null, Modifier.size(16.dp), tint = if (gog || extra != null) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (gog) "GOG" else extra?.title ?: "Steam", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("· " + uiStr(if (item.containerName.endsWith("· Installed")) R.string.gog_installed else R.string.gog_not_installed), color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun LibraryFilters(filter: LibraryFilter, select: (LibraryFilter) -> Unit, mask: Int, stores: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.padding(vertical = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(LibraryFilter.All, LibraryFilter.Favorites, LibraryFilter.Recent).forEach { option ->
            LibraryFilterChip(option.name, option == filter) { select(option) }
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Outlined.SportsEsports, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(uiStr(R.string.steam_ui_stores)); Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text(uiStr(R.string.gog_all_stores)) }, leadingIcon = { Checkbox(mask == 31, null) }, onClick = { stores(if (mask == 31) 0 else 31) })
                listOf(1 to uiStr(R.string.gog_local_games), 2 to "Steam", 4 to "GOG", 8 to "itch.io", 16 to "Amazon").forEach { (bit, name) ->
                    DropdownMenuItem(text = { Text(name) }, leadingIcon = { Checkbox(mask and bit != 0, null) }, onClick = { stores(mask xor bit) })
                }
                DropdownMenuItem(text = { Text(uiStr(R.string.gog_manage_stores)) }, onClick = {
                    expanded = false; context.startActivity(Intent(context, com.winlator.cmod.steam.StoresActivity::class.java))
                })
            }
        }
    }
}

@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, click: () -> Unit) {
    Surface(
        onClick = click,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Text(label, Modifier.padding(horizontal = 15.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().height(92.dp).combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            ArtworkCompat(
                userArtworkPath(item) ?: item.bannerPath ?: item.coverPath ?: item.iconPath,
                item.fallbackIcon,
                Modifier.fillMaxSize().alpha(.52f)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(.38f),
                            MaterialTheme.colorScheme.surface.copy(.82f),
                            MaterialTheme.colorScheme.surface.copy(.96f)
                        )
                    )
                )
            )
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkCompat(
                    userArtworkPath(item) ?: item.coverPath ?: item.bannerPath ?: item.iconPath,
                    item.fallbackIcon,
                    Modifier.size(68.dp).clip(RoundedCornerShape(11.dp))
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LibraryStoreLabel(item, false)
                }
                PlayCompat(item, cb, false)
                MenuButtonCompat(item, cb, false)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverArtworkCard(item: LibraryItem, cb: LibraryCallbacks) {
    RequestArtworkCompat(item, cb)
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { cb.onOpen(item.shortcutPath) },
            onLongClick = { cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS) }
        ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(Modifier.aspectRatio(.72f)) {
            ArtworkCompat(userArtworkPath(item) ?: item.coverPath ?: item.bannerPath ?: item.iconPath, item.fallbackIcon, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 50.dp, bottom = 13.dp)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LibraryStoreLabel(item, true)
            }
            Box(Modifier.align(Alignment.TopEnd)) { MenuButtonCompat(item, cb, true) }
            Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)) { PlayCompat(item, cb, true) }
        }
    }
}

@Composable
private fun RequestArtworkCompat(item: LibraryItem, cb: LibraryCallbacks) {
    LaunchedEffect(item.id, item.coverPath, item.bannerPath) {
        if (item.coverPath == null) cb.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) cb.onArtworkNeeded(item.shortcutPath, "banner")
    }
}

private fun userArtworkPath(item: LibraryItem): String? =
    item.iconPath?.takeIf { it.endsWith(".user.png", ignoreCase = true) }

@Composable
private fun ArtworkCompat(path: String?, fallback: Bitmap?, modifier: Modifier) {
    val artworkRevision = LibraryImageCache.revision
    val cacheKey = remember(path, artworkRevision) { LibraryImageCache.thumbnailKeyFor(path) }
    val bitmap by produceState<Bitmap?>(LibraryImageCache.peek(cacheKey) ?: fallback, cacheKey, fallback) {
        value = withContext(Dispatchers.IO) {
            path?.let(LibraryImageCache::loadThumbnail) ?: fallback
        }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun PlayCompat(item: LibraryItem, cb: LibraryCallbacks, overlay: Boolean) {
    Surface(
        onClick = { cb.onRun(item.shortcutPath) },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = if (overlay) Color.Black.copy(.72f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSurface
    ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, uiStr(R.string.play)) } }
}

@Composable
private fun MenuButtonCompat(item: LibraryItem, cb: LibraryCallbacks, light: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_more_options), tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) LibraryItemMenuCompat(item, cb) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryItemMenuCompat(item: LibraryItem, cb: LibraryCallbacks, close: () -> Unit) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .42f)
            ) {}
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = if (landscape) 920.dp else 760.dp)
                .padding(horizontal = if (landscape) 22.dp else 16.dp)
                .padding(bottom = if (landscape) 10.dp else 24.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Text(
                item.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
            )
            val favoriteLabel = if (item.favorite) "Unfavorite" else "Favorite"
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(
                    if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    favoriteLabel,
                    Modifier.weight(1f),
                    horizontal = landscape
                ) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_FAVORITE)
                }
                LibraryActionTileCompat(Icons.Outlined.Settings, "Configure", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_SETTINGS)
                }
                LibraryActionTileCompat(Icons.Outlined.Photo, "Artwork", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_ICON)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryActionTileCompat(Icons.Outlined.Home, "Home screen", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_HOME)
                }
                LibraryActionTileCompat(Icons.Outlined.ContentCopy, "Clone", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_CLONE)
                }
                LibraryActionTileCompat(Icons.Outlined.FileUpload, "Export", Modifier.weight(1f), horizontal = landscape) {
                    close()
                    cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_EXPORT)
                }
            }
            LibraryActionTileCompat(
                Icons.Outlined.DeleteOutline,
                "Remove from library",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                destructive = true,
                horizontal = landscape
            ) {
                close()
                cb.onAction(item.shortcutPath, LibraryComposeHost.ACTION_REMOVE)
            }
        }
    }
}

@Composable
private fun LibraryActionTileCompat(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    destructive: Boolean = false,
    horizontal: Boolean = false,
    click: () -> Unit
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = click,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (destructive)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = .22f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
        border = BorderStroke(
            1.dp,
            if (destructive) MaterialTheme.colorScheme.error.copy(alpha = .46f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .70f)
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (horizontal) 13.dp else 12.dp,
                vertical = if (horizontal) 9.dp else 10.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(if (horizontal) 21.dp else 23.dp), tint = tint)
            Spacer(Modifier.width(9.dp))
            Text(
                label,
                style = if (horizontal) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
