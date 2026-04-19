package dev.spatial.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import dev.spatial.scene.Highlight
import dev.spatial.scene.InteractionConfig
import dev.spatial.scene.InteractionState
import dev.spatial.scene.LandscapeTimeline
import dev.spatial.scene.Link
import dev.spatial.scene.Narrate
import dev.spatial.scene.Scene
import dev.spatial.scene.TourRequest
import dev.spatial.ui.SpatialBridgeMessage
import dev.spatial.ui.SpatialSceneBridge
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Collections
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

@Service(Service.Level.PROJECT)
class SpatialRemoteServer(private val project: Project) : SceneService.Listener, Disposable {

    @Serializable
    data class RemoteViewSettings(
        val worldScale: Double = DEFAULT_WORLD_SCALE,
        val immersiveEntryDepthMultiplier: Double = DEFAULT_IMMERSIVE_ENTRY_DEPTH_MULTIPLIER,
    )

    companion object {
        const val DEFAULT_WORLD_SCALE: Double = 0.1
        const val DEFAULT_IMMERSIVE_ENTRY_DEPTH_MULTIPLIER: Double = 10.0
    }

    private val sceneService = project.service<SceneService>()
    private val executor = Executors.newCachedThreadPool()
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val clients = CopyOnWriteArrayList<SseClient>()
    private val interfaceBindings: List<RemoteInterfaceBinding> = discoverBindings()
    private val tlsMaterial = SpatialRemoteTlsMaterial.loadOrCreate(interfaceBindings.map { it.hostAddress }.toSet())
    private val httpServer: HttpServer
    private val httpsServer: HttpsServer
    private val httpPort: Int
    private val httpsPort: Int
    @Volatile
    private var remoteViewSettings: RemoteViewSettings = RemoteViewSettings()

    init {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpPort = httpServer.address.port
        httpServer.executor = executor
        installRoutes(httpServer)
        httpServer.start()

        httpsServer = HttpsServer.create(InetSocketAddress(0), 0)
        httpsPort = httpsServer.address.port
        httpsServer.executor = executor
        httpsServer.httpsConfigurator = object : HttpsConfigurator(tlsMaterial.sslContext) {
            override fun configure(params: HttpsParameters) {
                val sslParams = sslContext.defaultSSLParameters
                params.setSSLParameters(sslParams)
            }
        }
        installRoutes(httpsServer)
        httpsServer.start()

        heartbeatExecutor.scheduleAtFixedRate({ sendHeartbeat() }, 15, 15, TimeUnit.SECONDS)
        sceneService.addListener(this)
    }

    fun remoteUrls(
        worldScale: Double? = null,
        immersiveEntryDepthMultiplier: Double? = null,
    ): List<RemoteUrlEntry> {
        val scale = worldScale ?: remoteViewSettings.worldScale
        val depthMultiplier = immersiveEntryDepthMultiplier ?: remoteViewSettings.immersiveEntryDepthMultiplier
        val entries = mutableListOf<RemoteUrlEntry>()
        entries += RemoteUrlEntry(
            label = "Local machine",
            interfaceName = "loopback",
            host = "localhost",
            httpUrl = urlWithRemoteParams("http://localhost:$httpPort/", scale, depthMultiplier),
            httpsUrl = urlWithRemoteParams("https://localhost:$httpsPort/", scale, depthMultiplier),
            certificateUrl = "http://localhost:$httpPort/spatial-remote.cer",
            secure = true,
        )
        entries += RemoteUrlEntry(
            label = "Local machine alias",
            interfaceName = "loopback",
            host = "127.0.0.1",
            httpUrl = urlWithRemoteParams("http://127.0.0.1:$httpPort/", scale, depthMultiplier),
            httpsUrl = urlWithRemoteParams("https://127.0.0.1:$httpsPort/", scale, depthMultiplier),
            certificateUrl = "http://127.0.0.1:$httpPort/spatial-remote.cer",
            secure = true,
        )
        interfaceBindings.forEach { binding ->
            val baseLabel = when (binding.kind) {
                InterfaceKind.LAN -> "LAN"
            }
            entries += RemoteUrlEntry(
                label = "$baseLabel (${binding.displayLabel})",
                interfaceName = binding.displayLabel,
                host = binding.hostAddress,
                httpUrl = urlWithRemoteParams("http://${binding.hostAddress}:$httpPort/", scale, depthMultiplier),
                httpsUrl = urlWithRemoteParams("https://${binding.hostAddress}:$httpsPort/", scale, depthMultiplier),
                certificateUrl = "http://${binding.hostAddress}:$httpPort/spatial-remote.cer",
                secure = true,
            )
        }
        return entries
    }

