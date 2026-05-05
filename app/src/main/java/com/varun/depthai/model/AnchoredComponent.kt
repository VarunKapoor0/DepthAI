package com.varun.depthai.model

import com.google.ar.core.Anchor

/**
 * Pairs a parsed ObjectComponent with its ARCore anchor in 3D world space.
 * The anchor is created by running a depth hit test at the component's UV coordinate.
 *
 * Each frame, the anchor's pose is projected back to 2D screen space
 * to position the Compose label overlay.
 */
data class AnchoredComponent(
    val component: ObjectComponent,
    val anchor: Anchor
)
