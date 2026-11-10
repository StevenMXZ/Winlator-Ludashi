package com.winlator.cmod.ui.library

import com.winlator.cmod.ui.uiStr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.drawerlayout.widget.DrawerLayout
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LandscapePagerCore(
    items: List<LibraryItem>,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks,
    header: @Composable () -> Unit,
    footerActions: @Composable (LibraryItem) -> Unit
) {
    val initialPage = remember(items, selectedShortcutPath.value) {
        items.indexOfFirst { it.shortcutPath == selectedShortcutPath.value }
            .takeIf { it >= 0 } ?: 0
    }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { items.size })
    val item = items[pager.currentPage.coerceIn(items.indices)]
    val environmentLabel = libraryEnvironmentLabel(item)
    val activity = LocalContext.current as? MainActivity

    KeepLandscapeChromeHidden(activity)

    DisposableEffect(activity) {
        val drawer = activity?.findViewById<DrawerLayout>(R.id.DrawerLayout)
        drawer?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        onDispose {
            if (activity?.resources?.configuration?.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                drawer?.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
        }
    }

    LaunchedEffect(items.map { it.id }, selectedShortcutPath.value) {
        val selectedPage = items.indexOfFirst { it.shortcutPath == selectedShortcutPath.value }
        val targetPage = if (selectedPage >= 0) selectedPage else pager.currentPage.coerceIn(items.indices)
        if (pager.currentPage != targetPage) pager.scrollToPage(targetPage)
    }

    LaunchedEffect(pager, items) {
        snapshotFlow { pager.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                items.getOrNull(page)?.let { selectedShortcutPath.value = it.shortcutPath }
            }
    }

    LaunchedEffect(item.id) {
        if (item.coverPath == null) callbacks.onArtworkNeeded(item.shortcutPath, "cover")
        if (item.bannerPath == null) callbacks.onArtworkNeeded(item.shortcutPath, "banner")
    }

    Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)) {
        PagerImage(userArtworkPath(item) ?: item.bannerPath ?: item.coverPath, item.fallbackIcon, Modifier.fillMaxSize().kenBurns(amplitude = 0.04f, pan = 0.5f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(.84f), Color.Black.copy(.48f), Color.Black.copy(.20f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.18f), Color.Transparent, Color.Black.copy(.72f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 12.dp)) {
            header()
            Spacer(Modifier.size(9.dp))
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1.16f)
                        .fillMaxHeight()
                ) {
                    val pageWidth = 214.dp
                    val edgePadding = 8.dp
                    val trailingPadding = (maxWidth - pageWidth - edgePadding).coerceAtLeast(edgePadding)

                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        pageSize = PageSize.Fixed(pageWidth),
                        pageSpacing = 6.dp,
                        contentPadding = PaddingValues(
                            start = edgePadding,
                            end = trailingPadding
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        userScrollEnabled = items.size > 1,
                        beyondViewportPageCount = 2,
                        snapPosition = SnapPosition.Start,
                        key = { page -> items.getOrNull(page)?.id ?: "stale-library-page-$page" }
                    ) { page ->
                        val candidate = items.getOrNull(page) ?: return@HorizontalPager
                        val pageOffset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                            .absoluteValue
                            .coerceIn(0f, 1f)
                        val cardScale = 1f - (0.30f * pageOffset)
                        val selected = pageOffset < 0.5f
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(
                                onClick = { callbacks.onOpen(candidate.shortcutPath) },
                                modifier = Modifier
                                    .size(206.dp, 322.dp)
                                    .scale(cardScale),
                                shape = RoundedCornerShape(16.dp),
                                color = Color.Black.copy(.30f),
                                border = BorderStroke(if (selected) 2.dp else 1.dp, Color.White.copy(if (selected) .88f else .20f))
                            ) {
                                PagerImage(userArtworkPath(candidate) ?: candidate.coverPath ?: candidate.bannerPath ?: candidate.iconPath, candidate.fallbackIcon, Modifier.fillMaxSize().kenBurns(amplitude = 0.05f, periodMs = 18_000, strength = if (selected) 1f else 0f))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(26.dp))
                Column(Modifier.weight(.9f)) {
                    Text(item.name, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(environmentLabel, color = Color.White.copy(.72f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(13.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { callbacks.onOpen(item.shortcutPath) },
                            shape = RoundedCornerShape(11.dp),
                            color = Color.White.copy(.20f),
                            contentColor = Color.White,
                            border = BorderStroke(1.dp, Color.White.copy(.22f))
                        ) { Text(uiStr(R.string.ui_view_details), Modifier.padding(horizontal = 21.dp, vertical = 11.dp)) }
                        Surface(
                            onClick = { callbacks.onRun(item.shortcutPath) },
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(.62f),
                            contentColor = Color.White
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, uiStr(R.string.play)) } }
                        footerActions(item)
                    }
                }
            }
        }
    }
}

private fun userArtworkPath(item: LibraryItem): String? =
    item.iconPath?.takeIf { it.endsWith(".user.png", ignoreCase = true) }

@Composable
private fun PagerImage(path: String?, fallback: Bitmap?, modifier: Modifier) {
    val cacheKey = LibraryImageCache.keyFor(path)
    val image by produceState<Bitmap?>(LibraryImageCache.peek(cacheKey) ?: fallback, cacheKey, fallback) {
        value = withContext(Dispatchers.IO) {
            path?.takeIf { File(it).isFile }?.let { LibraryImageCache.load(it) } ?: fallback
        }
    }
    if (image != null) Image(image!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}
