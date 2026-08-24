package com.example.pixcam

import com.example.pixcam.raw.ColorScience
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent double-precision copies of ColorScience's fixed matrices, for building expectations. */
private val BRADFORD_D50_TO_D65 = doubleArrayOf(
    0.9555766, -0.0230393, 0.0631636,
    -0.0282895, 1.0099416, 0.0210077,
    0.0122982, -0.0204830, 1.3299098,
)

private val XYZ_TO_SRGB = doubleArrayOf(
    3.2404542, -1.5371385, -0.4985314,
    -0.9692660, 1.8760108, 0.0415560,
    0.0556434, -0.2040259, 1.0572252,
)

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

private fun invert3x3(m: DoubleArray): DoubleArray {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    val invDet = 1.0 / det
    return doubleArrayOf(
        (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
        (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
        (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
    )
}

private fun toFloatArray(d: DoubleArray): FloatArray = FloatArray(9) { d[it].toFloat() }

class ColorScienceTest {

    @Test
    fun neutral_maps_to_white() {
        // Choose fm1 so that at w=0 (single illuminant), ccm = XYZ_TO_SRGB * BRADFORD * fm1
        // comes out to identity: fm1 = (XYZ_TO_SRGB * BRADFORD)^-1.
        val fm1 = toFloatArray(invert3x3(multiply3x3(XYZ_TO_SRGB, BRADFORD_D50_TO_D65)))
        val cm1 = toFloatArray(XYZ_TO_SRGB) // any invertible matrix
        val neutral = floatArrayOf(0.6f, 1.0f, 0.7f)

        val result = ColorScience.compute(21, cm1, fm1, null, null, null, neutral)
        assertNotNull(result)
        result!!

        for (i in 0..8) {
            val expected = if (i % 4 == 0) 1.0f else 0.0f
            assertTrue("ccm[$i]=${result.ccm[i]}", abs(result.ccm[i] - expected) < 1e-3f)
        }

        assertEquals(1.0f / 0.6f, result.wbGains[0], 1e-5f)
        assertEquals(1.0f / 1.0f, result.wbGains[1], 1e-5f)
        assertEquals(1.0f / 0.7f, result.wbGains[2], 1e-5f)

        // ccm * (neutral * wbGains) should map scene white to (1,1,1).
        val wbd = DoubleArray(3) { (neutral[it] * result.wbGains[it]).toDouble() }
        for (row in 0..2) {
            var sum = 0.0
            for (col in 0..2) sum += result.ccm[row * 3 + col] * wbd[col]
            assertTrue("row $row -> $sum", abs(sum - 1.0) < 1e-3)
        }
    }

    @Test
    fun interpolation_weight_moves_with_neutral() {
        // cm1 = cm2 = identity (camera == XYZ under both illuminants) isolates the weight
        // computation to depend purely on the neutral point's implied chromaticity.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val fm1 = identity
        val fm2 = floatArrayOf(1.05f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 0.95f)

        // Chromaticity of CIE StandardA (~2856K): x=0.4476, y=0.4074.
        val neutralWarm = floatArrayOf((0.4476f / 0.4074f), 1.0f, ((1f - 0.4476f - 0.4074f) / 0.4074f))
        // Chromaticity of CIE D65 (~6504K): x=0.31271, y=0.32902.
        val neutralCool = floatArrayOf((0.31271f / 0.32902f), 1.0f, ((1f - 0.31271f - 0.32902f) / 0.32902f))

        val warmResult = ColorScience.compute(17, identity, fm1, 21, identity, fm2, neutralWarm)
        val coolResult = ColorScience.compute(17, identity, fm1, 21, identity, fm2, neutralCool)
        assertNotNull(warmResult)
        assertNotNull(coolResult)
        warmResult!!; coolResult!!

        assertTrue(
            "warm cct=${warmResult.cct}",
            abs(warmResult.cct - 2856f) < abs(warmResult.cct - 6504f),
        )
        assertTrue(
            "cool cct=${coolResult.cct}",
            abs(coolResult.cct - 6504f) < abs(coolResult.cct - 2856f),
        )
        assertTrue(coolResult.cct > warmResult.cct)

        var differs = false
        for (i in 0..8) {
            if (abs(warmResult.ccm[i] - coolResult.ccm[i]) > 1e-4f) differs = true
        }
        assertTrue("expected ccm to differ between warm and cool neutral", differs)
    }

    @Test
    fun degenerate_neutral_returns_null() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val neutral = floatArrayOf(0f, 1f, 1f)
        val result = ColorScience.compute(21, identity, identity, null, null, null, neutral)
        assertNull(result)
    }

    @Test
    fun single_illuminant_no_interpolation() {
        val cm1 = toFloatArray(XYZ_TO_SRGB)
        val fm1 = floatArrayOf(1.02f, 0f, 0f, 0f, 1.0f, 0f, 0f, 0f, 0.98f)
        val neutral = floatArrayOf(1.0f, 1.0f, 1.0f)

        val result = ColorScience.compute(21, cm1, fm1, null, null, null, neutral)
        assertNotNull(result)
        result!!

        assertTrue(result.cct.isFinite() && result.cct > 0f)

        val expectedCcm = multiply3x3(XYZ_TO_SRGB, multiply3x3(BRADFORD_D50_TO_D65, doubleArrayOf(
            1.02, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.98,
        )))
        for (i in 0..8) {
            assertTrue("ccm[$i]=${result.ccm[i]} expected=${expectedCcm[i]}", abs(result.ccm[i] - expectedCcm[i]) < 1e-3)
        }
    }
}
