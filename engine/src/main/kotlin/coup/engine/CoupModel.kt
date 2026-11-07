package coup.engine

import kotlinx.serialization.Serializable

/** The five classic Coup characters (Ambassador set, no Inquisitor). */
@Serializable
enum class CoupCard(val displayName: String) {
    DUKE("Duke"),
    ASSASSIN("Assassin"),
    CAPTAIN("Captain"),
    AMBASSADOR("Ambassador"),
    CONTESSA("Contessa"),
}

/** Reformation allegiances. */
@Serializable
enum class Faction(val displayName: String) {
    LOYALIST("Loyalist"),
    REFORMIST("Reformist");

    val other: Faction get() = if (this == LOYALIST) REFORMIST else LOYALIST
}

/** BASE = classic Coup (2-6 players). REFORMATION = factions + Treasury Reserve (2-10 players). */
@Serializable
enum class Mode(val minPlayers: Int, val maxPlayers: Int) {
    BASE(2, 6),
    REFORMATION(2, 10),
}

@Serializable
enum class ActionType(
    val displayName: String,
    /** The character this action claims (null = anyone may do it, cannot be challenged). */
    val claim: CoupCard?,
    /** Characters that may block it. */
    val blockers: List<CoupCard>,
    val needsTarget: Boolean,
    val reformationOnly: Boolean = false,
) {
    INCOME("Income", null, emptyList(), false),
    FOREIGN_AID("Foreign Aid", null, listOf(CoupCard.DUKE), false),
    COUP("Coup", null, emptyList(), true),
    TAX("Tax", CoupCard.DUKE, emptyList(), false),
    ASSASSINATE("Assassinate", CoupCard.ASSASSIN, listOf(CoupCard.CONTESSA), true),
    STEAL("Steal", CoupCard.CAPTAIN, listOf(CoupCard.CAPTAIN, CoupCard.AMBASSADOR), true),
    EXCHANGE("Exchange", CoupCard.AMBASSADOR, emptyList(), false),
    /** Reformation: pay 1 to change your own allegiance, or 2 to change another player's (target optional). */
    CONVERT("Convert", null, emptyList(), false, reformationOnly = true),
    /** Reformation: take the Treasury Reserve by claiming you do NOT have the Duke. */
    EMBEZZLE("Embezzle", null, emptyList(), false, reformationOnly = true);

    /** Can another player challenge this action? (Embezzle is challenged as "you DO have the Duke".) */
    val challengeable: Boolean get() = claim != null || this == EMBEZZLE
    val blockable: Boolean get() = blockers.isNotEmpty()
}

@Serializable
enum class CoupPhase {
    LOBBY,
    /** The turn player chooses an action. */
    ACTION,
    /** Other players may challenge the action's claim and/or block it, or pass. */
    RESPONSE,
    /** A block was claimed: others may challenge the block, or pass. */
    BLOCK_RESPONSE,
    /** A player chooses which influence to reveal. */
    LOSE_INFLUENCE,
    /** The Ambassador picks which cards to keep. */
    EXCHANGE,
    GAME_OVER,
}

@Serializable
data class CoupOptions(val mode: Mode = Mode.BASE)

/** Thrown for any action that is not legal right now; the message is safe to show to the acting player. */
class CoupIllegalAction(message: String) : RuntimeException(message)

/** One influence card in a player's hand. */
class Influence(var character: CoupCard, var revealed: Boolean = false)

/** Mutable server-side player record. Never serialised to clients as-is. */
class CoupPlayer(val id: String, var name: String, seat: Int) {
    var seat: Int = seat
        internal set
    var isHost = false
    var connected = true
    var coins = 0
    var faction: Faction = Faction.LOYALIST
    val cards = mutableListOf<Influence>()

    val hidden: List<Influence> get() = cards.filter { !it.revealed }
    val alive: Boolean get() = cards.any { !it.revealed }
    fun has(c: CoupCard) = cards.any { !it.revealed && it.character == c }
}
