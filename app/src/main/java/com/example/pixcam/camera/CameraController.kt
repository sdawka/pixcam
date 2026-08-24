package com.example.pixcam.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.pixcam.gl.LutStillProcessor
import com.example.pixcam.lut.CubeLut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** Tone curve pushed to TONEMAP_CURVE; DEVICE leaves the HAL's own tone mapping alone. */
enum class ToneCurve { DEVICE, LINEAR, SRGB, FILMIC }

data class ManualControls(
    val manual: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 10_000_000L,
    // null = autofocus; otherwise diopters (0 = infinity)
    val focusDistance: Float? = null,
    // AE compensation index units (multiply by aeCompensationStep for EV); auto mode only
    val exposureCompensation: Int = 0,
    val aeLock: Boolean = false,
    val toneCurve: ToneCurve = ToneCurve.DEVICE,
)

data class CameraInfo(
    val cameraId: String,
    val hardwareLevel: String,
    val rawSupported: Boolean,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDistance: Float,
    val rawSize: Size?,
    val previewSize: Size,
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStep: Float,
    val aeLockAvailable: Boolean,
    val toneCurveSupported: Boolean,
    val maxCurvePoints: Int,
)

/**
 * Stage 0 capture core: back camera via Camera2, manual sensor controls,
 * ISP processing switched off where the HAL allows it, RAW_SENSOR stills
 * written as DNG (JPEG fallback on devices without the RAW capability).
 */
