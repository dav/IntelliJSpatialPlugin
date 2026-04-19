package dev.spatial

import dev.spatial.sarf.SarfCluster
import dev.spatial.sarf.SarfDependency
import dev.spatial.sarf.SarfLevel
import dev.spatial.sarf.SarfMapCompiler
import dev.spatial.sarf.SarfMapScene
import dev.spatial.sarf.SarfModule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import junit.framework.TestCase
import kotlin.math.hypot

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

    fun testCompilerCarriesPathMetadataIntoClusterAndModuleEntities() {
        val scene = SarfMapScene(
            clusters = listOf(
                SarfCluster(
                    id = "catalog",
                    label = "Catalog",
                    levelId = "domain",
                    path = "app/domain/catalog",
                    line = 12,
                    column = 3,
                )
            ),
            modules = listOf(
                SarfModule(
                    id = "catalog-service",
                    label = "CatalogService",
                    clusterId = "catalog",
                    path = "app/domain/catalog/catalog_service.rb",
                    line = 27,
                    column = 5,
                )
            ),
        )

        val materialized = SarfMapCompiler.compile(scene)
        val cluster = materialized.entities.first { it.id == "catalog" }
        val module = materialized.entities.first { it.id == "catalog-service" }

        assertEquals("app/domain/catalog", cluster.meta.getValue("path").jsonPrimitive.content)
        assertEquals(12, cluster.meta.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(3, cluster.meta.getValue("column").jsonPrimitive.content.toInt())
        assertEquals("app/domain/catalog/catalog_service.rb", module.meta.getValue("path").jsonPrimitive.content)
        assertEquals(27, module.meta.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(5, module.meta.getValue("column").jsonPrimitive.content.toInt())
    }

    fun testCompilerSpacesLargeModulesWithoutOverlap() {
        val scene = SarfMapScene(
            clusters = listOf(SarfCluster(id = "catalog", label = "Catalog", levelId = "domain")),
            modules = listOf(
                SarfModule(id = "a", label = "A", clusterId = "catalog", size = 16f),
                SarfModule(id = "b", label = "B", clusterId = "catalog", size = 16f),
                SarfModule(id = "c", label = "C", clusterId = "catalog", size = 16f),
                SarfModule(id = "d", label = "D", clusterId = "catalog", size = 16f),
            ),
        )

        val materialized = SarfMapCompiler.compile(scene)
        val modules = materialized.entities.filter { it.meta["sarfType"]?.jsonPrimitive?.content == "module" }

        assertEquals(4, modules.size)
        modules.forEachIndexed { index, left ->
            modules.drop(index + 1).forEach { right ->
                val centerDistance = hypot(
                    (left.position.x - right.position.x).toDouble(),
                    (left.position.z - right.position.z).toDouble(),
                ).toFloat()
                val minDistance = (left.scale.x + right.scale.x) / 2f
                assertTrue(
                    "Expected modules '${left.id}' and '${right.id}' not to overlap.",
                    centerDistance + 0.0001f >= minDistance,
                )
            }
        }
    }
}
