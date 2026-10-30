package avalon.server

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Duration

/**
 * The embeddable Avalon server: static player UI + one WebSocket game room.
 * The same class runs on a PC (see [main]) and inside the Android host app.
 */
class AvalonServer(
    val port: Int = 8080,
    private val host: String = "0.0.0.0",
    hostKey: String? = null,
    /** Resolves the address friends should use (hotspot IP). */
    private val joinAddress: () -> String? = { bestLanAddress() },
) {
    val room = Room(hostKey = hostKey ?: Room.randomToken(10), joinUrl = { joinUrlFor(joinAddress()) })
    val hostKey: String get() = room.hostKey

    private var engine: ApplicationEngine? = null

    /** The port actually bound (useful when started with port 0 in tests). */
    val boundPort: Int get() = engine?.let { e ->
        kotlinx.coroutines.runBlocking { e.resolvedConnectors().first().port }
    } ?: port

    fun joinUrlFor(address: String?): String? = address?.let { "http://$it:${boundPort}/" }

    /** URL that makes the joining browser the host (keep it on the host phone only). */
    fun hostUrl(address: String = "127.0.0.1") = "http://$address:${boundPort}/?host=$hostKey"

    fun start(wait: Boolean = false) {
        engine = embeddedServer(CIO, port = port, host = host) { module() }.start(wait = wait)
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(60)
            maxFrameSize = 64 * 1024
            masking = false
        }
        // Players load the page once per game; keep it simple and always fresh.
        intercept(ApplicationCallPipeline.Plugins) { call.response.cacheControl(CacheControl.NoCache(null)) }
        routing {
            get("/health") { call.respondText("ok", ContentType.Text.Plain) }
            webSocket("/ws") {
                val conn = Connection(this)
                // Single writer per socket keeps message order.
                val writer = launch {
                    for (text in conn.outbox) send(Frame.Text(text))
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) room.onMessage(conn, frame.readText())
                    }
                } catch (_: Exception) {
                    // closed or broken socket
                } finally {
                    conn.outbox.close()
                    writer.cancel()
                    room.onClose(conn)
                }
            }
            staticResources("/", "web") {
                default("index.html")
            }
        }
    }

    companion object {
        /** All IPv4 addresses that look like a LAN/hotspot address, best first. */
        fun lanAddresses(): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            out += nif.name to addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return out.sortedBy { (name, ip) -> addressRank(name, ip) }
        }

        /** Hotspot interfaces (ap0, swlan0, wlan1...) and gateway-looking addresses rank first. */
        fun addressRank(nifName: String, ip: String): Int {
            var r = 100
            val n = nifName.lowercase()
            if (n.startsWith("ap") || n.startsWith("swlan") || n.contains("hotspot")) r -= 50
            if (n.startsWith("wlan")) r -= 20
            if (ip.endsWith(".1")) r -= 15
            if (ip.startsWith("192.168.")) r -= 10
            if (ip.startsWith("10.")) r -= 5
            if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("docker") || n.startsWith("veth")) r += 100
            return r
        }

        fun bestLanAddress(): String? = lanAddresses().firstOrNull()?.second
    }
}
