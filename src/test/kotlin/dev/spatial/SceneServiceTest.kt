package dev.spatial

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.spatial.scene.Entity
import dev.spatial.scene.Vec3
import dev.spatial.service.SceneService

class SceneServiceTest : BasePlatformTestCase() {

    fun testPushReplacesExistingEntities() {
        val service = project.service<SceneService>()
        service.pushEntities(listOf(entity("a"), entity("b")))
        service.pushEntities(listOf(entity("c")))

        val ids = service.scene.entities.map { it.id }
        assertEquals(listOf("c"), ids)
    }

    fun testMergeUpsertsById() {
        val service = project.service<SceneService>()
        service.pushEntities(listOf(entity("a", color = "#111111")))
        service.pushEntities(listOf(entity("a", color = "#ffffff"), entity("b")), merge = true)

        val ids = service.scene.entities.map { it.id }.sorted()
        assertEquals(listOf("a", "b"), ids)
        assertEquals("#ffffff", service.scene.entities.first { it.id == "a" }.color)
    }

    fun testClearEmptiesScene() {
        val service = project.service<SceneService>()
        service.pushEntities(listOf(entity("a")))
        service.clear()
        assertTrue(service.scene.entities.isEmpty())
    }

    private fun entity(id: String, color: String = "#9aa0a6"): Entity =
        Entity(id = id, kind = "box", position = Vec3.ZERO, color = color)
}