    fun remoteViewSettings(): RemoteViewSettings = remoteViewSettings

    fun updateRemoteViewSettings(
        worldScale: Double? = null,
        immersiveEntryDepthMultiplier: Double? = null,
    ): RemoteViewSettings {
        val next = remoteViewSettings.copy(
            worldScale = sanitizeWorldScale(worldScale ?: remoteViewSettings.worldScale),
            immersiveEntryDepthMultiplier = sanitizeImmersiveEntryDepthMultiplier(
                immersiveEntryDepthMultiplier ?: remoteViewSettings.immersiveEntryDepthMultiplier
            ),
        )
        remoteViewSettings = next
        publish("remote-view", SceneService.encode(next))
        return next
    }

    override fun onSceneChanged(scene: Scene) {
        publish("scene", SceneService.encodeScene(scene))
    }

    override fun onFocus(focus: dev.spatial.scene.CameraFocus) {
        publish("focus", SceneService.encode(focus))
    }

    override fun onFocusEntity(req: dev.spatial.scene.FocusEntity) {
        publish("focus-entity", SceneService.encode(req))
    }

    override fun onSpeech(message: String) {
        publish("speech", SceneService.encode(message))
    }

    override fun onNarrate(req: Narrate) {
        publish("narrate", SceneService.encode(req))
    }

    override fun onHighlight(req: Highlight) {
        publish("highlight", SceneService.encode(req))
    }

    override fun onPlayTour(req: TourRequest) {
        publish("tour", SceneService.encode(req))
    }

    override fun onLandscape(timeline: LandscapeTimeline?) {
        publish("landscape", SceneService.encode(timeline))
    }

    override fun onLinksChanged(links: List<Link>) {
        publish("links", SceneService.encode(links))
    }

    override fun onInteractionConfig(config: InteractionConfig?) {
        publish("interaction-config", SceneService.encode(config))
    }

    override fun onInteractionStateChanged(state: InteractionState) {
        publish("interaction-state", SceneService.encode(state))
    }

    override fun dispose() {
        sceneService.removeListener(this)
        clients.forEach { it.close() }
        clients.clear()
        heartbeatExecutor.shutdownNow()
        executor.shutdownNow()
        httpsServer.stop(0)
        httpServer.stop(0)
    }

