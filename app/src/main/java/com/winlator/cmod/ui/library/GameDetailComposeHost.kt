package com.winlator.cmod.ui.library

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface GameDetailCallbacks {
    fun onPlay()
    fun onConfigure()
    fun onArguments()
    fun onSaves()
    fun onFavorite(favorite: Boolean)
    fun onRemove()
    fun onHome()
}

interface SteamDetailCallbacks : GameDetailCallbacks { fun onSteamMode(mode: String); fun onSteamExecutable() }

// Layout and statistics adapted from teldommm/WinLite. Ludashi keeps its original
// artwork selection/crop (including offline icons) and all six user-selected palettes.
object GameDetailComposeHost {
    @JvmStatic
    fun createSteam(context: Context, title: String, artwork: String?, id: Int, callbacks: GameDetailCallbacks): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme {
                val revision = com.winlator.cmod.steam.SteamGames.version
                val shortcut by produceState<Shortcut?>(null, id, revision) {
                    value = withContext(Dispatchers.IO) { com.winlator.cmod.steam.SteamGames.shortcut(context, id) }
                }
                GameDetailScreen(title, "Steam", artwork, null, context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false), shortcut, callbacks, id)
            } }
        }
    @JvmStatic
    fun create(context: Context, title: String, subtitle: String, artworkPath: String?, fallback: Bitmap?, favorite: Boolean, shortcut: Shortcut, callbacks: GameDetailCallbacks): ComposeView {
        applyAppFullscreen(context as? MainActivity)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { CompositionLocalProvider(LocalArtworkMotion provides rememberArtworkMotionEnabled()) { GameDetailScreen(title, subtitle, artworkPath, fallback, favorite, shortcut, callbacks) } } }
        }
    }
}

private val LocalSteamInfoId = compositionLocalOf<Int?> { null }
private val LocalSteamDetail = compositionLocalOf { false }
private val LocalSteamReady = compositionLocalOf { true }
private val LocalSteamExecutable = compositionLocalOf { "" }
private val LocalSteamMode = compositionLocalOf { "RealSteam" }

@Composable
private fun GameDetailScreen(title: String, subtitle: String, artworkPath: String?, fallback: Bitmap?, initialFavorite: Boolean, shortcut: Shortcut?, callbacks: GameDetailCallbacks, steamId: Int? = null) {
    val activity = LocalContext.current as? MainActivity
    val context = LocalContext.current.applicationContext
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    if (landscape) KeepLandscapeChromeHidden(activity, restoreChromeOnPortrait = false)
    DisposableEffect(activity, landscape) {
        activity?.setBottomNavigationVisible(false)
        activity?.setMainToolbarVisible(false)
        onDispose { }
    }
    val artworkRevision = LibraryImageCache.revision
    val key = LibraryImageCache.keyFor(artworkPath)
    val artwork by produceState(LibraryImageCache.peek(key) ?: fallback, key, fallback, artworkRevision) {
        value = withContext(Dispatchers.IO) {
            LibraryImageCache.load(artworkPath) ?: fallback ?: steamId?.let { id ->
                runCatching {
                    val request = com.bumptech.glide.Glide.with(context).downloadOnly()
                        .load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg").submit()
                    try { android.graphics.BitmapFactory.decodeFile(request.get(20, java.util.concurrent.TimeUnit.SECONDS).path) }
                    finally { com.bumptech.glide.Glide.with(context).clear(request) }
                }.getOrNull()
            }
        }
    }
    var resumeSerial by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeSerial++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val stats by produceState(GameStats(), shortcut?.file?.path, resumeSerial) {
        if (shortcut == null) return@produceState
        val loaded = withContext(Dispatchers.IO) { GameStatsLoader.load(context, shortcut) }
        value = loaded
        value = withContext(Dispatchers.IO) {
            val cached = GameStatsLoader.cachedSize(context, shortcut)
            loaded.copy(sizeText = if (cached?.fresh == true) cached.text else GameStatsLoader.sizeText(context, shortcut))
        }
    }
    var favorite by remember(initialFavorite) { mutableStateOf(initialFavorite) }
    val toggle = { favorite = !favorite; callbacks.onFavorite(favorite) }
    val configured = true
    val executable = shortcut?.getExtra("steamExecutable").orEmpty()
    androidx.compose.runtime.CompositionLocalProvider(LocalSteamExecutable provides executable, LocalSteamInfoId provides steamId, LocalSteamDetail provides (steamId != null), LocalSteamReady provides configured, LocalSteamMode provides (steamId?.let { com.winlator.cmod.steam.SteamSettings.mode(context, it) } ?: "RealSteam")) {
    if (landscape) LandscapeDetail(title, subtitle, artwork, favorite, stats, callbacks, toggle)
    else PortraitDetail(title, subtitle, artwork, favorite, stats, callbacks, toggle)
    }
}

