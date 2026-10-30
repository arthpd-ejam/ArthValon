package avalon.server

import avalon.engine.PlayerView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol. Clients send JSON objects with a "type" field:
 *
 *   {"type":"join",   "name":"Alice", "hostKey":"..."?}   -> joined + state
 *   {"type":"resume", "token":"..."}                        -> joined + state
 *   {"type":"action", "action":"vote", ...}                 -> state (to all) or error (to sender)
 *   {"type":"ping"}                                         -> pong
 *
 * Server messages are one of the classes below; the "type" field is the discriminator.
 */
val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "type"
}

@Serializable
sealed class ServerMessage

/** Sent once after a successful join or resume. The token is the reconnect secret for this player. */
@Serializable
@kotlinx.serialization.SerialName("joined")
data class JoinedMessage(val playerId: String, val token: String) : ServerMessage()

/** The player's filtered view of the game. Every player gets their own. */
@Serializable
@kotlinx.serialization.SerialName("state")
data class StateMessage(val state: PlayerView, val server: ServerInfo) : ServerMessage()

/** Non-game information the client needs. */
@Serializable
data class ServerInfo(
    /** Players the host has marked as "may be rejoined by name" (only sent to the host). */
    val rejoinable: List<String> = emptyList(),
    /** The address friends should open, for the lobby screen. */
    val joinUrl: String? = null,
)

@Serializable
@kotlinx.serialization.SerialName("error")
data class ErrorMessage(val message: String, val fatal: Boolean = false) : ServerMessage()

@Serializable
@kotlinx.serialization.SerialName("kicked")
data class KickedMessage(val message: String) : ServerMessage()

@Serializable
@kotlinx.serialization.SerialName("pong")
class PongMessage : ServerMessage()
