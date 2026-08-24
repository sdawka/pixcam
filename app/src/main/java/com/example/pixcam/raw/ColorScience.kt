package com.example.pixcam.raw

/**
 * DNG 1.4 spec, forward-matrix color path.
 *
 * Replaces the HAL's per-shot COLOR_CORRECTION_GAINS/TRANSFORM with color science derived
 * directly from the sensor's static calibration (SENSOR_COLOR_TRANSFORM*, SENSOR_FORWARD_MATRIX*)
 * and the per-shot neutral point (SENSOR_NEUTRAL_COLOR_POINT), so the ISP's own color processing
 * is fully out of the loop. Pure Kotlin/JVM math, no android.* — the caller (CameraController)
 * is responsible for converting Camera2 Rational matrices into row-major FloatArrays.
 */
object ColorScience {

    class Result(
        val wbGains: FloatArray, // length 3, camera R,G,B — component-wise 1/neutral
        val ccm: FloatArray, // row-major 3x3: white-balanced camera RGB -> linear sRGB (D65)
        val cct: Float, // estimated scene white CCT in kelvin (for debug/info UI)
    )

    // EXIF LightSource code -> reference CCT (kelvin). DNG spec ties each calibration
    // illuminant to one of these fixed white points; unknown codes fall back to daylight.
    private val ILLUMINANT_CCT = mapOf(
        1 to 5500f, 2 to 4200f, 3 to 2850f, 4 to 5500f,
        9 to 5500f, 10 to 6500f, 11 to 7500f, 12 to 6430f,
        13 to 5000f, 14 to 4230f, 15 to 3450f, 17 to 2856f,
        18 to 4874f, 19 to 6774f, 20 to 5503f, 21 to 6504f,
        22 to 7504f, 23 to 5003f, 24 to 3200f,
    )

    // Bradford chromatic adaptation, D50 -> D65 (precomputed, row-major).
    private val BRADFORD_D50_TO_D65 = doubleArrayOf(
        0.9555766, -0.0230393, 0.0631636,
        -0.0282895, 1.0099416, 0.0210077,
        0.0122982, -0.0204830, 1.3299098,
    )

    // Linear XYZ (D65) -> linear sRGB, row-major.
    private val XYZ_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )

    fun compute(
        refIll1: Int, cm1: FloatArray, fm1: FloatArray,
        refIll2: Int?, cm2: FloatArray?, fm2: FloatArray?,
        neutral: FloatArray,
    ): Result? {
        if (neutral.size != 3 || neutral.any { it <= 0f }) return null

        val cm1d = toDouble(cm1)
        val fm1d = toDouble(fm1)
        val neutralD = doubleArrayOf(neutral[0].toDouble(), neutral[1].toDouble(), neutral[2].toDouble())

        val cct1 = illuminantToCct(refIll1)
        val hasPair2 = refIll2 != null && cm2 != null && fm2 != null
        val cct2 = if (hasPair2) illuminantToCct(refIll2) else cct1
        val cm2d = if (hasPair2) toDouble(cm2) else cm1d
        val fm2d = if (hasPair2) toDouble(fm2) else fm1d

        var w = 0.0
        var cct = cct1.toDouble()

        if (hasPair2 && cct1 != cct2) {
            // Self-consistent iteration (DNG SDK style): re-estimate the scene CCT from the
            // current interpolation weight, then re-derive the weight from that CCT using
            // reciprocal-CCT (mired) interpolation, until it settles.
            w = 0.5
            repeat(10) {
                val cmW = lerp3x3(cm1d, cm2d, w)
                val invCmW = invert3x3(cmW) ?: return null
                val xyzWhite = matVec3(invCmW, neutralD)
                val (x, y) = xyzToXy(xyzWhite)
                cct = mcCamyCct(x, y)
                val denom = (1.0 / cct2 - 1.0 / cct1.toDouble())
                w = if (denom == 0.0) 0.0 else ((1.0 / cct - 1.0 / cct1) / denom).coerceIn(0.0, 1.0)
            }
        } else {
            // Single illuminant (or coincident CCTs): no interpolation, but still report the
            // estimated scene white CCT for the debug/info UI.
            val invCm1 = invert3x3(cm1d) ?: return null
            val xyzWhite = matVec3(invCm1, neutralD)
            val (x, y) = xyzToXy(xyzWhite)
            cct = mcCamyCct(x, y)
        }

        val wbGains = floatArrayOf(
            (1.0 / neutralD[0]).toFloat(),
            (1.0 / neutralD[1]).toFloat(),
            (1.0 / neutralD[2]).toFloat(),
        )

        val fmW = lerp3x3(fm1d, fm2d, w)
        val ccmD = multiply3x3(XYZ_TO_SRGB, multiply3x3(BRADFORD_D50_TO_D65, fmW))
        val ccm = FloatArray(9) { ccmD[it].toFloat() }

        return Result(wbGains, ccm, cct.toFloat())
    }

    private fun illuminantToCct(code: Int): Float = ILLUMINANT_CCT[code] ?: 5500f

    private fun toDouble(m: FloatArray): DoubleArray = DoubleArray(9) { m[it].toDouble() }

    private fun lerp(a: Double, b: Double, w: Double): Double = a + (b - a) * w

    private fun lerp3x3(a: DoubleArray, b: DoubleArray, w: Double): DoubleArray =
        DoubleArray(9) { lerp(a[it], b[it], w) }

    private fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
                r[row * 3 + col] = sum
            }
        }
        return r
    }

    private fun matVec3(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    private fun invert3x3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-9) return null
        val invDet = 1.0 / det

        return doubleArrayOf(
            (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
            (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
            (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
        )
    }

    private fun xyzToXy(xyz: DoubleArray): Pair<Double, Double> {
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum == 0.0) return Pair(0.3320, 0.1858)
        return Pair(xyz[0] / sum, xyz[1] / sum)
    }

    // McCamy's approximation of CCT from CIE 1931 xy chromaticity.
    private fun mcCamyCct(x: Double, y: Double): Double {
        val denom = 0.1858 - y
        if (denom == 0.0) return 5500.0
        val n = (x - 0.3320) / denom
        return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
    }
}
