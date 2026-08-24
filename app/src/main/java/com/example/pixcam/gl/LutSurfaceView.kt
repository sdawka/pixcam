package com.example.pixcam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.pixcam.lut.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "LutSurfaceView"

/**
 * GLES3 viewfinder: camera frames arrive via SurfaceTexture (OES external texture) and are
 * drawn through an optional 3D LUT (sampler3D, hardware trilinear).
 */
class LutSurfaceView(context: Context) : GLSurfaceView(context) {

    /** Invoked on the main thread with a fresh Surface once the GL surface is (re)created. */
    var onCameraSurfaceReady: ((Surface) -> Unit)? = null

    private val renderer: LutRenderer

    init {
        setEGLContextClientVersion(3)
        renderer = LutRenderer(this)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBufferSize(width: Int, height: Int) = renderer.setBufferSize(width, height)

    fun setLut(lut: CubeLut?) = renderer.setLut(lut)

    private class LutRenderer(private val view: LutSurfaceView) : GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

        private val mainHandler = Handler(Looper.getMainLooper())

        // GL objects, all (re)created in onSurfaceCreated since a lost EGL context loses them.
        private var program = 0
        private var cameraTexId = 0
        private var lutTexId = 0
        private var uTexMatrixLoc = 0
        private var uCameraLoc = 0
        private var uLutLoc = 0
        private var uUseLutLoc = 0
        private var uLutScaleLoc = 0
        private var uLutOffsetLoc = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0

        private var surfaceTexture: SurfaceTexture? = null
        private val texMatrix = FloatArray(16)

        @Volatile private var frameAvailable = false
        @Volatile private var pendingWidth = 0
        @Volatile private var pendingHeight = 0
        @Volatile private var sizeDirty = false

        private val pendingLut = AtomicReference<CubeLut?>()
        private var currentLutSize = 0
        private var useLut = false

        fun setBufferSize(width: Int, height: Int) {
            pendingWidth = width
            pendingHeight = height
            sizeDirty = true
            view.queueEvent { applyPendingSize() }
        }

        fun setLut(lut: CubeLut?) {
            pendingLut.set(lut)
            view.queueEvent { uploadPendingLutIfAny() }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Total context loss assumed: tear down and rebuild every GL object.
            surfaceTexture?.release()
            surfaceTexture = null
            currentLutSize = 0

            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
            uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
            uCameraLoc = GLES30.glGetUniformLocation(program, "uCamera")
            uLutLoc = GLES30.glGetUniformLocation(program, "uLut")
            uUseLutLoc = GLES30.glGetUniformLocation(program, "uUseLut")
            uLutScaleLoc = GLES30.glGetUniformLocation(program, "uLutScale")
            uLutOffsetLoc = GLES30.glGetUniformLocation(program, "uLutOffset")

            cameraTexId = createOesTexture()
            lutTexId = createLutTexturePlaceholder()

            val st = SurfaceTexture(cameraTexId)
            st.setOnFrameAvailableListener(this)
            if (sizeDirty && pendingWidth > 0 && pendingHeight > 0) {
                st.setDefaultBufferSize(pendingWidth, pendingHeight)
            }
            surfaceTexture = st
            frameAvailable = false

            // Re-upload whatever LUT was set (survives context loss) or was pending.
            uploadPendingLutIfAny()

            val surface = Surface(st)
            mainHandler.post { view.onCameraSurfaceReady?.invoke(surface) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            applyPendingSize()

            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val st = surfaceTexture ?: return
            if (frameAvailable) {
                try {
                    st.updateTexImage()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "updateTexImage failed", e)
                    return
                }
                frameAvailable = false
                st.getTransformMatrix(texMatrix)
            } else {
                // Nothing captured yet; leave the clear-to-black from above on screen.
                return
            }

            GLES30.glUseProgram(program)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            GLES30.glUniform1i(uCameraLoc, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glUniform1i(uLutLoc, 1)

            GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES30.glUniform1f(uUseLutLoc, if (useLut) 1f else 0f)
            if (useLut && currentLutSize > 0) {
                val scale = (currentLutSize - 1).toFloat() / currentLutSize.toFloat()
                val offset = 1f / (2f * currentLutSize.toFloat())
                GLES30.glUniform1f(uLutScaleLoc, scale)
                GLES30.glUniform1f(uLutOffsetLoc, offset)
            }

            GLES30.glEnableVertexAttribArray(aPositionLoc)
            GLES30.glVertexAttribPointer(aPositionLoc, 2, GLES30.GL_FLOAT, false, 0, quadVertices)
            GLES30.glEnableVertexAttribArray(aTexCoordLoc)
            GLES30.glVertexAttribPointer(aTexCoordLoc, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(aPositionLoc)
            GLES30.glDisableVertexAttribArray(aTexCoordLoc)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            frameAvailable = true
            view.requestRender()
        }

        private fun applyPendingSize() {
            if (sizeDirty) {
                surfaceTexture?.setDefaultBufferSize(pendingWidth, pendingHeight)
                sizeDirty = false
            }
        }

        private fun uploadPendingLutIfAny() {
            val lut = pendingLut.get()
            if (lut == null) {
                useLut = false
                return
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            val buffer = ByteBuffer.allocateDirect(lut.data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(lut.data).position(0)

            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                lut.size, lut.size, lut.size, 0,
                GLES30.GL_RGB, GLES30.GL_FLOAT, buffer,
            )
            currentLutSize = lut.size
            useLut = true
        }

        private fun createOesTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
            )
            return ids[0]
        }

        private fun createLutTexturePlaceholder(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            return ids[0]
        }

        private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val status = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == GLES30.GL_FALSE) {
                val log = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
                throw RuntimeException("Program link failed: $log")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == GLES30.GL_FALSE) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw RuntimeException("Shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                    position(0)
                }

            private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
                    position(0)
                }

            private const val VERTEX_SHADER = """#version 300 es
                uniform mat4 uTexMatrix;
                in vec4 aPosition;
                in vec2 aTexCoord;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """

            private const val FRAGMENT_SHADER = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                uniform samplerExternalOES uCamera;
                uniform lowp sampler3D uLut;
                uniform float uUseLut;
                uniform float uLutScale;
                uniform float uLutOffset;
                in vec2 vTexCoord;
                out vec4 fragColor;
                void main() {
                    vec3 color = texture(uCamera, vTexCoord).rgb;
                    if (uUseLut > 0.5) {
                        vec3 coord = color * uLutScale + uLutOffset;
                        color = texture(uLut, coord).rgb;
                    }
                    fragColor = vec4(color, 1.0);
                }
            """
        }
    }
}
