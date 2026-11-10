package com.winlator.cmod.ui.library

import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager

import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Slow breathing/zoom motion adapted from teldommm/WinLite for the detail
// header and the selected landscape cover. Existing image paths, crop and
// offline fallback are unchanged. The lifecycle pauses the clock when hidden;
// the Library settings switch can disable motion entirely.

// True where artwork may animate at all. Default false so a screen that never provides it stays still.
internal val LocalArtworkMotion = staticCompositionLocalOf { false }

// True while motion is allowed but the screen is not visible: the clock stops, the frame stays.
internal val LocalArtworkMotionPaused = compositionLocalOf { false }

// Observe preference changes so an already-composed screen updates immediately.
@Composable
internal fun rememberArtworkMotionEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context.applicationContext) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("animated_artwork", true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "animated_artwork") {
                enabled = changed.getBoolean("animated_artwork", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

/**
 * Ken Burns: the layer slowly zooms in and out and drifts. One looping clock 0..1 feeds sinusoids,
 * so the motion is smooth at both ends and seamless at the loop point. It is read only inside the
 * graphicsLayer block (draw phase), so a frame never triggers recomposition.
 *
 * The clock is an Animatable driven by an effect rather than an infinite transition, so it can be
 * paused (LocalArtworkMotionPaused) and resumed from the same frame.
 *
 * The drift is a fraction (pan) of the margin the current zoom leaves, so the picture never slides
 * far enough to show its edge. The caller must clip the parent (clipToBounds / a clipped Surface):
 * the scaled layer overflows its bounds.
 *
 * @param amplitude zoom added at the far end (0.07 = up to 107%).
 * @param periodMs  one full zoom in + out.
 * @param pan       0 = zoom only, 1 = drift by the whole free margin.
 * @param phase     0..1 shift of the loop (2 layers with different phases never move in step).
 * @param strength  0..1 fade of the whole effect; 0 leaves the modifier out (and stops the clock).
 */
@Composable
internal fun Modifier.kenBurns(
    amplitude: Float = 0.07f,
    periodMs: Int = 22_000,
    pan: Float = 0.8f,
    phase: Float = 0f,
    strength: Float = 1f
): Modifier {
    if (!LocalArtworkMotion.current || strength <= 0f) return this
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val paused = LocalArtworkMotionPaused.current || !resumed
    val clock = remember { Animatable(0f) }
    LaunchedEffect(paused, periodMs) {
        if (paused) return@LaunchedEffect
        while (isActive) {
            val from = clock.value
            val remaining = ((1f - from) * periodMs).toInt().coerceAtLeast(1)
            clock.animateTo(1f, tween(remaining, easing = LinearEasing))
            // 1 and 0 are the same frame of the loop (whole sine periods), so the wrap is invisible.
            clock.snapTo(0f)
        }
    }
    return this.graphicsLayer {
        val angle = (clock.value + phase) * 2f * PI.toFloat()
        val breathe = (1f - cos(angle)) / 2f
        val zoom = 1f + amplitude * strength * breathe
        val margin = (zoom - 1f) / 2f
        scaleX = zoom
        scaleY = zoom
        translationX = size.width * margin * pan * sin(angle)
        translationY = size.height * margin * pan * 0.75f * sin(angle * 2f)
    }
}

