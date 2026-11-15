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

/** The world's economy. Answers 503 until the first copy of the server's database. */
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
}

private fun ItemTotal.held() = Holdings(inInventories = inInventories, inBanks = inBanks, total = total)