@Composable
private fun Artwork(artwork: Bitmap?, modifier: Modifier) {
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.background)) {
        if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize().kenBurns(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.22f), Color.Transparent, Color.Black.copy(.88f)))))
    }
}

@Composable
private fun ArtControls(favorite: Boolean, toggle: () -> Unit) {
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        ArtButton({ activity?.onBackPressedDispatcher?.onBackPressed() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) }
        ArtButton(toggle) { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, stringResource(R.string.ui_favorite)) }
    }
}

@Composable
private fun ArtButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick, Modifier.size(44.dp), shape = WinZShapes.Medium, color = Color.Black.copy(.55f), contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(.18f))) { Box(contentAlignment = Alignment.Center) { content() } }
}

@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: Bitmap?, favorite: Boolean, stats: GameStats, callbacks: GameDetailCallbacks, toggle: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Artwork(artwork, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            ArtControls(favorite, toggle)
            Column(Modifier.align(Alignment.TopStart).padding(start = 76.dp, end = 76.dp, top = 18.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(.75f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            val compact = maxWidth < 700.dp || maxHeight < 360.dp
            val available = (maxWidth - 54.dp).coerceAtLeast(200.dp)
            val panelWidth = minOf(available * .55f, if (compact) 340.dp else 420.dp)
            val statsWidth = (available - panelWidth).coerceAtLeast(100.dp)
            Surface(Modifier.align(Alignment.BottomEnd).padding(18.dp).width(panelWidth).heightIn(max = (maxHeight - 116.dp).coerceAtLeast(100.dp)),
                shape = WinZShapes.ExtraLarge, color = Color.Black.copy(.48f), border = BorderStroke(1.dp, Color.White.copy(.18f))) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlayButton(callbacks::onPlay, { mode -> (callbacks as? SteamDetailCallbacks)?.onSteamMode(mode) }, { (callbacks as? SteamDetailCallbacks)?.onSteamExecutable() })
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LandscapeAction(Icons.Outlined.Settings, stringResource(R.string.ui_configure), Modifier.weight(1f), callbacks::onConfigure)
                        LandscapeAction(Icons.Outlined.Dns, stringResource(R.string.ui_container), Modifier.weight(1f), callbacks::onArguments)
                        LandscapeAction(Icons.Outlined.Backup, savesLabel(), Modifier.weight(1f), callbacks::onSaves)
                        LandscapeAction(Icons.Outlined.Home, stringResource(R.string.ui_home), Modifier.weight(1f), callbacks::onHome)
                        LandscapeAction(Icons.Outlined.DeleteOutline, if (LocalSteamDetail.current) stringResource(R.string.steam_ui_uninstall) else stringResource(R.string.remove), Modifier.weight(1f), callbacks::onRemove, true)
                    }
                    LocalSteamInfoId.current?.let { com.winlator.cmod.steam.SteamGameInfoPanel(it) }
                }
            }
            if (stats.hasAny) StatsGrid(stats, Modifier.align(Alignment.BottomStart).padding(18.dp).width(statsWidth).heightIn(max = (maxHeight - 128.dp).coerceAtLeast(80.dp)).verticalScroll(rememberScrollState()), onArtwork = true)
        }
    }
}

