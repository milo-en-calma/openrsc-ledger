package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.game.Skill
import ledger.core.game.shownExperience
import ledger.core.history.HistoryReader
import ledger.core.history.SkillChange
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class GainsReport(
    val from: String,
    val to: String,
    /** When the ledger began recording, or null if nothing is recorded yet. Nothing before it can show. */
    val recordedSince: String?,
    /** Every player's every skill that changed in the period, most experience gained first. */
    val gains: List<SkillGainRow>,
)

@Serializable
data class SkillGainRow(
    val playerId: Int,
    /** The player's name, or null if they're no longer in the server's database. */
    val name: String?,
    /** The skill's key, such as `woodcut`. */
    val skill: String,
    val skillName: String,
    /** Experience gained in the period, as players see it. */
    val experienceGained: Int,
    val levelsGained: Int,
    /** Experience at the end of the period, as players see it. */
    val experience: Int,
    /** The full level at the end of the period. */
    val level: Int,
    /** Whether the player is staff; only ever true when the gains were asked for with `staff=true`. */
    val staff: Boolean,
)

const val BAD_PERIOD = "Give `from` (and optionally `to`) as ISO-8601 times, such as 2026-01-01T00:00:00Z, with `from` no later than `to`."
const val NO_SUCH_SKILL = "There's no skill by that name."
const val BAD_PLAYER = "Give `player` as a player's id, a whole number."

private val skillsByKey = Skill.entries.associateBy { it.column }

/** Experience gained over a period, from the ledger's own history. Answers 503 until the first copy of the server's database, which names the players. */
fun Route.historyRoutes(lastCopy: () -> CopyRecord?, gameData: GameData, history: HistoryReader, clock: Clock = Clock.System) {
    get("/api/history/gains", {
        description = "How much experience each player gained in each skill over a period, from the ledger's recorded history. " +
            "A gain runs from the newest value recorded at or before `from` to the newest at or before `to`; a player first " +
            "recorded inside the period counts from that first value, not from zero. A skill whose experience went down " +
            "(only an admin command can do that) isn't a gain, and is left out."
        request {
            queryParameter<String>("from") { description = "The period's start, an ISO-8601 time such as 2026-01-01T00:00:00Z." }
            queryParameter<String>("to") {
                description = "The period's end, an ISO-8601 time. Now, if left out."
                required = false
            }
            queryParameter<Int>("player") {
                description = "Only this player's gains, by id."
                required = false
            }
            queryParameter<String>("skill") {
                description = "Only gains in this skill, by key, such as `woodcut`."
                required = false
            }
            queryParameter<Boolean>("staff") {
                description = "`true` to include the server's staff, as on the leaderboards. They're left out otherwise. " +
                    "A player no longer in the server's database counts as not staff."
                required = false
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The gains, most experience first. Empty if nothing changed."
                body<GainsReport>()
            }
            code(HttpStatusCode.BadRequest) {
                description = "The period, the player, the skill or `staff` couldn't be read."
                body<NoData>()
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "The ledger hasn't copied the server's database yet."
                body<NoData>()
            }
        }
    }) {
        val parameters = call.request.queryParameters
        val from = parameters["from"]?.let(::instantOrNull)
        // Left out, `to` is now; given but unreadable, it's an error like a bad `from`.
        val to = parameters["to"].let { if (it == null) clock.now() else instantOrNull(it) }
        if (from == null || to == null || from > to) {
            call.respond(HttpStatusCode.BadRequest, NoData(BAD_PERIOD))
            return@get
        }
        val skillKey = parameters["skill"]
        val skill = skillKey?.let { skillsByKey[it] }
        if (skillKey != null && skill == null) {
            call.respond(HttpStatusCode.BadRequest, NoData(NO_SUCH_SKILL))
            return@get
        }
        // Unreadable, `player` is an error like a bad skill, so a typo can't pass for everyone's gains.
        val playerKey = parameters["player"]
        val playerId = playerKey?.toIntOrNull()
        if (playerKey != null && playerId == null) {
            call.respond(HttpStatusCode.BadRequest, NoData(BAD_PLAYER))
            return@get
        }
        // Read as the leaderboards read it, so a typo such as staff=1 can't pass for gains without staff.
        val includeStaff = when (parameters["staff"]) {
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

        val players = gameData.players().associateBy { it.id }
        val gains = history.changes(from, to, playerId, skill)
            .filter { includeStaff || players[it.playerId]?.staff != true }
            // A drop isn't a gain; it stays in the history, but a minus number on a board reads as a bug.
            .filter { it.end.experience >= it.start.experience }
            .sortedWith(compareByDescending<SkillChange> { it.experienceGained }.thenBy { it.playerId }.thenBy { it.skill })
            .map {
                SkillGainRow(
                    playerId = it.playerId,
                    name = players[it.playerId]?.name,
                    skill = it.skill.column,
                    skillName = it.skill.displayName,
                    experienceGained = it.experienceGained,
                    levelsGained = it.levelsGained,
                    experience = shownExperience(it.end.experience),
                    level = it.end.level,
                    staff = players[it.playerId]?.staff == true,
                )
            }
        call.respond(GainsReport(from = from.toString(), to = to.toString(), recordedSince = history.recordedSince()?.toString(), gains = gains))
    }
}

private fun instantOrNull(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
