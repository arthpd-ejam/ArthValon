package coup.engine

import kotlin.random.Random

/**
 * The Coup referee. Holds the full secret state (hands, deck) of one table and
 * validates every action. Clients only ever get [viewFor].
 *
 * Responses have no timer: an action (or block) goes through once every player
 * who may respond has tapped Pass. The first Challenge or Block ends the window.
 *
 * Not thread safe: callers (the server) must serialise access.
 */
class CoupGame(private val random: Random = Random.Default) {

    var phase: CoupPhase = CoupPhase.LOBBY
        private set
    var options: CoupOptions = CoupOptions()
        private set

    private val seats = mutableListOf<CoupPlayer>()
    val players: List<CoupPlayer> get() = seats

    private var version = 0L
    private var lastEvent: String? = null
    private val log = mutableListOf<String>()

    // ---- in-game state ----
    private var mode: Mode = Mode.BASE
    private val deck = mutableListOf<CoupCard>()
    private var turnSeat = 0
    var treasuryReserve = 0
        private set
    private var turn: Turn? = null
    private val passed = linkedSetOf<String>()
    private var loss: PendingLoss? = null
    private var exchangeOptions: List<CoupCard> = emptyList()
    private var winnerId: String? = null

    private class Turn(
        val actorId: String,
        val action: ActionType,
        val targetId: String?,
        val paid: Int,
    ) {
        var challengeSettled = false
        var blockerId: String? = null
        var blockCharacter: CoupCard? = null
    }

    private class PendingLoss(val playerId: String, val reason: String, val then: () -> Unit)

    // ---- inspection (tests only; never send to clients) ----
    val secretDeck: List<CoupCard> get() = deck
    val currentTurnPlayer: CoupPlayer? get() = if (phase == CoupPhase.LOBBY) null else seats.getOrNull(turnSeat)
    val winner: CoupPlayer? get() = winnerId?.let { findPlayer(it) }
    val currentLoserId: String? get() = loss?.playerId
    val waitingForResponse: List<String> get() = waitingIds()

    /** Tests only: give a player a known hand / coin count (cards are swapped with the deck so totals stay right). */
    internal fun rig(playerId: String, hand: List<CoupCard>? = null, coins: Int? = null, faction: Faction? = null) {
        val p = player(playerId)
        if (hand != null) {
            p.cards.forEach { deck += it.character }
            p.cards.clear()
            hand.forEach { c ->
                val i = deck.indexOf(c)
                check(i >= 0) { "no $c left in the deck" }
                deck.removeAt(i)
                p.cards += Influence(c)
            }
        }
        if (coins != null) p.coins = coins
        if (faction != null) p.faction = faction
    }

    /** Tests only: make it this player's turn. */
    internal fun rigTurn(playerId: String) {
        turnSeat = player(playerId).seat
    }

    /** Tests only. */
    internal fun rigReserve(coins: Int) { treasuryReserve = coins }

    // ------------------------------------------------------------------ lobby

    fun player(id: String): CoupPlayer = seats.find { it.id == id } ?: throw CoupIllegalAction("Unknown player")
    fun findPlayer(id: String): CoupPlayer? = seats.find { it.id == id }

    fun addPlayer(id: String, name: String, host: Boolean = false): CoupPlayer {
        requirePhase(CoupPhase.LOBBY, "The game has already started")
        val clean = name.trim()
        if (clean.isEmpty()) throw CoupIllegalAction("Please enter a name")
        if (clean.length > 20) throw CoupIllegalAction("Name is too long (max 20 characters)")
        if (seats.any { it.name.equals(clean, ignoreCase = true) }) throw CoupIllegalAction("That name is already taken")
        if (seats.size >= 10) throw CoupIllegalAction("The table is full (10 players)")
        val p = CoupPlayer(id, clean, seats.size)
        if (host) {
            seats.forEach { it.isHost = false }
            p.isHost = true
        }
        seats += p
        bump("$clean joined", log = false)
        return p
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
        requirePhase(CoupPhase.LOBBY, "Players can only be removed in the lobby")
        val t = player(targetId)
        if (t.isHost) throw CoupIllegalAction("The host cannot be removed")
        seats.remove(t)
        seats.forEachIndexed { i, p -> p.seat = i }
        bump("${t.name} was removed", log = false)
    }

