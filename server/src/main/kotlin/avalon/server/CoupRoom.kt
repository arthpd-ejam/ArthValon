package avalon.server

import coup.engine.ActionType
import coup.engine.CoupCard
import coup.engine.CoupGame
import coup.engine.CoupIllegalAction
import coup.engine.CoupOptions
import coup.engine.CoupPhase
import coup.engine.Mode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The Coup table: same connection, token and rejoin model as the Avalon [Room],
 * driving a [CoupGame]. Lives on /coup/ws next to the Avalon room.
 */
class CoupRoom(
    random: Random = SecureRandom().asKotlinRandom(),
    val hostKey: String = Room.randomToken(10),
    private val joinUrl: () -> String? = { null },
) {
    val game = CoupGame(random)
    private val mutex = Mutex()
    private val tokens = HashMap<String, String>()
    private val connections = HashMap<String, MutableSet<Connection>>()
    private val rejoinable = HashSet<String>()
    private var nextId = 1

    private fun newPlayerId() = "c${nextId++}"

    suspend fun onMessage(conn: Connection, text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            send(conn, ErrorMessage("Bad message")); return
        }
        when (obj.str("type")) {
            "ping" -> send(conn, PongMessage())
            "join" -> join(conn, obj.str("name") ?: "", obj.str("hostKey"))
            "resume" -> resume(conn, obj.str("token") ?: "")
            "action" -> action(conn, obj)
            else -> send(conn, ErrorMessage("Unknown message type"))
        }
    }

    suspend fun onClose(conn: Connection) {
        val pid = conn.playerId ?: return
        val changed = mutex.withLock {
            val set = connections[pid] ?: return
            set.remove(conn)
            if (set.isEmpty()) {
                connections.remove(pid)
                game.setConnected(pid, false)
                true
            } else false
        }
        if (changed) broadcast()
    }

    private suspend fun join(conn: Connection, name: String, hostKey: String?) {
        val isHost = hostKey != null && hostKey == this.hostKey
        val outcome: Result<Pair<String, String>> = mutex.withLock {
            runCatching {
                if (conn.playerId != null) throw CoupIllegalAction("Already joined")
                val pid: String = when {
                    game.phase == CoupPhase.LOBBY -> game.addPlayer(newPlayerId(), name, host = isHost).id
                    isHost -> game.players.firstOrNull { it.isHost }?.id
                        ?: throw CoupIllegalAction("The game is already running")
                    else -> {
                        val seat = game.players.firstOrNull {
                            it.id in rejoinable && it.name.equals(name.trim(), ignoreCase = true)
                        } ?: throw CoupIllegalAction("A game is already running. Ask the host to let you rejoin.")
                        rejoinable.remove(seat.id)
                        seat.id
                    }
                }
                tokens.entries.removeIf { it.value == pid }
                val token = Room.randomToken(24)
                tokens[token] = pid
                attach(conn, pid)
                pid to token
            }
        }
        outcome.onSuccess { (pid, token) ->
            send(conn, JoinedMessage(pid, token))
            broadcast()
        }.onFailure { e -> send(conn, ErrorMessage(e.message ?: "Could not join", fatal = true)) }
    }

    private suspend fun resume(conn: Connection, token: String) {
        val pid = mutex.withLock {
            val pid = tokens[token]
            if (pid != null && game.findPlayer(pid) != null) {
                attach(conn, pid); pid
            } else null
        }
        if (pid == null) {
            send(conn, ErrorMessage("Your seat was not found. Please join again.", fatal = true))
        } else {
            send(conn, JoinedMessage(pid, token))
            broadcast()
        }
    }

    private fun attach(conn: Connection, pid: String) {
        conn.playerId = pid
        connections.getOrPut(pid) { HashSet() }.add(conn)
        game.setConnected(pid, true)
    }

    private suspend fun action(conn: Connection, obj: JsonObject) {
        val pid = conn.playerId
        if (pid == null) { send(conn, ErrorMessage("Join first")); return }
        val action = obj.str("action") ?: ""
        var kicked: String? = null
        val result = mutex.withLock {
            runCatching {
                if (game.findPlayer(pid) == null) throw CoupIllegalAction("You are no longer in this game")
                when (action) {
                    "start" -> game.start(pid)
                    "setOptions" -> game.setOptions(pid, CoupOptions(mode = enumArg<Mode>(obj, "mode")))
                    "kick" -> {
                        val target = obj.str("playerId") ?: throw CoupIllegalAction("Missing player")
                        game.kick(pid, target)
                        tokens.entries.removeIf { it.value == target }
                        rejoinable.remove(target)
                        kicked = target
                    }
                    "allowRejoin" -> {
                        if (!game.player(pid).isHost) throw CoupIllegalAction("Only the host can do that")
                        val target = game.player(obj.str("playerId") ?: "")
                        if (target.connected) throw CoupIllegalAction("${target.name} is still connected")
                        rejoinable.add(target.id)
                    }
                    "declare" -> game.declare(pid, enumArg<ActionType>(obj, "type"), obj.str("targetId"))
                    "pass" -> game.pass(pid)
                    "challenge" -> game.challenge(pid)
                    "block" -> game.block(pid, enumArg<CoupCard>(obj, "card"))
                    "loseInfluence" -> game.chooseLostInfluence(pid, obj.int("index"))
                    "exchange" -> game.exchange(pid, obj.intList("keep"))
                    "forceContinue" -> game.forceContinue(pid)
                    "playAgain" -> game.playAgain(pid)
                    "restart" -> { game.restart(pid); rejoinable.clear() }
                    else -> throw CoupIllegalAction("Unknown action")
                }
            }
        }
        result.onFailure { e ->
            val msg = if (e is CoupIllegalAction) e.message ?: "Not allowed" else "Not allowed"
            send(conn, ErrorMessage(msg))
        }.onSuccess {
            kicked?.let { closeKicked(it) }
            broadcast()
        }
    }

    private suspend fun closeKicked(pid: String) {
        val conns = mutex.withLock { connections.remove(pid)?.toList() ?: emptyList() }
        for (c in conns) {
            c.playerId = null
            send(c, KickedMessage("The host removed you from the table."))
        }
    }

    suspend fun broadcast() {
        val url = joinUrl()
        val batch: List<Pair<Connection, String>> = mutex.withLock {
            connections.flatMap { (pid, conns) ->
                if (game.findPlayer(pid) == null) return@flatMap emptyList()
                val view = game.viewFor(pid)
                val info = ServerInfo(
                    rejoinable = if (view.you.isHost) rejoinable.toList() else emptyList(),
                    joinUrl = url,
                )
                val text = json.encodeToString(ServerMessage.serializer(), CoupStateMessage(view, info))
                conns.map { it to text }
            }
        }
        for ((conn, text) in batch) conn.outbox.trySend(text)
    }

    private fun send(conn: Connection, msg: ServerMessage) {
        conn.outbox.trySend(json.encodeToString(ServerMessage.serializer(), msg))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: throw CoupIllegalAction("Missing $key")

    private fun JsonObject.intList(key: String): List<Int> {
        val arr = this[key] as? JsonArray ?: throw CoupIllegalAction("Missing $key")
        return arr.map { (it as? JsonPrimitive)?.intOrNull ?: throw CoupIllegalAction("Bad $key") }
    }

    private inline fun <reified E : Enum<E>> enumArg(obj: JsonObject, key: String): E {
        val v = obj.str(key) ?: throw CoupIllegalAction("Missing $key")
        return enumValues<E>().firstOrNull { it.name == v } ?: throw CoupIllegalAction("Bad $key")
    }
}
