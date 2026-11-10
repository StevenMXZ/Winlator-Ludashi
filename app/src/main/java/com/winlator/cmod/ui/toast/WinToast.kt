package com.winlator.cmod.ui.toast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.R
import java.lang.ref.WeakReference

object WinToast {
    private const val DEFAULT_LONG_DURATION_MS = 3500L
    private const val DEFAULT_SHORT_DURATION_MS = 2000L
    private const val LONG_TEXT_THRESHOLD = 40

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRef: WeakReference<PopupWindow>? = null
    private var dismissRunnable: Runnable? = null

    @JvmStatic
    fun show(context: Context, text: String) {
        show(context, text, null, autoDurationMs(text))
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int) {
        show(context, context.getString(textResId))
    }

    @JvmStatic
    fun show(context: Context, text: String, icon: Bitmap?) {
        show(context, text, icon, autoDurationMs(text))
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int, icon: Bitmap?) {
        show(context, context.getString(textResId), icon)
    }

    @JvmStatic
    fun show(context: Context, text: String, toastDuration: Int) {
        show(context, text, null, toastDurationToMs(toastDuration))
    }

    @JvmStatic
    fun show(context: Context, text: String, toastDuration: Int, anchor: android.view.View?) {
        show(context, text, null, toastDurationToMs(toastDuration), anchor)
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int, toastDuration: Int) {
        show(context, context.getString(textResId), toastDuration)
    }

    @JvmStatic
    fun show(context: Context, text: String, icon: Bitmap?, toastDuration: Int) {
        show(context, text, icon, toastDurationToMs(toastDuration))
    }

    @JvmStatic
    fun show(context: Context, text: String, durationMs: Long) {
        show(context, text, null, durationMs)
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int, durationMs: Long) {
        show(context, context.getString(textResId), durationMs)
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int, anchor: android.view.View?) {
        show(context, context.getString(textResId), anchor)
    }

    @JvmStatic
    fun show(context: Context, @StringRes textResId: Int, icon: Bitmap?, anchor: android.view.View?) {
        val text = context.getString(textResId)
        show(context, text, icon, autoDurationMs(text), anchor)
    }

    @JvmStatic
    fun show(context: Context, text: String, anchor: android.view.View?) {
        show(context, text, null, autoDurationMs(text), anchor)
    }

    @JvmStatic
    fun show(context: Context, text: String, icon: Bitmap?, durationMs: Long, anchor: android.view.View? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, text, icon, durationMs, anchor) }
            return
        }
        val activity = resolveActivity(context)
        if (activity == null) {
            showFallbackSystemToast(context, text, durationMs)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            showFallbackSystemToast(context, text, durationMs)
            return
        }
        val resolvedIcon = icon ?: getDefaultIcon(activity)
        showCompose(activity, text, resolvedIcon, durationMs, anchor)
    }

    private var defaultIconCache: Bitmap? = null

    private fun getDefaultIcon(context: Context): Bitmap? {
        defaultIconCache?.let { return it }
        val drawable = context.getDrawable(R.mipmap.ic_launcher) ?: return null
        val sizePx = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        defaultIconCache = bitmap
        return bitmap
    }

    private fun showCompose(
        activity: ComponentActivity,
        text: String,
        icon: Bitmap?,
        durationMs: Long,
        anchor: android.view.View? = null
    ) {
        dismissActive()

        val textState = mutableStateOf(text)
        val iconState = mutableStateOf(icon)
        val visibleState = mutableStateOf(true)

        val composeView = ComposeView(activity).apply {
            setContent {
                WinToastContent(
                    text = textState.value,
                    icon = iconState.value,
                    visible = visibleState.value,
                )
            }
        }
        // PopupWindow wraps the content view in its own PopupDecorView, which becomes
        // the rootView Compose's WindowRecomposer searches for tree owners. We need
        // those owners seeded on the decor view before ComposeView's onAttachedToWindow
        // runs. A wrapping FrameLayout's onAttachedToWindow fires before children
        // attach, so this is the right hook.
        val container = object : FrameLayout(activity) {
            override fun onAttachedToWindow() {
                rootView.setViewTreeLifecycleOwner(activity)
                rootView.setViewTreeViewModelStoreOwner(activity)
                rootView.setViewTreeSavedStateRegistryOwner(activity)
                super.onAttachedToWindow()
            }
        }
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isTouchable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            animationStyle = 0
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        }

        try {
            val targetAnchor = anchor ?: activity.window.decorView
            popup.showAtLocation(
                targetAnchor,
                Gravity.CENTER or Gravity.BOTTOM,
                0,
                BOTTOM_OFFSET_PX,
            )
        } catch (_: WindowManager.BadTokenException) {
            showFallbackSystemToast(activity, text, durationMs)
            return
        } catch (_: IllegalStateException) {
            showFallbackSystemToast(activity, text, durationMs)
            return
        }
        activeRef = WeakReference(popup)

        val dismiss = Runnable {
            visibleState.value = false
            mainHandler.postDelayed({
                runCatching { if (popup.isShowing) popup.dismiss() }
                if (activeRef?.get() === popup) activeRef = null
            }, EXIT_ANIM_MS)
        }
        dismissRunnable = dismiss
        mainHandler.postDelayed(dismiss, durationMs)
    }

    private fun dismissActive() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        activeRef?.get()?.let { popup ->
            runCatching { if (popup.isShowing) popup.dismiss() }
        }
        activeRef = null
    }

    private fun resolveActivity(context: Context): ComponentActivity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is ComponentActivity) return ctx
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        return ForegroundActivityTracker.current as? ComponentActivity
    }

    private fun showFallbackSystemToast(context: Context, text: String, durationMs: Long) {
        val length = if (durationMs >= DEFAULT_LONG_DURATION_MS) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context.applicationContext, text, length).show()
    }

    private fun autoDurationMs(text: String): Long =
        if (text.length >= LONG_TEXT_THRESHOLD) DEFAULT_LONG_DURATION_MS else DEFAULT_SHORT_DURATION_MS

    private fun toastDurationToMs(toastDuration: Int): Long =
        if (toastDuration == Toast.LENGTH_LONG) DEFAULT_LONG_DURATION_MS else DEFAULT_SHORT_DURATION_MS

    private const val EXIT_ANIM_MS = 180L
    private val BOTTOM_OFFSET_PX = (50 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
