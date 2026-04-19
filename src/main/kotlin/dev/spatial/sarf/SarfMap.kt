package dev.spatial.sarf

import dev.spatial.scene.Entity
import dev.spatial.scene.Link
import dev.spatial.scene.Vec3
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical semantic contract for a SaRF map scene.
 *
 * Agents should describe project structure in terms of levels, clusters,
 * modules, and dependencies, then let the plugin compute a readable default
 * spatial layout.
 */
@Serializable
data class SarfMapScene(
    val title: String? = null,
    val description: String? = null,
    val levels: List<SarfLevel> = emptyList(),
    val clusters: List<SarfCluster>,
    val modules: List<SarfModule> = emptyList(),
    val dependencies: List<SarfDependency> = emptyList(),
    val tourStops: List<SarfTourStop> = emptyList(),
    val styles: SarfStyles = SarfStyles(),
)

@Serializable
data class SarfLevel(
    val id: String,
    val label: String? = null,
    val color: String? = null,
)

@Serializable
data class SarfCluster(
    val id: String,
    val label: String,
    val levelId: String,
    val parentId: String? = null,
    val description: String? = null,
    val color: String? = null,
)

@Serializable
data class SarfModule(
    val id: String,
    val label: String,
    val clusterId: String,
    val size: Float = 1f,
    val description: String? = null,
    val color: String? = null,
    val kind: String = "sphere",
)

@Serializable
data class SarfDependency(
    val fromId: String,
    val toId: String,
    val label: String? = null,
    val weight: Float = 1f,
    val color: String? = null,
    val arrow: Boolean = true,
)

@Serializable
data class SarfTourStop(
    val entityId: String,
    val text: String,
    val highlightIds: List<String> = emptyList(),
    val distance: Float? = null,
)

@Serializable
data class SarfStyles(
    val levelGap: Float = 10f,
    val familyGap: Float = 4f,
    val clusterGap: Float = 2f,
    val laneDepth: Float = 7f,
    val laneHeight: Float = 0.06f,
    val laneOpacity: Float = 0.18f,
    val clusterHeight: Float = 0.8f,
    val clusterOpacity: Float = 0.92f,
    val clusterDepthStep: Float = 0.65f,
    val clusterLiftStep: Float = 0.7f,
    val moduleGap: Float = 0.9f,
    val moduleBaseSize: Float = 0.55f,
    val moduleOpacity: Float = 1f,
    val hierarchyLinkOpacity: Float = 0.28f,
    val dependencyLinkOpacity: Float = 0.72f,
    val levelPalette: List<String> = DEFAULT_LEVEL_PALETTE,
) {
    companion object {
        val DEFAULT_LEVEL_PALETTE = listOf(
            "#4f8fba",
            "#d97b66",
            "#6e9f6d",
            "#b08968",
            "#8d80c9",
            "#6c9a8b",
        )
    }
}

data class MaterializedSarfMap(
    val entities: List<Entity>,
    val links: List<Link>,
    val defaultFocusEntityId: String?,
    val tourStops: List<SarfTourStop>,
)

object SarfMapCompiler {

