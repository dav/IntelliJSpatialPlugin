package dev.spatial.scene

import kotlinx.serialization.Serializable

@Serializable
data class InteractionConfig(
    val controls: List<Pose2DControl> = emptyList(),
    val raySensors: List<RaySensorBinding> = emptyList(),
    val bearingSensors: List<BearingSensorBinding> = emptyList(),
)

@Serializable
data class Pose2DControl(
    val id: String,
    val entityId: String,
    val label: String? = null,
    val initialX: Float? = null,
    val initialZ: Float? = null,
    val initialHeadingDeg: Float? = null,
    val moveStep: Float = 0.5f,
    val rotateStepDeg: Float = 15f,
    val showUi: Boolean = true,
    val bounds: Pose2DBounds? = null,
)

@Serializable
data class Pose2DBounds(
    val minX: Float? = null,
    val maxX: Float? = null,
    val minZ: Float? = null,
    val maxZ: Float? = null,
)

@Serializable
data class RaySensorBinding(
    val id: String,
    val controlId: String,
    val wallEntityIds: List<String>,
    val anglesDeg: List<Float>,
    val maxDistance: Float,
    val valueNodeEntityIds: List<String> = emptyList(),
    val lineColor: String = "#8dd3ff",
    val missColor: String = "#6b7280",
    val valueMode: String = "proximity",
)

@Serializable
data class BearingSensorBinding(
    val id: String,
    val controlId: String,
    val targetEntityId: String,
    val sectorCenterAnglesDeg: List<Float>,
    val valueNodeEntityIds: List<String> = emptyList(),
    val falloffDeg: Float = 60f,
    val distanceNodeEntityId: String? = null,
    val distanceMax: Float = 20f,
    val distanceValueMode: String = "proximity",
)

@Serializable
data class InteractionState(
    val selectedControlId: String? = null,
    val controls: List<Pose2DControlState> = emptyList(),
    val values: List<InteractionValueState> = emptyList(),
)

@Serializable
data class Pose2DControlState(
    val id: String,
    val entityId: String,
    val x: Float,
    val z: Float,
    val headingDeg: Float,
)

@Serializable
data class InteractionValueState(
    val id: String,
    val value: Float,
)
