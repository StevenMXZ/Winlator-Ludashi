package com.winlator.cmod.ui.container

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme

object ContainerOverviewLandscapeComposeHost {
    @JvmStatic
    fun create(context: Context, model: ContainerOverviewModel, callbacks: ContainerOverviewCallbacks): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { LandscapeContainerOverview(model, callbacks) } }
        }
}

@Immutable
private data class LandscapeSection(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun LandscapeContainerOverview(model: ContainerOverviewModel, callbacks: ContainerOverviewCallbacks) {
    val sections = remember(model) {
        listOf(
            LandscapeSection(ContainerOverviewComposeHost.SECTION_SYSTEM, "System", "Wine ${model.wineVersion}", Icons.Outlined.Settings),
            LandscapeSection(ContainerOverviewComposeHost.SECTION_VIDEO, "Video", "${model.renderer}  •  ${model.screenSize}", Icons.Outlined.DesktopWindows),
            LandscapeSection(ContainerOverviewComposeHost.SECTION_AUDIO, "Audio", model.audioDriver, Icons.Outlined.VolumeUp),
            LandscapeSection(ContainerOverviewComposeHost.SECTION_COMPATIBILITY, "Compatibility", model.emulator, Icons.Outlined.VerifiedUser),
            LandscapeSection(ContainerOverviewComposeHost.SECTION_STORAGE, "Storage", "", Icons.Outlined.Folder),
            LandscapeSection(ContainerOverviewComposeHost.SECTION_ADVANCED, "Advanced", "", Icons.Outlined.Tune)
        )
    }
    var selected by remember { mutableStateOf(ContainerOverviewComposeHost.SECTION_SYSTEM) }
    val active = sections.firstOrNull { it.id == selected } ?: sections.first()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = callbacks::onLaunch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(uiStr(R.string.launch_environment), fontWeight = FontWeight.SemiBold) }
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.width(246.dp).fillMaxHeight(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .78f))
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(model.containerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(uiStr(R.string.ui_wine_1_s, model.wineVersion), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    sections.forEach { item -> LandscapeSectionRow(item, selected == item.id) { selected = item.id } }
                }
            }

            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .78f))
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f)) {
                            Box(contentAlignment = Alignment.Center) { Icon(active.icon, null, modifier = Modifier.size(21.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(active.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (active.subtitle.isNotBlank()) Text(active.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f))
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item(key = selected) {
                            when (selected) {
                                ContainerOverviewComposeHost.SECTION_SYSTEM -> ContainerSystemInline(model.containerId, callbacks)
                                ContainerOverviewComposeHost.SECTION_VIDEO,
                                ContainerOverviewComposeHost.SECTION_AUDIO,
                                ContainerOverviewComposeHost.SECTION_COMPATIBILITY -> ContainerRuntimePane(model.containerId, selected)
                                ContainerOverviewComposeHost.SECTION_STORAGE -> ContainerStorageInline(model.containerId, callbacks)
                                ContainerOverviewComposeHost.SECTION_ADVANCED -> ContainerAdvancedPane(model.containerId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeSectionRow(item: LandscapeSection, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(11.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
        }
    }
}
