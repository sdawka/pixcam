package com.example.pixcam.raw

/**
 * Everything the develop pipeline needs, extracted from CameraCharacteristics
 * and the shot's TotalCaptureResult.
 *
 * "Position order" below means the 2x2 CFA cell indexed by
 * pos = 2 * (y % 2) + (x % 2) for absolute pixel coords (x, y) — i.e.
 * [top-left, top-right, bottom-left, bottom-right] — NOT channel (RGGB) order.
 * Extraction code maps channel-ordered Camera2 values into position order so
 * the shader never needs to know the CFA layout except through [cfa].
 */
class DevelopParams(
    val width: Int,
    val height: Int,
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0=RGGB 1=GRBG 2=GBRG 3=BGGR */
    val cfa: Int,
    val whiteLevel: Float,
    /** 4 entries, position order (dynamic per-frame black level when available) */
    val blackLevel: FloatArray,
    /** 4 white-balance gains, position order */
    val wbGains: FloatArray,
    /** row-major 3x3, white-balanced camera RGB -> linear sRGB */
    val ccm: FloatArray,
    /** shadingRows * shadingCols * 4 gains in position order, bilinearly
     *  stretched over the full image; null = identity (no correction) */
    val shadingMap: FloatArray?,
    val shadingRows: Int,
    val shadingCols: Int,
) {
    init {
        require(cfa in 0..3) { "unsupported CFA $cfa" }
        require(blackLevel.size == 4 && wbGains.size == 4 && ccm.size == 9)
        if (shadingMap != null) {
            require(shadingMap.size == shadingRows * shadingCols * 4)
        }
    }
}
