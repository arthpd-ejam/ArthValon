package avalon.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTest {

    private fun lobby(n: Int, seed: Int = 1, lady: Boolean? = null): Game {
        val g = Game(Random(seed))
        for (i in 0 until n) g.addPlayer("p$i", "Player $i", host = i == 0)
        if (lady != null) g.setOptions("p0", GameOptions(ladyOfTheLake = lady))
        return g
    }

    private fun started(n: Int, seed: Int = 1, lady: Boolean? = null): Game {
        val g = lobby(n, seed, lady)
        g.start("p0")
        g.players.forEach { g.confirmRole(it.id) }
        assertEquals(Phase.TEAM_SELECTION, g.phase)
        return g
    }

    private fun Game.leader() = currentLeader!!
    private fun Game.byRole(r: Role) = players.first { it.role == r }

    /** Propose the first `size` players in seat order starting with the leader and approve unanimously. */
    private fun Game.approveTeam(team: List<String>? = null): List<String> {
        if (phase == Phase.LADY_PICK) { // Lady of the Lake is on by default at 7+: examine someone and move on
            val holder = currentLadyHolderId!!
            ladyExamine(holder, ladyTargets().first())
            ladyConfirm(holder)
        }
        val size = Setup.forPlayers(players.size).questSizes[currentQuestNumber - 1]
        val t = team ?: players.map { it.id }.take(size)
        proposeTeam(leader().id, t)
        players.forEach { vote(it.id, Vote.APPROVE) }
        assertEquals(Phase.QUEST, phase)
        return t
    }

    private fun Game.playQuest(team: List<String>, fails: Int) {
        var left = fails
        for (id in team) {
            val p = player(id)
            if (left > 0 && !p.role!!.good) { playQuestCard(id, QuestCard.FAIL); left-- } else playQuestCard(id, QuestCard.SUCCESS)
        }
        assertEquals(0, left, "not enough evil players on the team to play $fails fails")
    }

    private fun Game.teamWithEvil(count: Int): List<String> {
        val size = Setup.forPlayers(players.size).questSizes[currentQuestNumber - 1]
        val evil = players.filter { !it.role!!.good }.map { it.id }.take(count)
        val good = players.filter { it.role!!.good }.map { it.id }.take(size - count)
        return (evil + good).also { assertEquals(size, it.size) }
    }

    // ------------------------------------------------------------ setup

    @Test
    fun `setup table matches the rules for every player count`() {
        val expected = mapOf(
            5 to Triple(3, 2, listOf(2, 3, 2, 3, 3)),
            6 to Triple(4, 2, listOf(2, 3, 4, 3, 4)),
            7 to Triple(4, 3, listOf(2, 3, 3, 4, 4)),
            8 to Triple(5, 3, listOf(3, 4, 4, 5, 5)),
            9 to Triple(6, 3, listOf(3, 4, 4, 5, 5)),
            10 to Triple(6, 4, listOf(3, 4, 4, 5, 5)),
        )
        for ((n, e) in expected) {
            val s = Setup.forPlayers(n)
            assertEquals(e.first, s.good, "good@$n")
            assertEquals(e.second, s.evil, "evil@$n")
            assertEquals(e.third, s.questSizes, "sizes@$n")
            for (q in 0 until 5) assertEquals(if (n >= 7 && q == 3) 2 else 1, s.failsRequired(q), "fails@$n q$q")
        }
    }

    @Test
    fun `roles are dealt correctly for 5 to 10 players`() {
        for (n in 5..10) for (seed in 1..5) {
            val g = started(n, seed)
            val roles = g.players.map { it.role!! }
            assertEquals(1, roles.count { it == Role.MERLIN })
            assertEquals(1, roles.count { it == Role.PERCIVAL })
            assertEquals(1, roles.count { it == Role.MORGANA })
            assertEquals(1, roles.count { it == Role.ASSASSIN })
            assertEquals(Setup.forPlayers(n).good, roles.count { it.good })
            assertEquals(Setup.forPlayers(n).evil, roles.count { !it.good })
            assertEquals(n >= 7, g.viewFor("p0").ladyEnabled)
        }
    }

    @Test
    fun `cannot start with too few or too many players`() {
        val g = lobby(4)
        assertFailsWith<IllegalActionException> { g.start("p0") }
        val full = lobby(10)
        assertFailsWith<IllegalActionException> { full.addPlayer("x", "Extra") }
        assertFailsWith<IllegalActionException> { lobby(5).start("p1") } // not host
        assertFailsWith<IllegalActionException> { lobby(5).addPlayer("dup", "player 1") } // duplicate name, case-insensitive
        assertFailsWith<IllegalActionException> { lobby(5).addPlayer("blank", "   ") }
    }

    @Test
    fun `host can kick in lobby only and seats are compacted`() {
        val g = lobby(6)
        assertFailsWith<IllegalActionException> { g.kick("p1", "p2") }
        assertFailsWith<IllegalActionException> { g.kick("p0", "p0") }
        g.kick("p0", "p2")
        assertEquals(listOf(0, 1, 2, 3, 4), g.players.map { it.seat })
        assertEquals(listOf("p0", "p1", "p3", "p4", "p5"), g.players.map { it.id })
        g.start("p0")
        assertFailsWith<IllegalActionException> { g.kick("p0", "p1") }
    }

    // --------------------------------------------------- role knowledge

    @Test
    fun `role knowledge is exactly what the rules allow`() {
        for (n in 5..10) {
            val g = started(n, seed = n)
            val evil = g.players.filter { !it.role!!.good }.map { it.id }.toSet()
            for (p in g.players) {
                val v = g.viewFor(p.id).you
                assertEquals(p.role, v.role)
                assertEquals(p.role!!.loyalty, v.loyalty)
                when (p.role) {
                    Role.MERLIN -> {
                        assertEquals(evil, v.knownEvil.toSet())
                        assertTrue(v.merlinOrMorgana.isEmpty()); assertTrue(v.evilTeammates.isEmpty())
                    }
                    Role.PERCIVAL -> {
                        val expected = setOf(g.byRole(Role.MERLIN).id, g.byRole(Role.MORGANA).id)
                        assertEquals(expected, v.merlinOrMorgana.toSet())
                        assertTrue(v.knownEvil.isEmpty()); assertTrue(v.evilTeammates.isEmpty())
                    }
                    Role.LOYAL_SERVANT -> {
                        assertTrue(v.knownEvil.isEmpty()); assertTrue(v.merlinOrMorgana.isEmpty()); assertTrue(v.evilTeammates.isEmpty())
                    }
                    else -> {
                        assertEquals(evil - p.id, v.evilTeammates.toSet())
                        assertTrue(v.knownEvil.isEmpty()); assertTrue(v.merlinOrMorgana.isEmpty())
                    }
                }
                val good = p.role!!.good
                assertEquals(if (good) listOf(QuestCard.SUCCESS) else listOf(QuestCard.SUCCESS, QuestCard.FAIL), v.allowedQuestCards)
            }
        }
    }

    @Test
    fun `lobby view contains no roles`() {
        val g = lobby(5)
        g.players.forEach {
            val v = g.viewFor(it.id)
            assertNull(v.you.role); assertNull(v.you.loyalty); assertNull(v.gameOver)
            assertEquals(Phase.LOBBY, v.phase)
        }
    }

    // ----------------------------------------------------- leadership

    @Test
    fun `leader rotates in seat order and first leader is random`() {
        val firstLeaders = (1..40).map { started(5, seed = it).leader().seat }.toSet()
        assertTrue(firstLeaders.size > 1, "first leader should vary")

        val g = started(5)
        val first = g.leader().seat
        g.proposeTeam(g.leader().id, g.players.map { it.id }.take(2))
        g.players.forEach { g.vote(it.id, Vote.REJECT) }
        assertEquals((first + 1) % 5, g.leader().seat)
        assertEquals(1, g.currentRejections)
    }

    @Test
    fun `lady starts with the player before the first leader`() {
        for (seed in 1..10) {
            val g = started(7, seed)
            val n = 7
            assertEquals(g.players[(g.leader().seat - 1 + n) % n].id, g.currentLadyHolderId)
        }
    }

    // ----------------------------------------------------- team & votes

    @Test
    fun `team proposals are validated`() {
        val g = started(5)
        val leader = g.leader().id
        val other = g.players.first { it.id != leader }.id
        assertFailsWith<IllegalActionException> { g.proposeTeam(other, listOf("p0", "p1")) }
        assertFailsWith<IllegalActionException> { g.proposeTeam(leader, listOf("p0")) }
        assertFailsWith<IllegalActionException> { g.proposeTeam(leader, listOf("p0", "p1", "p2")) }
        assertFailsWith<IllegalActionException> { g.proposeTeam(leader, listOf("p0", "p0")) }
        assertFailsWith<IllegalActionException> { g.proposeTeam(leader, listOf("p0", "nobody")) }
        assertFailsWith<IllegalActionException> { g.vote("p0", Vote.APPROVE) } // not voting yet
        g.proposeTeam(leader, listOf("p1", "p0"))
        assertEquals(listOf("p0", "p1"), g.currentProposedTeam) // seat order
        assertEquals(Phase.TEAM_VOTE, g.phase)
    }

    @Test
    fun `votes are hidden until everyone voted then revealed with names`() {
        val g = started(5)
        g.proposeTeam(g.leader().id, listOf("p0", "p1"))
        g.vote("p0", Vote.APPROVE)
        assertFailsWith<IllegalActionException> { g.vote("p0", Vote.REJECT) }
        val v = g.viewFor("p1")
        assertEquals(listOf("p0"), v.votedIds)
        assertTrue(v.voteHistory.isEmpty())
        assertNull(v.you.yourVote)
        assertEquals(Vote.APPROVE, g.viewFor("p0").you.yourVote)
        g.vote("p1", Vote.APPROVE); g.vote("p2", Vote.APPROVE); g.vote("p3", Vote.REJECT); g.vote("p4", Vote.REJECT)
        assertEquals(Phase.QUEST, g.phase)
        val rec = g.viewFor("p4").voteHistory.single()
        assertEquals(mapOf("p0" to Vote.APPROVE, "p1" to Vote.APPROVE, "p2" to Vote.APPROVE, "p3" to Vote.REJECT, "p4" to Vote.REJECT), rec.votes)
        assertTrue(rec.approved)
    }

    @Test
    fun `tie vote rejects`() {
        val g = started(6)
        g.proposeTeam(g.leader().id, listOf("p0", "p1"))
        listOf("p0", "p1", "p2").forEach { g.vote(it, Vote.APPROVE) }
        listOf("p3", "p4", "p5").forEach { g.vote(it, Vote.REJECT) }
        assertEquals(Phase.TEAM_SELECTION, g.phase)
        assertEquals(1, g.currentRejections)
    }

    @Test
    fun `five rejections in a row make evil win`() {
        val g = started(5)
        repeat(4) {
            g.proposeTeam(g.leader().id, listOf("p0", "p1"))
            g.players.forEach { p -> g.vote(p.id, Vote.REJECT) }
            assertEquals(Phase.TEAM_SELECTION, g.phase)
        }
        assertEquals(4, g.currentRejections)
        g.proposeTeam(g.leader().id, listOf("p0", "p1"))
        g.players.forEach { p -> g.vote(p.id, Vote.REJECT) }
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(Winner.EVIL, g.result!!.first)
    }

    @Test
    fun `rejection counter resets after a quest`() {
        val g = started(5)
        g.proposeTeam(g.leader().id, listOf("p0", "p1"))
        g.players.forEach { p -> g.vote(p.id, Vote.REJECT) }
        val team = g.approveTeam()
        g.playQuest(team, 0)
        assertEquals(0, g.currentRejections)
        assertEquals(2, g.currentQuestNumber)
    }

    // ----------------------------------------------------------- quests

    @Test
    fun `good players can only play success and only team members play`() {
        val g = started(5)
        val team = g.approveTeam(g.teamWithEvil(1))
        val good = g.player(team.first { g.player(it).role!!.good })
        val evil = g.player(team.first { !g.player(it).role!!.good })
        val outsider = g.players.first { it.id !in team }
        assertFailsWith<IllegalActionException> { g.playQuestCard(good.id, QuestCard.FAIL) }
        assertFailsWith<IllegalActionException> { g.playQuestCard(outsider.id, QuestCard.SUCCESS) }
        g.playQuestCard(good.id, QuestCard.SUCCESS)
        assertFailsWith<IllegalActionException> { g.playQuestCard(good.id, QuestCard.SUCCESS) }
        assertEquals(listOf(good.id), g.viewFor(outsider.id).playedIds)
        assertNull(g.viewFor(outsider.id).you.yourQuestCard)
        g.playQuestCard(evil.id, QuestCard.FAIL)
        val rec = g.questRecords.single()
        assertEquals(1, rec.fails); assertEquals(1, rec.successes); assertFalse(rec.succeeded)
    }

    @Test
    fun `quest 4 needs two fails at 7 plus players`() {
        for (n in 7..10) {
            val g = started(n, seed = n)
            // Quests 1-3: alternate so no side reaches 3 before quest 4.
            g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)   // fail
            g.playQuest(g.approveTeam(), 0)                    // success (team may include evil playing success)
            g.playQuest(g.approveTeam(), 0)                    // success
            assertEquals(4, g.currentQuestNumber)
            g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)   // one fail is not enough
            val q4 = g.questRecords.last()
            assertEquals(4, q4.quest); assertEquals(2, q4.failsRequired); assertTrue(q4.succeeded, "n=$n")
            assertEquals(Phase.ASSASSINATION, g.phase) // 3 successes
        }
        for (n in 7..10) {
            val g = started(n, seed = n + 100)
            g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
            g.playQuest(g.approveTeam(), 0)
            g.playQuest(g.approveTeam(), 0)
            g.playQuest(g.approveTeam(g.teamWithEvil(2)), 2)
            assertFalse(g.questRecords.last().succeeded, "two fails must fail quest 4 at n=$n")
        }
        // At 5-6 players one fail is enough on quest 4.
        val g = started(5)
        g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
        g.playQuest(g.approveTeam(), 0)
        g.playQuest(g.approveTeam(), 0)
        g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
        assertFalse(g.questRecords.last().succeeded)
        assertEquals(1, g.questRecords.last().failsRequired)
    }

    @Test
    fun `three failed quests make evil win`() {
        val g = started(5)
        repeat(3) { g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1) }
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(Winner.EVIL, g.result!!.first)
        val over = g.viewFor("p0").gameOver!!
        assertEquals(g.players.associate { it.id to it.role!! }, over.roles)
    }

    // ----------------------------------------------------- assassination

    @Test
    fun `assassination decides the game after three successes`() {
        for (seed in 1..6) {
            val g = started(5, seed)
            repeat(3) { g.playQuest(g.approveTeam(), 0) }
            assertEquals(Phase.ASSASSINATION, g.phase)
            val assassin = g.byRole(Role.ASSASSIN)
            val merlin = g.byRole(Role.MERLIN)
            val morgana = g.byRole(Role.MORGANA)
            assertFailsWith<IllegalActionException> { g.assassinate(morgana.id, merlin.id) }
            assertFailsWith<IllegalActionException> { g.assassinate(merlin.id, merlin.id) }
            assertFailsWith<IllegalActionException> { g.assassinate(assassin.id, assassin.id) }
            assertEquals(PendingAction.ASSASSINATE, g.viewFor(assassin.id).you.pendingAction)
            assertEquals(PendingAction.WAIT, g.viewFor(merlin.id).you.pendingAction)
            if (seed % 2 == 0) {
                g.assassinate(assassin.id, merlin.id)
                assertEquals(Winner.EVIL, g.result!!.first)
            } else {
                val notMerlin = g.players.first { it.role == Role.LOYAL_SERVANT || it.role == Role.PERCIVAL }
                g.assassinate(assassin.id, notMerlin.id)
                assertEquals(Winner.GOOD, g.result!!.first)
            }
            assertEquals(Phase.GAME_OVER, g.phase)
            assertNotNull(g.viewFor("p0").gameOver!!.assassinTargetId)
        }
    }

    // ------------------------------------------------------ lady of lake

    @Test
    fun `lady of the lake default and toggle`() {
        assertFalse(started(5).viewFor("p0").ladyEnabled)
        assertFalse(started(6).viewFor("p0").ladyEnabled)
        assertTrue(started(7).viewFor("p0").ladyEnabled)
        assertTrue(started(5, lady = true).viewFor("p0").ladyEnabled)
        assertFalse(started(8, lady = false).viewFor("p0").ladyEnabled)
        assertNull(started(5).currentLadyHolderId)
        assertFailsWith<IllegalActionException> { lobby(5).setOptions("p1", GameOptions(true)) }
    }

    @Test
    fun `lady runs after quests 2 3 4, reveals loyalty only, never repeats targets`() {
        val g = started(5, seed = 3, lady = true)
        val visited = mutableListOf(g.currentLadyHolderId!!)

        // Quest 1: no lady.
        g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
        assertEquals(Phase.TEAM_SELECTION, g.phase)

        // Quest 2: lady.
        g.playQuest(g.approveTeam(), 0)
        assertEquals(Phase.LADY_PICK, g.phase)
        var holder = g.currentLadyHolderId!!
        assertEquals(PendingAction.LADY_PICK, g.viewFor(holder).you.pendingAction)
        val stranger = g.players.first { it.id != holder }.id
        assertFailsWith<IllegalActionException> { g.ladyExamine(stranger, g.ladyTargets().first()) }
        assertFailsWith<IllegalActionException> { g.ladyExamine(holder, holder) }
        assertFailsWith<IllegalActionException> { g.ladyConfirm(holder) }   // nothing to confirm yet
        assertFailsWith<IllegalActionException> { g.proposeTeam(g.leader().id, listOf("p0", "p1")) } // wrong phase
        var target = g.ladyTargets().first()
        g.ladyExamine(holder, target)
        assertEquals(Phase.LADY_RESULT, g.phase)
        val res = g.viewFor(holder).you.yourLadyResults.single()
        assertEquals(target, res.targetId)
        assertEquals(g.player(target).role!!.loyalty, res.loyalty)
        assertEquals(2, res.afterQuest)
        // Nobody else sees the loyalty; everybody sees the public fact.
        g.players.filter { it.id != holder }.forEach {
            assertTrue(g.viewFor(it.id).you.yourLadyResults.isEmpty())
            assertEquals(holder, g.viewFor(it.id).ladyHistory.single().holderId)
            assertEquals(target, g.viewFor(it.id).ladyHistory.single().targetId)
        }
        assertFailsWith<IllegalActionException> { g.ladyConfirm(stranger) }
        g.ladyConfirm(holder)
        assertEquals(target, g.currentLadyHolderId)
        visited += target
        assertEquals(Phase.TEAM_SELECTION, g.phase)
        assertEquals(3, g.currentQuestNumber)

        // Quest 3: lady again; former holders cannot be examined.
        g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
        assertEquals(Phase.LADY_PICK, g.phase)
        holder = g.currentLadyHolderId!!
        assertEquals(visited.last(), holder)
        for (v in visited) assertFailsWith<IllegalActionException>("should not re-examine $v") { g.ladyExamine(holder, v) }
        assertEquals(g.players.map { it.id }.filter { it !in visited }.toSet(), g.ladyTargets().toSet())
        target = g.ladyTargets().first()
        g.ladyExamine(holder, target)
        g.ladyConfirm(holder)
        visited += target

        // Quest 4: third and last lady.
        g.playQuest(g.approveTeam(), 0)
        assertEquals(Phase.LADY_PICK, g.phase)
        holder = g.currentLadyHolderId!!
        target = g.ladyTargets().first()
        g.ladyExamine(holder, target)
        // The old holder still remembers their own result, nobody else's.
        assertEquals(1, g.viewFor(visited[0]).you.yourLadyResults.size)
        assertEquals(1, g.viewFor(holder).you.yourLadyResults.size)
        g.ladyConfirm(holder)
        assertEquals(5, g.currentQuestNumber)

        // Quest 5: no lady; the game ends.
        g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1)
        assertEquals(Phase.GAME_OVER, g.phase)
        assertEquals(3, g.viewFor("p0").gameOver!!.ladyResults.size)
    }

    @Test
    fun `lady is skipped when the game ends on quest 3`() {
        val g = started(7)
        repeat(3) { g.playQuest(g.approveTeam(), 0) }
        assertEquals(Phase.ASSASSINATION, g.phase)
    }

    @Test
    fun `host can skip a stuck lady result`() {
        val g = started(5, lady = true)
        g.playQuest(g.approveTeam(), 0)
        g.playQuest(g.approveTeam(), 0)
        val holder = g.currentLadyHolderId!!
        val target = g.ladyTargets().first()
        g.ladyExamine(holder, target)
        assertFailsWith<IllegalActionException> { g.forceContinueLady(g.players.first { !it.isHost }.id) }
        g.forceContinueLady("p0")
        assertEquals(target, g.currentLadyHolderId)
        assertEquals(Phase.TEAM_SELECTION, g.phase)
    }

    // ------------------------------------------------- misc / lifecycle

    @Test
    fun `role reveal waits for everyone unless the host forces it`() {
        val g = lobby(5); g.start("p0")
        assertEquals(Phase.ROLE_REVEAL, g.phase)
        g.confirmRole("p1")
        assertEquals(listOf("p1"), g.viewFor("p0").readyIds)
        assertEquals(PendingAction.WAIT, g.viewFor("p1").you.pendingAction)
        assertEquals(PendingAction.CONFIRM_ROLE, g.viewFor("p2").you.pendingAction)
        assertFailsWith<IllegalActionException> { g.proposeTeam(g.leader().id, listOf("p0", "p1")) }
        assertFailsWith<IllegalActionException> { g.forceContinueRoleReveal("p1") }
        g.forceContinueRoleReveal("p0")
        assertEquals(Phase.TEAM_SELECTION, g.phase)
    }

    @Test
    fun `play again keeps players and reshuffles`() {
        val g = started(5)
        repeat(3) { g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1) }
        assertFailsWith<IllegalActionException> { g.playAgain("p1") }
        val before = g.players.map { it.id to it.role }
        g.playAgain("p0")
        assertEquals(Phase.ROLE_REVEAL, g.phase)
        assertContentEquals(before.map { it.first }, g.players.map { it.id })
        assertTrue(g.questRecords.isEmpty()); assertTrue(g.voteRecords.isEmpty())
        assertNull(g.result)
        // Role assignment is random so over a few re-deals something changes.
        var changed = false
        repeat(10) {
            g.players.forEach { p -> g.confirmRole(p.id) }
            repeat(3) { g.playQuest(g.approveTeam(g.teamWithEvil(1)), 1) }
            g.playAgain("p0")
            if (g.players.map { it.id to it.role } != before) changed = true
        }
        assertTrue(changed)
    }

    @Test
    fun `actions in the wrong phase are rejected`() {
        val g = lobby(5)
        assertFailsWith<IllegalActionException> { g.proposeTeam("p0", listOf("p0", "p1")) }
        assertFailsWith<IllegalActionException> { g.vote("p0", Vote.APPROVE) }
        assertFailsWith<IllegalActionException> { g.playQuestCard("p0", QuestCard.SUCCESS) }
        assertFailsWith<IllegalActionException> { g.ladyExamine("p0", "p1") }
        assertFailsWith<IllegalActionException> { g.assassinate("p0", "p1") }
        assertFailsWith<IllegalActionException> { g.confirmRole("p0") }
        assertFailsWith<IllegalActionException> { g.playAgain("p0") }
        assertFailsWith<IllegalActionException> { g.viewFor("ghost") }
        g.start("p0")
        assertFailsWith<IllegalActionException> { g.addPlayer("late", "Late") }
        assertFailsWith<IllegalActionException> { g.start("p0") }
    }

    @Test
    fun `view version increases on every change and status fields are consistent`() {
        val g = started(7)
        val v1 = g.viewFor("p0")
        assertEquals(1, v1.quest)
        assertEquals(listOf(2, 3, 3, 4, 4), v1.questSizes)
        assertEquals(listOf(1, 1, 1, 2, 1), v1.failsRequired)
        assertEquals(g.leader().id, v1.leaderId)
        assertEquals(g.currentLadyHolderId, v1.ladyHolderId)
        g.proposeTeam(g.leader().id, listOf("p0", "p1"))
        val v2 = g.viewFor("p0")
        assertTrue(v2.version > v1.version)
        assertEquals(listOf("p0", "p1"), v2.proposedTeam)
        g.setConnected("p3", false)
        assertFalse(g.viewFor("p0").players.first { it.id == "p3" }.connected)
    }
}
