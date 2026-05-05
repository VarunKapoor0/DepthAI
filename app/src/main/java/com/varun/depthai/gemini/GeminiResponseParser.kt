package com.varun.depthai.gemini

import android.util.Log
import com.varun.depthai.model.ObjectAnalysis
import com.varun.depthai.model.ObjectComponent
import org.json.JSONObject

/**
 * Parses the raw JSON string from Gemini into an ObjectAnalysis.
 * Uses org.json — built into Android, no extra dependency needed.
 */
object GeminiResponseParser {

    private const val TAG = "GeminiResponseParser"

    /**
     * Parses a raw JSON string into an ObjectAnalysis.
     * Returns null if the JSON is malformed or missing required fields.
     */
    fun parse(json: String): ObjectAnalysis? {
        return try {
            val root = JSONObject(json)

            val objectName = root.getString("object")

            val level1Array = root.getJSONArray("level_1")
            val level1 = mutableListOf<ObjectComponent>()

            for (i in 0 until level1Array.length()) {
                val item = level1Array.getJSONObject(i)
                val uvArray = item.getJSONArray("anchor_uv")

                val component = ObjectComponent(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    description = item.getString("description"),
                    anchorUv = Pair(
                        uvArray.getDouble(0).toFloat(),
                        uvArray.getDouble(1).toFloat()
                    ),
                    visible = item.getBoolean("visible")
                )

                level1.add(component)
            }

            val result = ObjectAnalysis(
                objectName = objectName,
                level1 = level1
            )

            Log.d(TAG, "Parsed: ${result.objectName} with ${result.level1.size} components")
            result.level1.forEach { c ->
                Log.d(TAG, "  [${c.id}] ${c.name} @ UV(${c.anchorUv.first}, ${c.anchorUv.second}) visible=${c.visible}")
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}")
            Log.e(TAG, "Raw JSON was: $json")
            null
        }
    }
}
