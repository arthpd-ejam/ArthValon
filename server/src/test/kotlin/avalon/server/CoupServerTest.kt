package avalon.server

import coup.engine.CoupPending
import coup.engine.CoupPhase
import coup.engine.CoupView
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A random Coup player over the real WebSocket protocol. */
class CoupBot(
    val name: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val random: Random,
    private val hostKey: String? = null,
) {
    var playerId: String? = null
    var token: String? = null
    @Volatile var view: CoupView? = null
    val views: MutableList<CoupView> = java.util.Collections.synchronizedList(mutableListOf())
    val errors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val joined = CompletableDeferred<Unit>()
    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var lastActed = -1L

    suspend fun connect(resume: Boolean = false) {
        val s = client.webSocketSession(host = "127.0.0.1", port = port, path = "/coup/ws")
        session = s
        reader = scope.launch {
            try {
                for (frame in s.incoming) if (frame is Frame.Text) handle(frame.readText())
            } catch (_: Exception) {
            }
        }
        if (resume && token != null) send(buildJsonObject { put("type", "resume"); put("token", token!!) })
        else send(buildJsonObject {
            put("type", "join"); put("name", name)
            if (hostKey != null) put("hostKey", hostKey)
        })
        withTimeout(10_000) { joined.await() }
    }

    suspend fun drop() { reader?.cancel(); session?.close(); session = null; delay(50) }

    suspend fun reconnect() { lastActed = -1; connect(resume = true) }

    private suspend fun send(o: JsonObject) { session?.send(Frame.Text(o.toString())) }

    suspend fun action(a: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        send(buildJsonObject { put("type", "action"); put("action", a); build() })

    private fun handle(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "joined" -> {
                playerId = obj["playerId"]!!.jsonPrimitive.content
                token = obj["token"]!!.jsonPrimitive.content
                if (!joined.isCompleted) joined.complete(Unit)
            }
            "coupState" -> {
                val st = json.decodeFromJsonElement(CoupStateMessage.serializer(), obj).state
                views += st
                if (view == null || st.version >= view!!.version) view = st
            }
            "error" -> errors += obj["message"]!!.jsonPrimitive.content
        }
    }

    suspend fun act(): Boolean {
        val v = view ?: return false
        if (v.version == lastActed) return false
        val me = v.you
        val acted = when (me.pending) {
            CoupPending.TAKE_ACTION -> {
                val o = me.actions.filter { it.allowed }.random(random)
                val target = if (o.type.needsTarget) o.targets.random(random) else null
                action("declare") { put("type", o.type.name); if (target != null) put("targetId", target) }
                true
            }
            CoupPending.RESPOND -> {
                val r = random.nextInt(10)
                when {
                    r < 2 && me.canChallenge -> action("challenge")
                    r < 4 && me.canBlockWith.isNotEmpty() -> action("block") { put("card", me.canBlockWith.random(random).name) }
                    else -> action("pass")
                }
                true
            }
            CoupPending.RESPOND_BLOCK -> { if (random.nextInt(4) == 0) action("challenge") else action("pass"); true }
            CoupPending.LOSE_INFLUENCE -> {
                val idx = me.cards.indices.filter { !me.cards[it].revealed }.random(random)
                action("loseInfluence") { put("index", idx) }; true
            }
            CoupPending.EXCHANGE -> {
                val keep = me.exchangeOptions.indices.shuffled(random).take(me.keepCount)
                action("exchange") { putJsonArray("keep") { keep.forEach { add(JsonPrimitive(it)) } } }; true
            }
            else -> false
        }
        if (acted) lastActed = v.version
        return acted
    }

    suspend fun close() {
        reader?.cancel()
        try { session?.close() } catch (_: Exception) {}
        client.close()
    }
}

class CoupServerTest {

    private suspend fun await(timeoutMs: Long = 10_000, cond: () -> Boolean) =
        withTimeout(timeoutMs) { while (!cond()) delay(10) }

