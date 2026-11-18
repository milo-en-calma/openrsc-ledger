package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.game.Skill
import ledger.core.rank.Leaderboards
import ledger.core.rank.Standing

/** The overall board's key; every other board is keyed by its skill's column, such as `woodcut`. */
const val OVERALL = "overall"

@Serializable
data class BoardList(val boards: List<Board>)

@Serializable
data class Board(
    /** What to ask `/api/leaderboards/{board}` for: `overall`, or a skill's key such as `woodcut`. */
    val key: String,
    val name: String,
)

@Serializable
data class Leaderboard(val board: Board, val entries: List<LeaderboardEntry>)

@Serializable
data class LeaderboardEntry(
    /** Tied players share a rank: 1, 2, 2, 4. */
    val rank: Int,
    val playerId: Int,
    val name: String,
    /** The skill's full level, or on the overall board the total level. */
    val level: Int,
    /** Experience as players see it, in the skill or in all skills together. */
    val experience: Long,
    /** Whether the player is staff; only ever true when the board was asked for with `staff=true`. */
    val staff: Boolean,
)

const val NO_SUCH_BOARD = "There's no leaderboard by that name."
const val BAD_STAFF = "Give `staff` as true or false."

private val boards = listOf(Board(OVERALL, "Overall")) + Skill.entries.map { Board(it.column, it.displayName) }

/** Classic ranks, per skill and overall. Boards answer 503 until the first copy of the server's database. */
fun Route.leaderboardRoutes(lastCopy: () -> CopyRecord?, gameData: GameData) {
    get("/api/leaderboards", {
        description = "The leaderboards there are: overall, then one per skill."
        response {
            code(HttpStatusCode.OK) {
                description = "The boards."
                body<BoardList>()
            }
        }
    }) {
        if (call.refusedUnknownQuery()) { return@get }
        call.respond(BoardList(boards))
    }

    get("/api/leaderboards/{board}", {
        description = "Every player ranked on one board."
        request {
            pathParameter<String>("board") { description = "`overall`, or a skill's key such as `woodcut`." }
            queryParameter<Boolean>("staff") {
                description = "`true` to rank the server's staff too. They're left out otherwise, and ranks are counted without them."
                required = false
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The board, from the latest copy of the server's database."
                body<Leaderboard>()
            }
            code(HttpStatusCode.NotFound) {
                description = "There's no board by that name."
                body<NoData>()
            }
            code(HttpStatusCode.BadRequest) {
                description = "`staff` was given as something other than true or false."
                body<NoData>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        if (call.refusedUnknownQuery("staff")) { return@get }
        val board = boards.firstOrNull { it.key == call.parameters["board"] }
        if (board == null) {
            call.respond(HttpStatusCode.NotFound, NoData(NO_SUCH_BOARD))
            return@get
        }
        // Unreadable, `staff` is an error, so a typo such as staff=1 can't pass for a board without staff.
        val includeStaff = when (call.request.queryParameters["staff"]) {
            null, "false" -> false
            "true" -> true
            else -> {
                call.respond(HttpStatusCode.BadRequest, NoData(BAD_STAFF))
                return@get
            }
        }
        if (lastCopy() == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, NoData(NO_COPY_YET))
            return@get
        }
        val players = gameData.players().filter { includeStaff || !it.staff }
        val skills = gameData.allSkills()
        val standings = when (board.key) {
            OVERALL -> Leaderboards.overall(players, skills)
            else -> Leaderboards.skill(Skill.entries.first { it.column == board.key }, players, skills)
        }
        call.respond(Leaderboard(board, standings.map { it.toEntry() }))
    }
}

private fun Standing.toEntry() = LeaderboardEntry(
    rank = rank,
    playerId = player.id,
    name = player.name,
    level = level,
    experience = experience,
    staff = player.staff,
)
