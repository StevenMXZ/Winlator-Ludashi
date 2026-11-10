package com.winlator.cmod.ui.container

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Extension
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
import androidx.compose.runtime.Stable
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

@Immutable
data class ContainerOverviewModel(
    val containerId: Int,
    val containerName: String,
    val wineVersion: String,
    val renderer: String,
    val screenSize: String,
    val audioDriver: String,
    val dxWrapper: String,
    val graphicsDriver: String,
    val emulator: String,
    val storagePath: String
)

@Stable
interface ContainerOverviewCallbacks : ContainerInlineCallbacks {
    fun createEmbeddedSection(section: String): View
    fun onAdvanced()
    fun onLaunch()
}

object ContainerOverviewComposeHost {
    const val SECTION_SYSTEM = "system"
    const val SECTION_VIDEO = "video"
    const val SECTION_AUDIO = "audio"
    const val SECTION_COMPATIBILITY = "compatibility"
    const val SECTION_STORAGE = "storage"
    const val SECTION_ADVANCED = "advanced"

    @JvmStatic
    fun create(context: Context, model: ContainerOverviewModel, callbacks: ContainerOverviewCallbacks): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { ContainerOverviewScreen(model, callbacks) } }
        }
}

@Immutable
private data class SectionItem(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

@Composable
private fun ContainerOverviewScreen(model: ContainerOverviewModel, callbacks: ContainerOverviewCallbacks) {
    val sections = remember(model) {
        listOf(
            SectionItem(ContainerOverviewComposeHost.SECTION_SYSTEM, "System", "Wine ${model.wineVersion}", Icons.Outlined.Settings),
            SectionItem(ContainerOverviewComposeHost.SECTION_VIDEO, "Video", "${model.renderer}  •  ${model.screenSize}", Icons.Outlined.DesktopWindows),
            SectionItem(ContainerOverviewComposeHost.SECTION_AUDIO, "Audio", model.audioDriver, Icons.Outlined.VolumeUp),
            SectionItem(ContainerOverviewComposeHost.SECTION_COMPATIBILITY, "Compatibility", model.emulator, Icons.Outlined.Extension),
            SectionItem(ContainerOverviewComposeHost.SECTION_STORAGE, "Storage", uiStr(R.string.ui_container_files), Icons.Outlined.Folder),
            SectionItem(ContainerOverviewComposeHost.SECTION_ADVANCED, "Advanced", "", Icons.Outlined.Tune)
        )
    }
    var expanded by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = callbacks::onLaunch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(uiStr(R.string.launch_environment), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = padding.calculateBottomPadding() + 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "container-sections") {
                SectionGroup(model, sections, expanded, callbacks) { section ->
                    expanded = if (expanded == section) null else section
                }
            }
        }
    }
}

@Composable
private fun SectionGroup(
    model: ContainerOverviewModel,
    sections: List<SectionItem>,
    expanded: String?,
    callbacks: ContainerOverviewCallbacks,
    onToggle: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .78f))
    ) {
        Column {
            sections.forEachIndexed { index, item ->
                val isExpanded = expanded == item.id
                SectionRow(item, isExpanded) { onToggle(item.id) }
                if (isExpanded) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        when (item.id) {
                            ContainerOverviewComposeHost.SECTION_SYSTEM -> ContainerSystemInline(model.containerId, callbacks)
                            ContainerOverviewComposeHost.SECTION_VIDEO,
                            ContainerOverviewComposeHost.SECTION_AUDIO,
                            ContainerOverviewComposeHost.SECTION_COMPATIBILITY -> ContainerRuntimePane(model.containerId, item.id)
                            ContainerOverviewComposeHost.SECTION_STORAGE -> ContainerStorageInline(model.containerId, callbacks)
                            ContainerOverviewComposeHost.SECTION_ADVANCED -> ContainerAdvancedPane(model.containerId)
                        }
                    }
                }
                if (index != sections.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = if (isExpanded) 14.dp else 68.dp, end = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(item: SectionItem, expanded: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp), shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f), contentColor = MaterialTheme.colorScheme.onSurface
            ) { Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, modifier = Modifier.size(22.dp)) } }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight, null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