@Composable
private fun PortraitDetail(title: String, subtitle: String, artwork: Bitmap?, favorite: Boolean, stats: GameStats, callbacks: GameDetailCallbacks, toggle: () -> Unit) {
    // One scrolling column: title, two-column statistics and actions stay reachable at large fonts.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.28f)) {
            Artwork(artwork, Modifier.fillMaxSize())
            ArtControls(favorite, toggle)
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color.White.copy(.75f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stats.hasAny) StatsGrid(stats, Modifier.fillMaxWidth())
            PlayButton(callbacks::onPlay, { mode -> (callbacks as? SteamDetailCallbacks)?.onSteamMode(mode) }, { (callbacks as? SteamDetailCallbacks)?.onSteamExecutable() })
            DetailAction(Icons.Outlined.Settings, stringResource(R.string.ui_configure), callbacks::onConfigure)
            DetailAction(Icons.Outlined.Dns, stringResource(R.string.ui_enter_container), callbacks::onArguments)
            DetailAction(Icons.Outlined.Backup, savesLabel(), callbacks::onSaves)
            DetailAction(Icons.Outlined.Home, stringResource(R.string.ui_add_to_home), callbacks::onHome)
            DetailAction(Icons.Outlined.DeleteOutline, if (LocalSteamDetail.current) stringResource(R.string.steam_ui_uninstall) else stringResource(R.string.remove), callbacks::onRemove, true)
            LocalSteamInfoId.current?.let { com.winlator.cmod.steam.SteamGameInfoPanel(it) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun savesLabel(): String = if (LocalSteamDetail.current && LocalSteamMode.current == "RealSteam")
    stringResource(R.string.steam_cloud_saves) else stringResource(R.string.ui_saves)

@Composable
private fun PlayButton(click: () -> Unit, chooseMode: (String) -> Unit, chooseExecutable: () -> Unit) {
    val steam = LocalSteamDetail.current
    val mode = LocalSteamMode.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(click, enabled = LocalSteamReady.current, modifier = Modifier.weight(1f).heightIn(min = if (steam) 44.dp else 52.dp), shape = WinZShapes.Medium) {
            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp))
            Text(if (!LocalSteamReady.current) stringResource(R.string.steam_ui_configure_first)
                else if (steam) stringResource(if (mode == "RealSteam") R.string.steam_ui_client else R.string.steam_ui_offline)
                else stringResource(R.string.ui_play), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (steam) IconButton(onClick = chooseExecutable, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.steam_ui_choose_exe), Modifier.size(20.dp))
        }
        if (steam) Box {
            FilledTonalIconButton(onClick = { expanded = true }, enabled = !com.winlator.cmod.steam.SteamSession.gameActive) {
                Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.steam_ui_launch))
            }
            DropdownMenu(expanded, { expanded = false }) {
                listOf("RealSteam" to R.string.steam_ui_client, "Goldberg" to R.string.steam_ui_offline).forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; chooseMode(value) },
                        trailingIcon = { if (mode == value) Icon(Icons.Outlined.Check, null) })
                }
            }
        }
    }
    }
}

@Composable
private fun LandscapeAction(icon: ImageVector, label: String, modifier: Modifier, click: () -> Unit, destructive: Boolean = false) {
    Surface(click, modifier.fillMaxHeight(), shape = WinZShapes.Medium, color = Color.White.copy(.10f), contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(.16f))) {
        Column(Modifier.padding(horizontal = 3.dp, vertical = if (LocalSteamDetail.current) 6.dp else 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(if (LocalSteamDetail.current) 20.dp else 23.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, click: () -> Unit, destructive: Boolean = false) {
    Surface(click, Modifier.fillMaxWidth().heightIn(min = if (LocalSteamDetail.current) 48.dp else 54.dp), shape = WinZShapes.Medium, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class Stat(val icon: ImageVector, val label: String, val value: String)
@Composable
private fun StatsGrid(stats: GameStats, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val entries = buildList {
        if (stats.playtimeMillis > 0) add(Stat(Icons.Outlined.Schedule, stringResource(R.string.ui_stat_playtime), formatPlaytime(stats.playtimeMillis)))
        if (stats.playCount > 0) add(Stat(Icons.Outlined.SportsEsports, stringResource(R.string.ui_stat_plays), stats.playCount.toString()))
        if (stats.lastPlayedMillis > 0) add(Stat(Icons.Outlined.History, stringResource(R.string.ui_stat_last_played), formatLastPlayed(stats.lastPlayedMillis)))
        stats.sizeText?.let { add(Stat(Icons.Outlined.Storage, stringResource(R.string.ui_stat_size), it)) }
    }
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= (if (onArtwork) 200.dp else 280.dp) && LocalConfiguration.current.fontScale <= 1.3f) 2 else 1
        val showIcons = maxWidth >= 280.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        Surface(Modifier.weight(1f), shape = WinZShapes.Small, color = if (onArtwork) Color.Black.copy(.58f) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (onArtwork) Color.White else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, if (onArtwork) Color.White.copy(.16f) else MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (showIcons) { Icon(entry.icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(.7f))
                                    Text(entry.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
