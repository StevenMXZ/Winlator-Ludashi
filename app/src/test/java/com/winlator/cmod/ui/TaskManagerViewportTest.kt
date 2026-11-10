package com.winlator.cmod.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.winlator.cmod.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskManagerViewportTest {
    @Test fun longNativeTaskListRemainsScrollableInsideCompose() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup().visible()
        try {
            val activity = controller.get()
            val original = LayoutInflater.from(activity).inflate(R.layout.xserver_display_activity, null)
            val taskPanel = original.findViewById<ScrollView>(R.id.LLSubTaskManager)
            val list = taskPanel.findViewById<LinearLayout>(R.id.LLProcessList)
            repeat(30) { index -> list.addView(TextView(activity).apply { text = "Task $index"; minimumHeight = 60 }) }
            val compose = ComposeView(activity).apply {
                setContent { Column(Modifier.fillMaxSize()) { TaskManagerPanel(taskPanel, true) } }
            }
            activity.setContentView(compose)
            compose.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            compose.layout(0, 0, 800, 400)
            assertTrue(taskPanel.height in 1..400)
            assertTrue(taskPanel.canScrollVertically(1))
            taskPanel.scrollTo(0, 300)
            assertTrue(taskPanel.scrollY > 0)
        } finally { controller.pause().stop().destroy() }
    }
}
