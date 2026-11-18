package ledger.admin.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall

const val BAD_PLAYER_ID = "Give the player's id as a whole number."
const val BAD_ITEM_ID = "Give the item's number as a whole number."

/**
 * An id written in an address, read as a whole number from 0 to 2147483647, or null if it
 * can't be. Null means the link is wrong, not that nobody has the id.
 */
fun wholeNumberOrNull(text: String?): Int? = text?.toIntOrNull()?.takeIf { it >= 0 }

/** What a query name the route doesn't take answers: the name, and the ones it does take. */
fun unknownQueryName(name: String, taken: List<String>): String {
    val listed = taken.map { "`$it`" }
    val takes = when (listed.size) {
        0 -> "none"
        1 -> listed.single()
        else -> listed.dropLast(1).joinToString(", ") + " or " + listed.last()
    }
    return "There's no query value `$name` here; this route takes $takes."
}

/**
 * Answers 400 if the query holds a name not in [taken], so a typo such as `plyer=1` can't
 * quietly pass for no value at all. True if it answered, and the route should stop.
 */
suspend fun RoutingCall.refusedUnknownQuery(vararg taken: String): Boolean {
    val unknown = request.queryParameters.names().firstOrNull { it !in taken } ?: return false
    respond(HttpStatusCode.BadRequest, NoData(unknownQueryName(unknown, taken.toList())))
    return true
}
