package dev.spatial.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.spatial.scene.CameraFocus
import dev.spatial.scene.Entity
import dev.spatial.scene.FocusEntity
import dev.spatial.scene.Highlight
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Link
import dev.spatial.scene.LinkSet
import dev.spatial.scene.Narrate
import dev.spatial.scene.Scene
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped scene state. Tool window browsers subscribe as [Listener]s and
 * receive the state the moment they open, so the MCP tools can push entities
 * even before the window has been revealed.
 */
@Service(Service.Level.PROJECT)
class SceneService(@Suppress("UNUSED_PARAMETER") project: Project) {

    interface Listener {
        fun onSceneChanged(scene: Scene) {}
        fun onFocus(focus: CameraFocus) {}
        fun onFocusEntity(req: FocusEntity) {}
        fun onSpeech(message: String) {}
        fun onNarrate(req: Narrate) {}
        fun onHighlight(req: Highlight) {}
        fun onLandscape(timeline: LandscapeTimeline?) {}
        fun onLinksChanged(links: List<Link>) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var scene: Scene = Scene(emptyList())
        private set

    @Volatile
    var landscape: LandscapeTimeline? = null
        private set

    @Volatile
    var links: List<Link> = emptyList()
        private set

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onSceneChanged(scene)
        landscape?.let(listener::onLandscape)
        if (links.isNotEmpty()) listener.onLinksChanged(links)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun pushEntities(entities: List<Entity>, merge: Boolean = false) {
        scene = if (merge) {
            val byId = scene.entities.associateBy { it.id }.toMutableMap()
            entities.forEach { byId[it.id] = it }
            Scene(byId.values.toList())
        } else {
            Scene(entities)
        }
        listeners.forEach { it.onSceneChanged(scene) }
    }

    fun clear() {
        scene = Scene(emptyList())
        listeners.forEach { it.onSceneChanged(scene) }
    }

    fun focus(focus: CameraFocus) {
        listeners.forEach { it.onFocus(focus) }
    }

    fun focusEntity(req: FocusEntity) {
        listeners.forEach { it.onFocusEntity(req) }
    }

    fun speak(message: String) {
        listeners.forEach { it.onSpeech(message) }
    }

    fun narrate(req: Narrate) {
        listeners.forEach { it.onNarrate(req) }
    }

    fun highlight(req: Highlight) {
        listeners.forEach { it.onHighlight(req) }
    }

    fun pushLandscape(timeline: LandscapeTimeline) {
        landscape = timeline
        listeners.forEach { it.onLandscape(timeline) }
    }

    fun clearLandscape() {
        landscape = null
        listeners.forEach { it.onLandscape(null) }
    }

    fun pushLinks(newLinks: List<Link>, merge: Boolean = false) {
        links = if (merge) {
            val byId = links.associateBy { it.id }.toMutableMap()
            newLinks.forEach { byId[it.id] = it }
            byId.values.toList()
        } else {
            newLinks
        }
        listeners.forEach { it.onLinksChanged(links) }
    }

    fun clearLinks() {
        links = emptyList()
        listeners.forEach { it.onLinksChanged(emptyList()) }
    }

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        inline fun <reified T> encode(value: T): String = JSON.encodeToString(value)
        fun encodeScene(scene: Scene): String = JSON.encodeToString(scene)
        fun encodeFocus(focus: CameraFocus): String = JSON.encodeToString(focus)
    }
}
