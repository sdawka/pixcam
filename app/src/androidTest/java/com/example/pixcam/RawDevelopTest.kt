package com.example.pixcam

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pixcam.lut.CubeLut
import com.example.pixcam.raw.DevelopParams
import com.example.pixcam.raw.RawDevelopReference
import com.example.pixcam.raw.RawDeveloper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RawDevelopTest {

    private val WIDTH = 64
    private val HEIGHT = 48

    private val IDENTITY_CCM = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    private fun buildParams(cfa: Int, wbGains: FloatArray = floatArrayOf(1f, 1f, 1f, 1f)): DevelopParams =
        DevelopParams(
            width = WIDTH,
            height = HEIGHT,
            cfa = cfa,
            whiteLevel = 1023f,
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            wbGains = wbGains,
            ccm = IDENTITY_CCM,
            shadingMap = null,
            shadingRows = 0,
            shadingCols = 0,
        )

    private fun mosaic(width: Int, height: Int, valueAt: (x: Int, y: Int) -> Int): ShortArray {
        val out = ShortArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            out[y * width + x] = valueAt(x, y).toShort()
        }
        return out
    }

    private fun uniformMosaic(value: Int): ShortArray = mosaic(WIDTH, HEIGHT) { _, _ -> value }

    /** Raw value at position pos (0=TL,1=TR,2=BL,3=BR) for pixel (x, y). */
    private fun posMosaic(valueAtPos: (pos: Int) -> Int): ShortArray = mosaic(WIDTH, HEIGHT) { x, y ->
        valueAtPos(2 * (y % 2) + (x % 2))
    }

    private fun centerPixel(bmp: android.graphics.Bitmap): Int = bmp.getPixel(bmp.width / 2, bmp.height / 2)

    @Test
    fun gray_field_develops_neutral() {
        val params = buildParams(cfa = 0)
        val raw = uniformMosaic(543)
        val bmp = RawDeveloper.develop(raw, params, null)
        assertNotNull("develop() returned null bitmap", bmp)
        assertEquals(WIDTH, bmp!!.width)
        assertEquals(HEIGHT, bmp.height)

        val c = centerPixel(bmp)
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        assertTrue("not neutral: $r,$g,$b", abs(r - g) <= 6 && abs(g - b) <= 6 && abs(r - b) <= 6)

        val expected = RawDevelopReference.expectedColor(floatArrayOf(543f, 543f, 543f, 543f), params)
        assertTrue("r=$r expected ${expected[0] * 255}", abs(r - expected[0] * 255) <= 10)
        assertTrue("g=$g expected ${expected[1] * 255}", abs(g - expected[1] * 255) <= 10)
        assertTrue("b=$b expected ${expected[2] * 255}", abs(b - expected[2] * 255) <= 10)
    }

    @Test
    fun red_field_is_red_under_each_cfa() {
        // pos -> color index (0=R,1=G,2=B) matching RawDevelopReference, per CFA.
        val colorAtPos = mapOf(
            0 to intArrayOf(0, 1, 1, 2), // RGGB
            1 to intArrayOf(1, 0, 2, 1), // GRBG
            2 to intArrayOf(1, 2, 0, 1), // GBRG
            3 to intArrayOf(2, 1, 1, 0), // BGGR
        )
        for (cfa in 0..3) {
            val params = buildParams(cfa = cfa)
            val colors = colorAtPos.getValue(cfa)
            val raw = posMosaic { pos -> if (colors[pos] == 0) 900 else 64 }
            val bmp = RawDeveloper.develop(raw, params, null)
            assertNotNull("develop() returned null bitmap for cfa=$cfa", bmp)

            val c = centerPixel(bmp!!)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            assertTrue("cfa=$cfa red not strong: r=$r,g=$g,b=$b", r > 150)
            assertTrue("cfa=$cfa green leaking: r=$r,g=$g,b=$b", g < 60)
            assertTrue("cfa=$cfa blue leaking: r=$r,g=$g,b=$b", b < 60)
        }
    }

    @Test
    fun black_level_maps_to_black() {
        val params = buildParams(cfa = 0)
        val raw = uniformMosaic(64)
        val bmp = RawDeveloper.develop(raw, params, null)
        assertNotNull("develop() returned null bitmap", bmp)

        val c = centerPixel(bmp!!)
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        assertTrue("not black: $r,$g,$b", r < 12 && g < 12 && b < 12)
    }

    @Test
    fun white_balance_gains_apply() {
        val wbGains = floatArrayOf(2.0f, 1.0f, 1.0f, 1.0f)
        val params = buildParams(cfa = 0, wbGains = wbGains)
        val raw = uniformMosaic(543)
        val bmp = RawDeveloper.develop(raw, params, null)
        assertNotNull("develop() returned null bitmap", bmp)

        val c = centerPixel(bmp!!)
        val r = Color.red(c); val g = Color.green(c)
        assertTrue("not warmer: r=$r g=$g", r >= g + 25)

        val expected = RawDevelopReference.expectedColor(floatArrayOf(543f, 543f, 543f, 543f), params)
        val b = Color.blue(c)
        assertTrue("r=$r expected ${expected[0] * 255}", abs(r - expected[0] * 255) <= 10)
        assertTrue("g=$g expected ${expected[1] * 255}", abs(g - expected[1] * 255) <= 10)
        assertTrue("b=$b expected ${expected[2] * 255}", abs(b - expected[2] * 255) <= 10)
    }

    @Test
    fun lut_applies_after_develop() {
        val params = buildParams(cfa = 0)
        val raw = uniformMosaic(543)
        val size = 2
        val data = FloatArray(size * size * size * 3)
        for (i in 0 until size * size * size) {
            data[3 * i] = 1f; data[3 * i + 1] = 0f; data[3 * i + 2] = 0f
        }
        val lut = CubeLut("test-all-red", size, data)

        val bmp = RawDeveloper.develop(raw, params, lut)
        assertNotNull("develop() returned null bitmap", bmp)

        val c = centerPixel(bmp!!)
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        assertTrue("lut not applied: r=$r,g=$g,b=$b", r > 200 && g < 40 && b < 40)
    }
}
