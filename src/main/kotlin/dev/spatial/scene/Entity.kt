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

/**
 * Edge between two entities for SARF / architecture-map style views.
 * Endpoints are looked up by entity id at render time; links pointing at
 * missing entities are silently skipped (so push order doesn't matter).
 */
@Serializable
data class Link(
    val id: String,
    val fromId: String,
    val toId: String,
    val color: String = "#7d8590",
    val label: String? = null,
    val arrow: Boolean = false,
    val opacity: Float = 1f,
    val thickness: Float? = null,
)

@Serializable
data class LinkSet(val links: List<Link>)

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

@Serializable
data class TourStop(
    val entityId: String,
    val text: String? = null,
    val highlightIds: List<String> = emptyList(),
    val distance: Float? = null,
    val focusDurationMs: Int = 600,
    val highlightDurationMs: Int? = null,
    val preDelayMs: Int = 0,
    val postDelayMs: Int = 250,
    val minHoldMs: Int = 0,
    val voice: String? = null,
    val rate: Float? = null,
    val caption: Boolean = true,
    val color: String = "#ffffff",
    val waitForSpeech: Boolean = true,
)

@Serializable
data class TourRequest(
    val stops: List<TourStop>,
    val startIndex: Int = 0,
)

/**
 * One leaf cell of a churn-landscape treemap at a given moment in time.
 *
 * `path` is the cell's stable identity across frames — same path in two frames
 * means the same cell, animated between heights. `loc` (lines of code, or any
 * "size" measure) drives the cell's footprint in the treemap. `churn` (or any
 * "intensity" measure) drives its extrusion height. `color` is optional —
 * absent means the renderer picks one from a churn-based palette.
 */
@Serializable
data class LandscapeEntry(
    val path: String,
    val loc: Float,
    val churn: Float,
    val color: String? = null,
    val author: String? = null,
)

@Serializable
data class LandscapeFrame(
    val label: String,
    val entries: List<LandscapeEntry>,
)

@Serializable
data class LandscapeTimeline(
    val frames: List<LandscapeFrame>,
    val floorSize: Float = 20f,
    val maxHeight: Float = 6f,
)
