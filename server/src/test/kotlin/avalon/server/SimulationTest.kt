package avalon.server

import avalon.engine.Phase
import avalon.engine.Winner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Full games over real WebSockets: 5-10 bot clients, random choices, random
 * disconnects, many times per player count. After each game every client's
 * message log is audited by [LeakChecker].
 */
class SimulationTest {

    private data class Outcome(val winner: Winner, val quests: Int, val lady: Int)

    private fun playGame(n: Int, seed: Int, ladyOverride: Boolean? = null, chaos: Boolean = true): Outcome = runBlocking {
        val random = Random(seed)
        val server = AvalonServer(port = 0, joinAddress = { "127.0.0.1" })
        server.start()
        val port = server.boundPort
        val scope = CoroutineScope(SupervisorJob())
        val bots = (1..n).map { i ->
            BotClient("Bot $i", port, scope, Random(random.nextInt()), hostKey = if (i == 1) server.hostKey else null)
        }
        try {
            bots.forEach { it.connect() }
            withTimeout(10_000) { while (bots.any { it.view?.players?.size != n }) delay(10) }
            if (ladyOverride != null) {
                bots[0].action("setOptions") { put("ladyOfTheLake", ladyOverride) }
            }
            // The host bot starts the game.
            val diag = {
                "phase=${server.room.game.phase} pending=" + bots.map { "${it.playerId}:${it.view?.you?.pendingAction}@${it.view?.version}" } +
                    " errors=" + bots.map { it.errors }
            }
            try { withTimeout(60_000) {
                var steps = 0
                while (server.room.game.phase != Phase.GAME_OVER) {
                    var acted = false
                    for (b in bots) if (b.act()) acted = true
                    if (chaos && random.nextInt(100) < 4) {
                        val b = bots.random(random)
                        b.dropConnection()
                        delay(20)
                        b.reconnect()
                    }
                    if (!acted) delay(5)
                    if (++steps > 20_000) fail("game did not finish for n=$n seed=$seed, ${diag()}")
                }
            } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { fail("timeout n=$n seed=$seed ${diag()}") }
            // Wait for the final state to reach everyone.
            withTimeout(10_000) { while (bots.any { it.phase != Phase.GAME_OVER }) delay(10) }

            val game = server.room.game
            val checker = LeakChecker(game)
            val problems = bots.flatMap { checker.check(it.playerId!!, it.snapshotReceived()) }
            if (problems.isNotEmpty()) fail("LEAKS (n=$n seed=$seed):\n" + problems.take(20).joinToString("\n"))

            // Sanity: every bot's final view agrees with the server about the winner and roles.
            for (b in bots) {
                val over = assertNotNull(b.view!!.gameOver)
                assertEquals(game.result!!.first, over.winner)
                assertEquals(game.players.associate { it.id to it.role!! }, over.roles)
            }
            // Bots only ever acted on what the server told them; illegal attempts should be rare and never fatal.
            val fatal = bots.flatMap { it.snapshotErrors() }.filter { it.contains("not found") || it.contains("Join first") }
            assertTrue(fatal.isEmpty(), "unexpected fatal errors: $fatal")

            Outcome(game.result!!.first, game.questRecords.size, game.secretLadyHistory.size)
        } finally {
            bots.forEach { it.close() }
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun `random full games for every player count with reconnects and no leaks`() {
        val stats = mutableMapOf<Int, MutableList<Outcome>>()
        for (n in 5..10) {
            repeat(6) { i -> stats.getOrPut(n) { mutableListOf() } += playGame(n, seed = n * 100 + i) }
        }
        for ((n, outcomes) in stats) {
            assertEquals(6, outcomes.size)
            if (n >= 7) assertTrue(outcomes.any { it.lady > 0 }, "Lady of the Lake should happen at n=$n")
        }
        println("Simulation outcomes: " + stats.map { (n, o) -> "$n players: good=${o.count { it.winner == Winner.GOOD }} evil=${o.count { it.winner == Winner.EVIL }}" })
    }

    @Test
    fun `lady of the lake can be enabled at 5 players and disabled at 8`() {
        var withLady = 0
        for (seed in 1..8) if (playGame(5, seed = 500 + seed, ladyOverride = true, chaos = false).lady > 0) withLady++
        assertTrue(withLady > 0, "Lady should be used in some 5-player games when enabled")
        for (seed in 1..3) assertEquals(0, playGame(8, seed = 800 + seed, ladyOverride = false, chaos = false).lady)
    }
}