    fun compile(scene: SarfMapScene): MaterializedSarfMap {
        require(scene.clusters.isNotEmpty()) { "SaRF scene must include at least one cluster." }

        val levels = resolveLevels(scene)
        val levelIds = levels.map { it.id }.toSet()
        val clustersById = linkedMapOf<String, SarfCluster>()
        scene.clusters.forEach { cluster ->
            require(cluster.id.isNotBlank()) { "Cluster ids must be non-blank." }
            require(clustersById.put(cluster.id, cluster) == null) { "Duplicate cluster id '${cluster.id}'." }
            require(cluster.levelId in levelIds) { "Cluster '${cluster.id}' references unknown level '${cluster.levelId}'." }
        }

        val modulesById = linkedMapOf<String, SarfModule>()
        scene.modules.forEach { module ->
            require(module.id.isNotBlank()) { "Module ids must be non-blank." }
            require(module.id !in clustersById) { "Module id '${module.id}' conflicts with a cluster id." }
            require(modulesById.put(module.id, module) == null) { "Duplicate module id '${module.id}'." }
            require(module.clusterId in clustersById) {
                "Module '${module.id}' references unknown cluster '${module.clusterId}'."
            }
        }

        clustersById.values.forEach { cluster ->
            cluster.parentId?.let { parentId ->
                require(parentId in clustersById) {
                    "Cluster '${cluster.id}' references unknown parent cluster '$parentId'."
                }
            }
        }
        validateAcyclicClusters(clustersById)

        val emittedEntityIds = clustersById.keys + modulesById.keys
        scene.dependencies.forEach { dependency ->
            require(dependency.fromId in emittedEntityIds) {
                "Dependency source '${dependency.fromId}' does not match a cluster or module id."
            }
            require(dependency.toId in emittedEntityIds) {
                "Dependency target '${dependency.toId}' does not match a cluster or module id."
            }
        }
        scene.tourStops.forEach { stop ->
            require(stop.entityId in emittedEntityIds) {
                "Tour stop target '${stop.entityId}' does not match a cluster or module id."
            }
            stop.highlightIds.forEach { highlightId ->
                require(highlightId in emittedEntityIds) {
                    "Tour stop highlight '$highlightId' does not match a cluster or module id."
                }
            }
        }

        val rootClusters = scene.clusters.filter { it.parentId == null }

        val rootByCluster = clustersById.keys.associateWith { clusterId ->
            var cursor = clustersById.getValue(clusterId)
            while (true) {
                val parentId = cursor.parentId ?: break
                cursor = clustersById.getValue(parentId)
            }
            cursor.id
        }

        val familyIds = rootClusters.map { it.id }
        val clusterLayouts = linkedMapOf<String, ClusterLayout>()
        val levelZ = levels.mapIndexed { index, _ ->
            index.toFloat() * scene.styles.levelGap - ((levels.size - 1) * scene.styles.levelGap / 2f)
        }
        val familyWidths = familyIds.associateWith { familyId ->
            levels.maxOfOrNull { level ->
                widthForClustersInFamilyLevel(
                    scene = scene,
                    familyId = familyId,
                    levelId = level.id,
                    rootByCluster = rootByCluster,
                    clustersById = clustersById,
                )
            } ?: 4f
        }

        var cursorX = 0f
        val familyStarts = linkedMapOf<String, Float>()
        familyIds.forEachIndexed { familyIndex, familyId ->
            if (familyIndex > 0) cursorX += scene.styles.familyGap
            familyStarts[familyId] = cursorX
            cursorX += familyWidths.getValue(familyId)
        }
        val totalWidth = max(cursorX, 6f)
        val sceneCenterX = totalWidth / 2f

        levels.forEachIndexed { levelIndex, level ->
            val z = levelZ[levelIndex]
            familyIds.forEach { familyId ->
                val clusters = scene.clusters
                    .filter { it.levelId == level.id && rootByCluster.getValue(it.id) == familyId }
                    .sortedWith(compareBy<SarfCluster> { pathKey(it, clustersById) }.thenBy { it.id })
                if (clusters.isEmpty()) return@forEach

                val occupiedWidth = clusters.sumOf { clusterFootprint(it, scene).width.toDouble() }.toFloat() +
                    scene.styles.clusterGap * (clusters.size - 1).coerceAtLeast(0)
                var x = familyStarts.getValue(familyId) + (familyWidths.getValue(familyId) - occupiedWidth) / 2f
                clusters.forEach { cluster ->
                    val footprint = clusterFootprint(cluster, scene)
                    val depth = hierarchyDepth(cluster, clustersById)
                    clusterLayouts[cluster.id] = ClusterLayout(
                        cluster = cluster,
                        x = x + footprint.width / 2f - sceneCenterX,
                        y = scene.styles.laneHeight / 2f + scene.styles.clusterHeight / 2f +
                            (depth * scene.styles.clusterLiftStep),
                        z = z + (depth * scene.styles.clusterDepthStep),
                        width = footprint.width,
                        depth = footprint.depth,
                        rootId = familyId,
                        levelIndex = levelIndex,
                    )
                    x += footprint.width + scene.styles.clusterGap
                }
            }
        }

        val entities = mutableListOf<Entity>()
        levels.forEachIndexed { index, level ->
            val color = resolveLevelColor(level, index, scene.styles)
            entities += Entity(
                id = levelEntityId(level.id),
                kind = "box",
                position = Vec3(0f, 0f, levelZ[index]),
                scale = Vec3(totalWidth + 3f, scene.styles.laneHeight, scene.styles.laneDepth),
                color = color,
                label = level.label ?: level.id,
                opacity = scene.styles.laneOpacity,
                meta = metaOf(
                    "sarfType" to "level",
                    "levelId" to level.id,
                    "title" to scene.title,
                ),
            )
        }

        clusterLayouts.values.forEach { layout ->
            val color = layout.cluster.color ?: shiftTowardWhite(
                resolveLevelColor(levels[layout.levelIndex], layout.levelIndex, scene.styles),
                0.12f,
            )
            entities += Entity(
                id = layout.cluster.id,
                kind = "box",
                position = Vec3(layout.x, layout.y, layout.z),
                scale = Vec3(layout.width, scene.styles.clusterHeight, layout.depth),
                color = color,
                label = layout.cluster.label,
                opacity = scene.styles.clusterOpacity,
                meta = metaOf(
                    "sarfType" to "cluster",
                    "levelId" to layout.cluster.levelId,
                    "parentId" to layout.cluster.parentId,
                    "rootId" to layout.rootId,
                    "description" to layout.cluster.description,
                ),
            )
        }

        val modulesByCluster = scene.modules.groupBy { it.clusterId }
        modulesByCluster.forEach { (clusterId, modules) ->
            val layout = clusterLayouts.getValue(clusterId)
            val sortedModules = modules.sortedBy { it.id }
            val columns = max(1, ceil(sqrt(sortedModules.size.toDouble())).toInt())
            val rows = ceil(sortedModules.size / columns.toDouble()).toInt()
            sortedModules.forEachIndexed { index, module ->
                val column = index % columns
                val row = index / columns
                val xOffset = (column - (columns - 1) / 2f) * scene.styles.moduleGap
                val zOffset = (row - (rows - 1) / 2f) * scene.styles.moduleGap
                val moduleSize = scene.styles.moduleBaseSize * moduleScale(module.size)
                val baseColor = module.color ?: shiftTowardWhite(
                    layout.cluster.color ?: resolveLevelColor(levels[layout.levelIndex], layout.levelIndex, scene.styles),
                    0.28f,
                )
                entities += Entity(
                    id = module.id,
                    kind = moduleKind(module.kind),
                    position = Vec3(
                        layout.x + xOffset,
                        layout.y + scene.styles.clusterHeight / 2f + moduleSize / 2f + 0.45f,
                        layout.z + zOffset,
                    ),
                    scale = Vec3(moduleSize, moduleSize, moduleSize),
                    color = baseColor,
                    label = module.label,
                    opacity = scene.styles.moduleOpacity,
                    meta = metaOf(
                        "sarfType" to "module",
                        "clusterId" to clusterId,
                        "levelId" to layout.cluster.levelId,
                        "description" to module.description,
                        "size" to module.size,
                    ),
                )
            }
        }

        val links = mutableListOf<Link>()
        clustersById.values.forEach { cluster ->
            cluster.parentId?.let { parentId ->
                links += Link(
                    id = "hierarchy:$parentId:${cluster.id}",
                    fromId = parentId,
                    toId = cluster.id,
                    color = shiftTowardWhite(
                        clusterLayouts.getValue(cluster.id).cluster.color
                            ?: resolveLevelColor(levels[clusterLayouts.getValue(cluster.id).levelIndex], clusterLayouts.getValue(cluster.id).levelIndex, scene.styles),
                        0.18f,
                    ),
                    label = null,
                    arrow = false,
                    opacity = scene.styles.hierarchyLinkOpacity,
                )
            }
        }
        scene.dependencies.forEachIndexed { index, dependency ->
            links += Link(
                id = "dependency:$index:${dependency.fromId}:${dependency.toId}",
                fromId = dependency.fromId,
                toId = dependency.toId,
                color = dependency.color ?: "#94a3b8",
                label = dependency.label,
                arrow = dependency.arrow,
                opacity = dependencyOpacity(dependency.weight, scene.styles),
            )
        }

        val defaultFocusEntityId = scene.tourStops.firstOrNull()?.entityId
            ?: rootClusters.firstOrNull()?.id
            ?: scene.clusters.firstOrNull()?.id

        return MaterializedSarfMap(
            entities = entities,
            links = links,
            defaultFocusEntityId = defaultFocusEntityId,
            tourStops = scene.tourStops,
        )
    }

