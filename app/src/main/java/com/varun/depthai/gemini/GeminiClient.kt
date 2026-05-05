package com.varun.depthai.gemini

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.varun.depthai.BuildConfig

/**
 * Handles the Gemini API call.
 * Sends a camera frame and returns raw JSON string.
 * Deliberately lean — Level 1 only, minimal prompt, minimal response.
 *
 * Model: gemini-2.5-flash-lite — free tier, 1000 RPD, fast, sufficient for object identification.
 */
object GeminiClient {

    private const val TAG = "GeminiClient"

    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash-lite",
        apiKey = BuildConfig.GEMINI_API_KEY,
        generationConfig = generationConfig {
            temperature = 0.1f
            maxOutputTokens = 300
        }
    )

    private val PROMPT = """
Identify the main object in this image. Return ONLY valid JSON, no markdown, no backticks, no code blocks:
{"object":"[object name]","level_1":[{"id":"[component_slug]","name":"[Component Name]","description":"[one sentence about this component]","anchor_uv":[u,v],"visible":true}]}
Rules:
- max 4 items in level_1
- id must be lowercase_underscore, no spaces
- anchor_uv is normalized [0.0 to 1.0] image coordinates, [0,0]=top-left, [1,1]=bottom-right
- place anchor_uv at center of where that component is visible or most likely located on the object
- visible is true if component is visible in this image, false if hidden
- output raw JSON only, nothing else
    """.trimIndent()

    /**
     * Calls Gemini with a base64-encoded JPEG frame.
     * Decodes to Bitmap — the SDK requires Bitmap for image input.
     * Strips markdown code fences from response before returning.
     * Returns raw JSON string or null on failure.
     * Must be called from a coroutine.
     */
    suspend fun analyze(base64Jpeg: String): String? {
        return try {
            Log.d(TAG, "Decoding base64 to Bitmap...")
            val bytes = Base64.decode(base64Jpeg, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null.also { Log.e(TAG, "Failed to decode bitmap") }

            Log.d(TAG, "Sending to Gemini (${bitmap.width}x${bitmap.height})...")

            val response = model.generateContent(
                content {
                    image(bitmap)
                    text(PROMPT)
                }
            )

            bitmap.recycle()

            val raw = response.text ?: return null
            // Strip markdown code fences if Gemini includes them despite instructions
            val cleaned = raw
                .replace("```json", "")
                .replace("```", "")
                .trim()

            Log.d(TAG, "Gemini response (cleaned): $cleaned")
            cleaned

        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed: ${e.message}", e)
            null
        }
    }
}
