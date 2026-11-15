package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
    fun `an unknown board is not found`() = adminTest(game) {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/leaderboards/juggling").status)
    }
}
