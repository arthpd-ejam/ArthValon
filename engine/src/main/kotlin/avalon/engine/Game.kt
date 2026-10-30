package avalon.engine

import kotlin.random.Random

/**
 * The referee. Holds the full secret state of one table and validates every
 * action. The only way to read game data for a client is [viewFor], which
 * filters the state down to what that player may know.
 *
 * Not thread safe: callers (the server) must serialise access.
 */
class Game(private val random: Random = Random.Default) {

    var phase: Phase = Phase.LOBBY
        private set
    var options: GameOptions = GameOptions()
        private set

    private val seats = mutableListOf<Player>()
    val players: List<Player> get() = seats

    private var version = 0L
    private var lastEvent: String? = null

    // ---- in-game state ----
    private var setup: Setup? = null
    private var questIndex = 0          // 0-based
    private var leaderSeat = 0
    private var rejections = 0
    private var proposedTeam: List<String> = emptyList()
    private val votes = linkedMapOf<String, Vote>()
    private val questCards = linkedMapOf<String, QuestCard>()
    private val ready = linkedSetOf<String>()
    private val quests = mutableListOf<QuestRecord>()
    private val voteHistory = mutableListOf<VoteRecord>()
    private var ladyEnabled = false
    private var ladyHolderId: String? = null
    private val ladyFormerHolders = linkedSetOf<String>()
    private val ladyHistory = mutableListOf<LadyRecord>()
    private var winner: Winner? = null
    private var winReason: String? = null
    private var assassinTargetId: String? = null

    private val questCardHistory = mutableListOf<Map<String, QuestCard>>()

    /** Test/inspection access to secret facts. Never expose to clients. */
    val secretQuestCards: Map<String, QuestCard> get() = questCards
    /** Who played what on each completed quest, in order. Tests only. */
    val secretQuestCardHistory: List<Map<String, QuestCard>> get() = questCardHistory
    val secretLadyHistory: List<LadyRecord> get() = ladyHistory
    val currentQuestNumber: Int get() = questIndex + 1
    val currentLeader: Player? get() = if (phase == Phase.LOBBY) null else seats[leaderSeat]
    val currentLadyHolderId: String? get() = ladyHolderId
    val currentRejections: Int get() = rejections
    val currentProposedTeam: List<String> get() = proposedTeam
    val questRecords: List<QuestRecord> get() = quests
    val voteRecords: List<VoteRecord> get() = voteHistory
    val result: Pair<Winner, String>? get() = winner?.let { it to (winReason ?: "") }

    // ------------------------------------------------------------------ lobby

    fun player(id: String): Player = seats.find { it.id == id } ?: throw IllegalActionException("Unknown player")
    fun findPlayer(id: String): Player? = seats.find { it.id == id }

    fun addPlayer(id: String, name: String, host: Boolean = false): Player {
        requirePhase(Phase.LOBBY, "The game has already started")
        val clean = name.trim()
        if (clean.isEmpty()) throw IllegalActionException("Please enter a name")
        if (clean.length > 20) throw IllegalActionException("Name is too long (max 20 characters)")
        if (seats.any { it.name.equals(clean, ignoreCase = true) }) throw IllegalActionException("That name is already taken")
        if (seats.size >= 10) throw IllegalActionException("The table is full (10 players)")
        val p = Player(id, clean, seats.size)
        if (host) {
            seats.forEach { it.isHost = false }
            p.isHost = true
        }
        seats += p
        bump("$clean joined")
        return p
    }

    fun setHost(id: String) {
        val p = player(id)
        seats.forEach { it.isHost = false }
        p.isHost = true
        bump()
    }

    fun setConnected(id: String, connected: Boolean) {
        val p = findPlayer(id) ?: return
        if (p.connected != connected) {
            p.connected = connected
            bump()
        }
    }

    fun kick(hostId: String, targetId: String) {
        requireHost(hostId)
        requirePhase(Phase.LOBBY, "Players can only be removed in the lobby")
        val t = player(targetId)
        if (t.isHost) throw IllegalActionException("The host cannot be removed")
        seats.remove(t)
        seats.forEachIndexed { i, p -> p.seat = i }
        bump("${t.name} was removed")
    }