    fun setOptions(hostId: String, newOptions: CoupOptions) {
        requireHost(hostId)
        requirePhase(CoupPhase.LOBBY, "Options can only be changed in the lobby")
        options = newOptions
        bump()
    }

    fun start(hostId: String) {
        requireHost(hostId)
        requirePhase(CoupPhase.LOBBY, "The game has already started")
        val m = options.mode
        val n = seats.size
        if (n < m.minPlayers) throw CoupIllegalAction("Need at least ${m.minPlayers} players (have $n)")
        if (n > m.maxPlayers) throw CoupIllegalAction(
            if (m == Mode.BASE) "Classic Coup is for up to 6 players. Switch to Reformation for 7-10."
            else "Too many players (max ${m.maxPlayers})"
        )
        beginGame()
    }

    fun playAgain(hostId: String) {
        requireHost(hostId)
        requirePhase(CoupPhase.GAME_OVER, "The game is still running")
        val m = options.mode
        if (seats.size > m.maxPlayers || seats.size < m.minPlayers) throw CoupIllegalAction("Wrong number of players for this mode: restart to the lobby")
        beginGame()
    }

    fun restart(hostId: String) {
        requireHost(hostId)
        if (phase == CoupPhase.LOBBY) throw CoupIllegalAction("No game is running")
        clearGame()
        phase = CoupPhase.LOBBY
        log.clear()
        bump("The host restarted the game. Back to the lobby.", log = false)
    }

    private fun clearGame() {
        seats.forEach { it.cards.clear(); it.coins = 0; it.faction = Faction.LOYALIST }
        deck.clear()
        turn = null; loss = null; passed.clear(); exchangeOptions = emptyList()
        winnerId = null; treasuryReserve = 0
    }

    /** Copies of each character in the Court deck for this table. */
    fun copiesPerCharacter(n: Int, m: Mode): Int = when {
        m == Mode.BASE -> 3
        n <= 6 -> 3
        n <= 8 -> 4
        else -> 5
    }

    private fun beginGame() {
        clearGame()
        log.clear()
        mode = options.mode
        val n = seats.size
        val copies = copiesPerCharacter(n, mode)
        CoupCard.entries.forEach { c -> repeat(copies) { deck += c } }
        deck.shuffle(random)
        seats.forEach { p ->
            p.coins = 2
            repeat(2) { p.cards += Influence(draw()) }
        }
        turnSeat = random.nextInt(n)
        // Official two-player rule: the starting player begins with only 1 coin.
        if (n == 2 && mode == Mode.BASE) seats[turnSeat].coins = 1
        if (mode == Mode.REFORMATION) {
            // The start player takes an allegiance; going clockwise each player takes the opposite one.
            var f = if (random.nextBoolean()) Faction.LOYALIST else Faction.REFORMIST
            for (k in 0 until n) {
                seats[(turnSeat + k) % n].faction = f
                f = f.other
            }
        }
        phase = CoupPhase.ACTION
        bump("The game has started. ${turnName()} goes first.")
    }

    // ------------------------------------------------------------ the turn

    fun declare(actorId: String, type: ActionType, targetId: String? = null) {
        requirePhase(CoupPhase.ACTION, "It is not time to choose an action")
        val a = player(actorId)
        if (seats[turnSeat].id != actorId) throw CoupIllegalAction("It is not your turn")
        val opt = optionFor(a, type)
        if (!opt.allowed) throw CoupIllegalAction(opt.reason ?: "You can't do that now")
        val target = targetId?.let { player(it) }
        if (type.needsTarget && target == null) throw CoupIllegalAction("Choose a player")
        if (target != null && target.id !in opt.targets) throw CoupIllegalAction("You can't target ${target.name}")
        if (!type.needsTarget && type != ActionType.CONVERT && target != null) throw CoupIllegalAction("This action has no target")

        val cost = costOf(type, a, target)
        if (a.coins < cost) throw CoupIllegalAction("You need $cost coins")
        a.coins -= cost
        if (type == ActionType.CONVERT) treasuryReserve += cost

        turn = Turn(actorId, type, target?.id, cost)
        passed.clear()
        bump(describeDeclare(a, type, target))

        if (waitingIds().isEmpty()) {
            resolveAction()
        } else {
            phase = CoupPhase.RESPONSE
            bump()
        }
    }

