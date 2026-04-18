package dev.spatial.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import dev.spatial.scene.CameraFocus
import dev.spatial.scene.Entity
import dev.spatial.service.SceneService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Single McpToolset that registers the four Spatial tools with the IDE's
 * bundled MCP Server (2026.1+). Each public annotated method becomes a tool;
 * its name is the method name, its description comes from @McpDescription.
 */
class SpatialToolset : McpToolset {

    @McpTool(name = "spatial_push_entities")
    @McpDescription(
        "Replace (or merge into) the Spatial scene with a list of 3D entities. " +
            "Each entity: {id, kind (box|sphere|cylinder|cone|plane), position:{x,y,z}, " +
            "rotation:{x,y,z}, scale:{x,y,z}, color (#hex), label, opacity}. " +
            "Use merge=true to upsert by id; default replaces everything."
    )
    suspend fun spatial_push_entities(
        @McpDescription("Entities to render in the scene.") entities: List<Entity>,
        @McpDescription("Keep existing entities and upsert by id. Default false (full replace).")
        merge: Boolean = false,
        @McpDescription("Reveal the Spatial tool window if hidden. Default true.")
        reveal: Boolean = true,
    ): PushResult {
        val project = currentCoroutineContext().project
        val service = project.service<SceneService>()
        service.pushEntities(entities, merge = merge)
        if (reveal) revealToolWindow()
        return PushResult(count = service.scene.entities.size)
    }

    @McpTool(name = "spatial_clear")
    @McpDescription("Remove all entities from the Spatial scene.")
    suspend fun spatial_clear(): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().clear()
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_focus")
    @McpDescription(
        "Ease the camera toward a target so the user's eye follows. " +
            "Call after pushing entities to highlight the region of interest."
    )
    suspend fun spatial_focus(
        @McpDescription("World-space point to look at.") target: dev.spatial.scene.Vec3,
        @McpDescription("Camera distance from the target. Default 5.")
        distance: Float = 5f,
        @McpDescription("Ease duration in milliseconds. Default 600.")
        durationMs: Int = 600,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().focus(CameraFocus(target, distance, durationMs))
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_speak")
    @McpDescription(
        "Show a short caption (one sentence) overlaid on the Spatial view. " +
            "Use to narrate what you're about to render before pushing entities."
    )
    suspend fun spatial_speak(
        @McpDescription("Sentence to display, cleared after a few seconds.") message: String,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().speak(message)
        return SimpleResult(ok = true)
    }

    private suspend fun revealToolWindow() {
        val project = currentCoroutineContext().project
        withContext(Dispatchers.EDT) {
            ToolWindowManager.getInstance(project)
                .getToolWindow("Spatial")
                ?.takeIf { !it.isVisible }
                ?.show(null)
        }
    }

    @Serializable
    data class PushResult(val count: Int)

    @Serializable
    data class SimpleResult(val ok: Boolean)
}
