package com.winlator.cmod.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.winlator.cmod.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SidebarMeasurementTest {
    // Exercise the real DrawerLayout hierarchy. Like Compose's scroll node,
    // this probe rejects infinite constraints passed by a nested ScrollView.
    private class ScrollContentProbe(context: Context) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            check(MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                "Sidebar scroll content received infinite height"
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    @Test fun sidebarGetsFiniteViewportInBothOrientations() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        for ((width, height) in listOf(1000 to 600, 600 to 1000)) {
            val root = LayoutInflater.from(context).inflate(R.layout.xserver_display_activity, null) as ViewGroup
            val sidebar = root.findViewById<ViewGroup>(R.id.IngameSidebarRoot)
            sidebar.removeAllViews()
            val probe = ScrollContentProbe(context)
            sidebar.addView(probe, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertTrue(probe.measuredHeight > 0)
            assertTrue(probe.measuredHeight <= height)
        }
    }
}
