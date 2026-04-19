package dev.spatial

import dev.spatial.neural.FeedForwardNetworkCompiler
import dev.spatial.neural.FeedForwardNetworkScene
import dev.spatial.neural.NetworkConnection
import dev.spatial.neural.NetworkLayer
import kotlinx.serialization.json.jsonPrimitive
import junit.framework.TestCase

class FeedForwardNetworkCompilerTest : TestCase() {

    fun testCompilerBuildsLayeredNetworkWithSignedWeightedLinks() {
        val scene = FeedForwardNetworkScene(
            layers = listOf(
                NetworkLayer(
                    id = "inputs",
                    label = "Sensor Inputs",
                    nodeCount = 3,
                    nodeLabels = listOf("bias", "distance", "velocity"),
                ),
                NetworkLayer(id = "hidden-1", nodeCount = 2),
                NetworkLayer(
                    id = "outputs",
                    label = "Action Outputs",
                    nodeCount = 2,
                    nodeLabels = listOf("turn", "thrust"),
                ),
            ),
            connections = listOf(
                NetworkConnection("inputs", 0, "hidden-1", 0, weight = 0.8f),
                NetworkConnection("inputs", 1, "hidden-1", 0, weight = -0.6f),
                NetworkConnection("hidden-1", 0, "outputs", 0, weight = 0f),
                NetworkConnection("hidden-1", 1, "outputs", 1, weight = 0.2f),
            ),
        )

        val materialized = FeedForwardNetworkCompiler.compile(scene)

        assertEquals(10, materialized.entities.size)
        assertEquals(4, materialized.links.size)
        assertEquals("nn-layer:hidden-1", materialized.defaultFocusEntityId)

        val inputNode = materialized.entities.first { it.id == "nn-node:inputs:1" }
        val outputNode = materialized.entities.first { it.id == "nn-node:outputs:0" }
        assertEquals("distance", inputNode.label)
        assertEquals("turn", outputNode.label)
        assertEquals("input", inputNode.meta.getValue("role").jsonPrimitive.content)
        assertEquals("output", outputNode.meta.getValue("role").jsonPrimitive.content)

        val positive = materialized.links.first { it.id.contains("inputs:0") }
        val negative = materialized.links.first { it.id.contains("inputs:1") }
        val zero = materialized.links.first { it.id.contains("hidden-1:0->outputs:0") }
        assertEquals("#69b86d", positive.color)
        assertEquals("#d95c5c", negative.color)
        assertEquals("#7d8590", zero.color)
        assertTrue((positive.thickness ?: 0f) > (zero.thickness ?: 0f))
        assertTrue((negative.thickness ?: 0f) > (zero.thickness ?: 0f))
    }

    fun testCompilerRejectsBackwardConnections() {
        val scene = FeedForwardNetworkScene(
            layers = listOf(
                NetworkLayer(id = "inputs", nodeCount = 2),
                NetworkLayer(id = "outputs", nodeCount = 1),
            ),
            connections = listOf(
                NetworkConnection("outputs", 0, "inputs", 0, weight = 0.3f),
            ),
        )

        val error = try {
            FeedForwardNetworkCompiler.compile(scene)
            fail("Expected the compiler to reject a backward connection.")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue((error.message ?: "").contains("not feed-forward"))
    }

    fun testCompilerRejectsTooManyNodeLabels() {
        val scene = FeedForwardNetworkScene(
            layers = listOf(
                NetworkLayer(id = "inputs", nodeCount = 1, nodeLabels = listOf("a", "b")),
                NetworkLayer(id = "outputs", nodeCount = 1),
            ),
            connections = emptyList(),
        )

        val error = try {
            FeedForwardNetworkCompiler.compile(scene)
            fail("Expected the compiler to reject an oversized node-label list.")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue((error.message ?: "").contains("node labels"))
    }
}