    fun setOptions(hostId: String, newOptions: GameOptions) {
        requireHost(hostId)
        requirePhase(Phase.LOBBY, "Options can only be changed in the lobby")
        options = newOptions
        bump()
    }

    fun start(hostId: String) {
        requireHost(hostId)
        requirePhase(Phase.LOBBY, "The game has already started")
        val n = seats.size
        if (n < 5) throw IllegalActionException("Need at least 5 players (have $n)")
        if (n > 10) throw IllegalActionException("Too many players (max 10)")
        beginGame()
    }

    /** Host, game over: play another game with the same players. */
    fun playAgain(hostId: String) {
        requireHost(hostId)
        requirePhase(Phase.GAME_OVER, "The game is still running")
        beginGame()
    }

    /** Host, any time during a game: abandon it and return everyone to the lobby (same players, roles discarded). */
    fun restart(hostId: String) {
        requireHost(hostId)
        if (phase == Phase.LOBBY) throw IllegalActionException("No game is running")
        seats.forEach { it.role = null }
        setup = null
        questIndex = 0; rejections = 0; proposedTeam = emptyList()
        votes.clear(); questCards.clear(); ready.clear()
        quests.clear(); voteHistory.clear(); ladyHistory.clear(); ladyFormerHolders.clear(); questCardHistory.clear()
        winner = null; winReason = null; assassinTargetId = null; ladyHolderId = null
        phase = Phase.LOBBY
        bump("The host restarted the game. Back to the lobby.")
    }

    private fun beginGame() {
        val n = seats.size
        setup = Setup.forPlayers(n)
        val roles = Setup.roles(n).shuffled(random)
        seats.forEachIndexed { i, p -> p.role = roles[i] }

        questIndex = 0
        rejections = 0
        proposedTeam = emptyList()
        votes.clear(); questCards.clear(); ready.clear()
        quests.clear(); voteHistory.clear(); ladyHistory.clear(); ladyFormerHolders.clear(); questCardHistory.clear()
        winner = null; winReason = null; assassinTargetId = null

        leaderSeat = random.nextInt(n)
        ladyEnabled = options.ladyOfTheLake ?: (n >= 7)
        ladyHolderId = if (ladyEnabled) {
            val holder = seats[(leaderSeat - 1 + n) % n]
            ladyFormerHolders += holder.id
            holder.id
        } else null

        phase = Phase.ROLE_REVEAL
        bump("The game has started. Look at your role in private.")
    }

    // ------------------------------------------------------------ role reveal

    fun confirmRole(playerId: String) {
        requirePhase(Phase.ROLE_REVEAL, "Not the role reveal phase")
        player(playerId)
        ready += playerId
        if (ready.size == seats.size) {
            ready.clear()
            phase = Phase.TEAM_SELECTION
            bump("Quest 1 begins. ${leaderName()} is the leader.")
        } else bump()
    }

    /** Host may move on if someone is stuck on the role reveal (e.g. phone died). */
    fun forceContinueRoleReveal(hostId: String) {
        requireHost(hostId)
        requirePhase(Phase.ROLE_REVEAL, "Not the role reveal phase")
        ready.clear()
        phase = Phase.TEAM_SELECTION
        bump("Quest 1 begins. ${leaderName()} is the leader.")
    }

    // ----------------------------------------------------------- team select

    fun proposeTeam(leaderId: String, teamIds: List<String>) {
        requirePhase(Phase.TEAM_SELECTION, "Not the team selection phase")
        if (seats[leaderSeat].id != leaderId) throw IllegalActionException("Only the leader can pick the team")
        val size = setup!!.questSizes[questIndex]
        val distinct = teamIds.distinct()
        if (distinct.size != teamIds.size) throw IllegalActionException("A player can only be on the team once")
        if (teamIds.size != size) throw IllegalActionException("This quest needs exactly $size players")
        teamIds.forEach { player(it) }
        proposedTeam = seats.filter { it.id in teamIds }.map { it.id } // seat order
        votes.clear()
        phase = Phase.TEAM_VOTE
        bump("${leaderName()} proposed a team. Everyone votes.")
    }