    fun pass(playerId: String) {
        if (phase != CoupPhase.RESPONSE && phase != CoupPhase.BLOCK_RESPONSE) throw CoupIllegalAction("Nothing to pass on")
        if (playerId !in waitingIds()) throw CoupIllegalAction("You don't need to respond")
        passed += playerId
        if (waitingIds().isEmpty()) windowClosed() else bump()
    }

    fun challenge(challengerId: String) {
        val c = player(challengerId)
        val t = turn ?: throw CoupIllegalAction("Nothing to challenge")
        when (phase) {
            CoupPhase.RESPONSE -> {
                if (challengerId !in waitingIds()) throw CoupIllegalAction("You can't challenge now")
                if (t.challengeSettled || !t.action.challengeable) throw CoupIllegalAction("This can't be challenged")
                challengeAction(c, t)
            }
            CoupPhase.BLOCK_RESPONSE -> {
                if (challengerId !in waitingIds()) throw CoupIllegalAction("You can't challenge now")
                challengeBlock(c, t)
            }
            else -> throw CoupIllegalAction("Nothing to challenge")
        }
    }

    fun block(blockerId: String, character: CoupCard) {
        requirePhase(CoupPhase.RESPONSE, "Nothing to block")
        val t = turn ?: throw CoupIllegalAction("Nothing to block")
        val b = player(blockerId)
        if (blockerId !in waitingIds() || b !in eligibleBlockers(t)) throw CoupIllegalAction("You can't block this")
        if (character !in t.action.blockers) throw CoupIllegalAction("${character.displayName} can't block ${t.action.displayName}")
        t.blockerId = blockerId
        t.blockCharacter = character
        passed.clear()
        phase = CoupPhase.BLOCK_RESPONSE
        bump("${b.name} claims ${character.displayName} to block the ${t.action.displayName}.")
        if (waitingIds().isEmpty()) windowClosed()
    }

    fun chooseLostInfluence(playerId: String, cardIndex: Int) {
        requirePhase(CoupPhase.LOSE_INFLUENCE, "You don't have to lose a card")
        val l = loss ?: throw CoupIllegalAction("You don't have to lose a card")
        if (l.playerId != playerId) throw CoupIllegalAction("You don't have to lose a card")
        val p = player(playerId)
        val card = p.cards.getOrNull(cardIndex) ?: throw CoupIllegalAction("Pick one of your cards")
        if (card.revealed) throw CoupIllegalAction("That card is already face up")
        loss = null
        reveal(p, cardIndex, l.reason)
        if (phase != CoupPhase.GAME_OVER) l.then()
    }

    /** Ambassador: [keep] are indices into the exchange options (own face-down cards + 2 drawn). */
    fun exchange(playerId: String, keep: List<Int>) {
        requirePhase(CoupPhase.EXCHANGE, "Not exchanging")
        val t = turn ?: throw CoupIllegalAction("Not exchanging")
        if (t.actorId != playerId) throw CoupIllegalAction("Only ${nameOf(t.actorId)} is exchanging")
        val p = player(playerId)
        val keepCount = p.hidden.size
        if (keep.size != keepCount || keep.distinct().size != keep.size) throw CoupIllegalAction("Keep exactly $keepCount card${if (keepCount == 1) "" else "s"}")
        if (keep.any { it !in exchangeOptions.indices }) throw CoupIllegalAction("Pick from the cards shown")
        val kept = keep.map { exchangeOptions[it] }
        val returned = exchangeOptions.indices.filter { it !in keep }.map { exchangeOptions[it] }
        val hiddenCards = p.cards.filter { !it.revealed }
        hiddenCards.forEachIndexed { i, card -> card.character = kept[i] }
        deck += returned
        deck.shuffle(random)
        exchangeOptions = emptyList()
        bump("${p.name} exchanged cards with the Court deck.")
        endTurn()
    }

