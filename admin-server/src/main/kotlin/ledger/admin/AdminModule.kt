package ledger.admin

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import ledger.admin.routes.NoData
import ledger.admin.routes.economyRoutes
import ledger.admin.routes.healthRoutes
import ledger.admin.routes.historyRoutes
import ledger.admin.routes.leaderboardRoutes
import ledger.admin.routes.playerRoutes
import ledger.core.copy.CopyRecord
import ledger.core.game.GameData
import ledger.core.gamedata.GameNames
import ledger.core.history.HistoryReader
import kotlin.time.Clock

/** The path the API's OpenAPI spec is served at; the committed `openapi.json` is a copy of it. */
const val OPENAPI_PATH = "/api/openapi.json"

const val NO_SUCH_API_ROUTE = "No such API route."

/** What a query value given more than once answers, naming it. */
fun givenTwice(name: String) = "Give `$name` once."

/**
 * Everything the admin console serves: the API, its spec and the built frontend.
 * [lastCopy] gives the newest copy of the server's database, or null before the first;
 * [gameData] reads that copy, [names] gives items and quests their names, and [history]
 * reads the ledger's own record of how skills changed. [clock] ends a period of gains left
 * open, as the copy loop's clock stamps the copies.
 */
fun Application.adminModule(
    lastCopy: () -> CopyRecord?,
    gameData: GameData,
    names: GameNames = GameNames.bundled(),
    history: HistoryReader = HistoryReader.NONE,
    clock: Clock = Clock.System,
) {
    // Every address that answers GET answers HEAD the same way, without the body.
    install(AutoHeadResponse)
    install(ContentNegotiation) { json() }
    install(OpenApi) {
        info {
            title = "openrsc-ledger admin API"
            version = "0.1.0"
            description = "Read-only stats for an OpenRSC server. Errors answer as `{\"message\": \"…\"}`, saying what was wrong; " +
                "any address under `/api` not listed here answers 404 with \"No such API route.\", a query value given more " +
                "than once answers 400, naming it, and so does a query value the route doesn't take. Each route reads the " +
                "values in its address before anything else, so a mistyped one answers 400 even before the first copy. An address that can't be read at all, such as one with a broken %-escape " +
                "or a request line over 4,096 bytes, gets a plain-text 400 from the web server instead."
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

    // A query value given twice is a mistake in the link: say so, rather than quietly use one of them.
    intercept(ApplicationCallPipeline.Plugins) {
        val request = context.request
        if (!request.path().startsWith("/api/")) { return@intercept }
        val doubled = request.queryParameters.names().firstOrNull { request.queryParameters.getAll(it).orEmpty().size > 1 }
        if (doubled != null) {
            context.respond(HttpStatusCode.BadRequest, NoData(givenTwice(doubled)))
            finish()
        }
    }

    routing {
        route(OPENAPI_PATH) { openApi() }
        healthRoutes(lastCopy)
        playerRoutes(lastCopy, gameData, names)
        leaderboardRoutes(lastCopy, gameData)
        economyRoutes(lastCopy, gameData, names)
        historyRoutes(lastCopy, gameData, history, clock)

        // Any other address under /api is a mistake in the link: say so, rather than answer
        // with the frontend below.
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, NoData(NO_SUCH_API_ROUTE)) }
        }

        // The built frontend, bundled under web/ in the jar. Any path that isn't a file
        // gets index.html, so the frontend's own routes survive a page reload.
        staticResources("/", "web") { default("index.html") }
    }
}
