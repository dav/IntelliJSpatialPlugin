package dev.spatial.neural

import dev.spatial.scene.Entity
import dev.spatial.scene.Link
import dev.spatial.scene.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical semantic contract for a feed-forward neural network scene.
 *
 * Agents describe the layers and weighted connections; the plugin computes a
 * readable layered layout with node entities and weight-aware links.
 */
@Serializable
data class FeedForwardNetworkScene(
    val title: String? = null,
    val description: String? = null,
    val layers: List<NetworkLayer>,
    val connections: List<NetworkConnection>,
    val styles: FeedForwardNetworkStyles = FeedForwardNetworkStyles(),
)

@Serializable
data class NetworkLayer(
    val id: String,
    val label: String? = null,
    val nodeCount: Int,
    val nodeLabels: List<String> = emptyList(),
    val color: String? = null,
)

@Serializable
data class NetworkConnection(
    val fromLayerId: String,
    val fromNodeIndex: Int,
    val toLayerId: String,
    val toNodeIndex: Int,
    val weight: Float,
    val label: String? = null,
)

@Serializable
data class FeedForwardNetworkStyles(
    val layerGap: Float = 4.8f,
    val nodeGap: Float = 1.2f,
    val nodeRadius: Float = 0.34f,
    val layerWidth: Float = 1.9f,
    val layerHeight: Float = 0.08f,
    val layerPaddingZ: Float = 0.8f,
    val layerOpacity: Float = 0.28f,
    val nodeOpacity: Float = 1f,
    val linkOpacity: Float = 0.92f,
    val minLinkThickness: Float = 0.03f,
    val maxLinkThickness: Float = 0.22f,
    val showWeightLabels: Boolean = false,
    val inputNodeColor: String = "#5c8fcb",
    val hiddenNodeColor: String = "#b08968",
    val outputNodeColor: String = "#a06cd5",
    val inputLayerColor: String = "#3f5f85",
    val hiddenLayerColor: String = "#6d5845",
    val outputLayerColor: String = "#5d4a78",
    val negativeLinkColor: String = "#d95c5c",
    val neutralLinkColor: String = "#7d8590",
    val positiveLinkColor: String = "#69b86d",
)

data class MaterializedFeedForwardNetwork(
    val entities: List<Entity>,
    val links: List<Link>,
    val defaultFocusEntityId: String,
)

object FeedForwardNetworkCompiler {