    private suspend fun playGames(n: Int, mode: String, games: Int, seed: Int) {
        val server = AvalonServer(port = 0, joinAddress = { "127.0.0.1" }).also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val bots = (1..n).map { i -> CoupBot("Bot $i", server.boundPort, scope, Random(seed * 100 + i), if (i == 1) server.hostKey else null) }
        try {
            bots.forEach { it.connect() }
            await { bots.all { it.view?.players?.size == n } }
            val host = bots[0]
            assertTrue(host.view!!.you.isHost)
            host.action("setOptions") { put("mode", mode) }
            await { bots.all { it.view?.mode?.name == mode } }
            repeat(games) { g ->
                if (g == 0) host.action("start") else host.action("playAgain")
                await { bots.all { it.view?.phase == CoupPhase.ACTION } }
                var dropped = false
                withTimeout(60_000) {
                    while (!bots.all { it.view?.phase == CoupPhase.GAME_OVER }) {
                        bots.forEach { it.act() }
                        // Somebody's phone sleeps for a moment mid-game and comes back to the same seat.
                        if (!dropped && (bots.last().view?.log?.size ?: 0) > 5) {
                            dropped = true
                            bots.last().drop()
                            bots.last().reconnect()
                        }
                        delay(3)
                    }
                }
                val winners = bots.map { it.view!!.winnerId }.toSet()
                assertEquals(1, winners.size)
                assertEquals(1, bots[0].view!!.players.count { it.alive })
            }
            // Leak audit: before the game is over nobody ever sees another player's face-down cards,
            // and Exchange draws are only shown to the player exchanging.
            for (b in bots) {
                val snapshot = synchronized(b.views) { b.views.toList() }
                for (v in snapshot) {
                    if (v.phase != CoupPhase.GAME_OVER) v.players.forEach { assertTrue(it.finalHidden.isEmpty(), "hand leaked") }
                    if (v.you.pending != CoupPending.EXCHANGE) assertTrue(v.you.exchangeOptions.isEmpty(), "exchange leaked")
                    assertEquals(b.playerId, v.you.id)
                }
            }
            bots.forEach { b -> assertTrue(b.errors.none { it.contains("Unknown") || it.contains("Bad") }, "protocol errors: ${b.errors}") }
        } finally {
            bots.forEach { it.close() }
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun `classic games run to a winner over websockets`() = runBlocking {
        playGames(2, "BASE", 2, 1)
        playGames(4, "BASE", 2, 2)
        playGames(6, "BASE", 2, 3)
    }

    @Test
    fun `reformation games run to a winner over websockets`() = runBlocking {
        playGames(5, "REFORMATION", 2, 4)
        playGames(8, "REFORMATION", 1, 5)
        playGames(10, "REFORMATION", 1, 6)
    }

    @Test
    fun `avalon and coup tables are separate`() = runBlocking {
        val server = AvalonServer(port = 0, joinAddress = { "127.0.0.1" }).also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val coup = CoupBot("Ann", server.boundPort, scope, Random(1), server.hostKey)
        val avalon = BotClient("Ann", server.boundPort, scope, Random(2), hostKey = server.hostKey)
        try {
            coup.connect(); avalon.connect()
            await { coup.view != null && avalon.view != null }
            assertEquals(1, coup.view!!.players.size)
            assertEquals(1, avalon.view!!.players.size)
            assertTrue(coup.view!!.you.isHost)
            assertEquals("http://127.0.0.1:${server.boundPort}/coup/", server.joinUrlFor("127.0.0.1", GameKind.COUP))
        } finally {
            coup.close(); avalon.close(); scope.cancel(); server.stop()
        }
    }

    @Test
    fun `coup pages are served and the bare address follows the host's game choice`() = runBlocking {
        val server = AvalonServer(port = 0).also { it.start() }
        val base = "http://127.0.0.1:${server.boundPort}"
        val client = HttpClient(CIO)
        try {
            val page = client.get("$base/coup/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("<title>Coup</title>"))
            assertTrue(client.get("$base/coup").bodyAsText().contains("<title>Coup</title>"))
            assertTrue(client.get("$base/coup/app.js").bodyAsText().contains("/coup/ws"))
            assertTrue(client.get("$base/coup/coup.css").bodyAsText().contains(".ccard"))
            assertTrue(client.get("$base/style.css").bodyAsText().contains(".btn"))
            assertTrue(client.get("$base/").bodyAsText().contains("<title>Avalon</title>"))
            server.selectedGame = GameKind.COUP
            assertTrue(client.get("$base/").bodyAsText().contains("<title>Coup</title>"))
            server.selectedGame = GameKind.AVALON
            assertTrue(client.get("$base/").bodyAsText().contains("<title>Avalon</title>"))
            assertTrue(server.hostUrl(game = GameKind.COUP).endsWith("/coup/?host=${server.hostKey}"))
        } finally {
            client.close(); server.stop()
        }
    }
}
