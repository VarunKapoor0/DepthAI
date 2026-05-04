package com.varun.depthai.arcore

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {

    companion object {
        private const val TAG = "BackgroundRenderer"
        private const val CAMERA_VERTEX_SHADER = "shaders/screenquad.vert"
        private const val CAMERA_FRAGMENT_SHADER = "shaders/screenquad.frag"
        private const val DEPTH_VERTEX_SHADER = "shaders/background_show_depth_map.vert"
        private const val DEPTH_FRAGMENT_SHADER = "shaders/background_show_depth_map.frag"

        private const val COORDS_PER_VERTEX = 2
        private const val TEXCOORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4
        private const val MAX_DEPTH_RANGE_MM = 20000.0f

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, +1.0f, -1.0f, -1.0f, +1.0f, +1.0f, +1.0f
        )
    }

    var textureId: Int = -1
        private set

    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private var calculateUVTransform = true

    private var quadProgram: Int = 0
    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0

    private var depthProgram: Int = 0
    private var depthTextureParam: Int = 0
    private var depthQuadPositionParam: Int = 0
    private var depthQuadTexCoordParam: Int = 0
    private var depthRangeToRenderMmParam: Int = 0
    private var depthTextureId: Int = -1
    private var depthRangeToRenderMm: Float = 0f

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val numVertices = 4
        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords.put(QUAD_COORDS)
        quadCoords.position(0)

        val bbTexCoords = ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE)
        bbTexCoords.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoords.asFloatBuffer()

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)
        GLES20.glUseProgram(quadProgram)
        ShaderUtil.checkGLError(TAG, "Program creation")

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        ShaderUtil.checkGLError(TAG, "Program parameters")
    }

    fun createDepthShaders(context: Context, depthTexId: Int) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, DEPTH_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, DEPTH_FRAGMENT_SHADER)

        depthProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(depthProgram, vertexShader)
        GLES20.glAttachShader(depthProgram, fragmentShader)
        GLES20.glLinkProgram(depthProgram)
        GLES20.glUseProgram(depthProgram)
        ShaderUtil.checkGLError(TAG, "Depth program creation")

        depthTextureParam = GLES20.glGetUniformLocation(depthProgram, "u_Depth")
        ShaderUtil.checkGLError(TAG, "Depth program parameters")

        depthQuadPositionParam = GLES20.glGetAttribLocation(depthProgram, "a_Position")
        depthQuadTexCoordParam = GLES20.glGetAttribLocation(depthProgram, "a_TexCoord")
        depthRangeToRenderMmParam = GLES20.glGetUniformLocation(depthProgram, "u_DepthRangeToRenderMm")
        depthTextureId = depthTexId
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged() || calculateUVTransform) {
            calculateUVTransform = false
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        if (frame.timestamp == 0L) return
        drawBackground()
    }

    private fun drawBackground() {
        quadTexCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(quadProgram)
        GLES20.glVertexAttribPointer(quadPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(quadTexCoordParam, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        ShaderUtil.checkGLError(TAG, "BackgroundRenderer draw")
    }

    fun drawDepth(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        if (frame.timestamp == 0L || depthTextureId == -1) return

        quadTexCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        // Blend must be enabled BEFORE the draw call for alpha sweep to work
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES20.glUseProgram(depthProgram)
        GLES20.glUniform1i(depthTextureParam, 0)

        // Update sweep range BEFORE draw so shader uses current value
        depthRangeToRenderMm += 50.0f
        if (depthRangeToRenderMm > MAX_DEPTH_RANGE_MM) depthRangeToRenderMm = 0f
        GLES20.glUniform1f(depthRangeToRenderMmParam, depthRangeToRenderMm)

        GLES20.glVertexAttribPointer(depthQuadPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(depthQuadTexCoordParam, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(depthQuadPositionParam)
        GLES20.glEnableVertexAttribArray(depthQuadTexCoordParam)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(depthQuadPositionParam)
        GLES20.glDisableVertexAttribArray(depthQuadTexCoordParam)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        ShaderUtil.checkGLError(TAG, "BackgroundRenderer drawDepth")
    }
}