    /**
     * Host: move the game on when someone is not responding (phone died, left the room).
     * Everyone still to respond passes; a stuck card choice is made at random; a stuck turn takes Income
     * (or a Coup on a random legal target when the player has 10+ coins).
     */
    fun forceContinue(hostId: String) {
        requireHost(hostId)
        when (phase) {
            CoupPhase.ACTION -> {
                val a = seats[turnSeat]
                if (a.coins >= 10) {
                    val targets = optionFor(a, ActionType.COUP).targets
                    declare(a.id, ActionType.COUP, targets.random(random))
                } else declare(a.id, ActionType.INCOME)
            }
            CoupPhase.RESPONSE, CoupPhase.BLOCK_RESPONSE -> {
                if (waitingIds().isEmpty()) throw CoupIllegalAction("Nobody to wait for")
                passed += waitingIds()
                bump("The host skipped the players who had not answered.")
                windowClosed()
            }
            CoupPhase.LOSE_INFLUENCE -> {
                val l = loss ?: throw CoupIllegalAction("Nothing to skip")
                val p = player(l.playerId)
                val idx = p.cards.indices.filter { !p.cards[it].revealed }.random(random)
                chooseLostInfluence(p.id, idx)
            }
            CoupPhase.EXCHANGE -> {
                val t = turn ?: throw CoupIllegalAction("Nothing to skip")
                val keepCount = player(t.actorId).hidden.size
                exchange(t.actorId, (0 until keepCount).toList()) // keep the original hand
            }
            else -> throw CoupIllegalAction("Nothing to skip right now")
        }
    }

    // ------------------------------------------------------- rule helpers

    private fun alivePlayers() = seats.filter { it.alive }

    fun allSameFaction(): Boolean = alivePlayers().map { it.faction }.distinct().size <= 1

    /** Reformation: you may not Coup, Assassinate or Steal from your own allegiance (unless everyone shares one). */
    private fun mayTarget(a: CoupPlayer, t: CoupPlayer): Boolean =
        mode != Mode.REFORMATION || a.faction != t.faction || allSameFaction()

    private fun costOf(type: ActionType, a: CoupPlayer, target: CoupPlayer?): Int = when (type) {
        ActionType.COUP -> 7
        ActionType.ASSASSINATE -> 3
        ActionType.CONVERT -> if (target == null || target.id == a.id) 1 else 2
        else -> 0
    }

    private fun optionFor(a: CoupPlayer, type: ActionType): ActionOption {
        val others = alivePlayers().filter { it.id != a.id }
        val legalTargets = others.filter { mayTarget(a, it) }.map { it.id }
        fun no(reason: String, targets: List<String> = emptyList(), cost: Int = 0) = ActionOption(type, false, reason, targets, cost)
        if (type.reformationOnly && mode != Mode.REFORMATION) return no("Reformation only")
        if (a.coins >= 10 && type != ActionType.COUP) return no("10+ coins: you must Coup")
        return when (type) {
            ActionType.COUP ->
                if (a.coins < 7) no("Needs 7 coins", legalTargets, 7)
                else if (legalTargets.isEmpty()) no("No one to target", cost = 7)
                else ActionOption(type, true, null, legalTargets, 7)
            ActionType.ASSASSINATE ->
                if (a.coins < 3) no("Needs 3 coins", legalTargets, 3)
                else if (legalTargets.isEmpty()) no("No one to target", cost = 3)
                else ActionOption(type, true, null, legalTargets, 3)
            ActionType.STEAL ->
                if (legalTargets.isEmpty()) no("No one to target")
                else ActionOption(type, true, null, legalTargets)
            ActionType.CONVERT ->
                // target list = other players (cost 2); converting yourself (no target) costs 1.
                if (a.coins < 1) no("Needs 1 coin", others.map { it.id }, 1)
                else ActionOption(type, true, null, if (a.coins >= 2) others.map { it.id } else emptyList(), 1)
            ActionType.EMBEZZLE ->
                if (treasuryReserve <= 0) no("The Treasury Reserve is empty")
                else ActionOption(type, true)
            else -> ActionOption(type, true)
        }
    }

    private fun eligibleBlockers(t: Turn): List<CoupPlayer> {
        if (!t.action.blockable) return emptyList()
        val actor = player(t.actorId)
        return when (t.action) {
            ActionType.FOREIGN_AID -> alivePlayers().filter { it.id != actor.id && mayTarget(it, actor) }
            else -> listOfNotNull(t.targetId?.let { findPlayer(it) }?.takeIf { it.alive })
        }
    }

    /** Who still has to answer in the current window. */
    private fun waitingIds(): List<String> {
        val t = turn ?: return emptyList()
        val candidates: List<CoupPlayer> = when (phase) {
            CoupPhase.ACTION, CoupPhase.RESPONSE -> {
                if (!t.challengeSettled && t.action.challengeable) alivePlayers().filter { it.id != t.actorId }
                else eligibleBlockers(t)
            }
            CoupPhase.BLOCK_RESPONSE -> alivePlayers().filter { it.id != t.blockerId }
            else -> emptyList()
        }
        return candidates.map { it.id }.filter { it !in passed }
    }

