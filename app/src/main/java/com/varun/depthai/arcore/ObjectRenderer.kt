package com.varun.depthai.arcore

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import de.javagl.obj.ObjData
import de.javagl.obj.ObjReader
import de.javagl.obj.ObjUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Renders an object loaded from an OBJ file in OpenGL. */
class ObjectRenderer {

    companion object {
        private const val TAG = "ObjectRenderer"
        private const val VERTEX_SHADER = "shaders/object.vert"
        private const val FRAGMENT_SHADER = "shaders/object.frag"
        private const val COORDS_PER_VERTEX = 3
        private val LIGHT_DIRECTION = floatArrayOf(0.250f, 0.866f, 0.433f, 0.0f)
    }

    private val viewLightDirection = FloatArray(4)

    private var vertexBufferId = 0
    private var verticesBaseAddress = 0
    private var texCoordsBaseAddress = 0
    private var normalsBaseAddress = 0
    private var indexBufferId = 0
    private var indexCount = 0

    private var program = 0
    private val textures = IntArray(1)

    private var modelViewUniform = 0
    private var modelViewProjectionUniform = 0
    private var positionAttribute = 0
    private var normalAttribute = 0
    private var texCoordAttribute = 0
    private var textureUniform = 0
    private var lightingParametersUniform = 0
    private var materialParametersUniform = 0
    private var colorCorrectionParameterUniform = 0
    private var colorUniform = 0

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    private var ambient = 0.3f
    private var diffuse = 1.0f
    private var specular = 1.0f
    private var specularPower = 6.0f

    fun createOnGlThread(context: Context, objAssetName: String, diffuseTextureAssetName: String) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)
        ShaderUtil.checkGLError(TAG, "Program creation")

        modelViewUniform = GLES20.glGetUniformLocation(program, "u_ModelView")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        normalAttribute = GLES20.glGetAttribLocation(program, "a_Normal")
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        lightingParametersUniform = GLES20.glGetUniformLocation(program, "u_LightingParameters")
        materialParametersUniform = GLES20.glGetUniformLocation(program, "u_MaterialParameters")
        colorCorrectionParameterUniform = GLES20.glGetUniformLocation(program, "u_ColorCorrectionParameters")
        colorUniform = GLES20.glGetUniformLocation(program, "u_ObjColor")
        ShaderUtil.checkGLError(TAG, "Program parameters")

        // Load texture
        val textureBitmap = BitmapFactory.decodeStream(context.assets.open(diffuseTextureAssetName))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glGenTextures(textures.size, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textureBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        textureBitmap.recycle()
        ShaderUtil.checkGLError(TAG, "Texture loading")

        // Load OBJ
        val obj = ObjUtils.convertToRenderable(ObjReader.read(context.assets.open(objAssetName)))
        val wideIndices = ObjData.getFaceVertexIndices(obj, 3)
        val vertices = ObjData.getVertices(obj)
        val texCoords = ObjData.getTexCoords(obj, 2)
        val normals = ObjData.getNormals(obj)

        val indices: ShortBuffer = ByteBuffer.allocateDirect(2 * wideIndices.limit())
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        while (wideIndices.hasRemaining()) indices.put(wideIndices.get().toShort())
        indices.rewind()

        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        vertexBufferId = buffers[0]
        indexBufferId = buffers[1]

        verticesBaseAddress = 0
        texCoordsBaseAddress = verticesBaseAddress + 4 * vertices.limit()
        normalsBaseAddress = texCoordsBaseAddress + 4 * texCoords.limit()
        val totalBytes = normalsBaseAddress + 4 * normals.limit()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, totalBytes, null, GLES20.GL_STATIC_DRAW)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, verticesBaseAddress, 4 * vertices.limit(), vertices)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, texCoordsBaseAddress, 4 * texCoords.limit(), texCoords)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, normalsBaseAddress, 4 * normals.limit(), normals)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        indexCount = indices.limit()
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, 2 * indexCount, indices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        ShaderUtil.checkGLError(TAG, "OBJ buffer load")

        Matrix.setIdentityM(modelMatrix, 0)
    }

    fun updateModelMatrix(modelMatrix: FloatArray, scaleFactor: Float) {
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        scaleMatrix[0] = scaleFactor
        scaleMatrix[5] = scaleFactor
        scaleMatrix[10] = scaleFactor
        Matrix.multiplyMM(this.modelMatrix, 0, modelMatrix, 0, scaleMatrix, 0)
    }

    fun setMaterialProperties(ambient: Float, diffuse: Float, specular: Float, specularPower: Float) {
        this.ambient = ambient
        this.diffuse = diffuse
        this.specular = specular
        this.specularPower = specularPower
    }

    fun draw(cameraView: FloatArray, cameraPerspective: FloatArray, colorCorrectionRgba: FloatArray, objColor: FloatArray) {
        ShaderUtil.checkGLError(TAG, "Before draw")
        Matrix.multiplyMM(modelViewMatrix, 0, cameraView, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraPerspective, 0, modelViewMatrix, 0)

        GLES20.glUseProgram(program)
        Matrix.multiplyMV(viewLightDirection, 0, modelViewMatrix, 0, LIGHT_DIRECTION, 0)
        normalizeVec3(viewLightDirection)
        GLES20.glUniform4f(lightingParametersUniform, viewLightDirection[0], viewLightDirection[1], viewLightDirection[2], 1f)
        GLES20.glUniform4fv(colorCorrectionParameterUniform, 1, colorCorrectionRgba, 0)
        GLES20.glUniform4fv(colorUniform, 1, objColor, 0)
        GLES20.glUniform4f(materialParametersUniform, ambient, diffuse, specular, specularPower)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
        GLES20.glVertexAttribPointer(positionAttribute, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, verticesBaseAddress)
        GLES20.glVertexAttribPointer(normalAttribute, 3, GLES20.GL_FLOAT, false, 0, normalsBaseAddress)
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, texCoordsBaseAddress)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glUniformMatrix4fv(modelViewUniform, 1, false, modelViewMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(normalAttribute)
        GLES20.glEnableVertexAttribArray(texCoordAttribute)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(normalAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        ShaderUtil.checkGLError(TAG, "After draw")
    }

    private fun normalizeVec3(v: FloatArray) {
        val reciprocalLength = 1.0f / Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        v[0] *= reciprocalLength
        v[1] *= reciprocalLength
        v[2] *= reciprocalLength
    }
}
