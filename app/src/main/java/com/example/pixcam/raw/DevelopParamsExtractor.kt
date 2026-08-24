package com.example.pixcam.raw

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log

/**
 * Pulls a [DevelopParams] out of CameraCharacteristics + a shot's TotalCaptureResult.
 *
 * Camera2 hands back per-channel data (R, G_even, G_odd, B — G_even being the green
 * that shares a row with red) while [DevelopParams] wants everything in *position*
 * order (top-left, top-right, bottom-left, bottom-right of the 2x2 CFA cell). All the
 * channel -> position remapping lives here so the rest of the pipeline never has to
 * think about the CFA layout except through [DevelopParams.cfa].
 */
object DevelopParamsExtractor {

    private const val TAG = "pixcam"

    // Channel index space shared with RggbChannelVector / LensShadingMap constants:
    // 0 = RED, 1 = GREEN_EVEN, 2 = GREEN_ODD, 3 = BLUE.
    private const val CH_R = 0
    private const val CH_GE = 1
    private const val CH_GO = 2
    private const val CH_B = 3

    // posToChannel[position] = channel index at that position, per CFA layout.
    private val POS_TO_CHANNEL = arrayOf(
        intArrayOf(CH_R, CH_GE, CH_GO, CH_B),  // 0 RGGB
        intArrayOf(CH_GE, CH_R, CH_B, CH_GO),  // 1 GRBG
        intArrayOf(CH_GE, CH_B, CH_R, CH_GO),  // 2 GBRG
        intArrayOf(CH_B, CH_GE, CH_GO, CH_R),  // 3 BGGR
    )

    fun extract(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        width: Int,
        height: Int,
    ): DevelopParams? {
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        if (cfa == null || cfa > 3) return null

        val posToChannel = POS_TO_CHANNEL[cfa]

        val whiteLevel = (
            result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            )?.toFloat() ?: 1023f

        val blackLevel = extractBlackLevel(characteristics, result)
        val wbGains = extractWbGains(characteristics, result, posToChannel)
        val ccm = extractCcm(result)
        val (shadingMap, shadingRows, shadingCols) = extractShadingMap(result, posToChannel)

        return DevelopParams(
            width = width,
            height = height,
            cfa = cfa,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
            wbGains = wbGains,
            ccm = ccm,
            shadingMap = shadingMap,
            shadingRows = shadingRows,
            shadingCols = shadingCols,
        )
    }

    private fun extractBlackLevel(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
    ): FloatArray {
        result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { return it.copyOf() }

        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (pattern != null) {
            // getOffsetForIndex takes (column, row); position order is
            // [top-left, top-right, bottom-left, bottom-right].
            return floatArrayOf(
                pattern.getOffsetForIndex(0, 0).toFloat(),
                pattern.getOffsetForIndex(1, 0).toFloat(),
                pattern.getOffsetForIndex(0, 1).toFloat(),
                pattern.getOffsetForIndex(1, 1).toFloat(),
            )
        }
        return floatArrayOf(64f, 64f, 64f, 64f)
    }

    private fun extractWbGains(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        posToChannel: IntArray,
    ): FloatArray {
        val channelGains: FloatArray = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
            floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
        } ?: result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { neutral ->
            val r = 1f / neutral[0].toFloat()
            val g = 1f / neutral[1].toFloat()
            val b = 1f / neutral[2].toFloat()
            val min = minOf(r, g, b)
            floatArrayOf(r / min, g / min, g / min, b / min)
        } ?: run {
            Log.w(TAG, "no COLOR_CORRECTION_GAINS or SENSOR_NEUTRAL_COLOR_POINT; using identity WB gains")
            floatArrayOf(1f, 1f, 1f, 1f)
        }

        return FloatArray(4) { pos -> channelGains[posToChannel[pos]] }
    }

    private fun extractCcm(result: TotalCaptureResult): FloatArray {
        val transform: ColorSpaceTransform? = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (transform == null) {
            Log.w(TAG, "no COLOR_CORRECTION_TRANSFORM; using identity CCM")
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
        }
        // getElement takes (column, row); output is row-major.
        val m = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                m[row * 3 + col] = transform.getElement(col, row).toFloat()
            }
        }
        return m
    }

    private fun extractShadingMap(
        result: TotalCaptureResult,
        posToChannel: IntArray,
    ): Triple<FloatArray?, Int, Int> {
        val map: LensShadingMap? = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        if (map == null) return Triple(null, 0, 0)

        val rows = map.rowCount
        val cols = map.columnCount
        val out = FloatArray(rows * cols * 4)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val base = (row * cols + col) * 4
                for (pos in 0 until 4) {
                    val channel = posToChannel[pos]
                    out[base + pos] = map.getGainFactor(channel, col, row)
                }
            }
        }
        return Triple(out, rows, cols)
    }
}