    private fun windowClosed() {
        val t = turn ?: return
        when (phase) {
            CoupPhase.RESPONSE -> resolveAction()
            CoupPhase.BLOCK_RESPONSE -> {
                bump("Nobody challenged: ${nameOf(t.blockerId!!)} blocks the ${t.action.displayName}.")
                endTurn()
            }
            else -> {}
        }
    }

    // ------------------------------------------------------ challenges

    private fun challengeAction(c: CoupPlayer, t: Turn) {
        val a = player(t.actorId)
        if (t.action == ActionType.EMBEZZLE) {
            if (a.has(CoupCard.DUKE)) {
                bump("${c.name} challenges: ${a.name} DID have the Duke. The Embezzle fails.")
                loseInfluence(a.id, "caught embezzling with the Duke") { endTurn() }
            } else {
                // Proved innocent: the shown cards go back into the deck and are replaced.
                val hiddenCards = a.cards.filter { !it.revealed }
                hiddenCards.forEach { deck += it.character }
                deck.shuffle(random)
                hiddenCards.forEach { it.character = draw() }
                bump("${c.name} challenges: ${a.name} had no Duke and draws new cards. ${c.name} loses the challenge.")
                t.challengeSettled = true
                loseInfluence(c.id, "lost a challenge") { resolveAction() }
            }
            return
        }
        val claim = t.action.claim!!
        if (a.has(claim)) {
            swapProvenCard(a, claim)
            bump("${c.name} challenges: ${a.name} reveals the ${claim.displayName} and draws a new card. ${c.name} loses the challenge.")
            t.challengeSettled = true
            loseInfluence(c.id, "lost a challenge") { afterActionChallengeLost(t) }
        } else {
            if (t.action == ActionType.ASSASSINATE) a.coins += t.paid // the action never happened
            bump("${c.name} challenges: ${a.name} did NOT have the ${claim.displayName}. The ${t.action.displayName} fails.")
            loseInfluence(a.id, "was caught bluffing") { endTurn() }
        }
    }

    /** The challenger was wrong: the action stands, but the target may still block it. */
    private fun afterActionChallengeLost(t: Turn) {
        passed.clear()
        if (eligibleBlockers(t).isNotEmpty()) {
            phase = CoupPhase.RESPONSE
            bump()
        } else resolveAction()
    }

    private fun challengeBlock(c: CoupPlayer, t: Turn) {
        val b = player(t.blockerId!!)
        val claim = t.blockCharacter!!
        if (b.has(claim)) {
            swapProvenCard(b, claim)
            bump("${c.name} challenges the block: ${b.name} reveals the ${claim.displayName} and draws a new card. The block stands.")
            loseInfluence(c.id, "lost a challenge") { endTurn() }
        } else {
            bump("${c.name} challenges the block: ${b.name} did NOT have the ${claim.displayName}. The ${t.action.displayName} goes ahead.")
            loseInfluence(b.id, "was caught bluffing") { resolveAction() }
        }
    }

    /** A proven card is shown, shuffled back into the Court deck, and replaced. */
    private fun swapProvenCard(p: CoupPlayer, c: CoupCard) {
        val card = p.cards.first { !it.revealed && it.character == c }
        deck += c
        deck.shuffle(random)
        card.character = draw()
    }

    // --------------------------------------------------------- effects

    private fun resolveAction() {
        val t = turn ?: return
        val a = player(t.actorId)
        val target = t.targetId?.let { player(it) }
        when (t.action) {
            ActionType.INCOME -> { a.coins += 1; bump("${a.name} takes Income (+1)."); endTurn() }
            ActionType.FOREIGN_AID -> { a.coins += 2; bump("${a.name} takes Foreign Aid (+2)."); endTurn() }
            ActionType.TAX -> { a.coins += 3; bump("${a.name} collects Tax (+3)."); endTurn() }
            ActionType.STEAL -> {
                val amount = minOf(2, target!!.coins)
                target.coins -= amount
                a.coins += amount
                bump("${a.name} steals $amount coin${if (amount == 1) "" else "s"} from ${target.name}.")
                endTurn()
            }
            ActionType.ASSASSINATE, ActionType.COUP -> {
                if (target != null && target.alive) {
                    loseInfluence(target.id, if (t.action == ActionType.COUP) "was hit by a Coup" else "was assassinated") { endTurn() }
                } else endTurn()
            }
            ActionType.EXCHANGE -> {
                val drawn = listOf(draw(), draw())
                exchangeOptions = a.hidden.map { it.character } + drawn
                phase = CoupPhase.EXCHANGE
                bump("${a.name} draws two cards from the Court deck to exchange.")
            }
            ActionType.CONVERT -> {
                val who = target ?: a
                who.faction = who.faction.other
                bump("${who.name} is now a ${who.faction.displayName}.")
                endTurn()
            }
            ActionType.EMBEZZLE -> {
                val amount = treasuryReserve
                treasuryReserve = 0
                a.coins += amount
                bump("${a.name} embezzles $amount coin${if (amount == 1) "" else "s"} from the Treasury Reserve.")
                endTurn()
            }
        }
    }

