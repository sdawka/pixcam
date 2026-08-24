package com.example.pixcam.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.pixcam.lut.CubeLut
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Bakes a [CubeLut] into a captured JPEG, preserving EXIF. Runs on the
 * caller's thread (the camera's background HandlerThread) — never throws,
 * on any failure the original bytes come back untouched.
 */
object LutStillProcessor {

    private const val TAG = "LutStillProcessor"

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
    )

    fun process(context: Context, jpeg: ByteArray, lut: CubeLut): ByteArray {
        return try {
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: return jpeg
            val graded = try {
                applyLut(src, lut) ?: applyLutCpu(src, lut)
            } finally {
                src.recycle()
            }
            val compressed = ByteArrayOutputStream().use { out ->
                graded.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.toByteArray()
            }
            graded.recycle()
            withExif(jpeg, compressed, context)
        } catch (t: Throwable) {
            Log.w(TAG, "LUT bake failed, returning original JPEG", t)
            jpeg
        }
    }

    // --- GPU path: EGL offscreen render, hardware trilinear via a 3D texture ---

    private fun applyLut(src: Bitmap, lut: CubeLut): Bitmap? {
        var display: EGLDisplay? = null
        var context: android.opengl.EGLContext? = null
        var surface: EGLSurface? = null
        var texSrc = 0
        var texLut = 0
        var texDst = 0
        var fbo = 0
        var program = 0
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) return null
            val config = configs[0] ?: return null

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val maxTexSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)
            if (src.width > maxTexSize[0] || src.height > maxTexSize[0]) return null

            program = buildProgram()
            if (program == 0) return null

            texSrc = uploadSourceTexture(src)
            texLut = uploadLutTexture(lut)

            // FBO-backed destination texture, same size as the source
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texDst = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texDst)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, src.width, src.height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )

            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            fbo = fboIds[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texDst, 0,
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                return null
            }

            GLES20.glViewport(0, 0, src.width, src.height)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texSrc)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSrc"), 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 1)

            val scale = (lut.size - 1).toFloat() / lut.size.toFloat()
            val offset = 1f / (2f * lut.size)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uScale"), scale)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOffset"), offset)

            drawFullscreenQuad(program)

            val buffer = ByteBuffer.allocateDirect(src.width * src.height * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, src.width, src.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
            )
            buffer.rewind()

            // GL_RGBA/UNSIGNED_BYTE bytes are R,G,B,A per pixel; Bitmap.ARGB_8888's
            // native buffer layout on little-endian Android is also R,G,B,A per byte
            // (the "ARGB" name describes the packed-int view, not the byte order), so
            // copyPixelsFromBuffer needs no channel swap here.
            val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            dst.copyPixelsFromBuffer(buffer)
            return dst
        } catch (t: Throwable) {
            Log.w(TAG, "GPU LUT path failed, falling back to CPU", t)
            return null
        } finally {
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (texDst != 0) GLES20.glDeleteTextures(1, intArrayOf(texDst), 0)
            if (texLut != 0) GLES20.glDeleteTextures(1, intArrayOf(texLut), 0)
            if (texSrc != 0) GLES20.glDeleteTextures(1, intArrayOf(texSrc), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (display != null) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != null) EGL14.eglDestroySurface(display, surface)
                if (context != null) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun uploadSourceTexture(src: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)
        return tex
    }

    private fun uploadLutTexture(lut: CubeLut): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val buffer = FloatBuffer.allocate(lut.data.size)
        buffer.put(lut.data)
        buffer.rewind()
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer,
        )
        return tex
    }

    private fun buildProgram(): Int {
        val vertexSrc = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTex;
            out vec2 vTex;
            void main() {
                // flip vertically: GL's row 0 is the bottom, the source bitmap's
                // row 0 is the top, so without this the readback comes out upside down
                vTex = vec2(aTex.x, 1.0 - aTex.y);
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val fragmentSrc = """
            #version 300 es
            precision highp float;
            in vec2 vTex;
            out vec4 fragColor;
            uniform sampler2D uSrc;
            uniform highp sampler3D uLut;
            uniform float uScale;
            uniform float uOffset;
            void main() {
                vec3 srcRgb = texture(uSrc, vTex).rgb;
                vec3 coord = clamp(srcRgb, 0.0, 1.0) * uScale + uOffset;
                fragColor = vec4(texture(uLut, coord).rgb, 1.0);
            }
        """.trimIndent()

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun drawFullscreenQuad(program: Int) {
        // triangle strip covering clip space, with matching texcoords
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val buffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(verts)
        buffer.rewind()

        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
        buffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    // --- CPU fallback: straightforward trilinear interpolation ---

    private fun applyLutCpu(src: Bitmap, lut: CubeLut): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val size = lut.size
        val data = lut.data
        val maxIdx = size - 1

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF

            val rf = (r / 255f) * maxIdx
            val gf = (g / 255f) * maxIdx
            val bf = (b / 255f) * maxIdx

            val r0 = rf.toInt().coerceIn(0, maxIdx)
            val g0 = gf.toInt().coerceIn(0, maxIdx)
            val b0 = bf.toInt().coerceIn(0, maxIdx)
            val r1 = (r0 + 1).coerceAtMost(maxIdx)
            val g1 = (g0 + 1).coerceAtMost(maxIdx)
            val b1 = (b0 + 1).coerceAtMost(maxIdx)

            val fr = rf - r0
            val fg = gf - g0
            val fb = bf - b0

            var outR = 0f; var outG = 0f; var outB = 0f
            for (ch in 0 until 3) {
                val c000 = data[3 * (r0 + size * g0 + size * size * b0) + ch]
                val c100 = data[3 * (r1 + size * g0 + size * size * b0) + ch]
                val c010 = data[3 * (r0 + size * g1 + size * size * b0) + ch]
                val c110 = data[3 * (r1 + size * g1 + size * size * b0) + ch]
                val c001 = data[3 * (r0 + size * g0 + size * size * b1) + ch]
                val c101 = data[3 * (r1 + size * g0 + size * size * b1) + ch]
                val c011 = data[3 * (r0 + size * g1 + size * size * b1) + ch]
                val c111 = data[3 * (r1 + size * g1 + size * size * b1) + ch]

                val c00 = c000 * (1 - fr) + c100 * fr
                val c10 = c010 * (1 - fr) + c110 * fr
                val c01 = c001 * (1 - fr) + c101 * fr
                val c11 = c011 * (1 - fr) + c111 * fr

                val c0 = c00 * (1 - fg) + c10 * fg
                val c1 = c01 * (1 - fg) + c11 * fg

                val c = c0 * (1 - fb) + c1 * fb
                when (ch) {
                    0 -> outR = c
                    1 -> outG = c
                    else -> outB = c
                }
            }

            val outRi = (outR * 255f).toInt().coerceIn(0, 255)
            val outGi = (outG * 255f).toInt().coerceIn(0, 255)
            val outBi = (outB * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (outRi shl 16) or (outGi shl 8) or outBi
        }

        val dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dst.setPixels(pixels, 0, width, 0, 0, width, height)
        return dst
    }

    // --- EXIF preservation ---

    private fun withExif(originalJpeg: ByteArray, compressedJpeg: ByteArray, context: Context): ByteArray {
        val temp = File.createTempFile("lut_still_", ".jpg", context.cacheDir)
        return try {
            temp.writeBytes(compressedJpeg)
            val src = ExifInterface(ByteArrayInputStream(originalJpeg))
            val dst = ExifInterface(temp.absolutePath)
            for (tag in EXIF_TAGS) {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            dst.saveAttributes()
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }
}
