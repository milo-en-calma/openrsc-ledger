package ledger.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import ledger.core.game.SqliteGameData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayersTest {
    private val game = SqliteGameData(testDatabase())

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
    fun `a player's page shows experience as players see it, every skill, and quests`() = adminTest(game) {
        val body = client.get("/api/players/1").bodyAsText()
        assertTrue(body.startsWith("""{"player":{"id":1,"name":"Bramblewick","""), body)
        assertTrue(""""questPoints":7""" in body)
        assertTrue("""{"skill":"hits","name":"Hits","level":10,"fullLevel":10,"experience":1154}""" in body, body)
        assertEquals(20, getJson("/api/players/1")["skills"].jsonArray.size)
        assertTrue(""""quests":[{"id":0,"name":"Black knight's fortress","stage":-1,"complete":true},{"id":3,"name":"Doric's quest","stage":2,"complete":false}]""" in body, body)
    }

    @Test
    fun `an unknown player is not found`() = adminTest(game) {
        assertEquals(HttpStatusCode.NotFound, client.get("/api/players/99").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/players/not-a-number").status)
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
