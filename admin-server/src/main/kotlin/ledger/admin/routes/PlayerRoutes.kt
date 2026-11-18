package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.game.HeldItem
import ledger.core.game.PlayerDetails
import ledger.core.game.PlayerSummary
import ledger.core.game.shownExperience
import ledger.core.gamedata.GameNames

@Serializable
data class PlayerList(val players: List<PlayerListItem>)

@Serializable
data class PlayerListItem(
    val id: Int,
    val name: String,
    val combatLevel: Int,
    val totalLevel: Int,
    /** When the player last logged in (ISO-8601), or null if they never have. The server records no logout time. */
    val lastLogin: String?,
    /** Whether the server had the player logged in at its last save. */
    val online: Boolean,
)

@Serializable
data class PlayerPage(
    val player: PlayerListItem,
    val questPoints: Int,
    /** All 20 skills, in the game's order. */
    val skills: List<SkillRow>,
    /** Every quest the player has started, by quest number. */
    val quests: List<QuestRow>,
    /**
     * What the player is wearing or wielding. Without the server's equipment tab, the worn
     * items in their inventory, with those slots; with it, numbered from 0 in the order the
     * server stored them.
     */
    val equipment: List<ItemRow>,
    /** What the player carries, by slot; worn items too, without the server's equipment tab. */
    val inventory: List<ItemRow>,
    /** What the player has banked, by slot. */
    val bank: List<ItemRow>,
)

@Serializable
data class ItemRow(
    val slot: Int,
    /** The item's type. */
    val itemId: Int,
    /** The item's name, or null if the ledger's game data doesn't know the number. */
    val name: String?,
    val amount: Int,
    /** Whether it's a bank note standing for [amount] of the item. */
    val noted: Boolean,
    /** Whether the player is wearing or wielding it. */
    val worn: Boolean,
)

@Serializable
data class SkillRow(
    /** The skill's key, its column in the server's tables, such as `woodcut`. */
    val skill: String,
    /** The name the game shows, such as `Woodcutting`. */
    val name: String,
    /** The level now, after boosts and drains. */
    val level: Int,
    /** The level before boosts and drains. */
    val fullLevel: Int,
    /** Experience as players see it in game. */
    val experience: Int,
)

@Serializable
data class QuestRow(
    val id: Int,
    /** The quest's name, or null if the ledger's game data doesn't know the number. */
    val name: String?,
    /** How far through the quest the player is; -1 once complete. */
    val stage: Int,
    val complete: Boolean,
)

/** Why the ledger has no data to give. */
@Serializable
data class NoData(val message: String)

const val NO_COPY_YET = "The ledger hasn't copied the server's database yet."
const val NO_SUCH_PLAYER = "There's no player with that id."
const val BAD_SEARCH = "Give `search` as plain text."

/** The player list and player pages. Both answer 503 until the ledger has copied the server's database for the first time. */
fun Route.playerRoutes(lastCopy: () -> CopyRecord?, gameData: GameData, names: GameNames) {
    get("/api/players", {
        description = "Every player on the server, sorted by name."
        request {
            queryParameter<String>("search") {
                description = "Only players whose name contains this, ignoring case."
                required = false
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The players, from the latest copy of the server's database."
                body<PlayerList>()
            }
            code(HttpStatusCode.BadRequest) {
                description = "`search` holds a control character, such as a NUL or a line break, which no name can."
                body<NoData>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        if (call.refusedUnknownQuery("search")) { return@get }
        // SQLite stops reading a pattern at a NUL, so such a search would quietly list everyone.
        val search = call.request.queryParameters["search"]
        if (search != null && search.any { it.isISOControl() }) {
            call.respond(HttpStatusCode.BadRequest, NoData(BAD_SEARCH))
            return@get
        }
        if (lastCopy() == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, NoData(NO_COPY_YET))
            return@get
        }
        val players = gameData.players(search)
        call.respond(PlayerList(players.map { it.toListItem() }))
    }

    get("/api/players/{id}", {
        description = "One player's skills, quests, equipment, inventory and bank."
        request {
            pathParameter<Int>("id") { description = "The player's id, from the player list." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The player, from the latest copy of the server's database."
                body<PlayerPage>()
            }
            code(HttpStatusCode.BadRequest) {
                description = "The id isn't a whole number from 0 to 2147483647."
                body<NoData>()
            }
            code(HttpStatusCode.NotFound) {
                description = "There's no player with that id, or the player has no maxstats or curstats row, " +
                    "which the server needs to load them too. The answer's message is \"There's no player with that id.\" either way."
                body<NoData>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        if (call.refusedUnknownQuery()) { return@get }
        // Read before the copy is checked: waiting for a copy won't mend a mistyped link.
        val id = wholeNumberOrNull(call.parameters["id"])
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, NoData(BAD_PLAYER_ID))
            return@get
        }
        if (lastCopy() == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, NoData(NO_COPY_YET))
            return@get
        }
        val player = gameData.player(id)
        if (player == null) {
            call.respond(HttpStatusCode.NotFound, NoData(NO_SUCH_PLAYER))
            return@get
        }
        call.respond(player.toPage(names))
    }
}

private fun PlayerDetails.toPage(names: GameNames) = PlayerPage(
    player = summary.toListItem(),
    questPoints = questPoints,
    skills = skills.map { (skill, standing) ->
        SkillRow(
            skill = skill.column,
            name = skill.displayName,
            level = standing.level,
            fullLevel = standing.fullLevel,
            experience = shownExperience(standing.storedExperience),
        )
    },
    quests = quests.map { QuestRow(id = it.questId, name = names.quest(it.questId), stage = it.stage, complete = it.complete) },
    equipment = equipment.map { it.toRow(names) },
    inventory = inventory.map { it.toRow(names) },
    bank = bank.map { it.toRow(names) },
)

private fun HeldItem.toRow(names: GameNames) = ItemRow(
    slot = slot,
    itemId = itemId,
    name = names.item(itemId)?.name,
    amount = amount,
    noted = noted,
    worn = worn,
)

private fun PlayerSummary.toListItem() = PlayerListItem(
    id = id,
    name = name,
    combatLevel = combatLevel,
    totalLevel = totalLevel,
    lastLogin = lastLogin?.toString(),
    online = online,
)
