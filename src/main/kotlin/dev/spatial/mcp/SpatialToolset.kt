package dev.spatial.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import dev.spatial.git.GitChurnAnalyzer
import dev.spatial.neural.FeedForwardNetworkCompiler
import dev.spatial.neural.FeedForwardNetworkScene
import dev.spatial.scene.CameraFocus
import dev.spatial.scene.Entity
import dev.spatial.scene.FocusEntity
import dev.spatial.scene.Highlight
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Link
import dev.spatial.scene.Narrate
import dev.spatial.scene.TourRequest
import dev.spatial.scene.TourStop
import dev.spatial.project.ProjectStructureMapBuilder
import dev.spatial.service.SceneService
import dev.spatial.sarf.SarfMapCompiler
import dev.spatial.sarf.SarfMapScene
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Single McpToolset that registers the Spatial tools with the IDE's
 * bundled MCP Server (2026.1+). Each public annotated method becomes a tool;
 * its name is the method name, its description comes from @McpDescription.
 */
class SpatialToolset : McpToolset {

    @McpTool(name = "spatial_push_entities")
    @McpDescription(
        "Replace (or merge into) the Spatial scene with a list of 3D entities. " +
            "Each entity: {id, kind (box|sphere|cylinder|cone|plane), position:{x,y,z}, " +
            "rotation:{x,y,z}, scale:{x,y,z}, color (#hex), label, opacity}. " +
            "Use merge=true to upsert by id; default replaces everything. Prefer " +
            "`spatial_push_project_structure` for the default project/folder view, " +
            "`spatial_push_sarf_map` for semantic architecture maps and `spatial_push_churn_landscape` " +
            "or `spatial_push_repo_churn` for file-history landscapes."
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
            "Silent — use spatial_narrate for spoken audio. Cleared after a few seconds."
    )
    suspend fun spatial_speak(
        @McpDescription("Sentence to display.") message: String,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().speak(message)
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_narrate")
    @McpDescription(
        "Speak a sentence out loud via the browser's TTS engine and optionally show a matching caption. " +
            "Use this for guided tours — one narrate call per stop. Works offline; no API keys needed."
    )
    suspend fun spatial_narrate(
        @McpDescription("Sentence to speak.") text: String,
        @McpDescription("TTS voice name. Null = browser default (e.g. Samantha on macOS).")
        voice: String? = null,
        @McpDescription("Speech rate multiplier; 1.0 is normal, 1.2 brisker, 0.9 slower.")
        rate: Float = 1f,
        @McpDescription("Also flash the text as an on-screen caption. Default true.")
        caption: Boolean = true,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().narrate(Narrate(text, voice, rate, caption))
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_focus_entity")
    @McpDescription(
        "Ease the camera onto a specific entity by id. Fails silently if the id is not in the scene."
    )
    suspend fun spatial_focus_entity(
        @McpDescription("Id of the entity to focus.") entityId: String,
        @McpDescription("Camera distance from the entity. Default 4.") distance: Float = 4f,
        @McpDescription("Ease duration ms. Default 600.") durationMs: Int = 600,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().focusEntity(FocusEntity(entityId, distance, durationMs))
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_highlight")
    @McpDescription(
        "Pulse one or more entities for a moment to draw the user's attention — typically called " +
            "alongside spatial_narrate at each tour stop."
    )
    suspend fun spatial_highlight(
        @McpDescription("Ids of entities to pulse.") entityIds: List<String>,
        @McpDescription("Pulse duration ms. Default 1500.") durationMs: Int = 1500,
        @McpDescription("Hex color of the glow. Default #ffffff.") color: String = "#ffffff",
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().highlight(Highlight(entityIds, durationMs, color))
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_push_project_structure")
    @McpDescription(
        "Render the project as a stacked folder/file structure view. Each folder becomes a platter, " +
            "files become blocks on that platter, and subfolders become smaller platters elevated above " +
            "their parent. Use this as the default project visualization when no more specific style is requested."
    )
    suspend fun spatial_push_project_structure(
        @McpDescription("Absolute path to the directory to visualize. Empty/null = current IDE project root.")
        rootPath: String? = null,
        @McpDescription("Maximum folder nesting depth to include below the root. Default 4.")
        maxDepth: Int = 4,
        @McpDescription("Maximum immediate child entries to include per folder. Default 80.")
        maxEntriesPerDirectory: Int = 80,
        @McpDescription("Whether to include hidden dotfiles and dot-directories. Default false.")
        includeHidden: Boolean = false,
        @McpDescription("Clear any active churn landscape before rendering this project structure. Default true.")
        clearLandscape: Boolean = true,
        @McpDescription("Clear any active links before rendering this project structure. Default true.")
        clearLinks: Boolean = true,
        @McpDescription("Focus the camera on the root platter after rendering. Default true.")
        focus: Boolean = true,
        @McpDescription("Reveal the Spatial tool window if hidden. Default true.")
        reveal: Boolean = true,
    ): ProjectStructureResult {
        val project = currentCoroutineContext().project
        val service = project.service<SceneService>()
        val resolvedRoot = rootPath?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: project.basePath?.let(Paths::get)
            ?: error("No rootPath given and the project has no base path.")
        if (!resolvedRoot.toFile().isDirectory) error("Not a directory: $resolvedRoot")

        val materialized = ProjectStructureMapBuilder.build(
            root = resolvedRoot,
            projectBasePath = project.basePath,
            maxDepth = maxDepth,
            maxEntriesPerDirectory = maxEntriesPerDirectory,
            includeHidden = includeHidden,
        )

        if (clearLandscape) service.clearLandscape()
        if (clearLinks) service.clearLinks()
        service.pushEntities(materialized.entities)
        if (focus) {
            service.focusEntity(FocusEntity(materialized.rootEntityId, distance = 14f, durationMs = 800))
        }
        if (reveal) revealToolWindow()

        return ProjectStructureResult(
            rootPath = resolvedRoot.toString(),
            directories = materialized.directories,
            files = materialized.files,
            entities = materialized.entities.size,
            rootEntityId = materialized.rootEntityId,
        )
    }

    @McpTool(name = "spatial_push_churn_landscape")
    @McpDescription(
        "Render a churn landscape: the project as a 3D treemap on the floor, each cell extruded by " +
            "its activity. Pass multiple frames to enable a time-scrub slider in the tool window. The " +
            "plugin computes the squarified treemap layout — agents only supply per-file numbers.\n" +
            "Each entry is one file in one frame: {path, loc (cell footprint size), churn (extrusion " +
            "height), color?, author?}. A path that appears in some frames but not others collapses to " +
            "height 0 in the missing frames."
    )
    suspend fun spatial_push_churn_landscape(
        @McpDescription("Ordered frames; the slider scrubs between them. Single-frame is fine — slider hides.")
        frames: List<dev.spatial.scene.LandscapeFrame>,
        @McpDescription("Side length of the floor square. Default 20.") floorSize: Float = 20f,
        @McpDescription("Maximum extrusion height for the busiest cell. Default 6.") maxHeight: Float = 6f,
    ): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().pushLandscape(LandscapeTimeline(frames, floorSize, maxHeight))
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_clear_landscape")
    @McpDescription("Remove the churn landscape (regular entities are unaffected).")
    suspend fun spatial_clear_landscape(): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().clearLandscape()
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_push_links")
    @McpDescription(
        "Render edges between entities — for SARF / architecture maps, dependency graphs, " +
            "service maps, etc. Each link references two entity ids. Links pointing at missing " +
            "entities are silently skipped, so push order doesn't matter. Set merge=true to keep " +
            "existing links and upsert by id; default replaces the whole link set. Prefer " +
            "`spatial_push_sarf_map` when you have semantic SaRF data and want the plugin to own layout, " +
            "or `spatial_push_feed_forward_network` for weighted neural-network diagrams."
    )
    suspend fun spatial_push_links(
        @McpDescription("Edges to render. Each: {id, fromId, toId, color?, label?, arrow?, opacity?, thickness?}.")
        links: List<Link>,
        @McpDescription("Keep existing links and upsert by id. Default false (full replace).")
        merge: Boolean = false,
    ): LinkResult {
        val project = currentCoroutineContext().project
        val service = project.service<SceneService>()
        service.pushLinks(links, merge = merge)
        return LinkResult(count = service.links.size)
    }

    @McpTool(name = "spatial_push_feed_forward_network")
    @McpDescription(
        "Render a canonical feed-forward neural network from semantic layer and weight data. " +
            "Provide ordered layers with node counts and optional node labels plus weighted node-to-node " +
            "connections; the plugin computes a readable layered layout with input/output labels and " +
            "weight-aware links where negative weights are red, positive weights are green, and link " +
            "thickness scales with absolute magnitude."
    )
    suspend fun spatial_push_feed_forward_network(
        @McpDescription(
            "Canonical feed-forward network contract. Layers are ordered input-to-output; each layer " +
                "declares a nodeCount and optional nodeLabels; connections target node indices by layer id " +
                "and must move forward through the layer order. Styles may override spacing, colors, and " +
                "link-thickness bounds."
        )
        scene: FeedForwardNetworkScene,
        @McpDescription("Clear any active churn landscape before rendering the network. Default true.")
        clearLandscape: Boolean = true,
        @McpDescription("Reveal the Spatial tool window if hidden. Default true.")
        reveal: Boolean = true,
        @McpDescription("Focus the camera on the center layer after rendering. Default true.")
        focus: Boolean = true,
    ): FeedForwardNetworkResult {
        val project = currentCoroutineContext().project
        val service = project.service<SceneService>()
        val materialized = FeedForwardNetworkCompiler.compile(scene)

        if (clearLandscape) service.clearLandscape()
        service.pushEntities(materialized.entities)
        service.pushLinks(materialized.links)
        if (focus) {
            service.focusEntity(FocusEntity(materialized.defaultFocusEntityId, distance = 10f, durationMs = 800))
        }
        if (reveal) revealToolWindow()

        return FeedForwardNetworkResult(
            layers = scene.layers.size,
            nodes = scene.layers.sumOf { it.nodeCount },
            links = materialized.links.size,
            focusEntityId = materialized.defaultFocusEntityId,
        )
    }

    @McpTool(name = "spatial_push_sarf_map")
    @McpDescription(
        "Render a canonical SaRF map scene from semantic project structure instead of raw coordinates. " +
            "Provide ordered levels, clusters, modules, dependencies, and optional tour stops; the plugin " +
            "computes a default layout with level lanes, cluster containers, module nodes, hierarchy links, " +
            "dependency links, and a default focus target."
    )
    suspend fun spatial_push_sarf_map(
        @McpDescription(
            "Canonical SaRF scene contract. Levels establish order; clusters define the hierarchy and " +
                "level placement; modules attach to clusters; dependencies may target either clusters or " +
                "modules; tourStops add optional guide metadata; styles override layout defaults such as " +
                "level gaps, cluster spacing, and opacity. Tour stops may also carry focus/highlight " +
                "durations, pre/post delays, voice, rate, and waitForSpeech."
        )
        scene: SarfMapScene,
        @McpDescription("Focus the camera on the first tour stop target or first root cluster. Default true.")
        focus: Boolean = true,
        @McpDescription("Clear any active churn landscape before rendering the SaRF map. Default true.")
        clearLandscape: Boolean = true,
        @McpDescription(
            "Play the scene's tourStops through the synchronized browser-side tour runner after rendering. " +
                "Default false."
        )
        playTour: Boolean = false,
        @McpDescription("Reveal the Spatial tool window if hidden. Default true.")
        reveal: Boolean = true,
    ): SarfMapResult {
        val project = currentCoroutineContext().project
        val service = project.service<SceneService>()
        val materialized = SarfMapCompiler.compile(scene)

        if (clearLandscape) service.clearLandscape()
        service.pushEntities(materialized.entities)
        service.pushLinks(materialized.links)
        val tourStarted = playTour && materialized.tourStops.isNotEmpty()
        if (tourStarted) {
            service.playTour(TourRequest(materialized.tourStops))
        } else if (focus) {
            materialized.defaultFocusEntityId?.let { service.focusEntity(FocusEntity(it, distance = 8f, durationMs = 750)) }
        }
        if (reveal) revealToolWindow()

        return SarfMapResult(
            levels = scene.levels.ifEmpty { scene.clusters.map { it.levelId }.distinct().map { dev.spatial.sarf.SarfLevel(it) } }.size,
            clusters = scene.clusters.size,
            modules = scene.modules.size,
            links = materialized.links.size,
            focusEntityId = materialized.defaultFocusEntityId,
            tourStops = scene.tourStops.size,
            tourStarted = tourStarted,
        )
    }

    @McpTool(name = "spatial_play_tour")
    @McpDescription(
        "Play a synchronized scene tour in the browser so focus, highlight, caption, and speech stay " +
            "in lockstep. Use this instead of issuing separate narrate/focus/highlight calls when the " +
            "timing matters across multiple stops."
    )
    suspend fun spatial_play_tour(
        @McpDescription(
            "Ordered tour stops. Each stop targets an existing entity id and may include narration text, " +
                "highlight ids, camera distance, focus/highlight durations, pre/post delays, caption, " +
                "voice, rate, and waitForSpeech."
        )
        stops: List<TourStop>,
        @McpDescription("Zero-based stop index to start from. Default 0.")
        startIndex: Int = 0,
        @McpDescription("Reveal the Spatial tool window if hidden. Default true.")
        reveal: Boolean = true,
    ): TourResult {
        require(stops.isNotEmpty()) { "Tour must contain at least one stop." }
        require(startIndex in stops.indices) { "startIndex $startIndex is out of bounds for ${stops.size} stops." }
        val project = currentCoroutineContext().project
        project.service<SceneService>().playTour(TourRequest(stops = stops, startIndex = startIndex))
        if (reveal) revealToolWindow()
        return TourResult(stops = stops.size, startIndex = startIndex)
    }

    @McpTool(name = "spatial_clear_links")
    @McpDescription("Remove all links. Entities and landscape are unaffected.")
    suspend fun spatial_clear_links(): SimpleResult {
        val project = currentCoroutineContext().project
        project.service<SceneService>().clearLinks()
        return SimpleResult(ok = true)
    }

    @McpTool(name = "spatial_push_repo_churn")
    @McpDescription(
        "Analyze a git repository's history and push a churn landscape to the Spatial view in one " +
            "call. Splits the chosen time range into N equal buckets, runs `git log --numstat` per " +
            "bucket, and renders each file as a treemap cell extruded by its activity. Use this " +
            "instead of spatial_push_churn_landscape when you don't want to compute the per-file " +
            "numbers yourself.\n" +
            "If repoPath is omitted, uses the current IDE project's root. Requires `git` on PATH."
    )
    suspend fun spatial_push_repo_churn(
        @McpDescription("Absolute path to the git repo. Empty/null = current IDE project root.")
        repoPath: String? = null,
        @McpDescription("Number of equal-width time buckets across the range. Default 6.")
        frames: Int = 6,
        @McpDescription("Keep the top K files by total churn across all frames. Default 40.")
        topK: Int = 40,
        @McpDescription("ISO date for timeline start (e.g. 2025-01-01 or 2025-01-01T00:00:00Z). " +
            "Default = first commit.")
        since: String? = null,
        @McpDescription("ISO date for timeline end. Default = HEAD.")
        until: String? = null,
        @McpDescription("Side length of the floor square. Default 22.")
        floorSize: Float = 22f,
        @McpDescription("Maximum extrusion height for the busiest cell. Default 7.")
        maxHeight: Float = 7f,
    ): RepoChurnResult {
        val project = currentCoroutineContext().project
        val resolvedRoot: Path = repoPath?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: project.basePath?.let(Paths::get)
            ?: error("No repoPath given and the project has no base path.")
        if (!resolvedRoot.toFile().isDirectory) {
            error("Not a directory: $resolvedRoot")
        }
        val analyzer = GitChurnAnalyzer(resolvedRoot)
        val timeline = analyzer.analyze(
            frames = frames,
            since = parseIsoOrNull(since),
            until = parseIsoOrNull(until),
            topK = topK,
            floorSize = floorSize,
            maxHeight = maxHeight,
        )
        project.service<SceneService>().pushLandscape(timeline)
        revealToolWindow()
        return RepoChurnResult(
            repoPath = resolvedRoot.toString(),
            frames = timeline.frames.size,
            files = timeline.frames.firstOrNull()?.entries?.size ?: 0,
            firstFrameLabel = timeline.frames.firstOrNull()?.label,
            lastFrameLabel = timeline.frames.lastOrNull()?.label,
        )
    }

    private fun parseIsoOrNull(s: String?): OffsetDateTime? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(v) }
            .recoverCatching { java.time.LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC) }
            .recoverCatching { java.time.LocalDateTime.parse(v).atOffset(ZoneOffset.UTC) }
            .getOrElse { error("Cannot parse date '$v' (try yyyy-MM-dd or full ISO 8601).") }
    }

    @Serializable
    data class RepoChurnResult(
        val repoPath: String,
        val frames: Int,
        val files: Int,
        val firstFrameLabel: String? = null,
        val lastFrameLabel: String? = null,
    )

    @Serializable
    data class ProjectStructureResult(
        val rootPath: String,
        val directories: Int,
        val files: Int,
        val entities: Int,
        val rootEntityId: String,
    )

    @Serializable
    data class SarfMapResult(
        val levels: Int,
        val clusters: Int,
        val modules: Int,
        val links: Int,
        val focusEntityId: String? = null,
        val tourStops: Int,
        val tourStarted: Boolean = false,
    )

    @Serializable
    data class FeedForwardNetworkResult(
        val layers: Int,
        val nodes: Int,
        val links: Int,
        val focusEntityId: String,
    )

    @Serializable
    data class TourResult(
        val stops: Int,
        val startIndex: Int = 0,
    )

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
    data class LinkResult(val count: Int)

    @Serializable
    data class SimpleResult(val ok: Boolean)
}
