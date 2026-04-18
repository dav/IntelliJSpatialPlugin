package dev.spatial.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal, generic 3D scene entity. Extend shape primitives by adding variants
 * handled in `scene.js`. The schema is deliberately small — renderers should
 * ignore unknown fields so older clients stay forward-compatible.
 */
@Serializable
data class Entity(
    val id: String,
    val kind: String = "box",
    val position: Vec3 = Vec3.ZERO,
    val rotation: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONE,
    val color: String = "#9aa0a6",
    val label: String? = null,
    val opacity: Float = 1f,
    val meta: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class Vec3(val x: Float, val y: Float, val z: Float) {
    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
    }
}

@Serializable
data class Scene(val entities: List<Entity>)

@Serializable
data class CameraFocus(
    val target: Vec3,
    val distance: Float = 5f,
    val durationMs: Int = 600,
)

@Serializable
data class FocusEntity(
    val entityId: String,
    val distance: Float = 4f,
    val durationMs: Int = 600,
)

@Serializable
data class Highlight(
    val entityIds: List<String>,
    val durationMs: Int = 1500,
    val color: String = "#ffffff",
)

@Serializable
data class Narrate(
    val text: String,
    val voice: String? = null,
    val rate: Float = 1f,
    val caption: Boolean = true,
)
