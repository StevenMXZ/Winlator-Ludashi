package com.winlator.cmod.ui.containers

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.LandscapeMainNavigation
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme

class ContainerUiModel(
    val id: Int,
    val name: String,
    val wineVersion: String,
    val screenSize: String
)

interface ContainersCallbacks {
    fun onAdd()
    fun onRun(containerId: Int)
    fun onEdit(containerId: Int)
    fun onDuplicate(containerId: Int)
    fun onRemove(containerId: Int)
    fun onInfo(containerId: Int)
}

class ContainersComposeHost(
    private val context: Context,
    private val callbacks: ContainersCallbacks
) {
    private val containers = mutableStateOf<List<ContainerUiModel>>(emptyList())

    fun createView(): ComposeView {
        applyAppFullscreen(context as? MainActivity)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    ContainersScreen(containers.value, callbacks)
                }
            }
        }
    }

    fun submitList(value: List<ContainerUiModel>) {
        containers.value = value.toList()
    }
}

@Composable
private fun ContainersScreen(
    containers: List<ContainerUiModel>,
    callbacks: ContainersCallbacks
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity

    DisposableEffect(activity, landscape) {
        if (landscape) {
            activity?.setBottomNavigationVisible(false)
            activity?.setMainToolbarVisible(false)
        }
        onDispose {
            if (landscape && activity?.resources?.configuration?.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                activity?.setBottomNavigationVisible(true)
                activity?.setMainToolbarVisible(true)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (landscape) {
            LandscapeMainNavigation(
                activity = activity,
                selected = R.id.main_menu_containers,
                title = "Containers",
                actionIcon = Icons.Outlined.Add,
                actionDescription = "Add container",
                onAction = callbacks::onAdd
            )
        }

        if (!landscape) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = callbacks::onAdd) {
                    Icon(Icons.Outlined.Add, "Add container")
                }
            }
        }

        if (containers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(if (landscape) 62.dp else 76.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(if (landscape) 30.dp else 38.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("No containers yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Create a container to prepare your first Windows environment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (landscape) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(460.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(items = containers, key = { it.id }) { container ->
                    ContainerCard(container, callbacks, compact = true)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = containers, key = { it.id }) { container ->
                    ContainerCard(container, callbacks, compact = false)
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    container: ContainerUiModel,
    callbacks: ContainersCallbacks,
    compact: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f))
    ) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = 14.dp,
                    top = if (compact) 10.dp else 14.dp,
                    end = 12.dp,
                    bottom = if (compact) 9.dp else 13.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(if (compact) 48.dp else 56.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(if (compact) 25.dp else 30.dp))
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!compact) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Windows 10, 64-bit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        container.wineVersion + "  •  " + container.screenSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    onClick = { callbacks.onRun(container.id) },
                    modifier = Modifier.size(if (compact) 44.dp else 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayArrow, "Run", modifier = Modifier.size(27.dp))
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.outlineVariant) {}
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (compact) {
                    IconButton(onClick = { callbacks.onEdit(container.id) }) { Icon(Icons.Outlined.Edit, "Edit") }
                    IconButton(onClick = { callbacks.onDuplicate(container.id) }) { Icon(Icons.Outlined.ContentCopy, "Duplicate") }
                    IconButton(onClick = { callbacks.onRemove(container.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove") }
                } else {
                    ContainerAction(Icons.Outlined.Edit, "Edit") { callbacks.onEdit(container.id) }
                    ContainerAction(Icons.Outlined.ContentCopy, "Duplicate") { callbacks.onDuplicate(container.id) }
                    ContainerAction(Icons.Outlined.DeleteOutline, "Remove") { callbacks.onRemove(container.id) }
                }
                IconButton(onClick = { callbacks.onInfo(container.id) }) {
                    Icon(Icons.Outlined.Info, "Container info")
                }
            }
        }
    }
}

@Composable
private fun ContainerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
