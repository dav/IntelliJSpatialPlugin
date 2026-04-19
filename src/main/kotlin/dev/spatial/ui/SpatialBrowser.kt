package dev.spatial.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import dev.spatial.scene.CameraFocus
import dev.spatial.scene.FocusEntity
import dev.spatial.scene.Highlight
import dev.spatial.scene.InteractionConfig
import dev.spatial.scene.InteractionState
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Link
import dev.spatial.scene.LinkSet
import dev.spatial.scene.Narrate
import dev.spatial.scene.Scene
import dev.spatial.scene.TourRequest
import dev.spatial.service.SceneService
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.ArrayDeque
import kotlinx.serialization.decodeFromString

/**
 * Wraps a single [JBCefBrowser] instance hosting the Three.js scene. Owns the
 * bridge back to Kotlin (currently unused, but wired in so future click/hover
 * events from the 3D view can surface in the IDE).
 *
 * Receives state updates by subscribing to [SceneService]. The HTML/JS bundle
 * lives under `/web/` in the plugin jar; we load it inline via `loadHTML` so
 * no scheme handler is needed and the bundle stays self-contained.
 */
class SpatialBrowser(project: Project, parentDisposable: Disposable) : SceneService.Listener, Disposable {

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(false)
        .build()

    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val sceneService = project.service<SceneService>()

    @Volatile
    private var pageReady: Boolean = false

    private val pendingLock = Any()
    private val pending = ArrayDeque<Runnable>()

    init {
        Disposer.register(parentDisposable, this)
        Disposer.register(this, browser)
        Disposer.register(this, jsQuery)

        jsQuery.addHandler { payload ->
            thisLogger().debug("spatial bridge <- $payload")
            val message = runCatching { SceneService.JSON.decodeFromString<SpatialBridgeMessage>(payload) }.getOrNull()
            when (message?.type) {
                SpatialSceneBridge.TYPE_OPEN_FILE -> {
                    if (SpatialSceneBridge.openFile(project, message)) {
                        JBCefJSQuery.Response(null as String?)
                    } else {
                        JBCefJSQuery.Response(null, 1, "Could not open file")
                    }
                }
                SpatialSceneBridge.TYPE_INTERACTION_STATE -> {
                    message.interactionState?.let(sceneService::updateInteractionState)
                    JBCefJSQuery.Response(null as String?)
                }
                else -> JBCefJSQuery.Response(null, 2, "Unsupported spatial bridge message")
            }
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    pageReady = true
                    flushPending()
                }
            }
        }, browser.cefBrowser)

        val html = loadResource("/web/index.html")
            .replace(
                "/*__SPATIAL_RUNTIME_INJECTION__*/",
                "window.__spatialConfig = { transport: 'jcef' }; window.__spatialSend = function (payload) { ${jsQuery.inject("payload")} };",
            )
            .replace("/*__SPATIAL_THREE_INJECTION__*/", loadResource("/web/three.min.js"))
            .replace("/*__SPATIAL_SCENE_INJECTION__*/", loadResource("/web/scene.js"))
            .replace("/*__SPATIAL_REMOTE_INJECTION__*/", "")

        browser.loadHTML(html)
        sceneService.addListener(this)
    }

    val component get() = browser.component

    override fun onSceneChanged(scene: Scene) {
        val json = SceneService.encodeScene(scene)
        runInBrowser("window.Spatial.setScene($json)")
    }

    override fun onFocus(focus: CameraFocus) {
        val json = SceneService.encodeFocus(focus)
        runInBrowser("window.Spatial.focus($json)")
    }

    override fun onFocusEntity(req: FocusEntity) {
        runInBrowser("window.Spatial.focusEntity(${SceneService.encode(req)})")
    }

    override fun onSpeech(message: String) {
        val escaped = SceneService.JSON.encodeToString(kotlinx.serialization.serializer<String>(), message)
        runInBrowser("window.Spatial.speak($escaped)")
    }

    override fun onNarrate(req: Narrate) {
        runInBrowser("window.Spatial.narrate(${SceneService.encode(req)})")
    }

    override fun onHighlight(req: Highlight) {
        runInBrowser("window.Spatial.highlight(${SceneService.encode(req)})")
    }

    override fun onPlayTour(req: TourRequest) {
        runInBrowser("window.Spatial.playTour(${SceneService.encode(req)})")
    }

    override fun onLandscape(timeline: LandscapeTimeline?) {
        if (timeline == null) {
            runInBrowser("window.Spatial.clearLandscape()")
        } else {
            runInBrowser("window.Spatial.setLandscape(${SceneService.encode(timeline)})")
        }
    }

    override fun onLinksChanged(links: List<Link>) {
        runInBrowser("window.Spatial.setLinks(${SceneService.encode(LinkSet(links))})")
    }

    override fun onInteractionConfig(config: InteractionConfig?) {
        if (config == null) {
            runInBrowser("window.Spatial.clearInteractions()")
        } else {
            runInBrowser("window.Spatial.setInteractions(${SceneService.encode(config)})")
        }
    }

    override fun onInteractionStateChanged(state: InteractionState) {
        runInBrowser("window.Spatial.setInteractionState(${SceneService.encode(state)})")
    }

    override fun dispose() {
        sceneService.removeListener(this)
    }

    private fun runInBrowser(js: String) {
        val invoke = Runnable {
            browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "", 0)
        }
        if (pageReady) {
            invoke.run()
            return
        }
        synchronized(pendingLock) {
            if (pageReady) invoke.run() else pending.addLast(invoke)
        }
    }

    private fun flushPending() {
        while (true) {
            val next = synchronized(pendingLock) {
                if (pending.isEmpty()) null else pending.removeFirst()
            } ?: return
            next.run()
        }
    }

    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Missing plugin resource: $path")

    companion object {
        fun isSupported(): Boolean = JBCefApp.isSupported()
    }
}
