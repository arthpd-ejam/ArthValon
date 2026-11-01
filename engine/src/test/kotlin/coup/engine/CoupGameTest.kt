package coup.engine

import coup.engine.ActionType.ASSASSINATE
import coup.engine.ActionType.COUP
import coup.engine.ActionType.CONVERT
import coup.engine.ActionType.EMBEZZLE
import coup.engine.ActionType.EXCHANGE
import coup.engine.ActionType.FOREIGN_AID
import coup.engine.ActionType.INCOME
import coup.engine.ActionType.STEAL
import coup.engine.ActionType.TAX
import coup.engine.CoupCard.AMBASSADOR
import coup.engine.CoupCard.ASSASSIN
import coup.engine.CoupCard.CAPTAIN
import coup.engine.CoupCard.CONTESSA
import coup.engine.CoupCard.DUKE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoupGameTest {

    private fun game(n: Int, mode: Mode = Mode.BASE, seed: Int = 1): CoupGame {
        val g = CoupGame(Random(seed))
        g.addPlayer("p1", "Ann", host = true)
        for (i in 2..n) g.addPlayer("p$i", "P$i")
        g.setOptions("p1", CoupOptions(mode))
        g.start("p1")
        return g
    }

    private fun CoupGame.passAll() {
        while (waitingForResponse.isNotEmpty()) pass(waitingForResponse.first())
    }

    private fun CoupGame.p(id: String) = player(id)

    private fun totalCards(g: CoupGame) = g.secretDeck.size + g.players.sumOf { it.cards.size }

    @Test
    fun `setup deals two cards and two coins and uses the right deck`() {
        for (n in 2..6) {
            val g = game(n)
            assertEquals(15, totalCards(g))
            g.players.forEach { assertEquals(2, it.cards.size) }
            val starter = g.currentTurnPlayer!!
            g.players.forEach { assertEquals(if (n == 2 && it == starter) 1 else 2, it.coins) }
        }
        assertEquals(20, totalCards(game(7, Mode.REFORMATION)))
        assertEquals(20, totalCards(game(8, Mode.REFORMATION)))
        assertEquals(25, totalCards(game(10, Mode.REFORMATION)))
        assertEquals(15, totalCards(game(5, Mode.REFORMATION)))
    }

    @Test
    fun `classic mode refuses more than six players`() {
        val g = CoupGame(Random(1))
        g.addPlayer("p1", "Ann", host = true)
        for (i in 2..7) g.addPlayer("p$i", "P$i")
        assertFailsWith<CoupIllegalAction> { g.start("p1") }
        g.setOptions("p1", CoupOptions(Mode.REFORMATION))
        g.start("p1")
        assertEquals(CoupPhase.ACTION, g.phase)
    }

    @Test
    fun `reformation alternates allegiances from the start player`() {
        val g = game(6, Mode.REFORMATION)
        val start = g.currentTurnPlayer!!.seat
        val fs = (0 until 6).map { g.players[(start + it) % 6].faction }
        for (i in 1 until 6) assertEquals(fs[i - 1].other, fs[i])
    }

    @Test
    fun `income is immediate and the turn passes`() {
        val g = game(3)
        g.rigTurn("p1")
        g.declare("p1", INCOME)
        assertEquals(3, g.p("p1").coins)
        assertEquals(CoupPhase.ACTION, g.phase)
        assertEquals("p2", g.currentTurnPlayer!!.id)
    }

    @Test
    fun `only the turn player acts`() {
        val g = game(3)
        g.rigTurn("p1")
        assertFailsWith<CoupIllegalAction> { g.declare("p2", INCOME) }
    }

    @Test
    fun `tax resolves only after everyone passes`() {
        val g = game(4)
        g.rigTurn("p1")
        g.declare("p1", TAX)
        assertEquals(CoupPhase.RESPONSE, g.phase)
        assertEquals(setOf("p2", "p3", "p4"), g.waitingForResponse.toSet())
        g.pass("p2"); g.pass("p3")
        assertEquals(2, g.p("p1").coins)
        assertFailsWith<CoupIllegalAction> { g.pass("p2") }
        g.pass("p4")
        assertEquals(5, g.p("p1").coins)
        assertEquals("p2", g.currentTurnPlayer!!.id)
    }

    @Test
    fun `failed challenge on a true claim swaps the card and costs the challenger`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(DUKE, CONTESSA))
        g.rig("p2", listOf(CAPTAIN, ASSASSIN))
        g.declare("p1", TAX)
        g.challenge("p2")
        assertEquals(CoupPhase.LOSE_INFLUENCE, g.phase)
        assertEquals("p2", g.currentLoserId)
        g.chooseLostInfluence("p2", 1)
        assertTrue(g.p("p2").cards[1].revealed)
        assertEquals(ASSASSIN, g.p("p2").cards[1].character)
        assertEquals(5, g.p("p1").coins)
        assertEquals(15, totalCards(g))
        assertEquals("p2", g.currentTurnPlayer!!.id)
    }

    @Test
    fun `caught bluff loses a card and the action fails, assassination coins are refunded`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(DUKE, CONTESSA), coins = 3)
        g.declare("p1", ASSASSINATE, "p2")
        assertEquals(0, g.p("p1").coins)
        g.challenge("p3")
        g.chooseLostInfluence("p1", 0)
        assertEquals(3, g.p("p1").coins)
        assertTrue(g.p("p2").cards.none { it.revealed })
        assertEquals(CoupPhase.ACTION, g.phase)
    }

    @Test
    fun `contessa block that nobody challenges stops the assassination and the coins stay spent`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(ASSASSIN, DUKE), coins = 3)
        g.declare("p1", ASSASSINATE, "p2")
        // Only the target may block, but everyone may challenge.
        assertTrue(g.viewFor("p2").you.canBlockWith == listOf(CONTESSA))
        assertTrue(g.viewFor("p3").you.canBlockWith.isEmpty())
        g.block("p2", CONTESSA)
        assertEquals(CoupPhase.BLOCK_RESPONSE, g.phase)
        assertEquals(setOf("p1", "p3"), g.waitingForResponse.toSet())
        g.passAll()
        assertEquals(0, g.p("p1").coins)
        assertTrue(g.p("p2").cards.none { it.revealed })
        assertEquals("p2", g.currentTurnPlayer!!.id)
    }

    @Test
    fun `a bluffed contessa block that is challenged costs the target both cards`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(ASSASSIN, DUKE), coins = 3)
        g.rig("p2", listOf(CAPTAIN, AMBASSADOR))
        g.declare("p1", ASSASSINATE, "p2")
        g.block("p2", CONTESSA)
        g.challenge("p1")
        g.chooseLostInfluence("p2", 0) // lost the challenge
        // then the assassination goes ahead and takes the last card automatically
        assertFalse(g.p("p2").alive)
        assertEquals(0, g.p("p2").coins)
        assertEquals("p3", g.currentTurnPlayer!!.id)
    }

    @Test
    fun `target may still block after losing a challenge against the assassin`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(ASSASSIN, DUKE), coins = 3)
        g.rig("p2", listOf(CONTESSA, CAPTAIN))
        g.declare("p1", ASSASSINATE, "p2")
        g.challenge("p2")
        g.chooseLostInfluence("p2", 1) // reveals the Captain
        assertEquals(CoupPhase.RESPONSE, g.phase)
        assertEquals(listOf("p2"), g.waitingForResponse)
        val v = g.viewFor("p2").you
        assertFalse(v.canChallenge)
        assertEquals(listOf(CONTESSA), v.canBlockWith)
        g.block("p2", CONTESSA)
        g.passAll()
        assertTrue(g.p("p2").alive)
    }

    @Test
    fun `steal takes two coins, or one, and can be blocked by captain or ambassador`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p2", coins = 1)
        g.declare("p1", STEAL, "p2")
        assertEquals(listOf(CAPTAIN, AMBASSADOR), g.viewFor("p2").you.canBlockWith)
        g.passAll()
        assertEquals(3, g.p("p1").coins)
        assertEquals(0, g.p("p2").coins)

        g.rigTurn("p1")
        g.rig("p3", coins = 4)
        g.declare("p1", STEAL, "p3")
        g.block("p3", AMBASSADOR)
        g.passAll()
        assertEquals(3, g.p("p1").coins)
        assertEquals(4, g.p("p3").coins)
    }

    @Test
    fun `foreign aid can be blocked by anyone claiming the duke`() {
        val g = game(4)
        g.rigTurn("p1")
        g.declare("p1", FOREIGN_AID)
        val v3 = g.viewFor("p3").you
        assertFalse(v3.canChallenge)
        assertEquals(listOf(DUKE), v3.canBlockWith)
        g.block("p3", DUKE)
        g.passAll()
        assertEquals(2, g.p("p1").coins)

        g.rigTurn("p1")
        g.declare("p1", FOREIGN_AID)
        g.passAll()
        assertEquals(4, g.p("p1").coins)
    }

    @Test
    fun `coup costs seven, cannot be answered and is forced at ten coins`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", coins = 10)
        assertFailsWith<CoupIllegalAction> { g.declare("p1", INCOME) }
        assertTrue(g.viewFor("p1").you.mustCoup)
        g.declare("p1", COUP, "p2")
        assertEquals(3, g.p("p1").coins)
        assertEquals(CoupPhase.LOSE_INFLUENCE, g.phase)
        g.chooseLostInfluence("p2", 0)
        assertEquals(CoupPhase.ACTION, g.phase)

        g.rigTurn("p1")
        assertFailsWith<CoupIllegalAction> { g.declare("p1", COUP, "p2") } // only 3 coins
    }

    @Test
    fun `exchange keeps the right number of cards and returns the rest`() {
        val g = game(3)
        g.rigTurn("p1")
        g.rig("p1", listOf(DUKE, CONTESSA))
        g.declare("p1", EXCHANGE)
        g.passAll()
        assertEquals(CoupPhase.EXCHANGE, g.phase)
        val opts = g.viewFor("p1").you.exchangeOptions
        assertEquals(4, opts.size)
        assertEquals(2, g.viewFor("p1").you.keepCount)
        assertTrue(g.viewFor("p2").you.exchangeOptions.isEmpty())
        assertFailsWith<CoupIllegalAction> { g.exchange("p1", listOf(2)) }
        g.exchange("p1", listOf(2, 3))
        assertEquals(listOf(opts[2], opts[3]), g.p("p1").cards.map { it.character })
        assertEquals(15, totalCards(g))
        assertEquals(CoupPhase.ACTION, g.phase)
    }

    @Test
    fun `last player standing wins and all cards are shown`() {
        val g = game(2)
        g.rigTurn("p1")
        g.rig("p1", listOf(ASSASSIN, DUKE), coins = 7)
        g.rig("p2", listOf(CAPTAIN, CAPTAIN))
        g.p("p2").cards[0].revealed = true
        g.declare("p1", COUP, "p2")
        assertEquals(CoupPhase.GAME_OVER, g.phase)
        assertEquals("p1", g.winner!!.id)
        val v = g.viewFor("p2")
        assertEquals("p1", v.winnerId)
        assertEquals(listOf(ASSASSIN, DUKE), v.players.first { it.id == "p1" }.finalHidden)
        assertEquals(CoupPending.PLAY_AGAIN, g.viewFor("p1").you.pending)
        g.playAgain("p1")
        assertEquals(CoupPhase.ACTION, g.phase)
        assertTrue(g.players.all { it.cards.size == 2 && it.cards.none { c -> c.revealed } })
    }

    @Test
    fun `eliminated players are skipped and cannot respond`() {
        val g = game(3)
        g.p("p2").cards.forEach { it.revealed = true }
        g.rigTurn("p1")
        g.declare("p1", TAX)
        assertEquals(listOf("p3"), g.waitingForResponse)
        g.passAll()
        assertEquals("p3", g.currentTurnPlayer!!.id)
        assertEquals(CoupPending.ELIMINATED, g.viewFor("p2").you.pending)
    }

    @Test
    fun `nobody sees other players' face-down cards`() {
        val g = game(4)
        val v = g.viewFor("p2")
        assertEquals(2, v.you.cards.size)
        v.players.forEach { assertTrue(it.finalHidden.isEmpty()); assertTrue(it.revealed.isEmpty()) }
        val text = v.toString()
        // p1's hand must not appear as a list in p2's view
        assertTrue(v.players.first { it.id == "p1" }.influence == 2)
        assertTrue(text.contains("influence=2"))
    }

    @Test
    fun `reformation forbids attacking your own allegiance until everyone shares one`() {
        val g = game(4, Mode.REFORMATION)
        g.rigTurn("p1")
        g.rig("p1", coins = 7, faction = Faction.LOYALIST)
        g.rig("p2", faction = Faction.LOYALIST)
        g.rig("p3", faction = Faction.REFORMIST)
        g.rig("p4", faction = Faction.REFORMIST)
        val coup = g.viewFor("p1").you.actions.first { it.type == COUP }
        assertEquals(setOf("p3", "p4"), coup.targets.toSet())
        assertFailsWith<CoupIllegalAction> { g.declare("p1", COUP, "p2") }

        // Foreign aid: only the other allegiance may block.
        g.declare("p1", FOREIGN_AID)
        assertEquals(setOf("p3", "p4"), g.waitingForResponse.toSet())
        assertTrue(g.viewFor("p2").you.canBlockWith.isEmpty())
        g.passAll()

        // Everyone the same: free for all.
        g.rig("p3", faction = Faction.LOYALIST)
        g.rig("p4", faction = Faction.LOYALIST)
        g.rigTurn("p1")
        val coup2 = g.viewFor("p1").you.actions.first { it.type == COUP }
        assertEquals(setOf("p2", "p3", "p4"), coup2.targets.toSet())
        assertTrue(g.viewFor("p1").allSameFaction)
    }

    @Test
    fun `convert pays the treasury reserve and embezzle takes it`() {
        val g = game(4, Mode.REFORMATION)
        g.rigTurn("p1")
        g.rig("p1", listOf(CAPTAIN, CONTESSA), coins = 5)
        val before = g.p("p1").faction
        g.declare("p1", CONVERT)
        assertEquals(before.other, g.p("p1").faction)
        assertEquals(1, g.treasuryReserve)
        assertEquals(4, g.p("p1").coins)

        g.rigTurn("p1")
        val f2 = g.p("p2").faction
        g.declare("p1", CONVERT, "p2")
        assertEquals(f2.other, g.p("p2").faction)
        assertEquals(3, g.treasuryReserve)
        assertEquals(2, g.p("p1").coins)

        g.rigTurn("p1")
        g.declare("p1", EMBEZZLE)
        g.passAll()
        assertEquals(5, g.p("p1").coins)
        assertEquals(0, g.treasuryReserve)
    }

    @Test
    fun `embezzle challenge punishes whoever is wrong about the duke`() {
        val g = game(3, Mode.REFORMATION)
        g.rigReserve(4)
        g.rigTurn("p1")
        g.rig("p1", listOf(DUKE, CAPTAIN))
        g.declare("p1", EMBEZZLE)
        g.challenge("p2")
        assertEquals("p1", g.currentLoserId)
        g.chooseLostInfluence("p1", 1)
        assertEquals(4, g.treasuryReserve)
        assertEquals(2, g.p("p1").coins)

        g.rigTurn("p3")
        g.rig("p3", listOf(AMBASSADOR, CONTESSA))
        g.declare("p3", EMBEZZLE)
        g.challenge("p2")
        assertEquals("p2", g.currentLoserId)
        g.chooseLostInfluence("p2", 0)
        assertEquals(6, g.p("p3").coins)
        assertEquals(0, g.treasuryReserve)
        assertEquals(15, totalCards(g))
    }

    @Test
    fun `embezzle and convert are not available in classic coup`() {
        val g = game(3)
        g.rigTurn("p1")
        assertFailsWith<CoupIllegalAction> { g.declare("p1", CONVERT) }
        assertTrue(g.viewFor("p1").you.actions.none { it.type == EMBEZZLE || it.type == CONVERT })
    }

    @Test
    fun `host can skip players who do not answer`() {
        val g = game(4)
        g.rigTurn("p2")
        g.declare("p2", TAX)
        assertFailsWith<CoupIllegalAction> { g.forceContinue("p2") }
        g.forceContinue("p1")
        assertEquals(5, g.p("p2").coins)
        // stuck turn: income
        val turn = g.currentTurnPlayer!!.id
        g.forceContinue("p1")
        assertEquals(3, g.p(turn).coins)
    }

    @Test
    fun `restart returns to the lobby with the same players`() {
        val g = game(4)
        g.restart("p1")
        assertEquals(CoupPhase.LOBBY, g.phase)
        assertEquals(4, g.players.size)
        assertTrue(g.players.all { it.cards.isEmpty() })
        assertNull(g.viewFor("p1").pending)
    }

    @Test
    fun `random full games always finish with one winner and conserve cards`() {
        for (seed in 1..300) {
            val n = 2 + seed % 9
            val mode = if (n > 6 || seed % 2 == 0) Mode.REFORMATION else Mode.BASE
            val g = game(n, mode, seed)
            val total = totalCards(g)
            val r = Random(seed * 31)
            var steps = 0
            while (g.phase != CoupPhase.GAME_OVER) {
                check(steps++ < 5000) { "game $seed did not finish" }
                randomMove(g, r)
                assertEquals(total, totalCards(g), "cards lost in game $seed")
                g.players.forEach { assertTrue(it.coins >= 0) }
            }
            assertEquals(1, g.players.count { it.alive })
        }
    }

    private fun randomMove(g: CoupGame, r: Random) {
        when (g.phase) {
            CoupPhase.ACTION -> {
                val p = g.currentTurnPlayer!!
                val opts = g.viewFor(p.id).you.actions.filter { it.allowed }
                val o = opts[r.nextInt(opts.size)]
                val target = when {
                    o.type.needsTarget -> o.targets[r.nextInt(o.targets.size)]
                    o.type == CONVERT && o.targets.isNotEmpty() && r.nextBoolean() -> o.targets[r.nextInt(o.targets.size)]
                    else -> null
                }
                g.declare(p.id, o.type, target)
            }
            CoupPhase.RESPONSE, CoupPhase.BLOCK_RESPONSE -> {
                val id = g.waitingForResponse[r.nextInt(g.waitingForResponse.size)]
                val me = g.viewFor(id).you
                val roll = r.nextInt(10)
                when {
                    roll == 0 && me.canChallenge -> g.challenge(id)
                    roll == 1 && me.canBlockWith.isNotEmpty() -> g.block(id, me.canBlockWith.random(r))
                    else -> g.pass(id)
                }
            }
            CoupPhase.LOSE_INFLUENCE -> {
                val id = g.currentLoserId!!
                val idx = g.player(id).cards.indices.filter { !g.player(id).cards[it].revealed }
                g.chooseLostInfluence(id, idx.random(r))
            }
            CoupPhase.EXCHANGE -> {
                val id = g.currentTurnPlayer!!.id
                val me = g.viewFor(id).you
                g.exchange(id, me.exchangeOptions.indices.shuffled(r).take(me.keepCount))
            }
            else -> error("unexpected ${g.phase}")
        }
    }
}
