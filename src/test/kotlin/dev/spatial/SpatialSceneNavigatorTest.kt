package dev.spatial

import dev.spatial.navigation.SpatialSceneNavigator
import dev.spatial.navigation.SpatialTargetKind
import dev.spatial.scene.Entity
import dev.spatial.scene.LandscapeEntry
import dev.spatial.scene.LandscapeFrame
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Scene
import junit.framework.TestCase
import kotlinx.serialization.json.JsonPrimitive

class SpatialSceneNavigatorTest : TestCase() {

    fun testExactSceneEntityMatchBeatsParentClusterPath() {
        val scene = Scene(
            listOf(
                Entity(
                    id = "catalog",
                    meta = mapOf("path" to JsonPrimitive("src/catalog"))
                ),
                Entity(
                    id = "catalog-service",
                    meta = mapOf("path" to JsonPrimitive("src/catalog/CatalogService.kt"))
                ),
            )
        )

        val match = SpatialSceneNavigator.findTargetForFile(
            projectBasePath = "/tmp/project",
            scene = scene,
            landscape = null,
            filePath = java.nio.file.Path.of("/tmp/project/src/catalog/CatalogService.kt"),
        )

        assertNotNull(match)
        assertEquals("catalog-service", match?.targetId)
        assertEquals(SpatialTargetKind.SCENE_ENTITY, match?.kind)
    }

    fun testParentClusterPathMatchesWhenNoExactEntityExists() {
        val scene = Scene(
            listOf(
                Entity(
                    id = "catalog",
                    meta = mapOf("path" to JsonPrimitive("src/catalog"))
                )
            )
        )

        val match = SpatialSceneNavigator.findTargetForFile(
            projectBasePath = "/tmp/project",
            scene = scene,
            landscape = null,
            filePath = java.nio.file.Path.of("/tmp/project/src/catalog/subdir/CatalogService.kt"),
        )

        assertNotNull(match)
        assertEquals("catalog", match?.targetId)
        assertEquals(SpatialTargetKind.SCENE_ENTITY, match?.kind)
    }

    fun testLandscapeCellMatchReturnsLandscapeTarget() {
        val landscape = LandscapeTimeline(
            frames = listOf(
                LandscapeFrame(
                    label = "now",
                    entries = listOf(LandscapeEntry(path = "src/catalog/CatalogService.kt", loc = 10f, churn = 2f))
                )
            )
        )

        val match = SpatialSceneNavigator.findTargetForFile(
            projectBasePath = "/tmp/project",
            scene = Scene(emptyList()),
            landscape = landscape,
            filePath = java.nio.file.Path.of("/tmp/project/src/catalog/CatalogService.kt"),
        )

        assertNotNull(match)
        assertEquals("src/catalog/CatalogService.kt", match?.targetId)
        assertEquals(SpatialTargetKind.LANDSCAPE_CELL, match?.kind)
    }
}
