package ledger.core.history

import ledger.core.fixture.Fixtures
import ledger.core.game.PlayerSkills
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.SqliteGameData
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HistoryStoreTest {
    private val dir = Files.createTempDirectory("history")
    private val game = Fixtures.database(dir.resolve("copy.db"))
    private val gameData = SqliteGameData(game)
    private val history = HistoryStore(dir.resolve("data/history.db"))
    private val at = Instant.parse("2026-01-01T09:20:00Z")

    @Test
    fun `the first recording writes every skill of every player`() {
        assertEquals(3 * Skill.entries.size, history.record(at, gameData.allSkills()))
        assertEquals(SkillValue(experience = 4616, level = 10), history.latest()[1 to Skill.HITS])
    }

    @Test
    fun `nothing changed writes nothing`() {
        history.record(at, gameData.allSkills())
        assertEquals(0, history.record(at + 1.minutes, gameData.allSkills()))
    }

    @Test
    fun `a change writes one row with the full new value`() {
        history.record(at, gameData.allSkills())
        sql("UPDATE experience SET woodcut = 12450 WHERE playerID = 2")
        sql("UPDATE maxstats SET woodcut = 22 WHERE playerID = 2")

        assertEquals(1, history.record(at + 1.minutes, gameData.allSkills()))
        assertEquals(
            listOf(at to SkillValue(0, 1), at + 1.minutes to SkillValue(12450, 22)),
            history.values(2, Skill.WOODCUT),
        )
        assertEquals(SkillValue(12450, 22), history.latest()[2 to Skill.WOODCUT])
    }

    @Test
    fun `compares with what was recorded, not with the last reading`() {
        history.record(at, gameData.allSkills())
        // A reading that was never recorded (say the ledger stopped) doesn't hide a change.
        val unchanged = gameData.allSkills()
        sql("UPDATE experience SET mining = 900 WHERE playerID = 3")
        assertEquals(0, history.record(at + 1.minutes, unchanged))
        assertEquals(1, history.record(at + 2.minutes, gameData.allSkills()))
    }

    @Test
    fun `a new player has every skill recorded`() {
        history.record(at, gameData.allSkills())
        val newcomer = PlayerSkills(playerId = 9, skills = Skill.entries.associateWith { SkillValue(0, 1) })
        assertEquals(Skill.entries.size, history.record(at + 1.minutes, gameData.allSkills() + newcomer))
    }

    @Test
    fun `history survives reopening the file`() {
        history.record(at, gameData.allSkills())
        val reopened = HistoryStore(dir.resolve("data/history.db"))
        assertEquals(0, reopened.record(at + 1.minutes, gameData.allSkills()))
    }

    /** One invented player whose skills are all level 1 with no experience, except those given (stored values). */
    private fun player(id: Int, vararg values: Pair<Skill, SkillValue>) =
        PlayerSkills(id, Skill.entries.associateWith { SkillValue(0, 1) } + values)

    @Test
    fun `nothing recorded has no start and no changes`() {
        assertEquals(null, history.recordedSince())
        assertEquals(emptyList(), history.changes(at, at + 60.minutes))
    }

    @Test
    fun `a change over a period runs from the newest value before it to the newest within it`() {
        history.record(at, listOf(player(1, Skill.WOODCUT to SkillValue(4000, 10))))
        history.record(at + 10.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(8000, 16))))
        history.record(at + 20.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(9000, 17))))
        history.record(at + 30.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(12000, 20))))

        val change = history.changes(at + 15.minutes, at + 25.minutes).single()
        assertEquals(SkillChange(1, Skill.WOODCUT, start = SkillValue(8000, 16), end = SkillValue(9000, 17)), change)
        // As players see it: 9000 / 4 - 8000 / 4.
        assertEquals(250, change.experienceGained)
        assertEquals(1, change.levelsGained)
        assertEquals(at, history.recordedSince())
    }

    @Test
    fun `a period ending on a change includes it, and one starting on it doesn't`() {
        history.record(at, listOf(player(1)))
        history.record(at + 10.minutes, listOf(player(1, Skill.MINING to SkillValue(400, 2))))
        assertEquals(400, history.changes(at, at + 10.minutes).single().end.experience)
        assertEquals(emptyList(), history.changes(at + 10.minutes, at + 20.minutes))
    }

    @Test
    fun `with several players and skills, a change stamped on the start counts as before and one on the end as within`() {
        val start = at + 10.minutes
        val end = at + 20.minutes
        history.record(at, listOf(player(1), player(2)))
        // Stamped exactly on the start: the period's starting values, not gains in it.
        history.record(start, listOf(player(1, Skill.WOODCUT to SkillValue(400, 2)), player(2, Skill.FISHING to SkillValue(800, 3))))
        // Stamped exactly on the end: gains in the period, for two skills of one player and one of the other.
        history.record(
            end,
            listOf(
                player(1, Skill.WOODCUT to SkillValue(1200, 4), Skill.MINING to SkillValue(400, 2)),
                player(2, Skill.FISHING to SkillValue(1600, 5)),
            ),
        )
        // After the end: none of this may show.
        history.record(end + 1.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(9000, 17), Skill.MINING to SkillValue(400, 2)), player(2, Skill.FISHING to SkillValue(1600, 5))))

        assertEquals(
            setOf(
                SkillChange(1, Skill.WOODCUT, start = SkillValue(400, 2), end = SkillValue(1200, 4)),
                SkillChange(1, Skill.MINING, start = SkillValue(0, 1), end = SkillValue(400, 2)),
                SkillChange(2, Skill.FISHING, start = SkillValue(800, 3), end = SkillValue(1600, 5)),
            ),
            history.changes(start, end).toSet(),
        )
        assertEquals(3, history.changes(start, end).size)
    }

    @Test
    fun `a period of no length has no changes, even on a recorded change`() {
        history.record(at, listOf(player(1)))
        history.record(at + 10.minutes, listOf(player(1, Skill.MINING to SkillValue(400, 2))))
        assertEquals(emptyList(), history.changes(at + 10.minutes, at + 10.minutes))
    }

    @Test
    fun `a value that went down is still a change here, and the gains list decides what to show`() {
        history.record(at, listOf(player(1, Skill.WOODCUT to SkillValue(8000, 16))))
        history.record(at + 10.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(6000, 14))))
        val change = history.changes(at, at + 10.minutes).single()
        assertEquals(-500, change.experienceGained)
        assertEquals(-2, change.levelsGained)
    }

    @Test
    fun `a player first recorded inside the period counts from that first value, not from zero`() {
        history.record(at, listOf(player(1)))
        // Recording of player 2 begins at +10 with experience they already had.
        history.record(at + 10.minutes, listOf(player(1), player(2, Skill.FISHING to SkillValue(20000, 30))))
        history.record(at + 20.minutes, listOf(player(1), player(2, Skill.FISHING to SkillValue(20400, 30))))

        assertEquals(
            listOf(SkillChange(2, Skill.FISHING, start = SkillValue(20000, 30), end = SkillValue(20400, 30))),
            history.changes(at, at + 30.minutes),
        )
    }

    @Test
    fun `skills that didn't change in the period are left out`() {
        history.record(at, listOf(player(1, Skill.COOKING to SkillValue(400, 2))))
        history.record(at + 10.minutes, listOf(player(1, Skill.COOKING to SkillValue(800, 3))))
        assertEquals(emptyList(), history.changes(at + 20.minutes, at + 30.minutes))
    }

    @Test
    fun `each player's and skill's change is read from its own rows`() {
        val p1 = { woodcut: SkillValue, fishing: SkillValue -> player(1, Skill.WOODCUT to woodcut, Skill.FISHING to fishing) }
        history.record(at, listOf(p1(SkillValue(4000, 10), SkillValue(100, 1)), player(3)))
        history.record(at + 10.minutes, listOf(p1(SkillValue(4000, 10), SkillValue(200, 2)), player(3)))
        history.record(at + 20.minutes, listOf(p1(SkillValue(5000, 11), SkillValue(200, 2)), player(2, Skill.WOODCUT to SkillValue(7000, 14)), player(3)))
        history.record(at + 30.minutes, listOf(p1(SkillValue(5000, 11), SkillValue(300, 3)), player(2, Skill.WOODCUT to SkillValue(7400, 15)), player(3)))
        // After the period: none of this may show.
        history.record(at + 40.minutes, listOf(p1(SkillValue(9999, 20), SkillValue(300, 3)), player(2, Skill.WOODCUT to SkillValue(7400, 15)), player(3, Skill.MINING to SkillValue(400, 2))))

        val changes = history.changes(at + 15.minutes, at + 35.minutes)
        assertEquals(
            setOf(
                SkillChange(1, Skill.WOODCUT, start = SkillValue(4000, 10), end = SkillValue(5000, 11)),
                SkillChange(1, Skill.FISHING, start = SkillValue(200, 2), end = SkillValue(300, 3)),
                // Player 2 was first recorded inside the period.
                SkillChange(2, Skill.WOODCUT, start = SkillValue(7000, 14), end = SkillValue(7400, 15)),
            ),
            changes.toSet(),
        )
        assertEquals(3, changes.size)
    }

    @Test
    fun `a period before recording began has no changes`() {
        history.record(at, listOf(player(1)))
        history.record(at + 10.minutes, listOf(player(1, Skill.MINING to SkillValue(400, 2))))
        assertEquals(emptyList(), history.changes(at - 60.minutes, at - 30.minutes))
    }

    @Test
    fun `a period running past the newest recording ends at the newest value`() {
        history.record(at, listOf(player(1, Skill.WOODCUT to SkillValue(4000, 10))))
        history.record(at + 10.minutes, listOf(player(1, Skill.WOODCUT to SkillValue(8000, 16))))
        assertEquals(
            listOf(SkillChange(1, Skill.WOODCUT, start = SkillValue(4000, 10), end = SkillValue(8000, 16))),
            history.changes(at + 5.minutes, at + 10_000.minutes),
        )
    }

    @Test
    fun `a period ending before it starts has no changes`() {
        history.record(at, listOf(player(1)))
        history.record(at + 10.minutes, listOf(player(1, Skill.MINING to SkillValue(400, 2))))
        assertEquals(emptyList(), history.changes(at + 20.minutes, at - 10.minutes))
    }

    @Test
    fun `recording nobody writes nothing and changes nothing`() {
        history.record(at, listOf(player(1)))
        assertEquals(0, history.record(at + 1.minutes, emptyList()))
        assertEquals(at, history.recordedSince())
        assertEquals(Skill.entries.size, history.latest().size)
    }

    @Test
    fun `a player no longer in the game keeps their history and gets no new rows`() {
        history.record(at, listOf(player(1, Skill.MINING to SkillValue(400, 2)), player(2)))
        assertEquals(0, history.record(at + 10.minutes, listOf(player(2))))
        assertEquals(listOf(at to SkillValue(400, 2)), history.values(1, Skill.MINING))
        assertEquals(SkillValue(400, 2), history.latest()[1 to Skill.MINING])
    }

    @Test
    fun `a player or skill never recorded has no values`() {
        history.record(at, listOf(player(1)))
        assertEquals(emptyList(), history.values(42, Skill.MINING))
        assertEquals(listOf(at to SkillValue(0, 1)), history.values(1, Skill.MINING))
    }

    @Test
    fun `values come back oldest first, and going back to an earlier value is a change`() {
        history.record(at, listOf(player(1, Skill.FISHING to SkillValue(100, 1))))
        history.record(at + 10.minutes, listOf(player(1, Skill.FISHING to SkillValue(200, 2))))
        history.record(at + 20.minutes, listOf(player(1, Skill.FISHING to SkillValue(100, 1))))
        assertEquals(
            listOf(at to SkillValue(100, 1), at + 10.minutes to SkillValue(200, 2), at + 20.minutes to SkillValue(100, 1)),
            history.values(1, Skill.FISHING),
        )
        // Back to an earlier value is still a change from the newest.
        assertEquals(SkillValue(100, 1), history.latest()[1 to Skill.FISHING])
    }

    @Test
    fun `times keep their milliseconds and lose anything finer`() {
        val precise = Instant.parse("2026-01-01T09:20:00.123456789Z")
        history.record(precise, listOf(player(1)))
        assertEquals(Instant.parse("2026-01-01T09:20:00.123Z"), history.recordedSince())
    }

    @Test
    fun `pages can read while a recording is written`() {
        val players = (1..300).map { player(it) }
        history.record(at, players)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val writing = AtomicBoolean(true)
        val readers = (1..4).map {
            thread {
                while (writing.get()) {
                    runCatching { history.changes(at, at + 60.minutes); history.recordedSince() }.onFailure { errors.add(it) }
                }
            }
        }
        repeat(10) { round ->
            val written = history.record(at + (round + 1).minutes, players.map { player(it.playerId, Skill.MINING to SkillValue(round + 1, 1)) })
            assertEquals(players.size, written)
        }
        writing.set(false)
        readers.forEach { it.join() }
        assertEquals(emptyList(), errors.toList())
    }

    @Test
    fun `changes stamped before the newest recording aren't recorded, and the next later save records them`() {
        history.record(at, listOf(player(1)))
        history.record(at + 10.minutes, listOf(player(1, Skill.MINING to SkillValue(400, 2))))

        // As if the server's database were restored from a backup that kept its old time.
        assertEquals(0, history.record(at + 5.minutes, listOf(player(1, Skill.MINING to SkillValue(800, 3)))))
        assertEquals(listOf(at to SkillValue(0, 1), at + 10.minutes to SkillValue(400, 2)), history.values(1, Skill.MINING))
        assertEquals(SkillValue(400, 2), history.latest()[1 to Skill.MINING])

        assertEquals(1, history.record(at + 20.minutes, listOf(player(1, Skill.MINING to SkillValue(800, 3)))))
        assertEquals(SkillValue(800, 3), history.values(1, Skill.MINING).last().second)
    }

    @Test
    fun `a change stamped at the newest recording's own time isn't recorded, and doesn't fail`() {
        history.record(at, listOf(player(1)))
        assertEquals(0, history.record(at, listOf(player(1, Skill.MINING to SkillValue(400, 2)))))
        assertEquals(listOf(at to SkillValue(0, 1)), history.values(1, Skill.MINING))
    }

    @Test
    fun `unchanged values at the newest recording's time write nothing, as after the ledger restarts`() {
        history.record(at, gameData.allSkills())
        // The first copy after a restart carries the same save time, with nothing new.
        assertEquals(0, HistoryStore(dir.resolve("data/history.db")).record(at, gameData.allSkills()))
        // Nothing changed is never refused, whatever its time.
        assertEquals(0, history.record(at - 10.minutes, gameData.allSkills()))
    }

    @Test
    fun `a data folder that is a file is refused, naming it`() {
        val notAFolder = Files.writeString(dir.resolve("data-file"), "x")
        val error = assertFailsWith<IllegalArgumentException> { HistoryStore(notAFolder.resolve("history.db")) }
        assertEquals("Can't make the folder $notAFolder for the history file: $notAFolder is a file, not a folder", error.message)
    }

    @Test
    fun `a history file that isn't a SQLite database is refused, naming it`() {
        val text = Files.writeString(dir.resolve("history.db"), "notes, not a database")
        val error = assertFailsWith<IllegalArgumentException> { HistoryStore(text) }
        assertEquals("The history file $text isn't a SQLite database", error.message)
        assertEquals("notes, not a database", Files.readString(text))
    }

    @Test
    fun `an empty history file is a new history`() {
        val empty = Files.createFile(dir.resolve("empty.db"))
        assertEquals(Skill.entries.size, HistoryStore(empty).record(at, listOf(player(1))))
    }

    @Test
    fun `a history file in a folder that can't be written is refused, naming it`() {
        val locked = Files.createDirectories(dir.resolve("locked"))
        locked.toFile().setWritable(false)
        try {
            val error = assertFailsWith<IllegalArgumentException> { HistoryStore(locked.resolve("history.db")) }
            assertTrue(error.message!!.startsWith("Can't open or create the history file ${locked.resolve("history.db")}: "), error.message)
        } finally {
            locked.toFile().setWritable(true)
        }
    }

    @Test
    fun `the history file's folder is made if it's missing`() {
        val file = dir.resolve("deeper/still/history.db")
        HistoryStore(file).record(at, listOf(player(1)))
        assertEquals(true, Files.isRegularFile(file))
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$game").use { it.createStatement().executeUpdate(statement) }
    }
}
