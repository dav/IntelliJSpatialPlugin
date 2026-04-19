package dev.spatial

import dev.spatial.ui.SpatialSceneBridge
import junit.framework.TestCase
import java.nio.file.Paths

class SpatialSceneBridgeTest : TestCase() {

    fun testResolvePathReturnsAbsolutePathAsIs() {
        val resolved = SpatialSceneBridge.resolvePath("/tmp/project", "/tmp/project/src/App.kt")

        assertEquals(Paths.get("/tmp/project/src/App.kt"), resolved)
    }

    fun testResolvePathResolvesRelativePathAgainstProjectBase() {
        val resolved = SpatialSceneBridge.resolvePath("/tmp/project", "src/App.kt")

        assertEquals(Paths.get("/tmp/project/src/App.kt"), resolved)
    }

    fun testResolvePathReturnsNullWhenProjectBaseMissingForRelativePath() {
        val resolved = SpatialSceneBridge.resolvePath(null, "src/App.kt")

        assertNull(resolved)
    }
}