    // ------------------------------------------------------------------ vote

    fun vote(playerId: String, vote: Vote) {
        requirePhase(Phase.TEAM_VOTE, "Not the voting phase")
        player(playerId)
        if (playerId in votes) throw IllegalActionException("You have already voted")
        votes[playerId] = vote
        if (votes.size < seats.size) { bump(); return }

        val approvals = votes.values.count { it == Vote.APPROVE }
        val approved = approvals > seats.size - approvals // ties reject
        voteHistory += VoteRecord(
            quest = questIndex + 1,
            attempt = rejections + 1,
            leaderId = seats[leaderSeat].id,
            teamIds = proposedTeam,
            votes = seats.associate { it.id to votes.getValue(it.id) },
            approved = approved,
        )
        if (approved) {
            questCards.clear()
            phase = Phase.QUEST
            bump("Team approved ($approvals–${seats.size - approvals}). The team plays the quest in secret.")
        } else {
            rejections++
            if (rejections >= 5) {
                endGame(Winner.EVIL, "Five teams were rejected in a row.")
                return
            }
            rotateLeader()
            proposedTeam = emptyList()
            phase = Phase.TEAM_SELECTION
            bump("Team rejected ($approvals–${seats.size - approvals}). Rejection $rejections of 5. ${leaderName()} is the new leader.")
        }
    }

    // ----------------------------------------------------------------- quest

    fun playQuestCard(playerId: String, card: QuestCard) {
        requirePhase(Phase.QUEST, "Not the quest phase")
        val p = player(playerId)
        if (playerId !in proposedTeam) throw IllegalActionException("You are not on this quest")
        if (playerId in questCards) throw IllegalActionException("You have already played")
        if (card == QuestCard.FAIL && p.role!!.good) throw IllegalActionException("Good players must play Success")
        questCards[playerId] = card
        if (questCards.size < proposedTeam.size) { bump(); return }

        questCardHistory += questCards.toMap()
        val fails = questCards.values.count { it == QuestCard.FAIL }
        val needed = setup!!.failsRequired(questIndex)
        val succeeded = fails < needed
        quests += QuestRecord(
            quest = questIndex + 1,
            leaderId = seats[leaderSeat].id,
            teamIds = proposedTeam,
            successes = proposedTeam.size - fails,
            fails = fails,
            failsRequired = needed,
            succeeded = succeeded,
        )
        val finishedQuest = questIndex + 1
        val summary = "Quest $finishedQuest ${if (succeeded) "SUCCEEDED" else "FAILED"} " +
            "(${proposedTeam.size - fails} success, $fails fail)."

        val wins = quests.count { it.succeeded }
        val losses = quests.size - wins
        if (wins >= 3) {
            questCards.clear()
            proposedTeam = emptyList()
            phase = Phase.ASSASSINATION
            bump("$summary Good has won three quests. The Assassin now tries to find Merlin.")
            return
        }
        if (losses >= 3) {
            endGame(Winner.EVIL, "$summary Three quests failed.")
            return
        }

        rejections = 0
        rotateLeader()
        questIndex++
        questCards.clear()
        proposedTeam = emptyList()
        if (ladyEnabled && finishedQuest in 2..4) {
            phase = Phase.LADY_PICK
            bump("$summary ${nameOf(ladyHolderId!!)} holds the Lady of the Lake and examines a player.")
        } else {
            phase = Phase.TEAM_SELECTION
            bump("$summary Quest ${questIndex + 1}: ${leaderName()} is the leader.")
        }
    }

    // ------------------------------------------------------------------ lady

    fun ladyTargets(): List<String> = seats.map { it.id }.filter { it !in ladyFormerHolders }

