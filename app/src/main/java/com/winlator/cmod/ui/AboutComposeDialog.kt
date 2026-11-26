package com.winlator.cmod.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.winlator.cmod.R
import com.winlator.cmod.core.ActionsUpdates

object AboutComposeDialog {
    private val projects = listOf(
        "Termux Packages" to "https://github.com/termux/termux-packages",
        "Wine" to "https://www.winehq.org",
        "Box64" to "https://github.com/ptitSeb/box64",
        "Mesa (Turnip/Zink/Wrapper)" to "https://github.com/xMeM/mesa/tree/wrapper",
        "DXVK" to "https://github.com/doitsujin/dxvk",
        "VKD3D" to "https://gitlab.winehq.org/wine/vkd3d",
        "D8VK" to "https://github.com/AlpyneDreams/d8vk",
        "CNC DDraw" to "https://github.com/FunkyFr3sh/cnc-ddraw",
        "dxwrapper" to "https://github.com/elishacloud/dxwrapper",
        "FEX-Emu" to "https://github.com/FEX-Emu/FEX",
        "libadrenotools" to "https://github.com/bylaws/libadrenotools",
        "The412Banner (BLDP incremental updates)" to "https://github.com/The412Banner/Bannerlator"
    )

    @JvmStatic fun show(activity: Activity) {
        AppInfoDialog.show(activity) { close -> AboutPage(activity, close) }
    }

    @Suppress("DEPRECATION")
    @Composable private fun AboutPage(activity: Activity, close: () -> Unit) {
        val info = remember { activity.packageManager.getPackageInfo(activity.packageName, 0) }
        val build = remember { ActionsUpdates.installedBuild(activity) }
        val icon = remember {
            activity.packageManager.getApplicationIcon(activity.applicationInfo)
                .toBitmap(192, 192).asImageBitmap()
        }
        val uri = LocalUriHandler.current
        Column(Modifier.padding(20.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(68.dp))
                    Text("Winlator Ludashi", style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                    Text(uiStr(R.string.version) + " " + info.versionName.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    build?.let {
                        Text("Build #" + it.number + " · " + it.sha.take(7),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val horizontal = maxWidth >= 260.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.2f
                    val github: @Composable (Modifier) -> Unit = { modifier ->
                        OutlinedButton(onClick = { uri.openUri("https://github.com/StevenMXZ/Winlator-Ludashi") },
                            modifier = modifier, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("GitHub", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    val report: @Composable (Modifier) -> Unit = { modifier ->
                        OutlinedButton(onClick = { uri.openUri("https://github.com/StevenMXZ/Winlator-Ludashi/issues") },
                            modifier = modifier, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(uiStr(R.string.app_info_report_issue), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        github(Modifier.weight(1f))
                        report(Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        github(Modifier.fillMaxWidth())
                        report(Modifier.fillMaxWidth())
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiStr(R.string.app_info_developed_by), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uri.openUri("https://github.com/StevenMXZ") },
                        modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        Text("StevenMXZ", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(uiStr(R.string.app_info_maintainer), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiStr(R.string.app_info_forked_from), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uri.openUri("https://github.com/Pipetto-crypto/winlator") },
                        modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Winlator", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("pipetto-crypto", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                ExpandableSection(uiStr(R.string.credits_and_third_party_apps)) {
                    Text(uiStr(R.string.ui_big_picture_mode_music_by), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Dale Melvin Blevens III (Fumer)", style = MaterialTheme.typography.bodyMedium)
                    projects.forEach { (name, url) -> ProjectLink(name, url) }
                }
                ExpandableSection(uiStr(R.string.glibc_exp_edition)) {
                    ProjectLink("Winlator", "https://www.winlator.org")
                    ProjectLink("longjunyu2 (GLIBC Fork)", "https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot")
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) {
                Text(uiStr(R.string.app_info_close))
            }
        }
    }

    @Composable private fun ProjectLink(name: String, url: String) {
        val uri = LocalUriHandler.current
        TextButton(onClick = { uri.openUri(url) }, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }

    @Composable private fun ExpandableSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            HorizontalDivider()
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null)
            }
            if (expanded) Column(Modifier.padding(start = 8.dp), content = content)
        }
    }
}
