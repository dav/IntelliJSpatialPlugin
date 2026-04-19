package dev.spatial

import dev.spatial.sarf.SarfCluster
import dev.spatial.sarf.SarfDependency
import dev.spatial.sarf.SarfLevel
import dev.spatial.sarf.SarfMapCompiler
import dev.spatial.sarf.SarfMapScene
import dev.spatial.sarf.SarfModule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import junit.framework.TestCase

class SarfMapCompilerTest : TestCase() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun testCanonicalExampleCompilesIntoExpectedSceneShape() {
        val exampleJson = javaClass.getResourceAsStream("/examples/canonical-sarf-map.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing canonical SaRF example resource.")

        val scene = json.decodeFromString<SarfMapScene>(exampleJson)
        val materialized = SarfMapCompiler.compile(scene)

        assertEquals(18, materialized.entities.size)
        assertEquals(10, materialized.links.size)
        assertEquals("catalog", materialized.defaultFocusEntityId)
        assertNotNull(materialized.entities.firstOrNull { it.id == "level:experience" })
        assertNotNull(materialized.entities.firstOrNull { it.id == "catalog" })
        assertNotNull(materialized.entities.firstOrNull { it.id == "catalog-page" })
        assertNotNull(materialized.links.firstOrNull { it.id == "hierarchy:catalog:catalog-ui" })
        assertNotNull(materialized.links.firstOrNull { it.fromId == "payment-service" && it.toId == "payments-client" })
    }

    fun testCompilerRejectsUnknownDependencyEndpoint() {
        val scene = SarfMapScene(
            levels = listOf(SarfLevel(id = "domain")),
            clusters = listOf(SarfCluster(id = "auth", label = "Auth", levelId = "domain")),
            modules = listOf(SarfModule(id = "login", label = "Login", clusterId = "auth")),
            dependencies = listOf(SarfDependency(fromId = "login", toId = "missing")),
        )

        val error = try {
            SarfMapCompiler.compile(scene)
            fail("Expected the compiler to reject an unknown dependency endpoint.")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
        assertTrue((error.message ?: "").contains("missing"))
    }

    fun testCompilerDerivesLevelsWhenContractOmitsExplicitList() {
        val scene = SarfMapScene(
            clusters = listOf(
                SarfCluster(id = "ui", label = "UI", levelId = "experience"),
                SarfCluster(id = "domain", label = "Domain", levelId = "domain"),
            ),
            modules = listOf(SarfModule(id = "screen", label = "Screen", clusterId = "ui")),
        )

        val materialized = SarfMapCompiler.compile(scene)

        assertNotNull(materialized.entities.firstOrNull { it.id == "level:experience" })
        assertNotNull(materialized.entities.firstOrNull { it.id == "level:domain" })
        assertEquals(5, materialized.entities.size)
    }
}