    fun ladyExamine(holderId: String, targetId: String) {
        requirePhase(Phase.LADY_PICK, "Not the Lady of the Lake phase")
        if (holderId != ladyHolderId) throw IllegalActionException("You do not hold the Lady of the Lake")
        if (targetId == holderId) throw IllegalActionException("You cannot examine yourself")
        val target = player(targetId)
        if (targetId in ladyFormerHolders) throw IllegalActionException("${target.name} has already held the Lady of the Lake")
        ladyHistory += LadyRecord(
            afterQuest = questIndex, // questIndex already advanced: this is the finished quest number
            holderId = holderId,
            targetId = targetId,
            loyalty = target.role!!.loyalty,
        )
        phase = Phase.LADY_RESULT
        bump("${nameOf(holderId)} examined ${target.name} with the Lady of the Lake.")
    }

    fun ladyConfirm(holderId: String) {
        requirePhase(Phase.LADY_RESULT, "Nothing to confirm")
        if (holderId != ladyHolderId) throw IllegalActionException("You do not hold the Lady of the Lake")
        finishLady()
    }

    /** Host may move on if the Lady holder is stuck on the result screen. */
    fun forceContinueLady(hostId: String) {
        requireHost(hostId)
        requirePhase(Phase.LADY_RESULT, "Nothing to skip")
        finishLady()
    }

    private fun finishLady() {
        val last = ladyHistory.last()
        ladyHolderId = last.targetId
        ladyFormerHolders += last.targetId
        phase = Phase.TEAM_SELECTION
        bump("${nameOf(last.targetId)} now holds the Lady of the Lake. Quest ${questIndex + 1}: ${leaderName()} is the leader.")
    }

    // --------------------------------------------------------- assassination

    fun assassinate(assassinId: String, targetId: String) {
        requirePhase(Phase.ASSASSINATION, "Not the assassination phase")
        val a = player(assassinId)
        if (a.role != Role.ASSASSIN) throw IllegalActionException("Only the Assassin can do that")
        if (targetId == assassinId) throw IllegalActionException("Pick another player")
        val t = player(targetId)
        assassinTargetId = targetId
        if (t.role == Role.MERLIN) {
            endGame(Winner.EVIL, "The Assassin found Merlin: it was ${t.name}.")
        } else {
            endGame(Winner.GOOD, "The Assassin chose ${t.name}, who was not Merlin.")
        }
    }

    // ------------------------------------------------------------------ view

