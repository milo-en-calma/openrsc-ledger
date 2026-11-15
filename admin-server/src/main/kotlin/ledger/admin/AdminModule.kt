package ledger.admin

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import ledger.admin.routes.healthRoutes

/** The path the API's OpenAPI spec is served at; the committed `openapi.json` is a copy of it. */
const val OPENAPI_PATH = "/api/openapi.json"

/** Everything the admin console serves: the API, its spec and the built frontend. */
fun Application.adminModule() {
    install(ContentNegotiation) { json() }
    install(OpenApi) {
        info {
            title = "openrsc-ledger admin API"
            version = "0.1.0"
            description = "Read-only stats for an OpenRSC server."
        }
        // Schemas named after the class alone (`Health`, not `ledger.admin.routes.Health`),
        // since the frontend's generated types are named after them.
        schemas {
            generator = SchemaGenerator.kotlinx {
                referencePath = RefType.OPENAPI_SIMPLE
                title = null
            }
        }
        // Only the API; the frontend's catch-all route isn't part of it.
        pathFilter = { _, url -> url.firstOrNull() == "api" }
    }

    routing {
        route(OPENAPI_PATH) { openApi() }
        healthRoutes()

        // The built frontend, bundled under web/ in the jar. Any path that isn't a file
        // gets index.html, so the frontend's own routes survive a page reload.
        staticResources("/", "web") { default("index.html") }
    }
}
