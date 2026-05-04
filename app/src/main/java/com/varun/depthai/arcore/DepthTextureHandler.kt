package com.varun.depthai.arcore

import android.media.Image
import android.opengl.GLES20
import android.opengl.GLES30
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException

/**
 * Handles the raw depth texture from ARCore.
 * Uses acquireDepthImage16Bits() for raw depth — deliberately chosen over
 * the smoothed depth API for lower computational overhead on mid/low-end devices.
 */
class DepthTextureHandler {

    var depthTextureId: Int = -1
        private set
    var depthWidth: Int = -1
        private set
    var depthHeight: Int = -1
        private set

    /** Must be called on the GL thread. */
    fun createOnGLThread() {
        val textureId = IntArray(1)
        GLES20.glGenTextures(1, textureId, 0)
        depthTextureId = textureId[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    /** Must be called on the GL thread each frame. */
    fun update(frame: Frame) {
        try {
            val depthImage: Image = frame.acquireDepthImage16Bits()
            depthWidth = depthImage.width
            depthHeight = depthImage.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES30.GL_RG8,
                depthWidth,
                depthHeight,
                0,
                GLES30.GL_RG,
                GLES20.GL_UNSIGNED_BYTE,
                depthImage.planes[0].buffer
            )
            depthImage.close()
        } catch (e: NotYetAvailableException) {
            // Depth not yet available — silently skip this frame
        }
    }
}
