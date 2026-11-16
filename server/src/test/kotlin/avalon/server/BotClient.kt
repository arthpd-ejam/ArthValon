package avalon.server

import avalon.engine.PendingAction
import avalon.engine.Phase
import avalon.engine.PlayerView
import avalon.engine.QuestCard
import avalon.engine.Vote
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random

/** Every message a client received, kept raw for the leak test. */
class Recorded(val raw: String, val element: JsonElement)

/**
 * A WebSocket client that behaves like a (random) human player. It only ever
 * acts on what the server tells it (its own view), exactly like the browser UI.
 */
class BotClient(
    val name: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val random: Random,
    private val hostKey: String? = null,
    /** Evil bots play FAIL with this probability when they may. */
    private val failRate: Double = 0.6,
) {
    val received: MutableList<Recorded> = java.util.Collections.synchronizedList(mutableListOf())
    var playerId: String? = null
    var token: String? = null
    var view: PlayerView? = null
    val errors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    fun snapshotReceived(): List<Recorded> = synchronized(received) { received.toList() }
    fun snapshotErrors(): List<String> = synchronized(errors) { errors.toList() }
    private val joined = CompletableDeferred<Unit>()
    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null
    private val client = HttpClient(CIO) { install(WebSockets) }
    private var lastActedVersion = -1L

    suspend fun connect(resume: Boolean = false) {
        val s = client.webSocketSession(host = "127.0.0.1", port = port, path = "/ws")
        session = s
        reader = scope.launch {
            try {
                for (frame in s.incoming) {
                    if (frame is Frame.Text) handle(frame.readText())
                }
            } catch (_: Exception) {
            }
        }
        if (resume && token != null) {
            s.sendJson(buildJsonObject { put("type", "resume"); put("token", token!!) })
        } else {
            s.sendJson(buildJsonObject {
                put("type", "join"); put("name", name)
                if (hostKey != null) put("hostKey", hostKey)
            })
        }
        withTimeout(10_000) { joined.await() }
    }

    /** Simulate a phone going to sleep: drop the socket without saying goodbye. */
    suspend fun dropConnection() {
        reader?.cancel()
        session?.close()
        session = null
        delay(50)
    }

    suspend fun reconnect() {
        lastActedVersion = -1
        connect(resume = true)
    }

    suspend fun send(obj: JsonObject) { session?.sendJson(obj) }

    suspend fun action(action: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        send(buildJsonObject { put("type", "action"); put("action", action); build() })
    }

    private fun handle(text: String) {
        val el = json.parseToJsonElement(text)
        received += Recorded(text, el)
        val obj = el.jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "joined" -> {
                playerId = obj["playerId"]!!.jsonPrimitive.content
                token = obj["token"]!!.jsonPrimitive.content
                if (!joined.isCompleted) joined.complete(Unit)
            }
            "state" -> {
                val st = json.decodeFromJsonElement(StateMessage.serializer(), obj).state
                // Like the browser: never let an older state overwrite a newer one.
                if (view == null || st.version >= view!!.version) view = st
            }
            "error" -> errors += obj["message"]!!.jsonPrimitive.content
        }
    }

    /** Take the one action the server says is pending, if we have not acted on this version yet. */
    suspend fun act(): Boolean {
        val v = view ?: return false
        if (v.version == lastActedVersion) return false
        val me = v.you
        val others = v.players.map { it.id }.filter { it != me.id }
        val acted = when (me.pendingAction) {
            PendingAction.START_GAME -> {
                if (v.players.size >= 5) { action("start"); true } else false
            }
            PendingAction.CONFIRM_ROLE -> { action("confirmRole"); true }
            PendingAction.PICK_TEAM -> {
                val size = v.questSizes[v.quest - 1]
                val team = v.players.map { it.id }.shuffled(random).take(size)
                action("proposeTeam") { putJsonArray("team") { team.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                true
            }
            PendingAction.VOTE -> {
                val vote = if (random.nextDouble() < 0.7) Vote.APPROVE else Vote.REJECT
                action("vote") { put("vote", vote.name) }; true
            }
            PendingAction.PLAY_QUEST -> {
                val card = if (QuestCard.FAIL in me.allowedQuestCards && random.nextDouble() < failRate) QuestCard.FAIL else QuestCard.SUCCESS
                action("playQuest") { put("card", card.name) }; true
            }
            PendingAction.LADY_PICK -> {
                // The bot does not know who held the token before except via public history: compute like the UI does.
                val held = (v.ladyHistory.flatMap { listOf(it.holderId, it.targetId) } + me.id).toSet() + firstHolderGuess(v)
                val candidates = others.filter { it !in held }.ifEmpty { others }
                action("ladyExamine") { put("playerId", candidates.random(random)) }; true
            }
            PendingAction.LADY_CONFIRM -> { action("ladyConfirm"); true }
            PendingAction.ASSASSINATE -> { action("assassinate") { put("playerId", others.random(random)) }; true }
            PendingAction.PLAY_AGAIN, PendingAction.WAIT, null -> false
        }
        if (acted) lastActedVersion = v.version
        return acted
    }

    private fun firstHolderGuess(v: PlayerView): Set<String> =
        v.ladyHistory.firstOrNull()?.let { setOf(it.holderId) } ?: emptySet()

    suspend fun close() {
        reader?.cancel()
        try { session?.close() } catch (_: Exception) {}
        client.close()
    }

    val phase: Phase? get() = view?.phase
}

private suspend fun DefaultClientWebSocketSession.sendJson(obj: JsonObject) = send(Frame.Text(obj.toString()))