    private fun resolveLevels(scene: SarfMapScene): List<SarfLevel> {
        val explicit = linkedMapOf<String, SarfLevel>()
        scene.levels.forEach { level ->
            require(level.id.isNotBlank()) { "Level ids must be non-blank." }
            require(explicit.put(level.id, level) == null) { "Duplicate level id '${level.id}'." }
        }
        scene.clusters.forEach { cluster ->
            if (cluster.levelId !in explicit) {
                explicit[cluster.levelId] = SarfLevel(id = cluster.levelId)
            }
        }
        return explicit.values.toList()
    }

    private fun validateAcyclicClusters(clustersById: Map<String, SarfCluster>) {
        val marks = mutableMapOf<String, Mark>()
        fun visit(clusterId: String) {
            when (marks[clusterId]) {
                Mark.VISITING -> error("Cluster hierarchy contains a cycle at '$clusterId'.")
                Mark.VISITED -> return
                null -> Unit
            }
            marks[clusterId] = Mark.VISITING
            clustersById.getValue(clusterId).parentId?.let(::visit)
            marks[clusterId] = Mark.VISITED
        }
        clustersById.keys.forEach(::visit)
    }

    private fun widthForClustersInFamilyLevel(
        scene: SarfMapScene,
        familyId: String,
        levelId: String,
        rootByCluster: Map<String, String>,
        clustersById: Map<String, SarfCluster>,
    ): Float {
        val clusters = clustersById.values
            .filter { it.levelId == levelId && rootByCluster.getValue(it.id) == familyId }
            .sortedWith(compareBy<SarfCluster> { pathKey(it, clustersById) }.thenBy { it.id })
        if (clusters.isEmpty()) return 4f
        return clusters.sumOf { clusterFootprint(it, scene).width.toDouble() }.toFloat() +
            scene.styles.clusterGap * (clusters.size - 1).coerceAtLeast(0)
    }

