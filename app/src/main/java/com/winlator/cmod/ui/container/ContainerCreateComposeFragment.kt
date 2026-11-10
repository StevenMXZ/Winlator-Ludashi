package com.winlator.cmod.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.winlator.cmod.ui.theme.WinZTheme

class ContainerCreateComposeFragment : Fragment() {
    companion object {
        private const val ARG_EDIT_CONTAINER_ID = "edit_container_id"

        @JvmStatic
        fun forEdit(containerId: Int): ContainerCreateComposeFragment = ContainerCreateComposeFragment().apply {
            arguments = Bundle().apply { putInt(ARG_EDIT_CONTAINER_ID, containerId) }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.winlator.cmod.MainActivity)?.apply {
            setDetailMode(true)
            setMainToolbarVisible(false)
        }
    }

    override fun onPause() {
        (activity as? com.winlator.cmod.MainActivity)?.apply {
            setDetailMode(false)
            setMainToolbarVisible(true)
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (activity as? com.winlator.cmod.MainActivity)?.setMainToolbarVisible(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        val editId = arguments?.getInt(ARG_EDIT_CONTAINER_ID, -1)?.takeIf { it > 0 }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                ContainerEditorV2(
                    editId = editId,
                    onBack = { parentFragmentManager.popBackStack() },
                    onCreated = { parentFragmentManager.popBackStack() }
                )
            }
        }
    }
}

