package com.example.pixcam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private const val FLASH_FADE_MILLIS = 200

/**
 * Shutter blink: each increment of [trigger] snaps the area to opaque black and
 * fades it back out. Idle renders nothing, so it never intercepts touches.
 */
@Composable
fun CaptureFlash(trigger: Int, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    // baseline so the first composition doesn't blink on app start
    val first = remember { trigger }

    LaunchedEffect(trigger) {
        if (trigger == first) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(durationMillis = FLASH_FADE_MILLIS))
    }

    if (alpha.value > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = alpha.value)),
        )
    }
}