    /** The player reveals one face-down card (chooses which if they have two), then [then] runs. */
    private fun loseInfluence(playerId: String, reason: String, then: () -> Unit) {
        val p = player(playerId)
        val hiddenIdx = p.cards.indices.filter { !p.cards[it].revealed }
        when (hiddenIdx.size) {
            0 -> then()
            1 -> {
                reveal(p, hiddenIdx[0], reason)
                if (phase != CoupPhase.GAME_OVER) then()
            }
            else -> {
                loss = PendingLoss(playerId, reason, then)
                passed.clear()
                phase = CoupPhase.LOSE_INFLUENCE
                bump()
            }
        }
    }

    private fun reveal(p: CoupPlayer, idx: Int, reason: String) {
        val card = p.cards[idx]
        card.revealed = true
        bump("${p.name} $reason and reveals the ${card.character.displayName}.")
        if (!p.alive) {
            p.coins = 0
            bump("${p.name} is out of the game.")
            val alive = alivePlayers()
            if (alive.size == 1) {
                winnerId = alive[0].id
                turn = null; loss = null; passed.clear(); exchangeOptions = emptyList()
                phase = CoupPhase.GAME_OVER
                bump("${alive[0].name} wins the game!")
            }
        }
    }

    private fun endTurn() {
        turn = null
        loss = null
        passed.clear()
        exchangeOptions = emptyList()
        if (phase == CoupPhase.GAME_OVER) return
        val n = seats.size
        var next = turnSeat
        for (k in 1..n) {
            next = (turnSeat + k) % n
            if (seats[next].alive) break
        }
        turnSeat = next
        phase = CoupPhase.ACTION
        bump()
    }

    private fun draw(): CoupCard {
        if (deck.isEmpty()) throw IllegalStateException("Court deck is empty")
        return deck.removeAt(deck.size - 1)
    }

    private fun describeDeclare(a: CoupPlayer, type: ActionType, target: CoupPlayer?): String = when (type) {
        ActionType.INCOME -> "${a.name} takes Income."
        ActionType.FOREIGN_AID -> "${a.name} wants Foreign Aid (+2)."
        ActionType.COUP -> "${a.name} pays 7 and launches a Coup against ${target!!.name}!"
        ActionType.TAX -> "${a.name} claims the Duke to collect Tax (+3)."
        ActionType.ASSASSINATE -> "${a.name} pays 3 and claims the Assassin to assassinate ${target!!.name}."
        ActionType.STEAL -> "${a.name} claims the Captain to steal from ${target!!.name}."
        ActionType.EXCHANGE -> "${a.name} claims the Ambassador to exchange cards."
        ActionType.CONVERT -> if (target == null || target.id == a.id) "${a.name} pays 1 to change allegiance." else "${a.name} pays 2 to convert ${target.name}."
        ActionType.EMBEZZLE -> "${a.name} claims to have no Duke and wants to embezzle the Treasury Reserve ($treasuryReserve)."
    }

    // ------------------------------------------------------------------ view

