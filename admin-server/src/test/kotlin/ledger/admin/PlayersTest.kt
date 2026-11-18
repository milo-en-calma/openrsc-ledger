package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import ledger.admin.routes.BAD_PLAYER_ID
import ledger.admin.routes.NO_SUCH_PLAYER
import ledger.core.game.SqliteGameData
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayersTest {
    private val copy = testDatabase()
    private val game = SqliteGameData(copy)

    /** Runs [statement] on the test database, for a test that needs it changed. */
    private fun change(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$copy").use { it.createStatement().executeUpdate(statement) }
    }

    @Test
    fun `lists every player`() = adminTest(game) {
        val response = client.get("/api/players")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"players":[""" +
                """{"id":1,"name":"Bramblewick","combatLevel":32,"totalLevel":210,"lastLogin":"2024-10-27T03:33:20Z","online":false},""" +
                """{"id":3,"name":"Oakhollow","combatLevel":14,"totalLevel":95,"lastLogin":"2024-11-07T17:20:00Z","online":true},""" +
                """{"id":2,"name":"Tansy Moss","combatLevel":3,"totalLevel":27,"lastLogin":"2024-07-03T09:55:00Z","online":false}""" +
                """]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `searches by name`() = adminTest(game) {
        assertEquals(listOf("Tansy Moss"), getJson("/api/players?search=moss")["players"].strings("name"))
    }

    @Test
    fun `a search that matches nobody lists no one, and a blank search lists everyone`() = adminTest(game) {
        val response = client.get("/api/players?search=nobody")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"players":[]}""", response.bodyAsText())

        val everyone = listOf("Bramblewick", "Oakhollow", "Tansy Moss")
        assertEquals(everyone, getJson("/api/players?search=")["players"].strings("name"))
        assertEquals(everyone, getJson("/api/players?search=%20%20")["players"].strings("name"))
    }

    @Test
    fun `search takes wildcard characters literally, sent through the URL`() {
        change("UPDATE players SET username = 'Fern_100%' WHERE id = 2")
        adminTest(game) {
            // %25 is a percent sign in a URL.
            assertEquals(emptyList(), getJson("/api/players?search=%25%25")["players"].strings("name"))
            assertEquals(emptyList(), getJson("/api/players?search=k_")["players"].strings("name"))
            assertEquals(listOf("Fern_100%"), getJson("/api/players?search=n_100%25")["players"].strings("name"))
        }
    }

    @Test
    fun `a player who never logged in has a null last login, on the list and their page`() {
        change("UPDATE players SET login_date = 0 WHERE id = 2")
        adminTest(game) {
            val tansy = """{"id":2,"name":"Tansy Moss","combatLevel":3,"totalLevel":27,"lastLogin":null,"online":false}"""
            val list = client.get("/api/players").bodyAsText()
            assertTrue(tansy in list, list)
            val page = client.get("/api/players/2").bodyAsText()
            assertTrue(page.startsWith("""{"player":$tansy,"""), page)
        }
    }

    @Test
    fun `a player's page shows experience as players see it, every skill, and quests`() = adminTest(game) {
        val body = client.get("/api/players/1").bodyAsText()
        assertTrue(body.startsWith("""{"player":{"id":1,"name":"Bramblewick","""), body)
        assertTrue(""""questPoints":7""" in body)
        assertTrue("""{"skill":"hits","name":"Hits","level":10,"fullLevel":10,"experience":1154}""" in body, body)
        assertEquals(20, getJson("/api/players/1")["skills"].jsonArray.size)
        assertTrue(""""quests":[{"id":0,"name":"Black knight's fortress","stage":-1,"complete":true},{"id":3,"name":"Doric's quest","stage":2,"complete":false}]""" in body, body)
    }

    @Test
    fun `a player's skills come in the game's order`() = adminTest(game) {
        val skills = getJson("/api/players/1")["skills"]
        assertEquals(
            listOf(
                "attack", "defense", "strength", "hits", "ranged", "prayer", "magic", "cooking", "woodcut", "fletching",
                "fishing", "firemaking", "crafting", "smithing", "mining", "herblaw", "agility", "thieving", "harvesting", "runecraft",
            ),
            skills.strings("skill"),
        )
        assertEquals("Woodcutting", skills.strings("name")[8])
    }

    @Test
    fun `a boosted or drained skill shows its level now and its full level apart`() {
        // Woodcutting boosted from 30 to 33; Attack drained from 7 to 5.
        change("UPDATE maxstats SET woodcut = 30, attack = 7 WHERE playerID = 1")
        change("UPDATE curstats SET woodcut = 33, attack = 5 WHERE playerID = 1")
        change("UPDATE experience SET woodcut = 53620 WHERE playerID = 1")
        adminTest(game) {
            val body = client.get("/api/players/1").bodyAsText()
            assertTrue("""{"skill":"woodcut","name":"Woodcutting","level":33,"fullLevel":30,"experience":13405}""" in body, body)
            assertTrue("""{"skill":"attack","name":"Attack","level":5,"fullLevel":7,"experience":0}""" in body, body)
        }
    }

    @Test
    fun `an item or quest the game data can't name is listed with a null name`() {
        // Item 9999 and quest 999 are past the end of the game data.
        change("INSERT INTO itemstatuses (itemID, catalogID, amount) VALUES (401, 9999, 3), (402, 9999, 1)")
        change("INSERT INTO invitems (playerID, itemID, slot) VALUES (2, 401, 0)")
        change("INSERT INTO bank (playerID, itemID, slot) VALUES (2, 402, 0)")
        change("INSERT INTO quests (playerID, id, stage) VALUES (2, 999, 4)")
        adminTest(game) {
            val body = client.get("/api/players/2").bodyAsText()
            assertTrue(""""quests":[{"id":999,"name":null,"stage":4,"complete":false}]""" in body, body)
            assertTrue(""""inventory":[{"slot":0,"itemId":9999,"name":null,"amount":3,"noted":false,"worn":false}]""" in body, body)
            assertTrue(""""bank":[{"slot":0,"itemId":9999,"name":null,"amount":1,"noted":false,"worn":false}]""" in body, body)
        }
    }

    @Test
    fun `a player with nothing has no quest points and empty lists, not missing ones`() = adminTest(game) {
        val body = client.get("/api/players/2").bodyAsText()
        assertTrue(""""questPoints":0,""" in body, body)
        assertTrue(body.endsWith(""""quests":[],"equipment":[],"inventory":[],"bank":[]}"""), body)
    }

    @Test
    fun `an id nobody has is not found and says so`() = adminTest(game) {
        for (id in listOf("99", "0", "2147483647")) {
            val response = client.get("/api/players/$id")
            assertEquals(HttpStatusCode.NotFound, response.status, id)
            assertEquals("""{"message":"$NO_SUCH_PLAYER"}""", response.bodyAsText(), id)
        }
    }

    @Test
    fun `a negative id, one too large for a player id, or one that isn't a number is refused and says why`() = adminTest(game) {
        for (id in listOf("-1", "2147483648", "99999999999999999999", "not-a-number")) {
            val response = client.get("/api/players/$id")
            assertEquals(HttpStatusCode.BadRequest, response.status, id)
            assertEquals("""{"message":"$BAD_PLAYER_ID"}""", response.bodyAsText(), id)
        }
    }

    @Test
    fun `a player's page lists equipment, inventory and bank with item names`() = adminTest(game) {
        val body = client.get("/api/players/1").bodyAsText()
        val mace = """{"slot":0,"itemId":0,"name":"Iron Mace","amount":1,"noted":false,"worn":true}"""
        val shield = """{"slot":1,"itemId":4,"name":"Wooden Shield","amount":1,"noted":false,"worn":true}"""
        assertTrue(""""equipment":[$mace,$shield]""" in body, body)
        assertTrue(
            """"inventory":[$mace,$shield,{"slot":2,"itemId":10,"name":"Coins","amount":1250,"noted":false,"worn":false},""" +
                """{"slot":3,"itemId":156,"name":"Bronze Pickaxe","amount":1,"noted":false,"worn":false}]""" in body,
            body,
        )
        assertTrue(""""bank":[{"slot":0,"itemId":10,"name":"Coins","amount":50000,"noted":false,"worn":false},""" in body, body)
        assertTrue("""{"slot":1,"itemId":14,"name":"Logs","amount":120,"noted":true,"worn":false}""" in body, body)
    }

    @Test
    fun `with the server's equipment tab on, a player's equipment comes from what they have equipped`() =
        adminTest(SqliteGameData(testDatabase(), equipmentTab = true)) {
            val oakhollow = client.get("/api/players/3").bodyAsText()
            val armour = """{"slot":0,"itemId":15,"name":"Leather Armour","amount":1,"noted":false,"worn":true}"""
            val sword = """{"slot":1,"itemId":66,"name":"Bronze Short Sword","amount":1,"noted":false,"worn":true}"""
            assertTrue(""""equipment":[$armour,$sword]""" in oakhollow, oakhollow)
            assertTrue(""""inventory":[{"slot":0,"itemId":20,"name":"""" in oakhollow, oakhollow)

            val bramblewick = client.get("/api/players/1").bodyAsText()
            assertTrue(""""equipment":[]""" in bramblewick, bramblewick)
            assertTrue(""""worn":true""" !in bramblewick, bramblewick)
        }
}
