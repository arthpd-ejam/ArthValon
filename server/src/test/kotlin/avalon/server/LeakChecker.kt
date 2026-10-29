package avalon.server

import avalon.engine.Game
import avalon.engine.Loyalty
import avalon.engine.QuestCard
import avalon.engine.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Inspects every raw message a client received during a game and fails if it
 * contains anything that player was not entitled to know. Ground truth comes
 * from the server-side [Game] after the game ended.
 */
class LeakChecker(private val game: Game) {

    private val roleNames = Role.entries.map { it.name }.toSet()
    private val loyaltyNames = Loyalty.entries.map { it.name }.toSet()
    private val cardNames = QuestCard.entries.map { it.name }.toSet()

    fun check(playerId: String, messages: List<Recorded>): List<String> {
        val problems = mutableListOf<String>()
        val me = game.player(playerId)
        val myRole = me.role!!
        val evil = game.players.filter { !it.role!!.good }.map { it.id }.toSet()
        val merlinMorgana = game.players.filter { it.role == Role.MERLIN || it.role == Role.MORGANA }.map { it.id }.toSet()
        val myCards: Set<String> = (game.secretQuestCardHistory.mapNotNull { it[playerId] } + listOfNotNull(game.secretQuestCards[playerId])).map { it.name }.toSet()
        val myLady = game.secretLadyHistory.filter { it.holderId == playerId }

        for ((index, rec) in messages.withIndex()) {
            val obj = rec.element as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != "state") {
                // joined / error / pong / kicked: must not contain any secret token at all
                walk(obj, "$") { path, v ->
                    if (v in roleNames || v in loyaltyNames || v in cardNames) problems += "[$playerId msg#$index] '$v' at $path in a $type message"
                }
                continue
            }
            val state = obj["state"]!!.jsonObject
            val phase = state["phase"]!!.jsonPrimitive.content
            val gameOver = phase == "GAME_OVER"
            val playerCount = state["playerCount"]!!.jsonPrimitive.content.toInt()
            val inGame = phase != "LOBBY"
            val you = state["you"]!!.jsonObject

            fun bad(msg: String) { problems += "[$playerId msg#$index phase=$phase] $msg" }

            if (!gameOver && state["gameOver"].let { it != null && it !is JsonNull }) bad("gameOver present before the end")

            walk(state, "state") { path, v ->
                when {
                    v in roleNames -> {
                        val ok = path == "state.you.role" && v == myRole.name ||
                            gameOver && path.startsWith("state.gameOver.roles.")
                        if (!ok) bad("role '$v' at $path")
                    }
                    v in loyaltyNames -> {
                        val ok = path == "state.you.loyalty" && v == myRole.loyalty.name ||
                            path.matches(Regex("""state\.you\.yourLadyResults\[\d+]\.loyalty""")) ||
                            gameOver && path.matches(Regex("""state\.gameOver\.ladyResults\[\d+]\.loyalty""")) ||
                            gameOver && path == "state.gameOver.winner"
                        if (!ok) bad("loyalty '$v' at $path")
                    }
                    v in cardNames -> {
                        val ok = path == "state.you.yourQuestCard" && v in myCards ||
                            path.matches(Regex("""state\.you\.allowedQuestCards\[\d+]""")) && (v == "SUCCESS" || !myRole.good)
                        if (!ok) bad("quest card '$v' at $path")
                    }
                }
            }

            // Role knowledge lists must match the rules exactly.
            val knownEvil = you.ids("knownEvil")
            val mm = you.ids("merlinOrMorgana")
            val teammates = you.ids("evilTeammates")
            if (inGame) {
                when (myRole) {
                    Role.MERLIN -> if (knownEvil != evil) bad("Merlin knownEvil=$knownEvil expected $evil")
                    Role.PERCIVAL -> if (mm != merlinMorgana) bad("Percival merlinOrMorgana=$mm expected $merlinMorgana")
                    Role.LOYAL_SERVANT -> {}
                    else -> if (teammates != evil - playerId) bad("evilTeammates=$teammates expected ${evil - playerId}")
                }
                if (myRole != Role.MERLIN && knownEvil.isNotEmpty()) bad("non-Merlin received knownEvil")
                if (myRole != Role.PERCIVAL && mm.isNotEmpty()) bad("non-Percival received merlinOrMorgana")
                if (myRole.good && teammates.isNotEmpty()) bad("good player received evilTeammates")
            } else if (knownEvil.isNotEmpty() || mm.isNotEmpty() || teammates.isNotEmpty() || you["role"] != null && you["role"] !is JsonNull) {
                bad("role information in the lobby")
            }

            // Lady results: only examinations this player made, with the correct loyalty.
            val lady = you["yourLadyResults"] as? JsonArray ?: JsonArray(emptyList())
            for (r in lady) {
                val o = r.jsonObject
                val target = o["targetId"]!!.jsonPrimitive.content
                val loyalty = o["loyalty"]!!.jsonPrimitive.content
                val mine = myLady.firstOrNull { it.targetId == target }
                if (mine == null) bad("lady result for $target that this player never obtained")
                else if (mine.loyalty.name != loyalty) bad("wrong lady loyalty for $target")
                else if (game.player(target).role!!.loyalty.name != loyalty) bad("lady loyalty inconsistent with role")
            }
            // Public lady history must not carry loyalty.
            (state["ladyHistory"] as? JsonArray)?.forEach { if ("loyalty" in it.jsonObject) bad("loyalty in public ladyHistory") }

            // Votes are only visible as complete records.
            (state["voteHistory"] as? JsonArray)?.forEach {
                val votes = it.jsonObject["votes"]!!.jsonObject
                if (votes.size != playerCount) bad("incomplete vote record exposed (${votes.size}/$playerCount)")
            }
            // Quest records are counts only.
            (state["quests"] as? JsonArray)?.forEach { q ->
                val keys = q.jsonObject.keys
                if (keys.any { it.contains("card", ignoreCase = true) }) bad("per-player quest cards in quest record: $keys")
            }
            // During a quest, only the fact that someone played is public.
            if (phase == "QUEST") {
                val card = you["yourQuestCard"]
                if (card != null && card !is JsonNull && playerId !in state.ids("proposedTeam")) bad("quest card for a non team member")
            }
        }
        return problems
    }

    private fun JsonObject.ids(key: String): Set<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    private fun walk(el: JsonElement, path: String, onString: (String, String) -> Unit) {
        when (el) {
            is JsonObject -> el.forEach { (k, v) -> walk(v, "$path.$k", onString) }
            is JsonArray -> el.forEachIndexed { i, v -> walk(v, "$path[$i]", onString) }
            is JsonPrimitive -> if (el.isString) onString(path, el.content)
            JsonNull -> {}
        }
    }
}
