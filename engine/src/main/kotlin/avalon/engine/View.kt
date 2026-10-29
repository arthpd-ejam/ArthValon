package avalon.engine

import kotlinx.serialization.Serializable

/**
 * Everything one particular player is allowed to see. This is the ONLY game
 * data the server ever sends to a client, so every field here must be
 * justified by the rules of Avalon.
 */
@Serializable
data class PlayerView(
    val phase: Phase,
    val you: SelfView,
    val players: List<PublicPlayer>,
    val playerCount: Int,
    /** 1-based current quest number (1..5); 0 in the lobby. */
    val quest: Int,
    val questSizes: List<Int>,
    /** For each quest index: how many fails are needed to fail it. */
    val failsRequired: List<Int>,
    val leaderId: String?,
    /** Consecutive rejected proposals in the current quest. */
    val rejections: Int,
    val ladyEnabled: Boolean,
    val ladyHolderId: String?,
    val quests: List<QuestRecord>,
    /** Completed team votes (everyone sees who voted what once a vote is complete). */
    val voteHistory: List<VoteRecord>,
    /** Public record of who examined whom with the Lady (never the result). */
    val ladyHistory: List<PublicLadyRecord>,
    /** Team currently proposed / on the quest. */
    val proposedTeam: List<String>,
    /** During TEAM_VOTE: ids that have voted (not how). */
    val votedIds: List<String>,
    /** During QUEST: team members that have played a card (not which). */
    val playedIds: List<String>,
    /** During ROLE_REVEAL: players who confirmed they saw their role. */
    val readyIds: List<String>,
    val options: GameOptions,
    val gameOver: GameOverView? = null,
    /** Human readable description of the last thing that happened; same for everyone. */
    val lastEvent: String? = null,
    /** Monotonic counter so clients can ignore stale messages. */
    val version: Long,
)

@Serializable
data class PublicPlayer(
    val id: String,
    val name: String,
    val seat: Int,
    val connected: Boolean,
    val isHost: Boolean,
)

@Serializable
data class SelfView(
    val id: String,
    val name: String,
    val seat: Int,
    val isHost: Boolean,
    val role: Role? = null,
    val loyalty: Loyalty? = null,
    /** Merlin only: ids of all evil players. */
    val knownEvil: List<String> = emptyList(),
    /** Percival only: Merlin and Morgana, shuffled, unlabeled. */
    val merlinOrMorgana: List<String> = emptyList(),
    /** Evil only: the other evil players. */
    val evilTeammates: List<String> = emptyList(),
    /** What this player must do right now, if anything. */
    val pendingAction: PendingAction? = null,
    /** This player's own vote in the current proposal (so a reconnect shows it). */
    val yourVote: Vote? = null,
    /** This player's own quest card in the current quest. */
    val yourQuestCard: QuestCard? = null,
    /** Lady of the Lake results this player personally obtained. */
    val yourLadyResults: List<LadyResultView> = emptyList(),
    /** Cards this player is allowed to play on a quest. */
    val allowedQuestCards: List<QuestCard> = emptyList(),
)

@Serializable
enum class PendingAction {
    START_GAME,      // host, lobby
    CONFIRM_ROLE,    // role reveal
    PICK_TEAM,       // leader
    VOTE,            // everyone
    PLAY_QUEST,      // team member
    LADY_PICK,       // lady holder
    LADY_CONFIRM,    // lady holder saw the result
    ASSASSINATE,     // assassin
    PLAY_AGAIN,      // host, game over
    WAIT,
}

@Serializable
data class LadyResultView(val afterQuest: Int, val targetId: String, val loyalty: Loyalty)

@Serializable
data class PublicLadyRecord(val afterQuest: Int, val holderId: String, val targetId: String)

@Serializable
data class GameOverView(
    val winner: Winner,
    val reason: String,
    val roles: Map<String, Role>,
    val assassinTargetId: String? = null,
    /** All Lady results are public once the game ends. */
    val ladyResults: List<LadyResultView>,
)
