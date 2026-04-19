package dev.spatial.project

import dev.spatial.scene.Entity
import dev.spatial.scene.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ProjectStructureStyles(
    val minPlatterWidth: Float = 3.2f,
    val minPlatterDepth: Float = 2.8f,
    val platterHeight: Float = 0.18f,
    val platterPadding: Float = 0.7f,
    val cellGap: Float = 0.45f,
    val folderLift: Float = 1.05f,
    val fileWidth: Float = 0.52f,
    val fileDepth: Float = 0.52f,
    val fileBaseHeight: Float = 0.34f,
    val fileHeightRange: Float = 0.26f,
    val fileClearance: Float = 0.14f,
    val rootColor: String = "#56777d",
    val folderColor: String = "#6b7c89",
    val fileColor: String = "#c59159",
)

data class MaterializedProjectStructure(
    val entities: List<Entity>,
    val rootEntityId: String,
    val directories: Int,
    val files: Int,
)

object ProjectStructureMapBuilder {

    fun build(
        root: Path,
        projectBasePath: String?,
        maxDepth: Int = 4,
        maxEntriesPerDirectory: Int = 80,
        includeHidden: Boolean = false,
        styles: ProjectStructureStyles = ProjectStructureStyles(),
    ): MaterializedProjectStructure {
        require(maxDepth >= 0) { "maxDepth must be >= 0." }
        require(maxEntriesPerDirectory > 0) { "maxEntriesPerDirectory must be > 0." }
        require(Files.isDirectory(root)) { "Project structure root must be a directory: $root" }

        val tree = scanDirectory(
            path = root.normalize(),
            root = root.normalize(),
            depth = 0,
            maxDepth = maxDepth,
            maxEntriesPerDirectory = maxEntriesPerDirectory,
            includeHidden = includeHidden,
        )
        val metrics = measure(tree, styles)
        val entities = mutableListOf<Entity>()
        layout(
            node = tree,
            metrics = metrics,
            centerX = 0f,
            centerZ = 0f,
            y = 0f,
            root = root.normalize(),
            projectBasePath = projectBasePath,
            styles = styles,
            entities = entities,
        )

        return MaterializedProjectStructure(
            entities = entities,
            rootEntityId = entityIdForDirectory(tree.relativePath),
            directories = metrics.directoryCount,
            files = metrics.fileCount,
        )
    }

    private fun scanDirectory(
        path: Path,
        root: Path,
        depth: Int,
        maxDepth: Int,
        maxEntriesPerDirectory: Int,
        includeHidden: Boolean,
    ): DirectoryNode {
        val entries = Files.list(path).use { stream ->
            stream
                .filter { candidate -> includeHidden || !candidate.fileName.toString().startsWith(".") }
                .filter { candidate -> !Files.isSymbolicLink(candidate) }
                .sorted(compareBy<Path>({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase() }))
                .limit(maxEntriesPerDirectory.toLong())
                .toList()
        }

        val directories = if (depth < maxDepth) {
            entries
                .filter { Files.isDirectory(it) }
                .map {
                    scanDirectory(
                        path = it,
                        root = root,
                        depth = depth + 1,
                        maxDepth = maxDepth,
                        maxEntriesPerDirectory = maxEntriesPerDirectory,
                        includeHidden = includeHidden,
                    )
                }
        } else {
            emptyList()
        }

        val files = entries
            .filter { !Files.isDirectory(it) }
            .map { file ->
                FileNode(
                    path = file.normalize(),
                    relativePath = root.relativize(file.normalize()).toString().ifBlank { file.fileName.toString() },
                    name = file.fileName.toString(),
                    sizeBytes = runCatching { file.fileSize() }.getOrDefault(0L),
                )
            }

        return DirectoryNode(
            path = path,
            relativePath = root.relativize(path).toString(),
            name = path.fileName?.toString() ?: path.toString(),
            depth = depth,
            directories = directories,
            files = files,
        )
    }

    private fun measure(node: DirectoryNode, styles: ProjectStructureStyles): DirectoryMetrics {
        val childMetrics = node.directories.associateWith { measure(it, styles) }
        val children = buildList<Footprint> {
            node.directories.forEach { directory ->
                val metric = childMetrics.getValue(directory)
                add(Footprint(metric.width, metric.depth))
            }
            node.files.forEach {
                add(Footprint(styles.fileWidth, styles.fileDepth))
            }
        }
        val packing = packChildren(children, styles.cellGap)
        val width = maxOf(styles.minPlatterWidth, packing.contentWidth + styles.platterPadding * 2f)
        val depth = maxOf(styles.minPlatterDepth, packing.contentDepth + styles.platterPadding * 2f)
        return DirectoryMetrics(
            width = width,
            depth = depth,
            childLayouts = packing.childLayouts,
            childMetrics = childMetrics,
            directoryCount = 1 + childMetrics.values.sumOf { it.directoryCount },
            fileCount = node.files.size + childMetrics.values.sumOf { it.fileCount },
        )
    }

