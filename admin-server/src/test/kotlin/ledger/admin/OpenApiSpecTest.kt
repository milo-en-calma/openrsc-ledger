package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the committed `openapi.json`, which the frontend's types are generated from, in
 * step with the routes. After changing a route, regenerate it with
 * `./gradlew :admin-server:test -PupdateOpenApi`.
 */
class OpenApiSpecTest {
    private val committed = Path.of("openapi.json")

    @Test
    fun `committed spec matches the routes`() = testApplication {
        application { adminModule(lastCopy = { null }, gameData = NoGameData) }
        startApplication()
        val generated = client.get(OPENAPI_PATH).bodyAsText().trimEnd() + "\n"

        if (System.getProperty("ledger.updateOpenApi").toBoolean()) {
            Files.writeString(committed, generated)
            return@testApplication
        }
        val current = if (Files.exists(committed)) Files.readString(committed) else ""
        assertEquals(
            current,
            generated,
            "openapi.json is out of date; run ./gradlew :admin-server:test -PupdateOpenApi and commit it",
        )
    }
}