class CameraController(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("pixcam-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val info: CameraInfo = probe()
    private val characteristics = manager.getCameraCharacteristics(info.cameraId)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var stillReader: ImageReader? = null
    private var pendingResult: TotalCaptureResult? = null
    private var pendingImage: Image? = null
    private var onSaved: ((String) -> Unit)? = null

    private val _status = MutableStateFlow("initialized: ${describe()}")
    val status: StateFlow<String> = _status

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    var controls: ManualControls = ManualControls()
        private set

    // The viewfinder applies this LUT on the GPU; stills get it baked in at save
    // time (JPEG path only — DNGs stay raw by design). Read on the camera thread.
    @Volatile
    var stillLut: CubeLut? = null

    private fun probe(): CameraInfo {
        val backIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        }
        val id = backIds.firstOrNull { hasCapability(it, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) }
            ?: backIds.firstOrNull()
            ?: manager.cameraIdList.first()

        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val rawSize = if (raw) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        } else null
        // preview renders through a SurfaceTexture (GL viewfinder), not a SurfaceHolder
        val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?: map.getOutputSizes(android.view.SurfaceHolder::class.java)!!
        val previewSize = previewSizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: previewSizes.last()

        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL"
        }
        return CameraInfo(
            cameraId = id,
            hardwareLevel = level,
            rawSupported = raw,
            manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualPostProcessing = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            rawSize = rawSize,
            previewSize = previewSize,
            aeCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                ?.takeIf { it.lower != 0 || it.upper != 0 },
            aeCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f),
            aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
            // gate on the mode list only: some HALs offer CONTRAST_CURVE without MANUAL_POST_PROCESSING
            toneCurveSupported = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                ?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true,
            maxCurvePoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 64,
        )
    }

    private fun hasCapability(id: String, capability: Int): Boolean =
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(capability) == true

    private fun describe() = buildString {
        append("camera ${info.cameraId} ${info.hardwareLevel}")
        append(if (info.rawSupported) ", RAW ${info.rawSize}" else ", no RAW (JPEG fallback)")
        if (!info.manualSensor) append(", no manual sensor")
    }

    @SuppressLint("MissingPermission")
    fun start(surface: Surface) {
        stop() // idempotent: drop any stale device/session from a previous surface
        previewSurface = surface
        manager.openCamera(info.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, surface)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                _status.value = "camera error $error"
            }
        }, handler)
    }

    private fun createSession(camera: CameraDevice, surface: Surface) {
        val reader = if (info.rawSupported) {
            ImageReader.newInstance(info.rawSize!!.width, info.rawSize.height, ImageFormat.RAW_SENSOR, 2)
        } else {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val jpeg = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
            ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        }
        reader.setOnImageAvailableListener({
            pendingImage?.close()
            pendingImage = it.acquireNextImage()
            maybeSave()
        }, handler)
        stillReader = reader

        val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface))
        camera.createCaptureSession(SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    _status.value = "session configuration failed"
                }
            },
        ))
    }

    private fun startPreview() {
        val camera = device ?: return
        val surface = previewSurface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyControls(this)
        }
        try {
            session?.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            // late onConfigured for a device that stop() already closed
            Log.w("pixcam", "startPreview on closed session", e)
            return
        }
        _status.value = describe()
    }

    fun updateControls(new: ManualControls) {
        controls = new
        handler.post { startPreview() }
    }

    /** The honest-pipeline defaults: kill everything the HAL lets us kill. */
    private fun applyControls(b: CaptureRequest.Builder) {
        setIfSupported(b, CaptureRequest.NOISE_REDUCTION_MODE,
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES))
        setIfSupported(b, CaptureRequest.EDGE_MODE,
            CameraMetadata.EDGE_MODE_OFF,
            characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES))
        setIfSupported(b, CaptureRequest.HOT_PIXEL_MODE,
            CameraMetadata.HOT_PIXEL_MODE_OFF,
            characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES))

        val c = controls
        if (c.manual && info.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, info.isoRange?.clamp(c.iso) ?: c.iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, info.exposureRange?.clamp(c.exposureNs) ?: c.exposureNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // ignored under AE_MODE_OFF, so only set in auto mode
            info.aeCompensationRange?.let {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it.clamp(c.exposureCompensation))
            }
            if (info.aeLockAvailable) b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
        }
        if (c.focusDistance != null && info.minFocusDistance > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistance.coerceIn(0f, info.minFocusDistance))
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (c.toneCurve != ToneCurve.DEVICE && info.toneCurveSupported) {
            val pts = buildCurve(c.toneCurve)
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
        }
    }

    /** Interleaved (Pin, Pout) pairs for TONEMAP_CURVE, sampled uniformly over [0,1]. */
    private fun buildCurve(curve: ToneCurve): FloatArray {
        val n = minOf(info.maxCurvePoints, 64).coerceAtLeast(2)
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1)
            pts[i * 2] = x
            pts[i * 2 + 1] = when (curve) {
                ToneCurve.LINEAR, ToneCurve.DEVICE -> x
                ToneCurve.SRGB -> if (x <= 0.0031308f) 12.92f * x
                    else 1.055f * Math.pow(x.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
                ToneCurve.FILMIC -> hable(x) / hable(1f)
            }.coerceIn(0f, 1f)
        }
        // endpoints exactly, so the HAL never sees a curve that clips black or white
        pts[0] = 0f
        pts[1] = 0f
        pts[n * 2 - 2] = 1f
        pts[n * 2 - 1] = 1f
        return pts
    }

    // Hable / Uncharted-2 filmic operator, unnormalized
    private fun hable(x: Float): Float {
        val a = 0.15f; val b = 0.50f; val c = 0.10f
        val d = 0.20f; val e = 0.02f; val f = 0.30f
        return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f
    }

    private fun setIfSupported(b: CaptureRequest.Builder, key: CaptureRequest.Key<Int>, value: Int, available: IntArray?) {
        if (available?.contains(value) == true) b.set(key, value)
    }

    private fun <T : Comparable<T>> Range<T>.clamp(v: T): T = when {
        v < lower -> lower
        v > upper -> upper
        else -> v
    }

    fun capture(onSaved: (String) -> Unit) {
        val camera = device ?: return
        val reader = stillReader ?: return
        val activeSession = session ?: return
        this.onSaved = onSaved
        _saving.value = true
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            previewSurface?.let { addTarget(it) }
            applyControls(this)
            if (!info.rawSupported) {
                // JPEG fallback: without this the HAL writes no EXIF orientation
                // and landscape-mounted sensors come out sideways (UI is portrait-locked)
                set(CaptureRequest.JPEG_ORIENTATION,
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
            }
            // needed later to undo/redo vignetting in our own pipeline
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            // some HALs (notably the emulator) omit this from the result unless
            // it's in the request, and DngCreator requires it for BaselineExposure
            set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
        }
        try {
            activeSession.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    pendingResult = result
                    maybeSave()
                }
            }, handler)
        } catch (e: Exception) {
            Log.w("pixcam", "capture on closed session", e)
            _saving.value = false
        }
    }

    private fun maybeSave() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        pendingImage = null
        pendingResult = null
        try {
            val name = saveImage(image, result)
            _status.value = "saved $name"
            onSaved?.invoke(name)
        } catch (e: Exception) {
            Log.e("pixcam", "save failed", e)
            _status.value = "save failed: ${e.message}"
        } finally {
            image.close()
            _saving.value = false
        }
    }

    private fun saveImage(image: Image, result: TotalCaptureResult): String {
        val raw = image.format == ImageFormat.RAW_SENSOR
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "PIX_$stamp." + if (raw) "dng" else "jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (raw) "image/x-adobe-dng" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pixcam")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                if (raw) {
                    DngCreator(characteristics, result).use { dng ->
                        dng.setOrientation(exifOrientation())
                        dng.writeImage(out, image)
                    }
                } else {
                    val buf = image.planes[0].buffer
                    var bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    stillLut?.let { bytes = LutStillProcessor.process(context, bytes, it) }
                    out.write(bytes)
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return name
    }

    // UI is locked to portrait, so orientation depends only on the sensor mounting
    private fun exifOrientation(): Int =
        when (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) {
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }

    fun stop() {
        session?.close()
        session = null
        device?.close()
        device = null
        stillReader?.close()
        stillReader = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        previewSurface = null
        _saving.value = false
    }

    fun release() {
        stop()
        thread.quitSafely()
    }
}
