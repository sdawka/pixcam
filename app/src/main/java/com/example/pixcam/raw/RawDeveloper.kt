package com.example.pixcam.raw

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.example.pixcam.lut.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Develops a 16-bit Bayer mosaic into a display-referred sRGB [Bitmap] on the
 * GPU: demosaic (Malvar-He-Cutler), CCM, filmic tone map, sRGB OETF, and an
 * optional 3D LUT — one fragment-shader pass over the full image, rendered
 * offscreen via EGL (same pattern as [com.example.pixcam.gl.LutStillProcessor]).
 * Runs on the camera's background HandlerThread, right after DNG save.
 */
object RawDeveloper {

    private const val TAG = "RawDeveloper"

    // Position-order (2x2 CFA cell) color codes matching the shader's
    // uCfaColor uniform: 0 = R, 1 = G, 2 = B.
    private val CFA_COLORS = arrayOf(
        intArrayOf(0, 1, 1, 2), // RGGB
        intArrayOf(1, 0, 2, 1), // GRBG
        intArrayOf(1, 2, 0, 1), // GBRG
        intArrayOf(2, 1, 1, 0), // BGGR
    )

    fun develop(raw: ShortArray, params: DevelopParams, lut: CubeLut?): Bitmap? {
        return try {
            developInternal(raw, params, lut)
        } catch (t: Throwable) {
            Log.w(TAG, "RAW develop failed", t)
            null
        }
    }

    private fun developInternal(raw: ShortArray, params: DevelopParams, lut: CubeLut?): Bitmap? {
        require(raw.size == params.width * params.height) {
            "raw size ${raw.size} != ${params.width}x${params.height}"
        }

        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        var program = 0
        var texRaw = 0
        var texShading = 0
        var texLut = 0
        var texDst = 0
        var fbo = 0
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
            if (params.width > maxTexSize[0] || params.height > maxTexSize[0]) {
                Log.w(TAG, "${params.width}x${params.height} exceeds GL_MAX_TEXTURE_SIZE ${maxTexSize[0]}")
                return null
            }

            program = buildProgram()
            if (program == 0) return null

            texRaw = uploadRawTexture(raw, params.width, params.height)
            texShading = uploadShadingTexture(params)
            texLut = if (lut != null) uploadLutTexture(lut) else 0

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texDst = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texDst)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, params.width, params.height, 0,
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