    private fun layout(
        node: DirectoryNode,
        metrics: DirectoryMetrics,
        centerX: Float,
        centerZ: Float,
        y: Float,
        root: Path,
        projectBasePath: String?,
        styles: ProjectStructureStyles,
        entities: MutableList<Entity>,
    ) {
        val directoryId = entityIdForDirectory(node.relativePath)
        entities += Entity(
            id = directoryId,
            kind = "box",
            position = Vec3(centerX, y, centerZ),
            scale = Vec3(metrics.width, styles.platterHeight, metrics.depth),
            color = if (node.depth == 0) styles.rootColor else shiftTowardWhite(styles.folderColor, node.depth * 0.06f),
            label = node.name,
            opacity = 0.92f,
            meta = metaOf(
                "structureType" to "directory",
                "folderPath" to pathForEntity(node.path, projectBasePath, root),
                "depth" to node.depth,
            ),
        )

        val children = buildList<Any> {
            addAll(node.directories)
            addAll(node.files)
        }
        children.forEachIndexed { index, child ->
            val childLayout = metrics.childLayouts.getOrNull(index) ?: ChildLayout(0f, 0f)
            val xOffset = childLayout.centerX
            val zOffset = childLayout.centerZ
            when (child) {
                is DirectoryNode -> {
                    val childMetrics = metrics.childMetrics.getValue(child)
                    val childY = y + styles.platterHeight / 2f + styles.folderLift + styles.platterHeight / 2f
                    layout(
                        node = child,
                        metrics = childMetrics,
                        centerX = centerX + xOffset,
                        centerZ = centerZ + zOffset,
                        y = childY,
                        root = root,
                        projectBasePath = projectBasePath,
                        styles = styles,
                        entities = entities,
                    )
                }

                is FileNode -> {
                    val fileHeight = fileHeight(child.sizeBytes, styles)
                    entities += Entity(
                        id = entityIdForFile(child.relativePath),
                        kind = "box",
                        position = Vec3(
                            centerX + xOffset,
                            y + styles.platterHeight / 2f + styles.fileClearance + fileHeight / 2f,
                            centerZ + zOffset,
                        ),
                        scale = Vec3(styles.fileWidth, fileHeight, styles.fileDepth),
                        color = fileColor(child.name, styles),
                        label = child.name,
                        opacity = 1f,
                        meta = metaOf(
                            "structureType" to "file",
                            "path" to pathForEntity(child.path, projectBasePath, root),
                            "sizeBytes" to child.sizeBytes,
                            "extension" to extensionOf(child.name),
                        ),
                    )
                }
            }
        }
    }

    private fun fileHeight(sizeBytes: Long, styles: ProjectStructureStyles): Float {
        if (sizeBytes <= 0L) return styles.fileBaseHeight
        val normalized = kotlin.math.min(1f, kotlin.math.log10(sizeBytes.toDouble() + 1.0).toFloat() / 6f)
        return styles.fileBaseHeight + normalized * styles.fileHeightRange
    }

    private fun packChildren(children: List<Footprint>, gap: Float): PackingResult {
        if (children.isEmpty()) {
            return PackingResult(contentWidth = 0f, contentDepth = 0f, childLayouts = emptyList())
        }

        var best: PackingResult? = null
        for (columns in 1..children.size) {
            val rows = kotlin.math.ceil(children.size / columns.toDouble()).toInt()
            val columnWidths = FloatArray(columns)
            val rowDepths = FloatArray(rows)
            children.forEachIndexed { index, footprint ->
                val column = index % columns
                val row = index / columns
                columnWidths[column] = maxOf(columnWidths[column], footprint.width)
                rowDepths[row] = maxOf(rowDepths[row], footprint.depth)
            }

            val contentWidth = columnWidths.sum() + gap * (columns - 1).coerceAtLeast(0)
            val contentDepth = rowDepths.sum() + gap * (rows - 1).coerceAtLeast(0)
            val layouts = MutableList(children.size) { ChildLayout(0f, 0f) }

            var left = -contentWidth / 2f
            val columnStarts = FloatArray(columns)
            for (column in 0 until columns) {
                columnStarts[column] = left
                left += columnWidths[column] + gap
            }

            var top = -contentDepth / 2f
            val rowStarts = FloatArray(rows)
            for (row in 0 until rows) {
                rowStarts[row] = top
                top += rowDepths[row] + gap
            }

            children.forEachIndexed { index, footprint ->
                val column = index % columns
                val row = index / columns
                val centerX = columnStarts[column] + columnWidths[column] / 2f
                val centerZ = rowStarts[row] + rowDepths[row] / 2f
                layouts[index] = ChildLayout(centerX = centerX, centerZ = centerZ)
            }

            val candidate = PackingResult(
                contentWidth = contentWidth,
                contentDepth = contentDepth,
                childLayouts = layouts,
            )
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate
            }
        }

