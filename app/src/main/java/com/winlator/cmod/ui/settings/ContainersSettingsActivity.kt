package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.winlator.cmod.XrActivity
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.StringUtils
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.container.ContainerCreateComposeFragment
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class ContainersSettingsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private val containersState = mutableStateOf<List<Container>>(emptyList())
    private val propertiesState = mutableStateOf<Container?>(null)
    private var showingEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppFullscreen(this)
        root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0 && showingEditor) {
                showingEditor = false
                refresh()
                showHome()
            }
        }

        if (savedInstanceState == null) showHome()
    }

    override fun onResume() {
        super.onResume()
        applyAppFullscreen(this)
        refresh()
    }

    private fun showHome() {
        root.removeAllViews()
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZTheme {
                    ContainersSettingsScreen(
                        containers = containersState.value,
                        propertiesContainer = propertiesState.value,
                        onBack = { finish() },
                        onAdd = { openFragment(ContainerCreateComposeFragment()) },
                        onEdit = { id -> openFragment(ContainerCreateComposeFragment.forEdit(id)) },
                        onProperties = { id ->
                            propertiesState.value = ContainerManager(this@ContainersSettingsActivity).getContainerById(id)
                        },
                        onDismissProperties = { propertiesState.value = null },
                        onRun = ::runContainer,
                        onDuplicate = ::duplicateContainer,
                        onRemove = ::removeContainer
                    )
                }
            }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun openFragment(fragment: androidx.fragment.app.Fragment) {
        propertiesState.value = null
        showingEditor = true
        supportFragmentManager.beginTransaction()
            .replace(root.id, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun refresh() {
        containersState.value = ContainerManager(this).containers.toList()
    }

    private fun duplicateContainer(id: Int) {
        val manager = ContainerManager(this)
        val container = manager.getContainerById(id) ?: return
        manager.duplicateContainerAsync(container) {
            refresh()
            Toast.makeText(this, uiStr(R.string.ui_container_duplicated), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeContainer(id: Int) {
        val manager = ContainerManager(this)
        val container = manager.getContainerById(id) ?: return
        AppCompatAlertDialog.Builder(this)
            .setTitle(uiStr(R.string.ui_remove_container))
            .setMessage(uiStr(R.string.ui_1_s_and_its_container_files_will_be_deleted, container.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ ->
                manager.removeContainerAsync(container) {
                    refresh()
                    Toast.makeText(this, uiStr(R.string.ui_container_removed), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun runContainer(id: Int) {
        if (!XrActivity.isEnabled(this)) {
            startActivity(Intent(this, XServerDisplayActivity::class.java).putExtra("container_id", id))
        } else {
            XrActivity.openIntent(this, id, null)
        }
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}

@Composable
private fun ContainersSettingsScreen(
    containers: List<Container>,
    propertiesContainer: Container?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onDismissProperties: () -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(uiStr(R.string.containers)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Outlined.Add, uiStr(R.string.ui_new_container)) }
        }
    ) { padding ->
        if (containers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(74.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(uiStr(R.string.ui_no_containers), style = MaterialTheme.typography.titleLarge)
                    Text(uiStr(R.string.ui_create_a_windows_environment_for_your_games), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(containers, key = { it.id }) { container ->
                    SettingsContainerCard(container, onEdit, onProperties, onRun, onDuplicate, onRemove)
                }
            }
        }
    }

    propertiesContainer?.let {
        ContainerPropertiesDialog(it, onDismissProperties)
    }
}

@Composable
private fun SettingsContainerCard(
    container: Container,
    onEdit: (Int) -> Unit,
    onProperties: (Int) -> Unit,
    onRun: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(50.dp), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(container.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${container.wineVersion} • ${container.screenSize}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(
                    onClick = { onRun(container.id) },
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayArrow, null) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(Icons.Outlined.Edit, uiStr(R.string.edit), Modifier.weight(1f)) { onEdit(container.id) }
                ActionButton(Icons.Outlined.Info, uiStr(R.string.ui_properties), Modifier.weight(1f)) { onProperties(container.id) }
                ContainerMoreButton(
                    modifier = Modifier.weight(1f),
                    onDuplicate = { onDuplicate(container.id) },
                    onRemove = { onRemove(container.id) }
                )
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Surface(
        onClick = action,
        modifier = modifier,
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContainerMoreButton(
    modifier: Modifier = Modifier,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        ActionButton(Icons.Outlined.MoreVert, uiStr(R.string.ui_more), Modifier.fillMaxWidth()) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(uiStr(R.string.duplicate)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    expanded = false
                    onDuplicate()
                }
            )
            DropdownMenuItem(
                text = { Text(uiStr(R.string.remove)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = {
                    expanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun ContainerPropertiesDialog(container: Container, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshKey by remember(container.id) { mutableIntStateOf(0) }
    var loading by remember(container.id) { mutableStateOf(true) }
    var clearing by remember(container.id) { mutableStateOf(false) }
    var driveSize by remember(container.id) { mutableStateOf(0L) }
    var cacheSize by remember(container.id) { mutableStateOf(0L) }
    var totalSize by remember(container.id) { mutableStateOf(0L) }

    val rootDir = container.rootDir
    val driveCDir = remember(container.id) { File(rootDir, ".wine/drive_c") }
    val cacheDir = remember(container.id) { File(rootDir, ".cache") }

    LaunchedEffect(container.id, refreshKey) {
        loading = true
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                directorySize(driveCDir),
                directorySize(cacheDir),
                directorySize(rootDir)
            )
        }
        driveSize = sizes.first
        cacheSize = sizes.second
        totalSize = sizes.third
        loading = false
    }

    val storageSize = FileUtils.getInternalStorageSize().coerceAtLeast(1L)
    val usedPercent = ((totalSize.toDouble() / storageSize.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Storage, null) },
        title = { Text(uiStr(R.string.ui_container_properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(uiStr(R.string.ui_calculating_storage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            StorageValue(uiStr(R.string.ui_drive_c), driveSize)
                            StorageValue(uiStr(R.string.cache), cacheSize)
                            StorageValue(uiStr(R.string.total), totalSize, emphasize = true)
                        }
                        Surface(
                            modifier = Modifier.size(90.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$usedPercent%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text(uiStr(R.string.ui_storage), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !clearing,
                onClick = {
                    if (!clearing) {
                        clearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FileUtils.clear(cacheDir)
                                container.putExtra("desktopTheme", null)
                                container.saveData()
                            }
                            clearing = false
                            refreshKey++
                        }
                    }
                }
            ) {
                if (clearing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(uiStr(R.string.ui_clearing))
                } else {
                    Text(uiStr(R.string.ui_clear_cache))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(uiStr(R.string.ui_close)) }
        }
    )
}

@Composable
private fun StorageValue(label: String, bytes: Long, emphasize: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            StringUtils.formatBytes(bytes),
            style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

private fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length().coerceAtLeast(0L)

    var total = 0L
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (FileUtils.isSymlink(current)) continue
        val files = current.listFiles() ?: continue
        for (file in files) {
            if (file.isDirectory) {
                if (!FileUtils.isSymlink(file)) stack.add(file)
            } else {
                total += file.length().coerceAtLeast(0L)
            }
        }
    }
    return total
}
