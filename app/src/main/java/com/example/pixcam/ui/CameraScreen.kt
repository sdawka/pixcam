package com.example.pixcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pixcam.camera.CameraController
import com.example.pixcam.camera.ManualControls
import com.example.pixcam.gallery.GalleryViewer
import com.example.pixcam.theme.PixBackground

@Composable
fun CameraScreen(controller: CameraController) {
    val status by controller.status.collectAsState()
    val saving by controller.saving.collectAsState()
    var controls by remember { mutableStateOf(ManualControls()) }
    var proOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var shutterCount by remember { mutableIntStateOf(0) }
    var shotCount by remember { mutableIntStateOf(0) }
    var showGallery by remember { mutableStateOf(false) }
    val notices = rememberNoticeState()

    fun push(new: ManualControls) {
        controls = new
        controller.updateControls(new)
    }

    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    Box(Modifier.fillMaxSize().background(PixBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            TopStrip(
                info = controller.info,
                controls = controls,
                onToggleAeLock = { push(controls.copy(aeLock = !controls.aeLock)) },
                // EV compensation is ignored under manual AE; reset it on the way in
                onToggleManual = { push(controls.copy(manual = !controls.manual, exposureCompensation = 0)) },
                onInfo = { infoOpen = true },
            )

            val preview = controller.info.previewSize
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    // sensor is landscape-oriented; portrait UI shows it rotated.
                    // aspectRatio shrinks to fit the weighted constraints, letterboxed by the centering Box
                    modifier = Modifier.aspectRatio(preview.height.toFloat() / preview.width),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.setFixedSize(preview.width, preview.height)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) = controller.start(h.surface)
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                                    override fun surfaceDestroyed(h: SurfaceHolder) = controller.stop()
                                })
                            }
                        },
                    )
                }

                CaptureFlash(trigger = shutterCount, modifier = Modifier.matchParentSize())

                NoticeHost(
                    notices,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )

                ProControlsPanel(
                    visible = proOpen,
                    info = controller.info,
                    controls = controls,
                    push = ::push,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            BottomBar(
                status = status,
                saving = saving,
                proOpen = proOpen,
                shotCount = shotCount,
                onTogglePro = { proOpen = !proOpen },
                onShutter = {
                    shutterCount++
                    controller.capture { name ->
                        notices.show("Saved $name")
                        shotCount++
                    }
                },
                onOpenGallery = { showGallery = true },
            )
        }

        // full-bleed on purpose: outside safeDrawingPadding
        if (showGallery) {
            GalleryViewer(onClose = { showGallery = false })
        }
    }

    if (infoOpen) {
        InfoDialog(controller.info, onDismiss = { infoOpen = false })
    }
}
