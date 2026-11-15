package ledger.admin.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
data class Health(val status: String)

fun Route.healthRoutes() {
    get("/api/health", {
        description = "Whether the ledger is running."
        response {
            code(HttpStatusCode.OK) {
                description = "The ledger is running."
                body<Health>()
            }
        }
    }) {
        call.respond(Health(status = "ok"))
    }
}
