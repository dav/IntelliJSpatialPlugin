package dev.spatial.navigation

import dev.spatial.scene.Entity
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Scene
import dev.spatial.ui.SpatialSceneBridge
import java.nio.file.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SpatialTargetMatch(
    val targetId: String,
    val kind: SpatialTargetKind,
)

enum class SpatialTargetKind {
    SCENE_ENTITY,
    LANDSCAPE_CELL,
}

object SpatialSceneNavigator {

    fun findTargetForFile(
        projectBasePath: String?,
        scene: Scene,
        landscape: LandscapeTimeline?,
        filePath: Path,
    ): SpatialTargetMatch? {
        val normalizedFile = filePath.normalize()
        val candidates = buildList {
            scene.entities.forEach { entity ->
                val rawPath = entityPath(entity.meta) ?: return@forEach
                val resolved = SpatialSceneBridge.resolvePath(projectBasePath, rawPath) ?: return@forEach
                scoreCandidate(
                    target = normalizedFile,
                    candidate = resolved,
                    id = entity.id,
                    kind = SpatialTargetKind.SCENE_ENTITY,
                )?.let(::add)
            }

            landscape
                ?.frames
                ?.asSequence()
                ?.flatMap { it.entries.asSequence() }
                ?.map { it.path }
                ?.distinct()
                ?.forEach { rawPath ->
                    val resolved = SpatialSceneBridge.resolvePath(projectBasePath, rawPath) ?: return@forEach
                    scoreCandidate(
                        target = normalizedFile,
                        candidate = resolved,
                        id = rawPath,
                        kind = SpatialTargetKind.LANDSCAPE_CELL,
                    )?.let(::add)
                }
        }

        return candidates.maxWithOrNull(
            compareBy<Candidate>({ it.score }, { it.resolved.nameCount }, { it.kindPriority })
        )?.let { SpatialTargetMatch(targetId = it.id, kind = it.kind) }
    }

    private fun scoreCandidate(
        target: Path,
        candidate: Path,
        id: String,
        kind: SpatialTargetKind,
    ): Candidate? {
        val normalizedCandidate = candidate.normalize()
        val score = when {
            normalizedCandidate == target -> 10_000
            target.startsWith(normalizedCandidate) -> 1_000
            else -> return null
        }
        return Candidate(
            id = id,
            kind = kind,
            resolved = normalizedCandidate,
            score = score,
            kindPriority = when (kind) {
                SpatialTargetKind.SCENE_ENTITY -> 2
                SpatialTargetKind.LANDSCAPE_CELL -> 1
            },
        )
    }

    private fun entityPath(meta: Map<String, JsonElement>): String? =
        meta["path"]?.jsonPrimitive?.contentOrNull
            ?: meta["filePath"]?.jsonPrimitive?.contentOrNull
            ?: meta["spatialPath"]?.jsonPrimitive?.contentOrNull
            ?: meta["folderPath"]?.jsonPrimitive?.contentOrNull

    private data class Candidate(
        val id: String,
        val kind: SpatialTargetKind,
        val resolved: Path,
        val score: Int,
        val kindPriority: Int,
    )
}
