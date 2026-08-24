package com.example.pixcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pixcam.gl.LutStillProcessor
import com.example.pixcam.lut.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LutStillProcessorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun monoLut(size: Int = 9): CubeLut {
        val data = FloatArray(size * size * size * 3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b) / (size - 1)
            val i = 3 * (r + size * g + size * size * b)
            data[i] = y; data[i + 1] = y; data[i + 2] = y
        }
        return CubeLut("test-mono", size, data)
    }

    /** Four solid color quadrants, JPEG-encoded, with an EXIF orientation stamped on. */
    private fun testJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint()
        p.color = Color.RED; canvas.drawRect(0f, 0f, 64f, 48f, p)
        p.color = Color.GREEN; canvas.drawRect(64f, 0f, 128f, 48f, p)
        p.color = Color.BLUE; canvas.drawRect(0f, 48f, 64f, 96f, p)
        p.color = Color.rgb(128, 128, 128); canvas.drawRect(64f, 48f, 128f, 96f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
        bmp.recycle()

        val f = File.createTempFile("src", ".jpg", context.cacheDir)
        f.writeBytes(out.toByteArray())
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_MAKE, "pixcam-test")
            saveAttributes()
        }
        val bytes = f.readBytes()
        f.delete()
        return bytes
    }

    @Test
    fun monoLutDesaturatesAndKeepsExif() {
        val src = testJpeg()
        val result = LutStillProcessor.process(context, src, monoLut())

        assertTrue("processor returned original bytes unchanged", !result.contentEquals(src))

        val bmp = BitmapFactory.decodeByteArray(result, 0, result.size)
        assertEquals(128, bmp.width)
        assertEquals(96, bmp.height)
        // sample quadrant centers; every one must be gray after a luma LUT
        for ((x, y) in listOf(32 to 24, 96 to 24, 32 to 72, 96 to 72)) {
            val c = bmp.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            assertTrue("pixel ($x,$y) not gray: $r,$g,$b", abs(r - g) <= 14 && abs(g - b) <= 14)
        }
        // red quadrant must be darker than green (Rec.709 luma), proving channel mixing
        val red = Color.red(bmp.getPixel(32, 24))
        val green = Color.red(bmp.getPixel(96, 24))
        assertTrue("luma weights wrong: red=$red green=$green", red < green)
        bmp.recycle()

        val f = File.createTempFile("out", ".jpg", context.cacheDir)
        f.writeBytes(result)
        val exif = ExifInterface(f.absolutePath)
        assertEquals("6", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
        assertEquals("pixcam-test", exif.getAttribute(ExifInterface.TAG_MAKE))
        f.delete()
    }

    @Test
    fun garbageInputReturnsOriginal() {
        val junk = ByteArray(64) { it.toByte() }
        assertTrue(LutStillProcessor.process(context, junk, monoLut()).contentEquals(junk))
    }
}