        return checkNotNull(best)
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "")

    private fun fileColor(name: String, styles: ProjectStructureStyles): String =
        when (extensionOf(name).lowercase()) {
            "kt", "kts", "java", "groovy", "scala" -> "#d59f6a"
            "js", "ts", "tsx", "jsx" -> "#c9b15c"
            "rb", "py", "php" -> "#c68173"
            "xml", "json", "yaml", "yml", "toml", "properties" -> "#8db07a"
            "md", "txt" -> "#9d99c8"
            else -> styles.fileColor
        }

    private fun entityIdForDirectory(relativePath: String): String =
        "dir:${relativePath.ifBlank { "." }}"

    private fun entityIdForFile(relativePath: String): String = "file:$relativePath"

    private fun pathForEntity(path: Path, projectBasePath: String?, root: Path): String {
        val normalized = path.normalize()
        val projectRoot = projectBasePath?.let(Path::of)?.normalize()
        return when {
            projectRoot != null && normalized.startsWith(projectRoot) -> projectRoot.relativize(normalized).toString()
            normalized.startsWith(root) -> root.relativize(normalized).toString()
            else -> normalized.toString()
        }
    }

    private fun shiftTowardWhite(hex: String, amount: Float): String {
        val normalized = hex.removePrefix("#")
        if (normalized.length != 6) return hex
        return runCatching {
            fun channel(offset: Int): Int {
                val value = normalized.substring(offset, offset + 2).toInt(16)
                return (value + ((255 - value) * amount.coerceAtMost(0.55f))).toInt().coerceIn(0, 255)
            }
            "#%02x%02x%02x".format(channel(0), channel(2), channel(4))
        }.getOrDefault(hex)
    }

    private fun metaOf(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
        pairs.mapNotNull { (key, value) -> value?.let { key to jsonPrimitive(it) } }.toMap()

    private fun jsonPrimitive(value: Any): JsonElement =
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private data class DirectoryNode(
        val path: Path,
        val relativePath: String,
        val name: String,
        val depth: Int,
        val directories: List<DirectoryNode>,
        val files: List<FileNode>,
    )

    private data class FileNode(
        val path: Path,
        val relativePath: String,
        val name: String,
        val sizeBytes: Long,
    )

    private data class DirectoryMetrics(
        val width: Float,
        val depth: Float,
        val childLayouts: List<ChildLayout>,
        val childMetrics: Map<DirectoryNode, DirectoryMetrics>,
        val directoryCount: Int,
        val fileCount: Int,
    )

    private data class Footprint(
        val width: Float,
        val depth: Float,
    )

    private data class ChildLayout(
        val centerX: Float,
        val centerZ: Float,
    )

    private data class PackingResult(
        val contentWidth: Float,
        val contentDepth: Float,
        val childLayouts: List<ChildLayout>,
    ) {
        fun isBetterThan(other: PackingResult): Boolean {
            val span = maxOf(contentWidth, contentDepth)
            val otherSpan = maxOf(other.contentWidth, other.contentDepth)
            if (span != otherSpan) return span < otherSpan
            val aspectRatio = aspectRatio(contentWidth, contentDepth)
            val otherAspectRatio = aspectRatio(other.contentWidth, other.contentDepth)
            if (aspectRatio != otherAspectRatio) return aspectRatio < otherAspectRatio
            val area = contentWidth * contentDepth
            val otherArea = other.contentWidth * other.contentDepth
            if (area != otherArea) return area < otherArea
            return minOf(contentWidth, contentDepth) >= minOf(other.contentWidth, other.contentDepth)
        }

        private fun aspectRatio(width: Float, depth: Float): Float {
            val larger = maxOf(width, depth)
            val smaller = maxOf(0.0001f, minOf(width, depth))
            return larger / smaller
        }
    }
}
