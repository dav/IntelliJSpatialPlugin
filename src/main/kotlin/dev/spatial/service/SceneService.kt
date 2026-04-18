package dev.spatial.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.spatial.scene.CameraFocus
import dev.spatial.scene.Entity
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
        fun onSceneChanged(scene: Scene)
        fun onFocus(focus: CameraFocus)
        fun onSpeech(message: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var scene: Scene = Scene(emptyList())
        private set

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onSceneChanged(scene)
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

    fun speak(message: String) {
        listeners.forEach { it.onSpeech(message) }
    }

    companion object {
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encodeScene(scene: Scene): String = JSON.encodeToString(scene)
        fun encodeFocus(focus: CameraFocus): String = JSON.encodeToString(focus)
    }
}
