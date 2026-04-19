package dev.spatial.git

import dev.spatial.scene.LandscapeEntry
import dev.spatial.scene.LandscapeFrame
import dev.spatial.scene.LandscapeTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Builds a [LandscapeTimeline] from a git repo by shelling out to `git`. Mirrors
 * scripts/push_churn_landscape.py so an agent can produce the same data with a
 * single MCP call instead of orchestrating shell commands.
 */
class GitChurnAnalyzer(private val repoRoot: Path) {

    private val renameRe = Regex("""^(.*?)\{([^{}]*) => ([^{}]*)\}(.*)$""")

    suspend fun analyze(
        frames: Int,
        since: OffsetDateTime?,
        until: OffsetDateTime?,
        topK: Int,
        floorSize: Float,
        maxHeight: Float,
    ): LandscapeTimeline {
        require(frames > 0) { "frames must be positive" }
        require(topK > 0) { "topK must be positive" }

        val (firstCommit, lastCommit) = commitRange()
        val rawStart = since ?: firstCommit
        val rawEnd = until ?: lastCommit
        if (rawEnd.isBefore(rawStart)) {
            error("end ($rawEnd) must be on or after start ($rawStart)")
        }
        val start = rawStart
        val end = if (rawEnd.isEqual(start)) rawStart.plusSeconds(1) else rawEnd
        val bucket = Duration.between(start, end).dividedBy(frames.toLong())

        val labelFmt = when {
            bucket >= Duration.ofDays(2) -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            bucket >= Duration.ofHours(1) -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")
            else -> DateTimeFormatter.ofPattern("HH:mm:ss")
        }

        val locByPath = locAtHead()

        // Build per-frame churn maps and grand totals.
        val rawFrames = mutableListOf<Pair<String, Map<String, Int>>>()
        val totals = mutableMapOf<String, Int>()
        for (i in 0 until frames) {
            val winStart = start.plus(bucket.multipliedBy(i.toLong()))
            val winEnd = start.plus(bucket.multipliedBy((i + 1).toLong()))
            val churn = churnForWindow(winStart, winEnd)
            churn.forEach { (p, v) -> totals.merge(p, v, Int::plus) }
            rawFrames += "${winStart.format(labelFmt)} → ${winEnd.format(labelFmt)}" to churn
        }

        // Top K paths that still exist at HEAD (so deleted-then-rewritten paths don't crowd).
        val keep = totals.entries
            .filter { it.key in locByPath }
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }

        if (keep.isEmpty()) {
            error("no eligible files in window (try a wider --since/--until or a different repo)")
        }

        val outFrames = rawFrames.map { (label, churn) ->
            LandscapeFrame(
                label = label,
                entries = keep.map { path ->
                    LandscapeEntry(
                        path = path,
                        loc = locByPath.getValue(path).toFloat(),
                        churn = (churn[path] ?: 0).toFloat(),
                    )
                },
            )
        }

        return LandscapeTimeline(outFrames, floorSize = floorSize, maxHeight = maxHeight)
    }

    private suspend fun commitRange(): Pair<OffsetDateTime, OffsetDateTime> {
        val first = git("log", "--reverse", "--pretty=format:%cI", "--max-count=1").trim()
        val last = git("log", "--pretty=format:%cI", "--max-count=1").trim()
        if (first.isBlank() || last.isBlank()) error("repo has no commits")
        return OffsetDateTime.parse(first) to OffsetDateTime.parse(last)
    }

    private suspend fun churnForWindow(
        start: OffsetDateTime,
        end: OffsetDateTime,
    ): Map<String, Int> {
        val out = git(
            "log",
            "--no-merges",
            "--since=${start}",
            "--until=${end}",
            "--numstat",
            "--pretty=tformat:",
        )
        val totals = HashMap<String, Int>()
        out.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@forEach
            val (insStr, delStr, rawPath) = parts
            if (insStr == "-" || delStr == "-") return@forEach // binary
            val path = normalizeRename(rawPath) ?: return@forEach
            val ins = insStr.toIntOrNull() ?: return@forEach
            val del = delStr.toIntOrNull() ?: return@forEach
            totals.merge(path, ins + del, Int::plus)
        }
        return totals
    }

    private fun normalizeRename(raw: String): String? {
        val m = renameRe.matchEntire(raw) ?: return raw
        val (pre, _, new, suf) = m.destructured
        return ("$pre$new$suf").replace("//", "/").trimStart('/')
    }

    /** {path → estimated LOC at HEAD}, derived from blob byte sizes. */
    private suspend fun locAtHead(): Map<String, Int> {
        val out = git("ls-tree", "-r", "-l", "HEAD")
        val result = HashMap<String, Int>()
        out.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@forEach
            val meta = line.substring(0, tab).split(Regex("\\s+"))
            if (meta.size < 4) return@forEach
            val size = meta[3].toIntOrNull() ?: return@forEach
            val path = line.substring(tab + 1)
            // ~30 bytes/line as a coarse LOC proxy; cells stay legibly sized in the treemap.
            result[path] = maxOf(1, size / 30)
        }
        return result
    }

    private suspend fun git(vararg args: String): String = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(listOf("git", *args))
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("git timed out after 30s: ${args.joinToString(" ")}")
        }
        if (proc.exitValue() != 0) {
            throw IOException("git ${args.joinToString(" ")} failed (exit ${proc.exitValue()}): ${output.takeLast(500)}")
        }
        output
    }
}
