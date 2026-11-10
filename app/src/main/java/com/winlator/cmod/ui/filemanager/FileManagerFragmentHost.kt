package com.winlator.cmod.ui.filemanager

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import com.winlator.cmod.ui.theme.WinZTheme
import androidx.compose.ui.platform.LocalConfiguration

object FileManagerFragmentHost {
    @JvmStatic fun create(activity: MainActivity, controller: FileManagerController, state: FileManagerViewState): View = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { WinZTheme {
            val config = LocalConfiguration.current
            val landscape = config.screenWidthDp > config.screenHeightDp
            if (landscape) KeepLandscapeChromeHidden(activity)
            DisposableEffect(landscape) {
                activity.setBottomNavigationVisible(!landscape)
                activity.setMainToolbarVisible(!landscape)
                onDispose { }
            }
            state.model?.let { FileManagerScreen(it, remember(controller) { controller.createCallbacks() }) }
        } }
    }
}
class FileManagerViewState {
    var model: FileManagerModel? by mutableStateOf(null)
}
