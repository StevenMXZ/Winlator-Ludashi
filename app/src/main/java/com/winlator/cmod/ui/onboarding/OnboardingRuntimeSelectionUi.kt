package com.winlator.cmod.ui.onboarding

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.core.ProtonPackageManager

private val bundledRuntimeId = "bundled:${ProtonPackageManager.DEFAULT_IDENTIFIER}"
private val bundledRuntimeName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER)?.title
    ?: "Proton 10.0-5 arm64ec"

@Composable
internal fun OnboardingRuntimeSelectionScreen(
    components: List<OnboardingComponent>,
    bundledInstalled: Boolean,
    preparing: Boolean,
    onBack: () -> Unit,
    onContinue: (String) -> Unit
) {
    val runtimes = remember(components, bundledInstalled) {
        buildList {
            if (bundledInstalled) {
                add(
                    OnboardingComponent(
                        id = bundledRuntimeId,
                        type = "Proton",
                        name = bundledRuntimeName,
                        installed = true,
                        recommended = true,
                        removable = true,
                        runtimeIdentifier = ProtonPackageManager.DEFAULT_IDENTIFIER,
                        bundled = true
                    )
                )
            }
            components
                .asSequence()
                .filter { it.installed && (it.type == "Wine" || it.type == "Proton") }
                .filter { !it.runtimeIdentifier.isNullOrBlank() }
                .filterNot { it.runtimeIdentifier == ProtonPackageManager.DEFAULT_IDENTIFIER }
                .distinctBy { it.runtimeIdentifier }
                .forEach(::add)
        }
    }
    var selected by rememberSaveable(runtimes) {
        mutableStateOf(runtimes.firstOrNull()?.runtimeIdentifier.orEmpty())
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(uiStr(R.string.ui_choose_environment), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (preparing) {
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(uiStr(R.string.ui_preparing_environment), fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            items(runtimes, key = { it.runtimeIdentifier ?: it.id }) { runtime ->
                val id = runtime.runtimeIdentifier.orEmpty()
                Surface(
                    onClick = { if (!preparing) selected = id },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected == id) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (selected == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(runtime.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(runtime.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected == id) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (runtimes.isEmpty()) {
                item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            uiStr(R.string.ui_install_at_least_one_wine_or_proton_versi),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !preparing,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(uiStr(R.string.ui_back))
                }
                Button(
                    onClick = { if (selected.isNotBlank() && !preparing) onContinue(selected) },
                    enabled = selected.isNotBlank() && !preparing,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (preparing) uiStr(R.string.ui_preparing) else uiStr(R.string._continue)) }
            }
        }
    }
}
