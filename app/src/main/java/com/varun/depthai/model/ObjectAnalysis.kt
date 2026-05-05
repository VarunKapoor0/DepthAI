package com.varun.depthai.model

/**
 * A single component of an object — one item in a level_1, level_2, or level_3 list.
 *
 * @param id Unique slug identifier, lowercase_underscore. Used as the key for drill-down lookups.
 * @param name Display name shown in the AR overlay label.
 * @param description One educational sentence about this component.
 * @param anchorUv Normalized image coordinates [u, v] where Gemini estimates this component
 *                 is located on the object surface. [0,0] = top-left, [1,1] = bottom-right.
 *                 Used to convert to 3D ARCore anchor via depth hit test.
 * @param visible True if the component is visible in the camera frame. False if hidden
 *                (e.g. internal components not visible from the current viewing angle).
 */
data class ObjectComponent(
    val id: String,
    val name: String,
    val description: String,
    val anchorUv: Pair<Float, Float>,
    val visible: Boolean
)

/**
 * The full analysis result for a scanned object.
 * Contains the object identity and its Level 1 component breakdown.
 *
 * Level 2 and Level 3 will be added in future iterations when drill-down is implemented.
 *
 * @param objectName The identified name of the scanned object (e.g. "computer monitor").
 * @param level1 Top-level components of the object, max 4 items.
 */
data class ObjectAnalysis(
    val objectName: String,
    val level1: List<ObjectComponent>
)
