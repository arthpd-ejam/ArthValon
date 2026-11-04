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
    MORDRED(false, "Mordred"),
    OBERON(false, "Oberon"),
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
    /** Optional characters. Merlin and the Assassin are always in play. */
    val percival: Boolean = true,
    val morgana: Boolean = true,
    val mordred: Boolean = false,
    val oberon: Boolean = false,
) {
    /** Evil characters besides the Assassin that these options ask for. */
    val specialEvilCount: Int get() = listOf(morgana, mordred, oberon).count { it }
}

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

        /** Throws if the optional characters do not fit this player count. */
        fun validate(n: Int, o: GameOptions) {
            val s = forPlayers(n)
            val evilSpecials = 1 + o.specialEvilCount // Assassin + chosen
            if (evilSpecials > s.evil) throw IllegalActionException(
                "With $n players there are only ${s.evil} evil roles: the Assassin plus at most ${s.evil - 1} of Morgana, Mordred and Oberon")
            if (o.morgana && !o.percival) throw IllegalActionException("Morgana only makes sense together with Percival")
        }

        fun roles(n: Int, o: GameOptions = GameOptions()): List<Role> {
            validate(n, o)
            val s = forPlayers(n)
            val good = listOf(Role.MERLIN) + (if (o.percival) listOf(Role.PERCIVAL) else emptyList())
            val evil = listOf(Role.ASSASSIN) + (if (o.morgana) listOf(Role.MORGANA) else emptyList()) +
                (if (o.mordred) listOf(Role.MORDRED) else emptyList()) + (if (o.oberon) listOf(Role.OBERON) else emptyList())
            return good + List(s.good - good.size) { Role.LOYAL_SERVANT } + evil + List(s.evil - evil.size) { Role.MINION }
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
