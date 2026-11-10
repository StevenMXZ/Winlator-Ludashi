package com.winlator.cmod.ui.settings

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.contents.D7VKManager

@Composable
internal fun DDrawWrapperChoice(selected: String, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    val entries = remember(context) {
        D7VKManager.getWrapperEntries(context).associateWith(D7VKManager::getWrapperLabel)
    }
    SettingMappedChoice(uiStr(R.string.ddraw_wrapper), selected, entries, onSelected)
}