    private fun installRoutes(server: HttpServer) {
        server.createContext("/") { exchange ->
            when (exchange.requestMethod.uppercase()) {
                "GET" -> {
                    val path = exchange.requestURI.path
                    when (path) {
                        "/", "/index.html" -> sendText(exchange, 200, "text/html; charset=utf-8", remoteHtml(exchange))
                        "/healthz" -> sendText(exchange, 200, "text/plain; charset=utf-8", "ok")
                        "/spatial-remote.cer" -> sendBinary(exchange, 200, "application/pkix-cert", tlsMaterial.certificateDer)
                        else -> sendText(exchange, 404, "text/plain; charset=utf-8", "Not found")
                    }
                }
                else -> sendMethodNotAllowed(exchange, listOf("GET"))
            }
        }
        server.createContext("/api/state") { exchange ->
            when (exchange.requestMethod.uppercase()) {
                "GET" -> sendJson(exchange, 200, SceneService.encode(stateSnapshot()))
                else -> sendMethodNotAllowed(exchange, listOf("GET"))
            }
        }
        server.createContext("/api/events") { exchange ->
            when (exchange.requestMethod.uppercase()) {
                "GET" -> openEventStream(exchange)
                else -> sendMethodNotAllowed(exchange, listOf("GET"))
            }
        }
        server.createContext("/api/bridge") { exchange ->
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handleBridge(exchange)
                else -> sendMethodNotAllowed(exchange, listOf("POST"))
            }
        }
    }

    private fun remoteHtml(exchange: HttpExchange): String {
        val requestHost = exchange.requestHeaders.getFirst("Host")
            ?.substringBefore(':')
            ?.takeIf { it.isNotBlank() }
            ?: exchange.localAddress.address.hostAddress
        val runtimeConfig = SceneService.JSON.encodeToString(
            SpatialRemoteRuntimeConfig(
                transport = "remote",
                stateUrl = "/api/state",
                eventsUrl = "/api/events",
                bridgeUrl = "/api/bridge",
                candidateEntries = remoteUrls(),
                recommendedHttpsUrl = "https://$requestHost:$httpsPort/",
                certificateUrl = "http://$requestHost:$httpPort/spatial-remote.cer",
                worldScale = remoteViewSettings.worldScale,
                immersiveEntryDepthMultiplier = remoteViewSettings.immersiveEntryDepthMultiplier,
            )
        )
        return loadResource("/web/index.html")
            .replace(
                "/*__SPATIAL_RUNTIME_INJECTION__*/",
                """
                window.__spatialConfig = $runtimeConfig;
                window.__spatialSend = function (payload) {
                  return window.__spatialRemoteSend ? window.__spatialRemoteSend(payload) : null;
                };
                """.trimIndent(),
            )
            .replace("/*__SPATIAL_THREE_INJECTION__*/", loadResource("/web/three.min.js"))
            .replace("/*__SPATIAL_SCENE_INJECTION__*/", loadResource("/web/scene.js"))
            .replace("/*__SPATIAL_REMOTE_INJECTION__*/", loadResource("/web/remote.js"))
    }

    private fun stateSnapshot(): SpatialRemoteStateSnapshot = SpatialRemoteStateSnapshot(
        scene = sceneService.scene,
        landscape = sceneService.landscape,
        links = sceneService.links,
        interactionConfig = sceneService.interactionConfig,
        interactionState = sceneService.interactionState,
        remoteView = remoteViewSettings,
    )

    private fun openEventStream(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache, no-transform")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)
        val writer = BufferedWriter(OutputStreamWriter(exchange.responseBody, StandardCharsets.UTF_8))
        val client = SseClient(exchange, writer)
        clients += client
        client.sendComment("connected")
        client.sendEvent("hello", SceneService.encode(SpatialRemoteHello(remoteUrls(), remoteViewSettings)))
    }

    private fun handleBridge(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val message = runCatching { SceneService.JSON.decodeFromString<SpatialBridgeMessage>(body) }.getOrNull()
        if (message == null) {
            sendText(exchange, 400, "text/plain; charset=utf-8", "Invalid request body")
            return
        }
        when (message.type) {
            SpatialSceneBridge.TYPE_OPEN_FILE -> {
                val ok = SpatialSceneBridge.openFile(project, message)
                sendJson(exchange, if (ok) 200 else 404, """{"ok":$ok}""")
            }
            SpatialSceneBridge.TYPE_INTERACTION_STATE -> {
                message.interactionState?.let(sceneService::updateInteractionState)
                sendJson(exchange, 200, """{"ok":true}""")
            }
            else -> sendText(exchange, 400, "text/plain; charset=utf-8", "Unsupported bridge message")
        }
    }

    private fun publish(event: String, data: String) {
        if (clients.isEmpty()) return
        val stale = mutableListOf<SseClient>()
        clients.forEach { client ->
            if (!client.sendEvent(event, data)) stale += client
        }
        if (stale.isNotEmpty()) {
            clients.removeAll(stale.toSet())
            stale.forEach(SseClient::close)
        }
    }

    private fun sendHeartbeat() {
        val stale = mutableListOf<SseClient>()
        clients.forEach { client ->
            if (!client.sendComment("heartbeat")) stale += client
        }
        if (stale.isNotEmpty()) {
            clients.removeAll(stale.toSet())
            stale.forEach(SseClient::close)
        }
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        sendText(exchange, status, "application/json; charset=utf-8", body)
    }

    private fun sendText(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        sendBinary(exchange, status, contentType, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendBinary(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendMethodNotAllowed(exchange: HttpExchange, allowed: List<String>) {
        exchange.responseHeaders.add("Allow", allowed.joinToString(", "))
        sendText(exchange, 405, "text/plain; charset=utf-8", "Method not allowed")
    }

    private fun loadResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Missing plugin resource: $path")

    private fun sanitizeWorldScale(value: Double): Double = value.coerceIn(0.01, 100.0)
    private fun sanitizeImmersiveEntryDepthMultiplier(value: Double): Double = value.coerceIn(0.01, 100.0)

    private fun urlWithRemoteParams(baseUrl: String, scale: Double, depthMultiplier: Double): String {
        val normalizedScale = sanitizeWorldScale(scale)
        val normalizedDepth = sanitizeImmersiveEntryDepthMultiplier(depthMultiplier)
        return "$baseUrl?scale=$normalizedScale&depth=$normalizedDepth"
    }

    private fun discoverBindings(): List<RemoteInterfaceBinding> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        return Collections.list(interfaces)
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { networkInterface ->
                Collections.list(networkInterface.inetAddresses)
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { address ->
                        RemoteInterfaceBinding(
                            hostAddress = address.hostAddress,
                            interfaceName = networkInterface.name,
                            interfaceDisplayName = networkInterface.displayName,
                            kind = InterfaceKind.LAN,
                        )
                    }
            }
            .sortedWith(compareBy<RemoteInterfaceBinding> { if (InetAddress.getByName(it.hostAddress).isSiteLocalAddress) 0 else 1 }
                .thenBy { it.interfaceName }
                .thenBy { it.hostAddress })
            .distinctBy { it.hostAddress }
            .toList()
    }

    enum class InterfaceKind {
        LAN,
    }

    data class RemoteInterfaceBinding(
        val hostAddress: String,
        val interfaceName: String,
        val interfaceDisplayName: String,
        val kind: InterfaceKind,
    ) {
        val displayLabel: String
            get() = listOf(interfaceDisplayName.trim(), interfaceName.trim())
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" · ")
    }

    @Serializable
    data class RemoteUrlEntry(
        val label: String,
        val interfaceName: String? = null,
        val host: String,
        val httpUrl: String,
        val httpsUrl: String,
        val certificateUrl: String,
        val secure: Boolean = true,
    )

    @Serializable
    private data class SpatialRemoteRuntimeConfig(
        val transport: String,
        val stateUrl: String,
        val eventsUrl: String,
        val bridgeUrl: String,
        val candidateEntries: List<RemoteUrlEntry>,
        val recommendedHttpsUrl: String,
        val certificateUrl: String,
        val worldScale: Double = 1.0,
        val immersiveEntryDepthMultiplier: Double = DEFAULT_IMMERSIVE_ENTRY_DEPTH_MULTIPLIER,
    )

    @Serializable
    private data class SpatialRemoteHello(
        val urls: List<RemoteUrlEntry>,
        val remoteView: RemoteViewSettings,
    )

    @Serializable
    private data class SpatialRemoteStateSnapshot(
        val scene: Scene,
        val landscape: LandscapeTimeline? = null,
        val links: List<Link> = emptyList(),
        val interactionConfig: InteractionConfig? = null,
        val interactionState: InteractionState = InteractionState(),
        val remoteView: RemoteViewSettings = RemoteViewSettings(),
    )

    private class SseClient(
        private val exchange: HttpExchange,
        private val writer: BufferedWriter,
    ) {
        private val lock = Any()

        fun sendEvent(event: String, data: String): Boolean = write {
            writer.append("event: ").append(event).append('\n')
            data.lineSequence().forEach { line ->
                writer.append("data: ").append(line).append('\n')
            }
            writer.append('\n')
        }

        fun sendComment(comment: String): Boolean = write {
            writer.append(": ").append(comment).append('\n').append('\n')
        }

        private fun write(block: () -> Unit): Boolean = synchronized(lock) {
            runCatching {
                block()
                writer.flush()
            }.isSuccess
        }

        fun close() {
            runCatching { writer.close() }
            runCatching { exchange.close() }
        }
    }
}

