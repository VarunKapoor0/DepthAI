package com.varun.depthai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Converts an ARCore camera image (YUV_420_888) to a JPEG bitmap
 * suitable for sending to the Gemini Vision API.
 *
 * ARCore provides raw camera frames in YUV_420_888 format.
 * Gemini expects images as base64-encoded JPEG or PNG.
 *
 * The image is downsampled to reduce API payload size and latency
 * while retaining enough detail for object identification and
 * component UV coordinate estimation.
 */
object FrameConverter {

    private const val TAG = "FrameConverter"

    // Target resolution for Gemini — enough detail for object
    // identification without excessive token cost or latency
    private const val TARGET_WIDTH = 768
    private const val JPEG_QUALITY = 85

    /**
     * Converts a YUV_420_888 ARCore camera image to a base64-encoded JPEG string.
     *
     * Must be called on a background thread — bitmap operations are CPU-intensive.
     * The caller is responsible for closing the Image after this call returns.
     *
     * @param image The ARCore camera image in YUV_420_888 format
     * @return Base64-encoded JPEG string ready for the Gemini API, or null on failure
     */
    fun toBase64Jpeg(image: Image): String? {
        return try {
            val bitmap = yuv420ToBitmap(image) ?: return null
            val scaled = scaleBitmap(bitmap)
            val jpeg = bitmapToJpegBytes(scaled)
            bitmap.recycle()
            if (scaled !== bitmap) scaled.recycle()
            Base64.encodeToString(jpeg, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert frame to base64 JPEG", e)
            null
        }
    }

    /**
     * Converts a YUV_420_888 Image to an ARGB_8888 Bitmap.
     *
     * YUV_420_888 has 3 planes:
     *   Plane 0: Y (luma) — full resolution
     *   Plane 1: U/Cb (chroma blue) — half resolution
     *   Plane 2: V/Cr (chroma red) — half resolution
     *
     * We use Android's YuvImage which expects NV21 (YVU) format,
     * so we need to interleave the U and V planes correctly.
     */
    private fun yuv420ToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 format: Y plane followed by interleaved VU
        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U into NV21 format
        // NV21 expects VU interleaved, not UV
        val vuBuffer = nv21
        var pos = ySize
        val vArray = ByteArray(vSize)
        val uArray = ByteArray(uSize)
        vBuffer.get(vArray)
        uBuffer.get(uArray)

        // Handle pixel stride — some devices have stride > 1
        val pixelStride = uPlane.pixelStride
        if (pixelStride == 1) {
            // Simple case — no padding between pixels
            for (i in 0 until uSize) {
                vuBuffer[pos++] = vArray[i]
                vuBuffer[pos++] = uArray[i]
            }
        } else {
            // Handle pixel stride — skip padding bytes
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            val rowStride = uPlane.rowStride
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val idx = row * rowStride + col * pixelStride
                    if (idx < vArray.size && idx < uArray.size) {
                        vuBuffer[pos++] = vArray[idx]
                        vuBuffer[pos++] = uArray[idx]
                    }
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val jpegBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Scales the bitmap down to TARGET_WIDTH while maintaining aspect ratio.
     * Returns the original bitmap if it's already smaller than the target.
     */
    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= TARGET_WIDTH) return bitmap
        val scale = TARGET_WIDTH.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, targetHeight, true)
    }

    /**
     * Compresses a bitmap to JPEG bytes at the configured quality.
     */
    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