    private fun hierarchyDepth(cluster: SarfCluster, clustersById: Map<String, SarfCluster>): Int {
        var depth = 0
        var cursor = cluster.parentId
        while (cursor != null) {
            depth += 1
            cursor = clustersById.getValue(cursor).parentId
        }
        return depth
    }

    private fun pathKey(cluster: SarfCluster, clustersById: Map<String, SarfCluster>): String {
        val parts = mutableListOf(cluster.id)
        var cursor = cluster.parentId
        while (cursor != null) {
            parts += cursor
            cursor = clustersById.getValue(cursor).parentId
        }
        return parts.asReversed().joinToString("/")
    }

    private fun clusterFootprint(cluster: SarfCluster, scene: SarfMapScene): Footprint {
        val modules = scene.modules.filter { it.clusterId == cluster.id }
        val columns = max(1, ceil(sqrt(max(modules.size, 1).toDouble())).toInt())
        val rows = max(1, ceil(max(modules.size, 1) / columns.toDouble()).toInt())
        val width = max(2.8f, 1.6f + (columns - 1) * scene.styles.moduleGap)
        val depth = max(2.2f, 1.2f + (rows - 1) * scene.styles.moduleGap)
        return Footprint(width = width, depth = depth)
    }

    private fun moduleKind(kind: String): String =
        when (kind.lowercase()) {
            "box", "sphere", "cylinder", "cone" -> kind.lowercase()
            else -> "sphere"
        }

    private fun moduleScale(size: Float): Float = min(1.8f, max(0.8f, sqrt(max(size, 0.25f))))

    private fun dependencyOpacity(weight: Float, styles: SarfStyles): Float {
        val normalized = min(1f, max(0.15f, weight / 3f))
        return min(1f, max(0.18f, styles.dependencyLinkOpacity * normalized + 0.15f))
    }

    private fun resolveLevelColor(level: SarfLevel, index: Int, styles: SarfStyles): String =
        level.color ?: styles.levelPalette.getOrElse(index % styles.levelPalette.size) { "#4f8fba" }

    private fun shiftTowardWhite(hex: String, amount: Float): String {
        val color = parseHexColor(hex) ?: return hex
        fun mix(channel: Int): Int = (channel + ((255 - channel) * amount)).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(mix(color.red), mix(color.green), mix(color.blue))
    }

    private fun parseHexColor(hex: String): Rgb? {
        val normalized = hex.removePrefix("#")
        if (normalized.length != 6) return null
        return runCatching {
            Rgb(
                red = normalized.substring(0, 2).toInt(16),
                green = normalized.substring(2, 4).toInt(16),
                blue = normalized.substring(4, 6).toInt(16),
            )
        }.getOrNull()
    }

    private fun levelEntityId(levelId: String): String = "level:$levelId"

    private fun metaOf(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
        pairs.mapNotNull { (key, value) ->
            value?.let { key to jsonPrimitive(it) }
        }.toMap()

    private fun jsonPrimitive(value: Any): JsonElement =
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private data class Footprint(val width: Float, val depth: Float)

    private enum class Mark { VISITING, VISITED }

    private data class ClusterLayout(
        val cluster: SarfCluster,
        val x: Float,
        val y: Float,
        val z: Float,
        val width: Float,
        val depth: Float,
        val rootId: String,
        val levelIndex: Int,
    )

    private data class Rgb(val red: Int, val green: Int, val blue: Int)
}
