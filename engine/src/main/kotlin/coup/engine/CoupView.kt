package coup.engine

import kotlinx.serialization.Serializable

/**
 * Everything one player may see. This is the ONLY Coup data the server sends
 * to a client: other players' face-down cards, the deck order and other
 * players' Exchange draws never appear here (until the game is over, when
 * all remaining cards are shown).
 */
@Serializable
data class CoupView(
    val phase: CoupPhase,
    val mode: Mode,
    val you: CoupSelf,
    val players: List<CoupPublicPlayer>,
    val turnId: String?,
    val deckSize: Int,
    /** Reformation: coins on the Treasury Reserve. */
    val treasuryReserve: Int,
    /** True when every living player shares one allegiance (Reformation targeting rules are lifted). */
    val allSameFaction: Boolean,
    val pending: PendingView? = null,
    val winnerId: String? = null,
    /** Public game log, newest last. */
    val log: List<String>,
    val lastEvent: String? = null,
    val version: Long,
)

@Serializable
data class CoupPublicPlayer(
    val id: String,
    val name: String,
    val seat: Int,
    val connected: Boolean,
    val isHost: Boolean,
    val coins: Int,
    /** Number of face-down cards. */
    val influence: Int,
    /** Face-up (lost) cards. */
    val revealed: List<CoupCard>,
    val alive: Boolean,
    val faction: Faction? = null,
    /** Only at game over: the cards that were still face down. */
    val finalHidden: List<CoupCard> = emptyList(),
)

@Serializable
data class OwnCard(val character: CoupCard, val revealed: Boolean)

@Serializable
enum class CoupPending {
    START_GAME,      // host, lobby
    TAKE_ACTION,     // your turn
    RESPOND,         // challenge / block / pass an action
    RESPOND_BLOCK,   // challenge / pass a block
    LOSE_INFLUENCE,  // pick a card to reveal
    EXCHANGE,        // pick cards to keep
    PLAY_AGAIN,      // host, game over
    ELIMINATED,
    WAIT,
}

@Serializable
data class ActionOption(
    val type: ActionType,
    val allowed: Boolean,
    /** Why it is not allowed right now (shown on the button). */
    val reason: String? = null,
    /** Legal targets, if the action needs (or may take) a target. */
    val targets: List<String> = emptyList(),
    val cost: Int = 0,
)

@Serializable
data class CoupSelf(
    val id: String,
    val name: String,
    val seat: Int,
    val isHost: Boolean,
    val coins: Int,
    val cards: List<OwnCard>,
    val alive: Boolean,
    val faction: Faction? = null,
    val pending: CoupPending,
    /** Your turn: every action and whether it is legal. */
    val actions: List<ActionOption> = emptyList(),
    val mustCoup: Boolean = false,
    /** Responding: may challenge the current claim. */
    val canChallenge: Boolean = false,
    /** Responding: characters you may claim to block with. */
    val canBlockWith: List<CoupCard> = emptyList(),
    /** Exchange: your face-down cards plus the two drawn, choose [keepCount]. */
    val exchangeOptions: List<CoupCard> = emptyList(),
    val keepCount: Int = 0,
)

/** The action in progress, as everyone sees it. */
@Serializable
data class PendingView(
    val actorId: String,
    val action: ActionType,
    val targetId: String? = null,
    /** The character the actor claims (null for Income/Foreign Aid/Coup/Convert/Embezzle). */
    val claim: CoupCard? = null,
    val blockerId: String? = null,
    val blockCharacter: CoupCard? = null,
    /** Players who still have to respond in the current window. */
    val waitingIds: List<String> = emptyList(),
    /** Players who already passed in the current window. */
    val passedIds: List<String> = emptyList(),
    /** True once a challenge to the action has been settled (only a block can follow). */
    val challengeSettled: Boolean = false,
    val losingPlayerId: String? = null,
    val lossReason: String? = null,
)