    fun viewFor(playerId: String): PlayerView {
        val me = player(playerId)
        val inGame = phase != Phase.LOBBY
        val role = me.role.takeIf { inGame }
        val s = setup

        val knownEvil = if (inGame && role == Role.MERLIN) seats.filter { !it.role!!.good }.map { it.id } else emptyList()
        val merlinOrMorgana = if (inGame && role == Role.PERCIVAL)
            seats.filter { it.role == Role.MERLIN || it.role == Role.MORGANA }.map { it.id }.shuffled(random)
        else emptyList()
        val evilTeammates = if (inGame && role != null && !role.good)
            seats.filter { !it.role!!.good && it.id != me.id }.map { it.id } else emptyList()

        val pending = pendingActionFor(me)
        val yourLady = if (inGame) ladyHistory.filter { it.holderId == me.id }
            .map { LadyResultView(it.afterQuest, it.targetId, it.loyalty) } else emptyList()

        val self = SelfView(
            id = me.id, name = me.name, seat = me.seat, isHost = me.isHost,
            role = role, loyalty = role?.loyalty,
            knownEvil = knownEvil, merlinOrMorgana = merlinOrMorgana, evilTeammates = evilTeammates,
            pendingAction = pending,
            yourVote = if (phase == Phase.TEAM_VOTE) votes[me.id] else null,
            yourQuestCard = if (phase == Phase.QUEST) questCards[me.id] else null,
            yourLadyResults = yourLady,
            allowedQuestCards = when {
                role == null -> emptyList()
                role.good -> listOf(QuestCard.SUCCESS)
                else -> listOf(QuestCard.SUCCESS, QuestCard.FAIL)
            },
        )

        val over = if (phase == Phase.GAME_OVER) GameOverView(
            winner = winner!!,
            reason = winReason ?: "",
            roles = seats.associate { it.id to it.role!! },
            assassinTargetId = assassinTargetId,
            ladyResults = ladyHistory.map { LadyResultView(it.afterQuest, it.targetId, it.loyalty) },
        ) else null

        return PlayerView(
            phase = phase,
            you = self,
            players = seats.map { PublicPlayer(it.id, it.name, it.seat, it.connected, it.isHost) },
            playerCount = seats.size,
            quest = if (inGame) questIndex + 1 else 0,
            questSizes = s?.questSizes ?: emptyList(),
            failsRequired = s?.let { st -> st.questSizes.indices.map { st.failsRequired(it) } } ?: emptyList(),
            leaderId = if (inGame) seats[leaderSeat].id else null,
            rejections = rejections,
            ladyEnabled = if (inGame) ladyEnabled else (options.ladyOfTheLake ?: (seats.size >= 7)),
            ladyHolderId = if (inGame) ladyHolderId else null,
            quests = quests.toList(),
            voteHistory = voteHistory.toList(),
            ladyHistory = ladyHistory.map { PublicLadyRecord(it.afterQuest, it.holderId, it.targetId) },
            proposedTeam = proposedTeam,
            votedIds = if (phase == Phase.TEAM_VOTE) votes.keys.toList() else emptyList(),
            playedIds = if (phase == Phase.QUEST) questCards.keys.toList() else emptyList(),
            readyIds = if (phase == Phase.ROLE_REVEAL) ready.toList() else emptyList(),
            options = options,
            gameOver = over,
            lastEvent = lastEvent,
            version = version,
        )
    }

    private fun pendingActionFor(me: Player): PendingAction = when (phase) {
        Phase.LOBBY -> if (me.isHost) PendingAction.START_GAME else PendingAction.WAIT
        Phase.ROLE_REVEAL -> if (me.id in ready) PendingAction.WAIT else PendingAction.CONFIRM_ROLE
        Phase.TEAM_SELECTION -> if (seats[leaderSeat].id == me.id) PendingAction.PICK_TEAM else PendingAction.WAIT
        Phase.TEAM_VOTE -> if (me.id in votes) PendingAction.WAIT else PendingAction.VOTE
        Phase.QUEST -> if (me.id in proposedTeam && me.id !in questCards) PendingAction.PLAY_QUEST else PendingAction.WAIT
        Phase.LADY_PICK -> if (me.id == ladyHolderId) PendingAction.LADY_PICK else PendingAction.WAIT
        Phase.LADY_RESULT -> if (me.id == ladyHolderId) PendingAction.LADY_CONFIRM else PendingAction.WAIT
        Phase.ASSASSINATION -> if (me.role == Role.ASSASSIN) PendingAction.ASSASSINATE else PendingAction.WAIT
        Phase.GAME_OVER -> if (me.isHost) PendingAction.PLAY_AGAIN else PendingAction.WAIT
    }

    // --------------------------------------------------------------- helpers

    private fun endGame(w: Winner, reason: String) {
        winner = w
        winReason = reason
        questCards.clear()
        votes.clear()
        phase = Phase.GAME_OVER
        bump("Game over: ${if (w == Winner.GOOD) "GOOD" else "EVIL"} wins. $reason")
    }

    private fun rotateLeader() {
        leaderSeat = (leaderSeat + 1) % seats.size
    }

    private fun leaderName() = seats[leaderSeat].name
    private fun nameOf(id: String) = player(id).name

    private fun requirePhase(p: Phase, msg: String) {
        if (phase != p) throw IllegalActionException(msg)
    }

    private fun requireHost(id: String) {
        if (!player(id).isHost) throw IllegalActionException("Only the host can do that")
    }

    private fun bump(event: String? = null) {
        version++
        if (event != null) lastEvent = event
    }
}