private data class SpatialRemoteTlsMaterial(
    val sslContext: SSLContext,
    val certificateDer: ByteArray,
) {
    companion object {
        private const val KEY_ALIAS = "spatial-remote"
        private val STORE_PASSWORD = "spatial-remote".toCharArray()
        private val CERT_LIFETIME = Duration.ofDays(825)

        fun loadOrCreate(ipAddresses: Set<String>): SpatialRemoteTlsMaterial {
            val dir = Paths.get(PathManager.getSystemPath(), "spatial-remote")
            Files.createDirectories(dir)
            val storePath = dir.resolve("spatial-remote.p12")
            val keyStore = if (Files.exists(storePath)) {
                loadKeyStore(storePath)
            } else {
                createKeyStore(storePath, ipAddresses)
            }
            val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
                ?: error("Missing TLS certificate alias '$KEY_ALIAS'")
            val sans = certificate.subjectAlternativeNames
                ?.mapNotNull { entry ->
                    val type = entry.getOrNull(0) as? Number ?: return@mapNotNull null
                    val value = entry.getOrNull(1) as? String ?: return@mapNotNull null
                    when (type.toInt()) {
                        GeneralName.iPAddress -> value
                        else -> null
                    }
                }
                ?.toSet()
                ?: emptySet()
            val missingIps = ipAddresses - sans
            val effectiveKeyStore = if (missingIps.isNotEmpty()) {
                createKeyStore(storePath, ipAddresses)
            } else {
                keyStore
            }
            val effectiveCertificate = effectiveKeyStore.getCertificate(KEY_ALIAS) as X509Certificate
            val sslContext = SSLContext.getInstance("TLS")
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(effectiveKeyStore, STORE_PASSWORD)
            }.keyManagers
            sslContext.init(keyManagers, null, SecureRandom())
            return SpatialRemoteTlsMaterial(
                sslContext = sslContext,
                certificateDer = effectiveCertificate.encoded,
            )
        }

        private fun createKeyStore(path: Path, ipAddresses: Set<String>): KeyStore {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            val now = System.currentTimeMillis()
            val notBefore = Date(now - Duration.ofDays(1).toMillis())
            val notAfter = Date(now + CERT_LIFETIME.toMillis())
            val subject = X500Name("CN=Spatial Remote")
            val certificateBuilder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, SecureRandom()).abs(),
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            val generalNames = mutableListOf(
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, "127.0.0.1"),
            )
            ipAddresses.sorted().forEach { generalNames += GeneralName(GeneralName.iPAddress, it) }
            certificateBuilder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(generalNames.toTypedArray()),
            )
            certificateBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            certificateBuilder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            certificateBuilder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            val certificate = JcaX509CertificateConverter()
                .getCertificate(certificateBuilder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)))
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, STORE_PASSWORD)
            keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD, arrayOf(certificate))
            Files.newOutputStream(path).use { output ->
                keyStore.store(output, STORE_PASSWORD)
            }
            return keyStore
        }

        private fun loadKeyStore(path: Path): KeyStore {
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(path).use { input ->
                keyStore.load(input, STORE_PASSWORD)
            }
            return keyStore
        }
    }
}
