package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.game.PlayerSummary

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

/** Why the ledger has no data to give yet. */
@Serializable
data class NoData(val message: String)

const val NO_COPY_YET = "The ledger hasn't copied the server's database yet."

/** Answers 503 until the ledger has copied the server's database for the first time. */
fun Route.playerRoutes(lastCopy: () -> CopyRecord?, gameData: GameData) {
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
        val players = gameData.players(call.request.queryParameters["search"])
        call.respond(PlayerList(players.map { it.toListItem() }))
    }
}

private fun PlayerSummary.toListItem() = PlayerListItem(
    id = id,
    name = name,
    combatLevel = combatLevel,
    totalLevel = totalLevel,
    lastLogin = lastLogin?.toString(),
    online = online,
)
