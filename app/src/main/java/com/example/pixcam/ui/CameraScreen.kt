package com.example.pixcam.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pixcam.camera.CameraController
import com.example.pixcam.camera.ManualControls
import com.example.pixcam.gallery.GalleryViewer
import com.example.pixcam.gl.LutSurfaceView
import com.example.pixcam.lut.CubeLut
import com.example.pixcam.lut.LutEntry
import com.example.pixcam.lut.LutRepository
import com.example.pixcam.theme.PixBackground

@Composable
fun CameraScreen(controller: CameraController) {
    val context = LocalContext.current
    val status by controller.status.collectAsState()
    val saving by controller.saving.collectAsState()
    var controls by remember { mutableStateOf(ManualControls()) }
    var proOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var shutterCount by remember { mutableIntStateOf(0) }
    var shotCount by remember { mutableIntStateOf(0) }
    var showGallery by remember { mutableStateOf(false) }
    var lutEntries by remember { mutableStateOf(LutRepository.list(context)) }
    var activeLutEntry by remember { mutableStateOf<LutEntry?>(null) }
    val notices = rememberNoticeState()

    val preview = controller.info.previewSize
    val glView = remember {
        LutSurfaceView(context).apply {
            setBufferSize(preview.width, preview.height)
            onCameraSurfaceReady = { surface -> controller.start(surface) }
        }
    }

    fun push(new: ManualControls) {
        if (new.grade != controls.grade) glView.setGrade(new.grade)
        controls = new
        controller.updateControls(new)
    }

    fun selectLut(entry: LutEntry?) {
        val cube: CubeLut? = entry?.let {
            try {
                LutRepository.load(context, it)
            } catch (e: Exception) {
                notices.show("LUT failed: ${e.message}")
                return
            }
        }
        activeLutEntry = entry
        glView.setLut(cube)
        controller.stillLut = cube
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val entry = LutRepository.import(context, uri)
            lutEntries = LutRepository.list(context)
            selectLut(entry)
            notices.show("Imported ${entry.name}")
        } catch (e: Exception) {
            notices.show("Import failed: ${e.message}")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // resume recreates the GL surface, which re-delivers the camera
                // surface via onCameraSurfaceReady and restarts the camera
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    controller.stop()
                    glView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
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
                        factory = { glView },
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
                    lutEntries = lutEntries,
                    activeLutId = activeLutEntry?.id,
                    onSelectLut = ::selectLut,
                    onImportLut = { importLauncher.launch(arrayOf("*/*")) },
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