            GLES20.glViewport(0, 0, params.width, params.height)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texRaw)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uRaw"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texShading)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uShading"), 1)
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(program, "uUseShading"),
                if (params.shadingMap != null) 1 else 0,
            )

            val useLut = lut != null
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            if (useLut) GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 2)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uUseLut"), if (useLut) 1 else 0)
            if (useLut && lut != null) {
                val scale = (lut.size - 1).toFloat() / lut.size.toFloat()
                val offset = 1f / (2f * lut.size)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutScale"), scale)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutOffset"), offset)
            }

            GLES20.glUniform2i(GLES20.glGetUniformLocation(program, "uSize"), params.width, params.height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWhite"), params.whiteLevel)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uBlack"), 1, params.blackLevel, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uWb"), 1, params.wbGains, 0)
            GLES20.glUniform4iv(
                GLES20.glGetUniformLocation(program, "uCfaColor"), 1, CFA_COLORS[params.cfa], 0,
            )
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(program, "uShadingUvScale"),
                1f / params.width, 1f / params.height,
            )
            GLES20.glUniformMatrix3fv(
                GLES20.glGetUniformLocation(program, "uCcm"), 1, false, transposeMat3(params.ccm), 0,
            )

            drawFullscreenQuad(program)

            val buffer = ByteBuffer.allocateDirect(params.width * params.height * 4)
                .order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, params.width, params.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
            )
            buffer.rewind()

            // As in LutStillProcessor: GL_RGBA/UNSIGNED_BYTE byte order matches
            // ARGB_8888's native little-endian byte layout, no channel swap needed.
            // No extra V-flip is needed either: texelFetch(uRaw, ivec2(x,0)) always
            // addresses the first row of the array we uploaded (raw row 0, the top
            // of the image), and we map that row to gl_FragCoord.y ~= 0.5 (the
            // bottom of the viewport) — which is exactly the first row glReadPixels
            // returns, and exactly the row copyPixelsFromBuffer treats as row 0
            // (top) of the output Bitmap. The two conventions cancel out.
            val bitmap = Bitmap.createBitmap(params.width, params.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        } finally {
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (texDst != 0) GLES20.glDeleteTextures(1, intArrayOf(texDst), 0)
            if (texLut != 0) GLES20.glDeleteTextures(1, intArrayOf(texLut), 0)
            if (texShading != 0) GLES20.glDeleteTextures(1, intArrayOf(texShading), 0)
            if (texRaw != 0) GLES20.glDeleteTextures(1, intArrayOf(texRaw), 0)
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

    /** Row-major 3x3 -> the column-major layout glUniformMatrix3fv(transpose=false) needs. */
    private fun transposeMat3(m: FloatArray): FloatArray {
        val t = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                t[col * 3 + row] = m[row * 3 + col]
            }
        }
        return t
    }

    private fun uploadRawTexture(raw: ShortArray, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val buffer: ShortBuffer = ByteBuffer.allocateDirect(raw.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        buffer.put(raw)
        buffer.rewind()

        // Each texel is a single 2-byte uint16 sample. GL_UNPACK_ALIGNMENT
        // defaults to 4, which assumes each row is padded to a 4-byte boundary;
        // for an odd width that's wrong (row byte size = 2*width is not a
        // multiple of 4) and rows would be read mis-shifted. 2 matches the
        // texel's own byte size, so every row is read tightly packed regardless
        // of width parity.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, width, height, 0,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buffer,
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        return tex
    }

    /** RGBA16F table of position-order shading gains, or a 1x1 identity texture if none. */
    private fun uploadShadingTexture(params: DevelopParams): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val map = params.shadingMap
        val (w, h, data) = if (map != null) {
            Triple(params.shadingCols, params.shadingRows, map)
        } else {
            Triple(1, 1, floatArrayOf(1f, 1f, 1f, 1f))
        }
        val buffer = FloatBuffer.allocate(data.size)
        buffer.put(data)
        buffer.rewind()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer,
        )
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

    private fun drawFullscreenQuad(program: Int) {
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        val buffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(verts)
        buffer.rewind()

        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        buffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 8, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun buildProgram(): Int {
        val vertexSrc = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        // Demosaic uses the classic Malvar-He-Cutler (2004) 5x5 linear filters,
        // "High-Quality Linear Interpolation for Demosaicing of Bayer-Patterned
        // Color Images", in their integer/8 kernel form:
        //   filterG    - G at R or B locations
        //   filterRow  - color shared with the horizontal (row) neighbors
        //   filterCol  - color shared with the vertical (column) neighbors
        //   filterDiag - R at B locations / B at R locations
        val fragmentSrc = """
            #version 300 es
            precision highp float;
            precision highp int;
            precision highp usampler2D;
            precision highp sampler2D;
            precision highp sampler3D;

            out vec4 fragColor;

            uniform usampler2D uRaw;
            uniform ivec2 uSize;
            uniform float uWhite;
            uniform vec4 uBlack;
            uniform vec4 uWb;
            uniform ivec4 uCfaColor;

            uniform sampler2D uShading;
            uniform bool uUseShading;
            uniform vec2 uShadingUvScale;

            uniform mat3 uCcm;

            uniform sampler3D uLut;
            uniform bool uUseLut;
            uniform float uLutScale;
            uniform float uLutOffset;

            int cfaColorAt(int p) {
                if (p == 0) return uCfaColor.x;
                if (p == 1) return uCfaColor.y;
                if (p == 2) return uCfaColor.z;
                return uCfaColor.w;
            }

            float comp4(vec4 v, int idx) {
                if (idx == 0) return v.x;
                if (idx == 1) return v.y;
                if (idx == 2) return v.z;
                return v.w;
            }

            int posIndex(ivec2 p) {
                return 2 * (p.y & 1) + (p.x & 1);
            }

            uint fetchRaw(ivec2 p) {
                ivec2 c = clamp(p, ivec2(0), uSize - ivec2(1));
                return texelFetch(uRaw, c, 0).r;
            }

            float value(ivec2 p) {
                int pos = posIndex(p);
                float black = comp4(uBlack, pos);
                float norm = clamp((float(fetchRaw(p)) - black) / (uWhite - black), 0.0, 1.0);
                float shadeGain = 1.0;
                if (uUseShading) {
                    vec2 uv = (vec2(p) + 0.5) * uShadingUvScale;
                    shadeGain = comp4(texture(uShading, uv), pos);
                }
                return norm * shadeGain * comp4(uWb, pos);
            }

            float V(ivec2 p, int dx, int dy) {
                return value(p + ivec2(dx, dy));
            }

            // Filter 1: G at R/B locations.
            float filterG(ivec2 p) {
                return (
                    -V(p, 0, -2) + 2.0 * V(p, 0, -1)
                    - V(p, -2, 0) + 2.0 * V(p, -1, 0) + 4.0 * V(p, 0, 0) + 2.0 * V(p, 1, 0) - V(p, 2, 0)
                    + 2.0 * V(p, 0, 1) - V(p, 0, 2)
                ) / 8.0;
            }

            // Filter 2: color shared with the horizontal (row) neighbors.
            float filterRow(ivec2 p) {
                return (
                    0.5 * V(p, 0, -2)
                    - V(p, -1, -1) - V(p, 1, -1)
                    - V(p, -2, 0) + 4.0 * V(p, -1, 0) + 5.0 * V(p, 0, 0) + 4.0 * V(p, 1, 0) - V(p, 2, 0)
                    - V(p, -1, 1) - V(p, 1, 1)
                    + 0.5 * V(p, 0, 2)
                ) / 8.0;
            }

            // Filter 3: color shared with the vertical (column) neighbors.
            float filterCol(ivec2 p) {
                return (
                    -V(p, 0, -2)
                    - V(p, -1, -1) + 4.0 * V(p, 0, -1) - V(p, 1, -1)
                    + 0.5 * V(p, -2, 0) + 5.0 * V(p, 0, 0) + 0.5 * V(p, 2, 0)
                    - V(p, -1, 1) + 4.0 * V(p, 0, 1) - V(p, 1, 1)
                    - V(p, 0, 2)
                ) / 8.0;
            }

            // Filter 4: R at B locations / B at R locations (diagonal).
            float filterDiag(ivec2 p) {
                return (
                    -1.5 * V(p, 0, -2)
                    + 2.0 * V(p, -1, -1) + 2.0 * V(p, 1, -1)
                    - 1.5 * V(p, -2, 0) + 6.0 * V(p, 0, 0) - 1.5 * V(p, 2, 0)
                    + 2.0 * V(p, -1, 1) + 2.0 * V(p, 1, 1)
                    - 1.5 * V(p, 0, 2)
                ) / 8.0;
            }

            // Hable / Uncharted-2 filmic operator, unnormalized (matches
            // CameraController.buildCurve's ToneCurve.FILMIC).
            float hable(float x) {
                float a = 0.15; float b = 0.50; float c = 0.10;
                float d = 0.20; float e = 0.02; float f = 0.30;
                return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f;
            }

            float toneMap(float x) {
                return hable(x) / hable(1.0);
            }

            float srgbOetf(float x) {
                return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
            }

            void main() {
                ivec2 p = ivec2(int(gl_FragCoord.x), int(gl_FragCoord.y));
                int pos = posIndex(p);
                int myColor = cfaColorAt(pos);
                int colorH = cfaColorAt(pos ^ 1);

                float own = value(p);
                float r; float g; float b;
                if (myColor == 1) {
                    // Own pixel is green; the missing R/B channel that lies in the
                    // same row (horizontal neighbor) uses filterRow, the one in the
                    // same column uses filterCol.
                    g = own;
                    if (colorH == 0) {
                        r = filterRow(p);
                        b = filterCol(p);
                    } else {
                        r = filterCol(p);
                        b = filterRow(p);
                    }
                } else if (myColor == 0) {
                    r = own;
                    g = filterG(p);
                    b = filterDiag(p);
                } else {
                    b = own;
                    g = filterG(p);
                    r = filterDiag(p);
                }

                vec3 rgb = clamp(vec3(r, g, b), 0.0, 1.0);
                rgb = clamp(uCcm * rgb, 0.0, 1.0);
                rgb = vec3(toneMap(rgb.r), toneMap(rgb.g), toneMap(rgb.b));
                rgb = vec3(srgbOetf(rgb.r), srgbOetf(rgb.g), srgbOetf(rgb.b));

                if (uUseLut) {
                    vec3 coord = clamp(rgb, 0.0, 1.0) * uLutScale + uLutOffset;
                    rgb = texture(uLut, coord).rgb;
                }

                fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
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
}
