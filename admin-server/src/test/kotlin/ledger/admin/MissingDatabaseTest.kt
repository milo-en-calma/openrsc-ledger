package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import ledger.core.config.CopyConfig
import ledger.core.copy.DatabaseCopier
import ledger.core.copy.DatabaseCopyLoop
import ledger.core.game.SqliteGameData
import ledger.core.history.HistoryReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds

/** The game server's database going missing mid-run, as when the server is stopped and its files moved. */
class MissingDatabaseTest {
    @Test
    fun `keeps answering from the last copy while the server's database is missing`() {
        val live = testDatabase()
        val copier = DatabaseCopier(live, live.resolveSibling("data/server-copy.db"))
        val loop = DatabaseCopyLoop(live, copier, CopyConfig(checkEvery = 15.seconds, atMostEvery = 60.seconds))
        loop.checkOnce()
        val copiedAt = loop.lastCopy?.copiedAt

        Files.delete(live)
        assertFails { loop.checkOnce() }

        testApplication {
            application { adminModule(lastCopy = { loop.lastCopy }, gameData = SqliteGameData(copier.copy), history = HistoryReader.NONE) }
            assertEquals("""{"status":"ok","dataCopiedAt":"$copiedAt"}""", client.get("/api/health").bodyAsText())
            assertEquals(listOf("Bramblewick", "Oakhollow", "Tansy Moss"), getJson("/api/players")["players"].strings("name"))
        }
    }
}
