package ledger.core.game

import ledger.core.fixture.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class SqliteGameDataTest {
    private val dir: Path = Files.createTempDirectory("game-data")
    private val file: Path = Fixtures.database(dir.resolve("copy.db"))
    private val gameData = SqliteGameData(file)

    @Test
    fun `lists every player by name, ignoring case`() {
        assertEquals(listOf("Bramblewick", "Oakhollow", "Tansy Moss"), gameData.players().map { it.name })
    }

    @Test
    fun `reads each player's summary`() {
        val bramblewick = gameData.players().first { it.name == "Bramblewick" }
        assertEquals(
            PlayerSummary(
                id = 1,
                name = "Bramblewick",
                combatLevel = 32,
                totalLevel = 210,
                lastLogin = Instant.fromEpochSeconds(1730000000),
                online = false,
            ),
            bramblewick,
        )
        assertEquals(true, gameData.players().first { it.name == "Oakhollow" }.online)
    }

    @Test
    fun `players in a group below the ordinary players' are staff`() {
        sql("UPDATE players SET group_id = 9 WHERE id = 1")
        sql("UPDATE players SET group_id = 0 WHERE id = 2")
        assertEquals(
            mapOf("Bramblewick" to true, "Tansy Moss" to true, "Oakhollow" to false),
            gameData.players().associate { it.name to it.staff },
        )
    }

    @Test
    fun `a player who never logged in has no last login`() {
        sql("UPDATE players SET login_date = 0 WHERE id = 2")
        assertNull(gameData.players().first { it.name == "Tansy Moss" }.lastLogin)
    }

    @Test
    fun `search matches part of a name, ignoring case and surrounding spaces`() {
        assertEquals(listOf("Oakhollow"), gameData.players("HOLL").map { it.name })
        assertEquals(listOf("Tansy Moss"), gameData.players("  y m ").map { it.name })
        assertEquals(3, gameData.players("  ").size)
        assertEquals(emptyList(), gameData.players("nobody"))
    }

    @Test
    fun `search takes wildcard characters literally`() {
        assertEquals(emptyList(), gameData.players("%"))
        assertEquals(emptyList(), gameData.players("_"))
        sql("UPDATE players SET username = 'Fern_100%' WHERE id = 2")
        assertEquals(listOf("Fern_100%"), gameData.players("n_100%").map { it.name })
    }

    @Test
    fun `reads the newest copy after it is renamed over the old one`() {
        assertEquals(3, gameData.players().size)
        val next = Fixtures.database(dir.resolve("next.db"))
        DriverManager.getConnection("jdbc:sqlite:$next").use { it.createStatement().executeUpdate("DELETE FROM players WHERE id = 3") }
        Files.move(next, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(2, gameData.players().size)
    }

    @Test
    fun `reads one player's skills and quests`() {
        sql("UPDATE experience SET woodcut = 49800 WHERE playerID = 1")
        sql("UPDATE maxstats SET woodcut = 30 WHERE playerID = 1")
        sql("UPDATE curstats SET woodcut = 33 WHERE playerID = 1")

        val player = assertNotNull(gameData.player(1))
        assertEquals("Bramblewick", player.summary.name)
        assertEquals(7, player.questPoints)
        assertEquals(Skill.entries.toSet(), player.skills.keys)
        assertEquals(SkillStanding(level = 33, fullLevel = 30, storedExperience = 49800), player.skills[Skill.WOODCUT])
        assertEquals(SkillStanding(level = 10, fullLevel = 10, storedExperience = 4616), player.skills[Skill.HITS])
        assertEquals(listOf(QuestProgress(0, -1), QuestProgress(3, 2)), player.quests)
        assertEquals(listOf(true, false), player.quests.map { it.complete })
    }

    @Test
    fun `a player with no quests has none`() {
        assertEquals(emptyList(), assertNotNull(gameData.player(2)).quests)
    }

    @Test
    fun `there is no player with an unknown id`() {
        assertNull(gameData.player(99))
    }

    @Test
    fun `experience shown is a quarter of what's stored, rounded down`() {
        // A new player's Hits: 4616 stored, 1154 in game, exactly level 10.
        assertEquals(1154, shownExperience(4616))
        assertEquals(83, shownExperience(335))
        assertEquals(0, shownExperience(3))
        // The client reads the stored number as unsigned.
        assertEquals(0x3fffffff, shownExperience(-1))
    }

    @Test
    fun `reads a player's inventory and bank by slot, with what each item is`() {
        val player = assertNotNull(gameData.player(1))
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 0, amount = 1, noted = false, worn = true),
                HeldItem(slot = 1, itemId = 4, amount = 1, noted = false, worn = true),
                HeldItem(slot = 2, itemId = 10, amount = 1250, noted = false, worn = false),
                HeldItem(slot = 3, itemId = 156, amount = 1, noted = false, worn = false),
            ),
            player.inventory,
        )
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 10, amount = 50000, noted = false, worn = false),
                HeldItem(slot = 1, itemId = 14, amount = 120, noted = true, worn = false),
                HeldItem(slot = 2, itemId = 373, amount = 1, noted = false, worn = false),
            ),
            player.bank,
        )
    }

    @Test
    fun `without an equipment tab, equipment is the inventory's worn items, and the equipped table is ignored`() {
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 0, amount = 1, noted = false, worn = true),
                HeldItem(slot = 1, itemId = 4, amount = 1, noted = false, worn = true),
            ),
            assertNotNull(gameData.player(1)).equipment,
        )
        // Oakhollow's rows in `equipped` count only with the tab on.
        assertEquals(emptyList(), assertNotNull(gameData.player(3)).equipment)
    }

    @Test
    fun `with an equipment tab, equipment comes from the equipped table, every item worn, in the order stored`() {
        val tabbed = SqliteGameData(file, equipmentTab = true)
        val oakhollow = assertNotNull(tabbed.player(3))
        assertEquals(
            listOf(
                HeldItem(slot = 0, itemId = 15, amount = 1, noted = false, worn = true),
                // Its row isn't marked worn, but it's in `equipped`.
                HeldItem(slot = 1, itemId = 66, amount = 1, noted = false, worn = true),
            ),
            oakhollow.equipment,
        )
        assertEquals(listOf(HeldItem(slot = 0, itemId = 20, amount = 1, noted = false, worn = false)), oakhollow.inventory)
    }

    @Test
    fun `with an equipment tab, nothing in the inventory is worn, as the server reads it`() {
        val bramblewick = assertNotNull(SqliteGameData(file, equipmentTab = true).player(1))
        assertEquals(emptyList(), bramblewick.equipment)
        assertEquals(listOf(0, 4, 10, 156), bramblewick.inventory.map { it.itemId })
        assertEquals(listOf(false), bramblewick.inventory.map { it.worn }.distinct())
    }

    @Test
    fun `a player with nothing has an empty inventory, bank and equipment, with or without an equipment tab`() {
        listOf(gameData, SqliteGameData(file, equipmentTab = true)).forEach { data ->
            val player = assertNotNull(data.player(2))
            assertEquals(emptyList(), player.equipment)
            assertEquals(emptyList(), player.inventory)
            assertEquals(emptyList(), player.bank)
        }
    }

    @Test
    fun `reads every held item in every inventory and bank, a noted item as the item itself`() {
        assertEquals(
            listOf(
                HoldingRecord(1, Place.INVENTORY, 0, 1),
                HoldingRecord(1, Place.INVENTORY, 4, 1),
                HoldingRecord(1, Place.INVENTORY, 10, 1250),
                HoldingRecord(1, Place.INVENTORY, 156, 1),
                HoldingRecord(1, Place.BANK, 10, 50000),
                // The 120 logs are banked as a note.
                HoldingRecord(1, Place.BANK, 14, 120),
                HoldingRecord(1, Place.BANK, 373, 1),
                HoldingRecord(3, Place.INVENTORY, 20, 1),
            ),
            gameData.allHeldItems().sortedWith(compareBy({ it.playerId }, { it.place }, { it.itemId })),
        )
    }

    @Test
    fun `with an equipment tab, every held item includes what's equipped, counted as carried`() {
        val tabbed = SqliteGameData(file, equipmentTab = true)
        val oakhollow = tabbed.allHeldItems().filter { it.playerId == 3 }.sortedBy { it.itemId }
        assertEquals(
            listOf(
                HoldingRecord(3, Place.INVENTORY, 15, 1),
                HoldingRecord(3, Place.INVENTORY, 20, 1),
                HoldingRecord(3, Place.INVENTORY, 66, 1),
            ),
            oakhollow,
        )
        // Everyone else's holdings are the same either way.
        assertEquals(
            gameData.allHeldItems().filter { it.playerId != 3 }.sortedWith(compareBy({ it.playerId }, { it.place }, { it.itemId })),
            tabbed.allHeldItems().filter { it.playerId != 3 }.sortedWith(compareBy({ it.playerId }, { it.place }, { it.itemId })),
        )
    }

    @Test
    fun `sorts names ignoring case when the cases differ`() {
        sql("UPDATE players SET username = 'aspen' WHERE id = 2")
        assertEquals(listOf("aspen", "Bramblewick", "Oakhollow"), gameData.players().map { it.name })
    }

    @Test
    fun `search takes a backslash literally`() {
        assertEquals(emptyList(), gameData.players("\\"))
        sql("UPDATE players SET username = 'Fern\\Moss' WHERE id = 2")
        assertEquals(listOf("Fern\\Moss"), gameData.players("n\\m").map { it.name })
    }

    @Test
    fun `empty columns read as nothing earned, never logged in, offline and an ordinary player`() {
        sql("UPDATE players SET combat = NULL, skill_total = NULL, login_date = NULL, online = NULL, quest_points = NULL, group_id = NULL WHERE id = 1")
        val expected = PlayerSummary(id = 1, name = "Bramblewick", combatLevel = 0, totalLevel = 0, lastLogin = null, online = false, staff = false)
        assertEquals(expected, gameData.players().first { it.id == 1 })
        val player = assertNotNull(gameData.player(1))
        assertEquals(expected, player.summary)
        assertEquals(0, player.questPoints)
    }

    @Test
    fun `reads every player's experience and full level in every skill, by player id`() {
        sql("UPDATE experience SET fishing = 2000 WHERE playerID = 3")
        sql("UPDATE maxstats SET fishing = 12 WHERE playerID = 3")
        // A boost changes only the current level, which isn't read here.
        sql("UPDATE curstats SET fishing = 15 WHERE playerID = 3")

        val all = gameData.allSkills()
        assertEquals(listOf(1, 2, 3), all.map { it.playerId })
        all.forEach { assertEquals(Skill.entries.toSet(), it.skills.keys) }
        assertEquals(SkillValue(experience = 2000, level = 12), all[2].skills[Skill.FISHING])
        assertEquals(SkillValue(experience = 0, level = 1), all[1].skills[Skill.FISHING])
        assertEquals(SkillValue(experience = 4616, level = 10), all[0].skills[Skill.HITS])
    }

    @Test
    fun `each skill reads its own column in every stat table`() {
        Skill.entries.forEachIndexed { index, skill ->
            sql("UPDATE experience SET ${skill.column} = ${1000 + index} WHERE playerID = 1")
            sql("UPDATE maxstats SET ${skill.column} = ${20 + index} WHERE playerID = 1")
            sql("UPDATE curstats SET ${skill.column} = ${50 + index} WHERE playerID = 1")
        }

        val player = assertNotNull(gameData.player(1))
        val skills = gameData.allSkills().first { it.playerId == 1 }.skills
        Skill.entries.forEachIndexed { index, skill ->
            assertEquals(SkillStanding(level = 50 + index, fullLevel = 20 + index, storedExperience = 1000 + index), player.skills[skill], skill.name)
            assertEquals(SkillValue(experience = 1000 + index, level = 20 + index), skills[skill], skill.name)
        }
    }

    @Test
    fun `a slot pointing at no item row is left out, as the server's own join leaves it out`() {
        // Bramblewick's pickaxe (inventory slot 3) and lobster (bank slot 2), and Oakhollow's equipped armour.
        sql("DELETE FROM itemstatuses WHERE itemID IN (104, 203, 302)")

        val bramblewick = assertNotNull(gameData.player(1))
        assertEquals(listOf(0, 1, 2), bramblewick.inventory.map { it.slot })
        assertEquals(listOf(0, 1), bramblewick.bank.map { it.slot })
        assertEquals(listOf(0, 4, 10, 10, 14), gameData.allHeldItems().filter { it.playerId == 1 }.map { it.itemId }.sorted())

        val tabbed = SqliteGameData(file, equipmentTab = true)
        assertEquals(listOf(HeldItem(slot = 0, itemId = 66, amount = 1, noted = false, worn = true)), assertNotNull(tabbed.player(3)).equipment)
        assertEquals(listOf(20, 66), tabbed.allHeldItems().filter { it.playerId == 3 }.map { it.itemId }.sorted())
    }

    @Test
    fun `reading everything leaves the copy and its folder untouched`() {
        val bytes = Files.readAllBytes(file)
        val modified = Files.getLastModifiedTime(file)
        val files = dir.listDirectoryEntries().sorted()

        listOf(gameData, SqliteGameData(file, equipmentTab = true)).forEach { data ->
            data.players()
            data.players("oak")
            data.allSkills()
            data.player(1)
            data.player(3)
            data.player(99)
            data.allHeldItems()
            data.playersMissingStats()
        }

        assertContentEquals(bytes, Files.readAllBytes(file))
        assertEquals(modified, Files.getLastModifiedTime(file))
        assertEquals(files, dir.listDirectoryEntries().sorted())
    }

    @Test
    fun `a player with no experience row has no experience in any skill, as the server loads them`() {
        sql("UPDATE maxstats SET woodcut = 5 WHERE playerID = 2")
        sql("DELETE FROM experience WHERE playerID = 2")

        val player = assertNotNull(gameData.player(2))
        assertEquals(SkillStanding(level = 1, fullLevel = 5, storedExperience = 0), player.skills[Skill.WOODCUT])
        assertEquals(listOf(0), player.skills.values.map { it.storedExperience }.distinct())

        val skills = gameData.allSkills().first { it.playerId == 2 }.skills
        assertEquals(SkillValue(experience = 0, level = 5), skills[Skill.WOODCUT])
        assertEquals(listOf(0), skills.values.map { it.experience }.distinct())
        assertEquals(emptyList(), gameData.playersMissingStats())
    }

    @Test
    fun `a player with no maxstats or curstats row has no page, and is named as missing stats`() {
        assertEquals(emptyList(), gameData.playersMissingStats())
        sql("DELETE FROM maxstats WHERE playerID = 1")
        sql("DELETE FROM curstats WHERE playerID = 3")

        assertEquals(listOf(1, 3), gameData.playersMissingStats())
        // Still on the player list, so the log line is the only sign of the gap.
        assertEquals(3, gameData.players().size)
        assertNull(gameData.player(1))
        assertNull(gameData.player(3))
        // Without full levels there's nothing to rank, so only the maxstats gap leaves the leaderboards.
        assertEquals(listOf(2, 3), gameData.allSkills().map { it.playerId })
    }

    @Test
    fun `a quest row with no quest number is left out, and one with no stage is at stage 0`() {
        // The server reads an empty quest number as quest 0, only because JDBC's getInt turns
        // an empty value into 0; it never writes such a row, so it's skipped here.
        sql("INSERT INTO quests (playerID, id, stage) VALUES (1, NULL, 5), (1, 7, NULL)")
        assertEquals(
            listOf(QuestProgress(0, -1), QuestProgress(3, 2), QuestProgress(7, 0)),
            assertNotNull(gameData.player(1)).quests,
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate(statement) }
    }
}
