package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import ledger.core.copy.CopyRecord

@Serializable
data class Health(
    val status: String,
    /** When the ledger last copied the server's database (ISO-8601), or null before the first copy. */
    val dataCopiedAt: String?,
)

fun Route.healthRoutes(lastCopy: () -> CopyRecord?) {
    get("/api/health", {
        description = "Whether the ledger is running, and how fresh its data is."
        response {
            code(HttpStatusCode.OK) {
                description = "The ledger is running."
                body<Health>()
            }
        }
    }) {
        if (call.refusedUnknownQuery()) { return@get }
        call.respond(Health(status = "ok", dataCopiedAt = lastCopy()?.copiedAt?.toString()))
    }
}
