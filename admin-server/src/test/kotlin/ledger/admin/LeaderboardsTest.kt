package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import ledger.admin.routes.BAD_STAFF
import ledger.admin.routes.NO_SUCH_BOARD
import ledger.core.game.Skill
import ledger.core.game.SqliteGameData
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaderboardsTest {
    private val copy = testDatabase()
    private val game = SqliteGameData(copy)

    /** Runs [statement] on the test database, for a test that needs it changed. */
    private fun change(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$copy").use { it.createStatement().executeUpdate(statement) }
    }

    @Test
    fun `lists overall first, then every skill`() = adminTest(game) {
        val boards = getJson("/api/leaderboards")["boards"]
        assertEquals(listOf("overall", "attack"), boards.strings("key").take(2))
        assertEquals(listOf("Overall", "Attack"), boards.strings("name").take(2))
        assertEquals(21, boards.jsonArray.size)
    }

    @Test
    fun `a skill's board ranks by experience as players see it`() {
        change("UPDATE experience SET woodcut = 2000 WHERE playerID = 3")
        change("UPDATE maxstats SET woodcut = 5 WHERE playerID = 3")
        adminTest(game) {
            assertEquals(
                """{"board":{"key":"woodcut","name":"Woodcutting"},"entries":[""" +
                    """{"rank":1,"playerId":3,"name":"Oakhollow","level":5,"experience":500,"staff":false},""" +
                    """{"rank":2,"playerId":1,"name":"Bramblewick","level":1,"experience":0,"staff":false},""" +
                    """{"rank":2,"playerId":2,"name":"Tansy Moss","level":1,"experience":0,"staff":false}]}""",
                client.get("/api/leaderboards/woodcut").bodyAsText(),
            )
        }
    }

    @Test
    fun `staff are left off unless asked for, and ranks close up without them`() {
        change("UPDATE players SET group_id = 3 WHERE id = 1")
        adminTest(game) {
            suspend fun standings(path: String) = getJson(path)["entries"].jsonArray.map {
                Triple(it["rank"].jsonPrimitive.int, it["name"].jsonPrimitive.content, it["staff"].jsonPrimitive.boolean)
            }

            assertEquals(
                listOf(Triple(1, "Oakhollow", false), Triple(2, "Tansy Moss", false)),
                standings("/api/leaderboards/overall"),
            )
            assertEquals(
                listOf(Triple(1, "Bramblewick", true), Triple(2, "Oakhollow", false), Triple(3, "Tansy Moss", false)),
                standings("/api/leaderboards/overall?staff=true"),
            )
        }
    }

    @Test
    fun `a skill's board leaves staff off unless asked for, as the overall board does`() {
        change("UPDATE players SET group_id = 3 WHERE id = 3")
        change("UPDATE experience SET woodcut = 2000 WHERE playerID = 3")
        adminTest(game) {
            suspend fun standings(path: String) = getJson(path)["entries"].jsonArray.map {
                Triple(it["rank"].jsonPrimitive.int, it["name"].jsonPrimitive.content, it["staff"].jsonPrimitive.boolean)
            }

            assertEquals(
                listOf(Triple(1, "Bramblewick", false), Triple(1, "Tansy Moss", false)),
                standings("/api/leaderboards/woodcut"),
            )
            assertEquals(
                listOf(Triple(1, "Oakhollow", true), Triple(2, "Bramblewick", false), Triple(2, "Tansy Moss", false)),
                standings("/api/leaderboards/woodcut?staff=true"),
            )
        }
    }

    @Test
    fun `the overall board ranks by total level, with experience summed over every skill as players see it`() {
        // Everyone starts with 4616 stored Hits experience, 1154 as players see it.
        change("UPDATE experience SET mining = 4003 WHERE playerID = 1")
        change("UPDATE experience SET woodcut = 2000 WHERE playerID = 3")
        adminTest(game) {
            assertEquals(
                """{"board":{"key":"overall","name":"Overall"},"entries":[""" +
                    """{"rank":1,"playerId":1,"name":"Bramblewick","level":210,"experience":2154,"staff":false},""" +
                    """{"rank":2,"playerId":3,"name":"Oakhollow","level":95,"experience":1654,"staff":false},""" +
                    """{"rank":3,"playerId":2,"name":"Tansy Moss","level":27,"experience":1154,"staff":false}]}""",
                client.get("/api/leaderboards/overall").bodyAsText(),
            )
        }
    }

    @Test
    fun `every board on the list answers with every player`() = adminTest(game) {
        val keys = getJson("/api/leaderboards")["boards"].strings("key")
        assertEquals(listOf("overall") + Skill.entries.map { it.column }, keys)
        for (key in keys) {
            val board = getJson("/api/leaderboards/$key")
            assertEquals(key, board["board"]["key"].jsonPrimitive.content)
            assertEquals(3, board["entries"].jsonArray.size, key)
        }
    }

    @Test
    fun `an unknown board is not found, and board keys are case-sensitive`() = adminTest(game) {
        for (board in listOf("juggling", "Woodcut", "OVERALL", "woodcutting")) {
            val response = client.get("/api/leaderboards/$board")
            assertEquals(HttpStatusCode.NotFound, response.status, board)
            assertEquals("""{"message":"$NO_SUCH_BOARD"}""", response.bodyAsText(), board)
        }
    }

    @Test
    fun `an unknown board is not found even before the first copy`() = adminTest(game, copied = false) {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/leaderboards/juggling").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/leaderboards/woodcut").status)
    }

    @Test
    fun `staff=false is the same as not asking for staff`() {
        change("UPDATE players SET group_id = 3 WHERE id = 1")
        adminTest(game) {
            val withoutStaff = client.get("/api/leaderboards/overall").bodyAsText()
            assertEquals(withoutStaff, client.get("/api/leaderboards/overall?staff=false").bodyAsText())
            assertEquals(listOf("Oakhollow", "Tansy Moss"), getJson("/api/leaderboards/overall?staff=false")["entries"].strings("name"))
        }
    }

    @Test
    fun `a staff value other than true or false is refused, before and after the first copy`() {
        for (copied in listOf(true, false)) {
            adminTest(game, copied = copied) {
                for (value in listOf("TRUE", "True", "1", "yes", "")) {
                    val response = client.get("/api/leaderboards/overall?staff=$value")
                    assertEquals(HttpStatusCode.BadRequest, response.status, "staff=$value, copied=$copied")
                    assertEquals("""{"message":"$BAD_STAFF"}""", response.bodyAsText(), "staff=$value, copied=$copied")
                }
            }
        }
    }

    @Test
    fun `a server with no players has empty boards`() = adminTest(NoGameData) {
        assertEquals("""{"board":{"key":"overall","name":"Overall"},"entries":[]}""", client.get("/api/leaderboards/overall").bodyAsText())
        assertEquals("""{"board":{"key":"woodcut","name":"Woodcutting"},"entries":[]}""", client.get("/api/leaderboards/woodcut").bodyAsText())
    }
}
