package dev.spatial

import dev.spatial.project.ProjectStructureMapBuilder
import dev.spatial.project.ProjectStructureStyles
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.json.jsonPrimitive

class ProjectStructureMapBuilderTest : TestCase() {

    fun testBuildCreatesLayeredDirectoryAndFileEntities() {
        val root = Files.createTempDirectory("spatial-project-structure-test")
        try {
            root.resolve("src/main").createDirectories()
            root.resolve("src/main/App.kt").writeText("fun main() = Unit\n")
            root.resolve("README.md").writeText("# demo\n")

            val materialized = ProjectStructureMapBuilder.build(
                root = root,
                projectBasePath = root.toString(),
                maxDepth = 3,
                maxEntriesPerDirectory = 20,
                includeHidden = false,
            )

            assertEquals(3, materialized.directories)
            assertEquals(2, materialized.files)
            assertEquals("dir:.", materialized.rootEntityId)

            val rootDir = materialized.entities.first { it.id == "dir:." }
            val srcDir = materialized.entities.first { it.id == "dir:src" }
            val appFile = materialized.entities.first { it.id == "file:src/main/App.kt" }
            val readme = materialized.entities.first { it.id == "file:README.md" }

            assertEquals("directory", rootDir.meta.getValue("structureType").jsonPrimitive.content)
            assertEquals("directory", srcDir.meta.getValue("structureType").jsonPrimitive.content)
            assertEquals("file", appFile.meta.getValue("structureType").jsonPrimitive.content)
            assertEquals("src/main/App.kt", appFile.meta.getValue("path").jsonPrimitive.content)
            assertEquals("README.md", readme.meta.getValue("path").jsonPrimitive.content)
            assertTrue("Subfolder platter should be above the root platter.", srcDir.position.y > rootDir.position.y)
            assertTrue("File block should sit above its containing platter.", appFile.position.y > srcDir.position.y)
        } finally {
            deleteRecursively(root)
        }
    }

    fun testBuildSizesParentPlatterFromActualChildFootprints() {
        val root = Files.createTempDirectory("spatial-project-structure-compact-test")
        try {
            root.resolve("large/a").createDirectories()
            root.resolve("large/b").createDirectories()
            root.resolve("large/a/A1.kt").writeText("class A1\n")
            root.resolve("large/a/A2.kt").writeText("class A2\n")
            root.resolve("large/b/B1.kt").writeText("class B1\n")
            root.resolve("tiny.txt").writeText("tiny\n")

            val styles = ProjectStructureStyles()
            val materialized = ProjectStructureMapBuilder.build(
                root = root,
                projectBasePath = root.toString(),
                maxDepth = 4,
                maxEntriesPerDirectory = 20,
                includeHidden = false,
                styles = styles,
            )

            val rootDir = materialized.entities.first { it.id == "dir:." }
            val largeDir = materialized.entities.first { it.id == "dir:large" }

            val oldWastefulWidth = (largeDir.scale.x * 2f) + styles.cellGap + (styles.platterPadding * 2f)
            assertTrue(
                "Expected root platter to be narrower than the old max-cell layout.",
                rootDir.scale.x < oldWastefulWidth,
            )
        } finally {
            deleteRecursively(root)
        }
    }

    fun testBuildPrefersSquareishPackingForManyFiles() {
        val root = Files.createTempDirectory("spatial-project-structure-square-test")
        try {
            repeat(24) { index ->
                root.resolve("File$index.kt").writeText("class File$index\n")
            }

            val materialized = ProjectStructureMapBuilder.build(
                root = root,
                projectBasePath = root.toString(),
                maxDepth = 2,
                maxEntriesPerDirectory = 50,
                includeHidden = false,
            )

            val rootDir = materialized.entities.first { it.id == "dir:." }
            val aspectRatio = maxOf(rootDir.scale.x, rootDir.scale.z) / minOf(rootDir.scale.x, rootDir.scale.z)

            assertTrue(
                "Expected the root platter to stay reasonably square, got aspect ratio $aspectRatio.",
                aspectRatio < 2.2f,
            )
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