    fun compile(scene: FeedForwardNetworkScene): MaterializedFeedForwardNetwork {
        require(scene.layers.size >= 2) { "Feed-forward network scene must include at least two layers." }

        val layersById = linkedMapOf<String, NetworkLayer>()
        scene.layers.forEachIndexed { index, layer ->
            require(layer.id.isNotBlank()) { "Layer ids must be non-blank." }
            require(layer.nodeCount > 0) { "Layer '${layer.id}' must contain at least one node." }
            require(layer.nodeLabels.size <= layer.nodeCount) {
                "Layer '${layer.id}' provides ${layer.nodeLabels.size} node labels for ${layer.nodeCount} nodes."
            }
            require(layersById.put(layer.id, layer) == null) { "Duplicate layer id '${layer.id}'." }
            require(layer.label?.isNotBlank() != false) { "Layer '${layer.id}' has a blank label." }
        }

        val layerIndices = scene.layers.mapIndexed { index, layer -> layer.id to index }.toMap()
        scene.connections.forEach { connection ->
            val fromLayer = layersById[connection.fromLayerId]
                ?: error("Connection source layer '${connection.fromLayerId}' does not exist.")
            val toLayer = layersById[connection.toLayerId]
                ?: error("Connection target layer '${connection.toLayerId}' does not exist.")
            require(connection.fromNodeIndex in 0 until fromLayer.nodeCount) {
                "Connection source node ${connection.fromNodeIndex} is out of bounds for layer '${fromLayer.id}'."
            }
            require(connection.toNodeIndex in 0 until toLayer.nodeCount) {
                "Connection target node ${connection.toNodeIndex} is out of bounds for layer '${toLayer.id}'."
            }
            require(layerIndices.getValue(connection.fromLayerId) < layerIndices.getValue(connection.toLayerId)) {
                "Connection '${connection.fromLayerId}[${connection.fromNodeIndex}] -> ${connection.toLayerId}[${connection.toNodeIndex}]' is not feed-forward."
            }
        }

        val entities = mutableListOf<Entity>()
        val links = mutableListOf<Link>()
        val styles = scene.styles
        val maxAbsWeight = scene.connections.maxOfOrNull { abs(it.weight) }?.takeIf { it > 0f } ?: 1f
        val totalWidth = (scene.layers.size - 1) * styles.layerGap
        val nodeDiameter = styles.nodeRadius * 2f
        val nodeY = styles.layerHeight + styles.nodeRadius + 0.06f

        scene.layers.forEachIndexed { index, layer ->
            val role = layerRole(index, scene.layers.size)
            val layerX = index * styles.layerGap - totalWidth / 2f
            val layerDepth = max(
                nodeDiameter + styles.layerPaddingZ * 2f,
                (layer.nodeCount - 1) * styles.nodeGap + nodeDiameter + styles.layerPaddingZ * 2f,
            )
            val layerEntityId = layerEntityId(layer.id)
            entities += Entity(
                id = layerEntityId,
                kind = "box",
                position = Vec3(layerX, styles.layerHeight / 2f, 0f),
                scale = Vec3(styles.layerWidth, styles.layerHeight, layerDepth),
                color = layer.color ?: layerColor(role, styles),
                label = layer.label ?: defaultLayerLabel(index, scene.layers.size),
                opacity = styles.layerOpacity,
                meta = metaOf(
                    "networkType" to "feedForwardLayer",
                    "layerId" to layer.id,
                    "role" to role,
                    "nodeCount" to layer.nodeCount,
                ),
            )

            val nodeZs = centeredOffsets(layer.nodeCount, styles.nodeGap)
            repeat(layer.nodeCount) { nodeIndex ->
                val nodeId = nodeEntityId(layer.id, nodeIndex)
                val nodeLabel = layer.nodeLabels.getOrNull(nodeIndex)
                entities += Entity(
                    id = nodeId,
                    kind = "sphere",
                    position = Vec3(layerX, nodeY, nodeZs[nodeIndex]),
                    scale = Vec3(nodeDiameter, nodeDiameter, nodeDiameter),
                    color = nodeColor(role, styles),
                    label = nodeLabel,
                    opacity = styles.nodeOpacity,
                    meta = metaOf(
                        "networkType" to "feedForwardNode",
                        "layerId" to layer.id,
                        "role" to role,
                        "nodeIndex" to nodeIndex,
                        "nodeLabel" to nodeLabel,
                    ),
                )
            }
        }

        scene.connections.forEach { connection ->
            val magnitude = abs(connection.weight) / maxAbsWeight
            links += Link(
                id = linkId(connection),
                fromId = nodeEntityId(connection.fromLayerId, connection.fromNodeIndex),
                toId = nodeEntityId(connection.toLayerId, connection.toNodeIndex),
                color = linkColor(connection.weight, styles),
                label = connection.label ?: connection.weight.takeIf { styles.showWeightLabels }?.let(::formatWeight),
                opacity = styles.linkOpacity,
                thickness = styles.minLinkThickness +
                    (styles.maxLinkThickness - styles.minLinkThickness) * magnitude,
            )
        }

        return MaterializedFeedForwardNetwork(
            entities = entities,
            links = links,
            defaultFocusEntityId = layerEntityId(scene.layers[scene.layers.size / 2].id),
        )
    }

    private fun centeredOffsets(count: Int, gap: Float): List<Float> =
        List(count) { index -> index * gap - ((count - 1) * gap / 2f) }

    private fun layerRole(index: Int, totalLayers: Int): String =
        when (index) {
            0 -> "input"
            totalLayers - 1 -> "output"
            else -> "hidden"
        }

    private fun defaultLayerLabel(index: Int, totalLayers: Int): String =
        when (index) {
            0 -> "Inputs"
            totalLayers - 1 -> "Outputs"
            else -> "Hidden ${index}"
        }

    private fun layerColor(role: String, styles: FeedForwardNetworkStyles): String =
        when (role) {
            "input" -> styles.inputLayerColor
            "output" -> styles.outputLayerColor
            else -> styles.hiddenLayerColor
        }

    private fun nodeColor(role: String, styles: FeedForwardNetworkStyles): String =
        when (role) {
            "input" -> styles.inputNodeColor
            "output" -> styles.outputNodeColor
            else -> styles.hiddenNodeColor
        }

    private fun linkColor(weight: Float, styles: FeedForwardNetworkStyles): String =
        when {
            weight > 0f -> styles.positiveLinkColor
            weight < 0f -> styles.negativeLinkColor
            else -> styles.neutralLinkColor
        }

    private fun formatWeight(weight: Float): String = if (weight >= 0f) {
        "+%.2f".format(weight)
    } else {
        "%.2f".format(weight)
    }

    private fun layerEntityId(layerId: String): String = "nn-layer:$layerId"

    private fun nodeEntityId(layerId: String, nodeIndex: Int): String = "nn-node:$layerId:$nodeIndex"

    private fun linkId(connection: NetworkConnection): String =
        "nn-link:${connection.fromLayerId}:${connection.fromNodeIndex}->${connection.toLayerId}:${connection.toNodeIndex}"

    private fun metaOf(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
        pairs.mapNotNull { (key, value) -> value?.let { key to jsonPrimitive(it) } }.toMap()

    private fun jsonPrimitive(value: Any): JsonElement =
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}
