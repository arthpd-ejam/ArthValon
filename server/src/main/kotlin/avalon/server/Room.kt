package avalon.server

import avalon.engine.Game
import avalon.engine.GameOptions
import avalon.engine.IllegalActionException
import avalon.engine.Phase
import avalon.engine.QuestCard
import avalon.engine.Vote
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * One connected browser tab. Outgoing messages go through [outbox] and are
 * written by a single coroutine (see [AvalonServer]) so a client always
 * receives states in order; a slow phone can never stall the game loop.
 */
class Connection(val session: WebSocketSession) {
    var playerId: String? = null
    val outbox = Channel<String>(Channel.UNLIMITED)
}

/**
 * One table. Owns the [Game], the reconnect tokens and the open sockets, and
 * turns incoming JSON into validated engine calls. All state is guarded by
 * [mutex]; outgoing messages are sent outside the lock.
 */
class Room(
    random: Random = SecureRandom().asKotlinRandom(),
    val hostKey: String = randomToken(10),
    /** Called to compute the join URL shown in the lobby (e.g. from the hotspot IP). */
    private val joinUrl: () -> String? = { null },
) {
    val game = Game(random)
    private val mutex = Mutex()
    private val tokens = HashMap<String, String>()               // token -> playerId
    private val connections = HashMap<String, MutableSet<Connection>>() // playerId -> tabs
    private val rejoinable = HashSet<String>()
    private var nextId = 1

    private fun newPlayerId() = "p${nextId++}"

    // ------------------------------------------------------------ inbound

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
                if (conn.playerId != null) throw IllegalActionException("Already joined")
                val pid: String = when {
                    game.phase == Phase.LOBBY -> game.addPlayer(newPlayerId(), name, host = isHost).id
                    // Mid-game: the host key may reclaim the host seat, and the host may open a seat to be
                    // rejoined by name (for a player who lost their phone's storage).
                    isHost -> game.players.firstOrNull { it.isHost }?.id
                        ?: throw IllegalActionException("The game is already running")
                    else -> {
                        val seat = game.players.firstOrNull {
                            it.id in rejoinable && it.name.equals(name.trim(), ignoreCase = true)
                        } ?: throw IllegalActionException("A game is already running. Ask the host to let you rejoin.")
                        rejoinable.remove(seat.id)
                        seat.id
                    }
                }
                // Any older token for this seat stops working.
                tokens.entries.removeIf { it.value == pid }
                val token = randomToken(24)
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

    /** Must hold the mutex. */
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
                if (game.findPlayer(pid) == null) throw IllegalActionException("You are no longer in this game")
                when (action) {
                    "start" -> game.start(pid)
                    "setOptions" -> game.setOptions(pid, GameOptions(ladyOfTheLake = obj["ladyOfTheLake"]?.jsonPrimitive?.booleanOrNull))
                    "kick" -> {
                        val target = obj.str("playerId") ?: throw IllegalActionException("Missing player")
                        game.kick(pid, target)
                        tokens.entries.removeIf { it.value == target }
                        rejoinable.remove(target)
                        kicked = target
                    }
                    "allowRejoin" -> {
                        if (!game.player(pid).isHost) throw IllegalActionException("Only the host can do that")
                        val target = game.player(obj.str("playerId") ?: "")
                        if (target.connected) throw IllegalActionException("${target.name} is still connected")
                        rejoinable.add(target.id)
                    }
                    "confirmRole" -> game.confirmRole(pid)
                    "forceContinue" -> when (game.phase) {
                        Phase.ROLE_REVEAL -> game.forceContinueRoleReveal(pid)
                        Phase.LADY_RESULT -> game.forceContinueLady(pid)
                        else -> throw IllegalActionException("Nothing to skip right now")
                    }
                    "proposeTeam" -> game.proposeTeam(pid, obj.strList("team"))
                    "vote" -> game.vote(pid, enumArg<Vote>(obj, "vote"))
                    "playQuest" -> game.playQuestCard(pid, enumArg<QuestCard>(obj, "card"))
                    "ladyExamine" -> game.ladyExamine(pid, obj.str("playerId") ?: throw IllegalActionException("Missing player"))
                    "ladyConfirm" -> game.ladyConfirm(pid)
                    "assassinate" -> game.assassinate(pid, obj.str("playerId") ?: throw IllegalActionException("Missing player"))
                    "playAgain" -> game.playAgain(pid)
                    "restart" -> { game.restart(pid); rejoinable.clear() }
                    else -> throw IllegalActionException("Unknown action")
                }
            }
        }
        result.onFailure { e ->
            val msg = if (e is IllegalActionException) e.message ?: "Not allowed" else "Not allowed"
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

    // ----------------------------------------------------------- outbound

    /** Send each connected player their own filtered view. */
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
                val text = json.encodeToString(ServerMessage.serializer(), StateMessage(view, info))
                conns.map { it to text }
            }
        }
        for ((conn, text) in batch) sendRaw(conn, text)
    }

    private fun send(conn: Connection, msg: ServerMessage) =
        sendRaw(conn, json.encodeToString(ServerMessage.serializer(), msg))

    private fun sendRaw(conn: Connection, text: String) {
        conn.outbox.trySend(text) // never blocks; closed channels simply drop
    }

    // ------------------------------------------------------------ helpers

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strList(key: String): List<String> {
        val arr = this[key] as? JsonArray ?: throw IllegalActionException("Missing $key")
        return arr.map { (it as? JsonPrimitive)?.contentOrNull ?: throw IllegalActionException("Bad $key") }
    }

    private inline fun <reified E : Enum<E>> enumArg(obj: JsonObject, key: String): E {
        val v = obj.str(key) ?: throw IllegalActionException("Missing $key")
        return enumValues<E>().firstOrNull { it.name == v } ?: throw IllegalActionException("Bad $key")
    }

    companion object {
        private val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun randomToken(len: Int): String {
            val r = SecureRandom()
            return (1..len).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
