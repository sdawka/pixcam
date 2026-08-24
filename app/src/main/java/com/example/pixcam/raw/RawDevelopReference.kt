package com.example.pixcam.raw

import kotlin.math.pow

/**
 * Compact CPU reference for the RAW develop pipeline, valid for uniform-color
 * Bayer fields only (no real demosaic — skips straight to the per-channel
 * mean a Malvar demosaic converges to on a flat field). Used to cross-check
 * the GPU pipeline in tests, and kept in main source so it can double as a
 * CPU fallback later.
 */
object RawDevelopReference {

    // pos -> color index (0=R,1=G,2=B) for each CFA arrangement, position order
    // [top-left, top-right, bottom-left, bottom-right].
    private val COLOR_AT_POS = arrayOf(
        intArrayOf(0, 1, 1, 2), // 0 = RGGB
        intArrayOf(1, 0, 2, 1), // 1 = GRBG
        intArrayOf(1, 2, 0, 1), // 2 = GBRG
        intArrayOf(2, 1, 1, 0), // 3 = BGGR
    )

    /** Expected display-referred sRGB (0..1 floats) for a uniform Bayer field where every
     *  position p in the 2x2 cell has raw value rawValue[p]. Ignores shading (assume identity). */
    fun expectedColor(rawValue: FloatArray /*4, position order*/, params: DevelopParams): FloatArray /*3*/ {
        val colorAtPos = COLOR_AT_POS[params.cfa]
        val sums = FloatArray(3)
        val counts = IntArray(3)
        for (pos in 0 until 4) {
            val normalized = (rawValue[pos] - params.blackLevel[pos]) /
                (params.whiteLevel - params.blackLevel[pos])
            val wb = normalized * params.wbGains[pos]
            val color = colorAtPos[pos]
            sums[color] += wb
            counts[color]++
        }
        val cameraRgb = FloatArray(3) { sums[it] / counts[it] }

        val ccm = params.ccm
        val linear = FloatArray(3) { i ->
            ccm[i * 3] * cameraRgb[0] + ccm[i * 3 + 1] * cameraRgb[1] + ccm[i * 3 + 2] * cameraRgb[2]
        }.map { it.coerceIn(0f, 1f) }.toFloatArray()

        val toned = FloatArray(3) { hableNormalized(linear[it]) }
        return FloatArray(3) { srgbOetf(toned[it].coerceIn(0f, 1f)) }
    }

    private const val A = 0.15f
    private const val B = 0.50f
    private const val C = 0.10f
    private const val D = 0.20f
    private const val E = 0.02f
    private const val F = 0.30f

    private fun hable(x: Float): Float =
        ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F

    private fun hableNormalized(x: Float): Float = hable(x) / hable(1f)

    private fun srgbOetf(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f
        else 1.055f * c.pow(1f / 2.4f) - 0.055f
}
