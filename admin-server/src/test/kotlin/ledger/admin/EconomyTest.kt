package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import ledger.core.game.SqliteGameData
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EconomyTest {
    private val copy = testDatabase()
    private val game = SqliteGameData(copy)

    /** Runs [statement] on the test database, for a test that needs it changed. */
    private fun change(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$copy").use { it.createStatement().executeUpdate(statement) }
    }

    @Test
    fun `totals coins and lists items most-held first, with names`() = adminTest(game) {
        val body = client.get("/api/economy").bodyAsText()
        assertTrue(body.startsWith("""{"coins":{"inInventories":1250,"inBanks":50000,"total":51250},"items":["""), body)
        assertTrue(
            """"items":[{"itemId":10,"name":"Coins","held":{"inInventories":1250,"inBanks":50000,"total":51250},"holders":1},""" +
                """{"itemId":14,"name":"Logs","held":{"inInventories":0,"inBanks":120,"total":120},"holders":1},""" in body,
            body,
        )
    }

    @Test
    fun `no coins is zero, not missing`() = adminTest(NoGameData) {
        assertEquals("""{"coins":{"inInventories":0,"inBanks":0,"total":0},"items":[]}""", client.get("/api/economy").bodyAsText())
    }

    @Test
    fun `lists who holds an item, with the world's total`() = adminTest(game) {
        assertEquals(
            """{"itemId":10,"name":"Coins","held":{"inInventories":1250,"inBanks":50000,"total":51250},"holders":[""" +
                """{"playerId":1,"name":"Bramblewick","staff":false,"held":{"inInventories":1250,"inBanks":50000,"total":51250}}]}""",
            client.get("/api/items/10/holders").bodyAsText(),
        )
    }

    @Test
    fun `several holders are listed most first, staff marked, and the world's total is theirs added up`() {
        // Tansy Moss banks 60,000 coins; Oakhollow, made staff, carries 700.
        change("INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (402, 10, 60000), (403, 10, 700)")
        change("INSERT INTO bank (playerID, itemID, slot) VALUES (2, 402, 0)")
        change("INSERT INTO invitems (playerID, itemID, slot) VALUES (3, 403, 1)")
        change("UPDATE players SET group_id = 3 WHERE id = 3")
        adminTest(game) {
            assertEquals(
                """{"itemId":10,"name":"Coins","held":{"inInventories":1950,"inBanks":110000,"total":111950},"holders":[""" +
                    """{"playerId":2,"name":"Tansy Moss","staff":false,"held":{"inInventories":0,"inBanks":60000,"total":60000}},""" +
                    """{"playerId":1,"name":"Bramblewick","staff":false,"held":{"inInventories":1250,"inBanks":50000,"total":51250}},""" +
                    """{"playerId":3,"name":"Oakhollow","staff":true,"held":{"inInventories":700,"inBanks":0,"total":700}}]}""",
                client.get("/api/items/10/holders").bodyAsText(),
            )
            // Unlike the leaderboards, the economy counts staff's holdings too.
            assertTrue(
                """{"coins":{"inInventories":1950,"inBanks":110000,"total":111950},"items":[""" +
                    """{"itemId":10,"name":"Coins","held":{"inInventories":1950,"inBanks":110000,"total":111950},"holders":3},""" in
                    client.get("/api/economy").bodyAsText(),
            )
        }
    }

    @Test
    fun `an item the game data can't name is still listed, and its holders found`() {
        // Item 9999 is past the end of the game data.
        change("INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (401, 9999, 3)")
        change("INSERT INTO invitems (playerID, itemID, slot) VALUES (2, 401, 0)")
        adminTest(game) {
            val held = """"held":{"inInventories":3,"inBanks":0,"total":3}"""
            val economy = client.get("/api/economy").bodyAsText()
            assertTrue("""{"itemId":9999,"name":null,$held,"holders":1}""" in economy, economy)

            val response = client.get("/api/items/9999/holders")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                """{"itemId":9999,"name":null,$held,"holders":[{"playerId":2,"name":"Tansy Moss","staff":false,$held}]}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a named item nobody holds has no holders, an unknown one isn't found, and an unreadable number is refused`() = adminTest(game) {
        // Item 1 is an iron short sword, which nobody in the test database holds.
        assertEquals(
            """{"itemId":1,"name":"Iron Short Sword","held":{"inInventories":0,"inBanks":0,"total":0},"holders":[]}""",
            client.get("/api/items/1/holders").bodyAsText(),
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/999999/holders").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/items/-1/holders").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/items/coins/holders").status)
    }
}
