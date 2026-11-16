package avalon.server

import avalon.engine.PendingAction
import avalon.engine.Phase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerProtocolTest {

    private class Table(n: Int) {
        val server = AvalonServer(port = 0, joinAddress = { "127.0.0.1" }).also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val bots = (1..n).map { i -> BotClient("Bot $i", server.boundPort, scope, Random(i), hostKey = if (i == 1) server.hostKey else null) }
        val host get() = bots[0]

        suspend fun joinAll() {
            bots.forEach { it.connect() }
            await { bots.all { it.view?.players?.size == bots.size } }
        }

        suspend fun startAndReveal() {
            host.action("start")
            await { bots.all { it.phase == Phase.ROLE_REVEAL } }
            bots.forEach { it.action("confirmRole") }
            await { bots.all { it.phase == Phase.TEAM_SELECTION } }
        }

        suspend fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) =
            withTimeout(timeoutMs) { while (!cond()) delay(10) }

        suspend fun errorsOf(b: BotClient, after: Int): List<String> { delay(150); return b.errors.drop(after) }

        suspend fun close() { bots.forEach { it.close() }; scope.cancel(); server.stop() }
    }

    @Test
    fun `host key makes the joiner host and start requires five players`() = runBlocking {
        val t = Table(4)
        try {
            t.joinAll()
            assertTrue(t.host.view!!.you.isHost)
            assertFalse(t.bots[1].view!!.you.isHost)
            assertEquals(PendingAction.START_GAME, t.host.view!!.you.pendingAction)
            assertEquals("http://127.0.0.1:${t.server.boundPort}/", t.host.received.filterIsInstance<Recorded>().last().let {
                json.decodeFromJsonElement(StateMessage.serializer(), it.element).server.joinUrl
            })

            val before = t.host.errors.size
            t.host.action("start")
            assertTrue(t.errorsOf(t.host, before).any { it.contains("at least 5") })

            val nb = t.bots[1].errors.size
            t.bots[1].action("start")
            assertTrue(t.errorsOf(t.bots[1], nb).any { it.contains("Only the host") })
            assertEquals(Phase.LOBBY, t.server.room.game.phase)
        } finally { t.close() }
    }

    @Test
    fun `illegal actions over the wire are rejected and leave state untouched`() = runBlocking {
        val t = Table(5)
        try {
            t.joinAll(); t.startAndReveal()
            val game = t.server.room.game
            val leader = t.bots.first { it.playerId == game.currentLeader!!.id }
            val notLeader = t.bots.first { it.playerId != game.currentLeader!!.id }
            val version = game.viewFor(leader.playerId!!).version

            var e = notLeader.errors.size
            notLeader.action("proposeTeam") { putJsonArray("team") { add(JsonPrimitive(leader.playerId!!)); add(JsonPrimitive(notLeader.playerId!!)) } }
            assertTrue(t.errorsOf(notLeader, e).any { it.contains("Only the leader") })

            e = leader.errors.size
            leader.action("proposeTeam") { putJsonArray("team") { add(JsonPrimitive(leader.playerId!!)) } }
            assertTrue(t.errorsOf(leader, e).any { it.contains("exactly 2") })

            e = leader.errors.size
            leader.action("vote") { put("vote", "APPROVE") }
            assertTrue(t.errorsOf(leader, e).any { it.contains("voting") })

            e = leader.errors.size
            leader.action("playQuest") { put("card", "FAIL") }
            assertTrue(t.errorsOf(leader, e).any { it.contains("quest") })

            e = leader.errors.size
            leader.action("vote") { put("vote", "MAYBE") }
            assertTrue(t.errorsOf(leader, e).isNotEmpty())

            e = leader.errors.size
            leader.action("nonsense")
            assertTrue(t.errorsOf(leader, e).any { it.contains("Unknown action") })

            e = leader.errors.size
            leader.send(buildJsonObject { put("type", "garbage") })
            assertTrue(t.errorsOf(leader, e).any { it.contains("Unknown message") })

            assertEquals(Phase.TEAM_SELECTION, game.phase)
            assertEquals(version, game.viewFor(leader.playerId!!).version)

            // A good player cannot play Fail on a quest. Make sure the team contains a good player.
            val goodId = game.players.first { it.role!!.good }.id
            val ids = listOf(goodId) + game.players.map { it.id }.filter { it != goodId }
            leader.action("proposeTeam") { putJsonArray("team") { ids.take(2).forEach { add(JsonPrimitive(it)) } } }
            t.await { game.phase == Phase.TEAM_VOTE }
            t.bots.forEach { it.action("vote") { put("vote", "APPROVE") } }
            t.await { game.phase == Phase.QUEST }
            val goodOnTeam = t.bots.first { it.playerId in ids.take(2) && game.player(it.playerId!!).role!!.good }
            e = goodOnTeam.errors.size
            goodOnTeam.action("playQuest") { put("card", "FAIL") }
            assertTrue(t.errorsOf(goodOnTeam, e).any { it.contains("Good players must play Success") })
            assertTrue(game.secretQuestCards.isEmpty())
        } finally { t.close() }
    }

    @Test
    fun `reconnect restores the exact private view and nobody can join mid game`() = runBlocking {
        val t = Table(6)
        try {
            t.joinAll(); t.startAndReveal()
            val game = t.server.room.game
            val b = t.bots[3]
            val before = game.viewFor(b.playerId!!)

            b.dropConnection()
            t.await { !game.player(b.playerId!!).connected }
            t.await { t.host.view!!.players.first { it.id == b.playerId }.connected == false }

            b.reconnect()
            t.await { game.player(b.playerId!!).connected }
            t.await { b.view != null && b.view!!.players.all { it.connected } }
            val after = b.view!!
            assertEquals(before.you.role, after.you.role)
            assertEquals(before.you.knownEvil, after.you.knownEvil)
            assertEquals(before.you.evilTeammates, after.you.evilTeammates)
            assertEquals(before.phase, after.phase)
            assertEquals(before.leaderId, after.leaderId)
            assertEquals(b.playerId, after.you.id)

            // Fresh browser trying to join a running game is refused...
            val late = BotClient("Late", t.server.boundPort, t.scope, Random(9))
            try {
                runCatching { late.connect() }
                delay(200)
                assertTrue(late.errors.any { it.contains("already running") }, late.errors.toString())
                assertNull(late.playerId)
            } finally { late.close() }

            // ...and a bad token is refused.
            val ghost = BotClient("Ghost", t.server.boundPort, t.scope, Random(10))
            try {
                ghost.token = "not-a-real-token"
                runCatching { ghost.connect(resume = true) }
                delay(200)
                assertTrue(ghost.errors.any { it.contains("not found") })
            } finally { ghost.close() }

            // ...unless the host opens that seat for rejoining by name, while it is disconnected.
            b.dropConnection()
            t.await { !game.player(b.playerId!!).connected }
            t.host.action("allowRejoin") { put("playerId", b.playerId!!) }
            t.await { t.host.received.last().raw.contains("\"rejoinable\":[\"${b.playerId}\"]") }
            val replacement = BotClient("bot 4", t.server.boundPort, t.scope, Random(11)) // same name, different case
            try {
                replacement.connect()
                t.await { replacement.view?.phase == Phase.TEAM_SELECTION }
                assertEquals(b.playerId, replacement.playerId)
                assertEquals(before.you.role, replacement.view!!.you.role)
                // The old token is dead now.
                runCatching { b.reconnect() }
                delay(200)
                assertTrue(b.errors.any { it.contains("not found") })
            } finally { replacement.close() }
        } finally { t.close() }
    }

    @Test
    fun `host can kick in the lobby and the kicked player is disconnected`() = runBlocking {
        val t = Table(6)
        try {
            t.joinAll()
            val victim = t.bots[2]
            t.bots[1].action("kick") { put("playerId", victim.playerId!!) }
            assertTrue(t.errorsOf(t.bots[1], 0).any { it.contains("Only the host") })

            t.host.action("kick") { put("playerId", victim.playerId!!) }
            t.await { t.host.view!!.players.size == 5 }
            t.await { victim.received.any { it.raw.contains("\"type\":\"kicked\"") } }
            assertTrue(t.host.view!!.players.none { it.id == victim.playerId })
            // The kicked token no longer resumes.
            runCatching { victim.reconnect() }
            delay(200)
            assertTrue(victim.errors.any { it.contains("not found") })
            // The name is free again.
            val again = BotClient("Bot 3", t.server.boundPort, t.scope, Random(3))
            try {
                again.connect()
                t.await { t.host.view!!.players.size == 6 }
            } finally { again.close() }
        } finally { t.close() }
    }

    @Test
    fun `host key reclaims the host seat mid game and play again keeps players`() = runBlocking {
        val t = Table(5)
        try {
            t.joinAll(); t.startAndReveal()
            val hostId = t.host.playerId
            t.host.dropConnection()
            t.await { !t.server.room.game.player(hostId!!).connected }
            val newPhone = BotClient("Whatever", t.server.boundPort, t.scope, Random(5), hostKey = t.server.hostKey)
            try {
                newPhone.connect()
                t.await { newPhone.view?.you?.isHost == true }
                assertEquals(hostId, newPhone.playerId)
                assertNotNull(newPhone.view!!.you.role)
            } finally { newPhone.close() }
        } finally { t.close() }
    }

    @Test
    fun `static pages are served with no caching`() = runBlocking {
        val server = AvalonServer(port = 0).also { it.start() }
        try {
            val client = HttpClient(CIO)
            val r = client.get("http://127.0.0.1:${server.boundPort}/")
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("<html", ignoreCase = true))
            assertEquals("no-cache", r.headers["Cache-Control"])
            assertEquals("ok", client.get("http://127.0.0.1:${server.boundPort}/health").bodyAsText())
            // Unknown paths fall back to the single page (so a mistyped path still opens the game).
            assertTrue(client.get("http://127.0.0.1:${server.boundPort}/anything").bodyAsText().contains("<html", ignoreCase = true))
            assertTrue(client.get("http://127.0.0.1:${server.boundPort}/app.js").bodyAsText().contains("WebSocket"))
            client.close()
        } finally { server.stop() }
    }
}
