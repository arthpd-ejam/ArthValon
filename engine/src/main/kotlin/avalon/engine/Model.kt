package avalon.engine

import kotlinx.serialization.Serializable

/** Roles used in this implementation of Avalon. */
@Serializable
enum class Role(val good: Boolean, val displayName: String) {
    MERLIN(true, "Merlin"),
    PERCIVAL(true, "Percival"),
    LOYAL_SERVANT(true, "Loyal Servant of Arthur"),
    MORGANA(false, "Morgana"),
    ASSASSIN(false, "Assassin"),
    MINION(false, "Minion of Mordred");

    val loyalty: Loyalty get() = if (good) Loyalty.GOOD else Loyalty.EVIL
}

@Serializable
enum class Loyalty { GOOD, EVIL }

@Serializable
enum class Phase {
    LOBBY,
    ROLE_REVEAL,
    TEAM_SELECTION,
    TEAM_VOTE,
    QUEST,
    LADY_PICK,
    LADY_RESULT,
    ASSASSINATION,
    GAME_OVER,
}

@Serializable
enum class Vote { APPROVE, REJECT }

@Serializable
enum class QuestCard { SUCCESS, FAIL }

@Serializable
enum class Winner { GOOD, EVIL }

/** Thrown for any action that is not legal right now; the message is safe to show to the acting player. */
class IllegalActionException(message: String) : RuntimeException(message)

@Serializable
data class GameOptions(
    /** null = default (Lady of the Lake on at 7+ players). */
    val ladyOfTheLake: Boolean? = null,
)

/** Per player-count configuration from the official rules. */
data class Setup(val players: Int, val good: Int, val evil: Int, val questSizes: List<Int>) {
    /** Quest 4 (index 3) needs two fails at 7+ players. */
    fun failsRequired(questIndex: Int): Int = if (players >= 7 && questIndex == 3) 2 else 1

    companion object {
        val TABLE: Map<Int, Setup> = mapOf(
            5 to Setup(5, 3, 2, listOf(2, 3, 2, 3, 3)),
            6 to Setup(6, 4, 2, listOf(2, 3, 4, 3, 4)),
            7 to Setup(7, 4, 3, listOf(2, 3, 3, 4, 4)),
            8 to Setup(8, 5, 3, listOf(3, 4, 4, 5, 5)),
            9 to Setup(9, 6, 3, listOf(3, 4, 4, 5, 5)),
            10 to Setup(10, 6, 4, listOf(3, 4, 4, 5, 5)),
        )

        fun forPlayers(n: Int): Setup = TABLE[n] ?: throw IllegalActionException("Avalon needs 5 to 10 players")

        fun roles(n: Int): List<Role> {
            val s = forPlayers(n)
            val good = listOf(Role.MERLIN, Role.PERCIVAL) + List(s.good - 2) { Role.LOYAL_SERVANT }
            val evil = listOf(Role.MORGANA, Role.ASSASSIN) + List(s.evil - 2) { Role.MINION }
            return good + evil
        }
    }
}

/** Mutable server-side player record. Never serialized to clients as-is. */
class Player(
    val id: String,
    var name: String,
    seat: Int,
) {
    var seat: Int = seat
        internal set
    var role: Role? = null
    var connected: Boolean = true
    var isHost: Boolean = false
}

/** A completed team vote. */
@Serializable
data class VoteRecord(
    val quest: Int,              // 1-based quest number
    val attempt: Int,            // 1-based proposal number within this quest
    val leaderId: String,
    val teamIds: List<String>,
    val votes: Map<String, Vote>,
    val approved: Boolean,
)

/** A completed quest. */
@Serializable
data class QuestRecord(
    val quest: Int,
    val leaderId: String,
    val teamIds: List<String>,
    val successes: Int,
    val fails: Int,
    val failsRequired: Int,
    val succeeded: Boolean,
)

/** A Lady of the Lake examination. The loyalty is private to the examiner; the fact of the examination is public. */
data class LadyRecord(
    val afterQuest: Int,
    val holderId: String,
    val targetId: String,
    val loyalty: Loyalty,
)
