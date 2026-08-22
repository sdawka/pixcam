package com.example.pixcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.pixcam.camera.CameraInfo
import com.example.pixcam.theme.PixAccent
import com.example.pixcam.theme.PixDim
import com.example.pixcam.theme.PixOnDark
import com.example.pixcam.theme.PixSurfaceHigh

@Composable
fun InfoDialog(info: CameraInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PixSurfaceHigh,
        titleContentColor = PixOnDark,
        textContentColor = PixOnDark,
        title = { Text("Hardware", style = MaterialTheme.typography.titleMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = PixAccent)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InfoRow("Camera id", info.cameraId)
                InfoRow("Hardware level", info.hardwareLevel)
                InfoRow(
                    "RAW",
                    if (info.rawSupported) {
                        info.rawSize?.let { "yes (${it.width} x ${it.height})" } ?: "yes"
                    } else {
                        "no (JPEG fallback)"
                    },
                )
                InfoRow("Manual sensor", yesNo(info.manualSensor))
                InfoRow("Manual post-processing", yesNo(info.manualPostProcessing))
                InfoRow(
                    "Tone curve",
                    if (info.toneCurveSupported) "yes (${info.maxCurvePoints} points)" else "no",
                )
                InfoRow("ISO", info.isoRange?.let { "${it.lower} – ${it.upper}" } ?: "unknown")
                InfoRow(
                    "Exposure",
                    info.exposureRange
                        ?.let { "${formatShutter(it.lower)} – ${formatShutter(it.upper)}" }
                        ?: "unknown",
                )
                InfoRow(
                    "Min focus distance",
                    if (info.minFocusDistance > 0f) {
                        "%.2f diopters".format(info.minFocusDistance)
                    } else {
                        "fixed focus"
                    },
                )
                InfoRow("Preview size", "${info.previewSize.width} x ${info.previewSize.height}")
                InfoRow(
                    "AE compensation",
                    info.aeCompensationRange?.let { range ->
                        "%+.1f – %+.1f EV (step %.2f EV)".format(
                            range.lower * info.aeCompensationStep,
                            range.upper * info.aeCompensationStep,
                            info.aeCompensationStep,
                        )
                    } ?: "unsupported",
                )
                InfoRow("AE lock", yesNo(info.aeLockAvailable))
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = PixDim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(150.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = PixOnDark,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun yesNo(value: Boolean) = if (value) "yes" else "no"

/** Like [formatExposure] but always carries the unit, for range endpoints. */
private fun formatShutter(ns: Long): String {
    val s = ns / 1e9
    return if (s >= 0.5) "%.3g s".format(s) else "1/%d s".format((1 / s).toInt())
}
