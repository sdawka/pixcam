package com.example.pixcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pixcam.camera.CameraInfo
import com.example.pixcam.camera.ManualControls
import com.example.pixcam.camera.ToneCurve
import com.example.pixcam.gallery.LastShotThumb
import com.example.pixcam.theme.PixAccent
import com.example.pixcam.theme.PixDim
import com.example.pixcam.theme.PixOnDark
import com.example.pixcam.theme.PixSurface
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun TopStrip(
    info: CameraInfo,
    controls: ManualControls,
    onToggleAeLock: () -> Unit,
    onToggleManual: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PixSurface.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (info.rawSupported) "DNG" else "JPEG",
            color = PixAccent,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(info.hardwareLevel, color = PixDim, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        if (info.aeLockAvailable && !controls.manual) {
            Chip(label = "AE-L", active = controls.aeLock, enabled = true, onClick = onToggleAeLock)
        }
        Chip(
            label = if (controls.manual) "M" else "A",
            active = controls.manual,
            enabled = info.manualSensor,
            onClick = onToggleManual,
        )
        Chip(label = "ⓘ", active = false, enabled = true, onClick = onInfo)
    }
}

@Composable
fun BottomBar(
    status: String,
    saving: Boolean,
    proOpen: Boolean,
    shotCount: Int,
    onTogglePro: () -> Unit,
    onShutter: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PixSurface.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            status,
            color = PixDim,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Chip(label = "PRO", active = proOpen, enabled = true, onClick = onTogglePro)
            }
            ShutterButton(saving = saving, onClick = onShutter)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                LastShotThumb(refresh = shotCount, onClick = onOpenGallery)
            }
        }
    }
}

@Composable
fun ShutterButton(saving: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .border(3.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (saving) PixDim else Color.White)
            .clickable(enabled = !saving) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (saving) CircularProgressIndicator(modifier = Modifier.size(32.dp), color = PixAccent)
    }
}

@Composable
fun ProControlsPanel(
    visible: Boolean,
    info: CameraInfo,
    controls: ManualControls,
    push: (ManualControls) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(PixSurface.copy(alpha = 0.85f))
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controls.manual) {
                info.isoRange?.let { range ->
                    LogSlider(
                        label = "ISO ${controls.iso}",
                        value = controls.iso.toFloat(),
                        min = range.lower.toFloat(),
                        max = range.upper.toFloat(),
                    ) { push(controls.copy(iso = it.toInt())) }
                }
                info.exposureRange?.let { range ->
                    LogSlider(
                        label = "Shutter ${formatExposure(controls.exposureNs)}",
                        value = controls.exposureNs.toFloat(),
                        min = range.lower.toFloat(),
                        // slider stops at 1/2s even if the device allows longer
                        max = minOf(range.upper, 500_000_000L).toFloat(),
                    ) { push(controls.copy(exposureNs = it.toLong())) }
                }
            } else {
                info.aeCompensationRange?.let { range ->
                    Column {
                        Text(
                            "EV %+.1f".format(controls.exposureCompensation * info.aeCompensationStep),
                            color = PixOnDark,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = controls.exposureCompensation.toFloat(),
                            onValueChange = { push(controls.copy(exposureCompensation = it.roundToInt())) },
                            valueRange = range.lower.toFloat()..range.upper.toFloat(),
                            steps = (range.upper - range.lower - 1).coerceAtLeast(0),
                        )
                    }
                }
            }

            if (info.toneCurveSupported) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Tone", color = PixDim, style = MaterialTheme.typography.bodySmall)
                    ToneCurve.entries.forEach { curve ->
                        Chip(
                            label = when (curve) {
                                ToneCurve.DEVICE -> "Device"
                                ToneCurve.LINEAR -> "Linear"
                                ToneCurve.SRGB -> "sRGB"
                                ToneCurve.FILMIC -> "Filmic"
                            },
                            active = controls.toneCurve == curve,
                            enabled = true,
                            onClick = { push(controls.copy(toneCurve = curve)) },
                        )
                    }
                }
            }

            if (info.minFocusDistance > 0f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = controls.focusDistance != null,
                        onCheckedChange = { push(controls.copy(focusDistance = if (it) 0f else null)) },
                    )
                    Text("MF", color = PixOnDark)
                    controls.focusDistance?.let { fd ->
                        Slider(
                            value = fd,
                            onValueChange = { push(controls.copy(focusDistance = it)) },
                            valueRange = 0f..info.minFocusDistance,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = when {
            !enabled -> PixDim.copy(alpha = 0.4f)
            active -> Color.Black
            else -> PixOnDark
        },
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active && enabled) PixAccent else PixSurface.copy(alpha = 0.8f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun LogSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = PixOnDark, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = ln(value.coerceIn(min, max) / min) / ln(max / min),
            onValueChange = { t -> onChange(min * exp(t * ln(max / min))) },
            valueRange = 0f..1f,
        )
    }
}

fun formatExposure(ns: Long): String {
    val s = ns / 1e9
    return if (s >= 0.5) "%.1fs".format(s) else "1/%d".format((1 / s).toInt())
}