    fun viewFor(playerId: String): CoupView {
        val me = player(playerId)
        val inGame = phase != CoupPhase.LOBBY
        val over = phase == CoupPhase.GAME_OVER
        val reform = inGame && mode == Mode.REFORMATION
        val t = turn
        val waiting = waitingIds()

        val pendingKind: CoupPending = when {
            phase == CoupPhase.LOBBY -> if (me.isHost) CoupPending.START_GAME else CoupPending.WAIT
            over -> if (me.isHost) CoupPending.PLAY_AGAIN else CoupPending.WAIT
            !me.alive -> CoupPending.ELIMINATED
            phase == CoupPhase.ACTION && seats[turnSeat].id == me.id -> CoupPending.TAKE_ACTION
            phase == CoupPhase.RESPONSE && me.id in waiting -> CoupPending.RESPOND
            phase == CoupPhase.BLOCK_RESPONSE && me.id in waiting -> CoupPending.RESPOND_BLOCK
            phase == CoupPhase.LOSE_INFLUENCE && loss?.playerId == me.id -> CoupPending.LOSE_INFLUENCE
            phase == CoupPhase.EXCHANGE && t?.actorId == me.id -> CoupPending.EXCHANGE
            else -> CoupPending.WAIT
        }

        val actions = if (pendingKind == CoupPending.TAKE_ACTION)
            ActionType.entries.filter { !it.reformationOnly || mode == Mode.REFORMATION }.map { optionFor(me, it) }
        else emptyList()

        val self = CoupSelf(
            id = me.id, name = me.name, seat = me.seat, isHost = me.isHost,
            coins = me.coins,
            cards = if (inGame) me.cards.map { OwnCard(it.character, it.revealed) } else emptyList(),
            alive = !inGame || me.alive,
            faction = if (reform) me.faction else null,
            pending = pendingKind,
            actions = actions,
            mustCoup = pendingKind == CoupPending.TAKE_ACTION && me.coins >= 10,
            canChallenge = when (pendingKind) {
                CoupPending.RESPOND -> t != null && !t.challengeSettled && t.action.challengeable
                CoupPending.RESPOND_BLOCK -> true
                else -> false
            },
            canBlockWith = if (pendingKind == CoupPending.RESPOND && t != null && me in eligibleBlockers(t)) t.action.blockers else emptyList(),
            exchangeOptions = if (pendingKind == CoupPending.EXCHANGE) exchangeOptions else emptyList(),
            keepCount = if (pendingKind == CoupPending.EXCHANGE) me.hidden.size else 0,
        )

        val pendingView = if (t != null && inGame && !over) PendingView(
            actorId = t.actorId,
            action = t.action,
            targetId = t.targetId,
            claim = t.action.claim,
            blockerId = t.blockerId,
            blockCharacter = t.blockCharacter,
            waitingIds = if (phase == CoupPhase.RESPONSE || phase == CoupPhase.BLOCK_RESPONSE) waiting else emptyList(),
            passedIds = if (phase == CoupPhase.RESPONSE || phase == CoupPhase.BLOCK_RESPONSE) passed.toList() else emptyList(),
            challengeSettled = t.challengeSettled,
            losingPlayerId = loss?.playerId,
            lossReason = loss?.reason,
        ) else if (loss != null) PendingView(
            actorId = loss!!.playerId, action = ActionType.INCOME,
            losingPlayerId = loss!!.playerId, lossReason = loss!!.reason,
        ) else null

        return CoupView(
            phase = phase,
            mode = if (inGame) mode else options.mode,
            you = self,
            players = seats.map { p ->
                CoupPublicPlayer(
                    id = p.id, name = p.name, seat = p.seat, connected = p.connected, isHost = p.isHost,
                    coins = if (inGame) p.coins else 0,
                    influence = if (inGame) p.hidden.size else 0,
                    revealed = if (inGame) p.cards.filter { it.revealed }.map { it.character } else emptyList(),
                    alive = !inGame || p.alive,
                    faction = if (reform) p.faction else null,
                    finalHidden = if (over) p.hidden.map { it.character } else emptyList(),
                )
            },
            turnId = if (inGame && !over) seats[turnSeat].id else null,
            deckSize = deck.size,
            treasuryReserve = treasuryReserve,
            allSameFaction = reform && allSameFaction(),
            pending = pendingView,
            winnerId = winnerId,
            log = log.takeLast(60),
            lastEvent = lastEvent,
            version = version,
        )
    }

    // --------------------------------------------------------------- helpers

    private fun turnName() = seats[turnSeat].name
    private fun nameOf(id: String) = player(id).name

    private fun requirePhase(p: CoupPhase, msg: String) {
        if (phase != p) throw CoupIllegalAction(msg)
    }

    private fun requireHost(id: String) {
        if (!player(id).isHost) throw CoupIllegalAction("Only the host can do that")
    }

    private fun bump(event: String? = null, log: Boolean = true) {
        version++
        if (event != null) {
            lastEvent = event
            if (log) this.log += event
        }
    }
}
