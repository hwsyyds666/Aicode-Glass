package com.aicode.core.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.tween
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** True while this UI owner is visible; animations can stop at ON_STOP without losing their phase. */
@Composable
internal fun rememberLifecycleStarted(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = lifecycle.currentStateFlow.collectAsState()
    return state.value.isAtLeast(Lifecycle.State.STARTED)
}

/** Phase offset for restarting an infinite animation after a stopped UI interval. */
internal fun animationStartOffsetMillis(originUptimeMs: Long, nowUptimeMs: Long, cycleMillis: Int): Int {
    if (cycleMillis <= 0 || nowUptimeMs <= originUptimeMs) return 0
    return ((nowUptimeMs - originUptimeMs) % cycleMillis).toInt()
}

/** A phase-preserving reverse pulse that leaves composition while its lifecycle owner is stopped. */
@Composable
internal fun rememberLifecyclePulseAlpha(
    label: String,
    halfCycleMillis: Int = 650,
    minAlpha: Float = 0.3f,
    maxAlpha: Float = 1f,
    easing: Easing = FastOutSlowInEasing
): State<Float> {
    val running = rememberLifecycleStarted()
    val halfCycle = halfCycleMillis.coerceAtLeast(1)
    val cycle = halfCycle * 2
    val origin = remember { SystemClock.uptimeMillis() }
    val phase = remember(running, halfCycle) {
        animationStartOffsetMillis(origin, SystemClock.uptimeMillis(), cycle)
    }
    val reverseLeg = phase >= halfCycle
    val transition = if (running) rememberInfiniteTransition(label = label) else null
    return transition?.animateFloat(
        initialValue = if (reverseLeg) minAlpha else maxAlpha,
        targetValue = if (reverseLeg) maxAlpha else minAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = halfCycle, easing = easing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(phase % halfCycle, StartOffsetType.FastForward)
        ),
        label = "$label-alpha"
    ) ?: remember { mutableFloatStateOf(maxAlpha) }
}
