package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.gamedata.GameNames
import ledger.core.rank.COINS
import ledger.core.rank.Economy
import ledger.core.rank.ItemTotal

@Serializable
data class EconomyReport(
    val coins: Holdings,
    /** Every item anyone holds, most-held first. */
    val items: List<ItemHoldings>,
)

/** How much of something is held, and where. */
@Serializable
data class Holdings(val inInventories: Long, val inBanks: Long, val total: Long)

@Serializable
data class ItemHoldings(
    val itemId: Int,
    /** The item's name, or null if the ledger's game data doesn't know the number. */
    val name: String?,
    val held: Holdings,
    /** How many players hold any. */
    val holders: Int,
)

@Serializable
data class ItemHolders(
    val itemId: Int,
    /** The item's name, or null if the ledger's game data doesn't know the number. */
    val name: String?,
    /** How much the whole world holds. */
    val held: Holdings,
    /** Everyone holding any, most first, then by name. */
    val holders: List<ItemHolderRow>,
)

@Serializable
data class ItemHolderRow(
    val playerId: Int,
    val name: String,
    /** Whether the player is one of the server's staff. */
    val staff: Boolean,
    val held: Holdings,
)

const val NO_SUCH_ITEM = "There's no item with that number."

/** The world's economy, and who holds each item. Both answer 503 until the first copy of the server's database. */
fun Route.economyRoutes(lastCopy: () -> CopyRecord?, gameData: GameData, names: GameNames) {
    get("/api/economy", {
        description = "Total coins, and how much of every item is held, across every inventory and bank. Noted items count as the item."
        response {
            code(HttpStatusCode.OK) {
                description = "The economy, from the latest copy of the server's database."
                body<EconomyReport>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        if (lastCopy() == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, NoData(NO_COPY_YET))
            return@get
        }
        val totals = Economy.itemTotals(gameData.allHeldItems())
        val coins = totals.firstOrNull { it.itemId == COINS }?.held() ?: Holdings(0, 0, 0)
        call.respond(
            EconomyReport(
                coins = coins,
                items = totals.map { ItemHoldings(it.itemId, names.item(it.itemId)?.name, it.held(), it.holders) },
            ),
        )
    }

    get("/api/items/{itemId}/holders", {
        description = "Everyone holding one item, in their inventory or bank. Noted items count as the item."
        request {
            pathParameter<Int>("itemId") { description = "The item's number, as the economy lists it." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The item's holders, from the latest copy of the server's database. Empty if nobody holds it."
                body<ItemHolders>()
            }
            code(HttpStatusCode.NotFound) {
                description = "The game data names no item with that number, and nobody holds one."
                body<NoData>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        if (lastCopy() == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, NoData(NO_COPY_YET))
            return@get
        }
        val itemId = call.parameters["itemId"]?.toIntOrNull()
        val holders = itemId?.let { Economy.holders(it, gameData.allHeldItems(), gameData.players()) }.orEmpty()
        val name = itemId?.let { names.item(it)?.name }
        if (itemId == null || (name == null && holders.isEmpty())) {
            call.respond(HttpStatusCode.NotFound, NoData(NO_SUCH_ITEM))
            return@get
        }
        call.respond(
            ItemHolders(
                itemId = itemId,
                name = name,
                held = Holdings(
                    inInventories = holders.sumOf { it.inInventory },
                    inBanks = holders.sumOf { it.inBank },
                    total = holders.sumOf { it.total },
                ),
                holders = holders.map {
                    ItemHolderRow(
                        playerId = it.player.id,
                        name = it.player.name,
                        staff = it.player.staff,
                        held = Holdings(inInventories = it.inInventory, inBanks = it.inBank, total = it.total),
                    )
                },
            ),
        )
    }
}

private fun ItemTotal.held() = Holdings(inInventories = inInventories, inBanks = inBanks, total = total)
