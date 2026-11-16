package ledger.core.rank

import ledger.core.game.PlayerSkills
import ledger.core.game.PlayerSummary
import ledger.core.game.Skill
import ledger.core.game.SkillValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaderboardsTest {
    private fun player(id: Int, name: String, totalLevel: Int) =
        PlayerSummary(id = id, name = name, combatLevel = 3, totalLevel = totalLevel, lastLogin = null, online = false)

    /** A player whose every skill is level 1 with no experience, except those given (stored experience). */
    private fun skills(id: Int, vararg values: Pair<Skill, SkillValue>) =
        PlayerSkills(id, Skill.entries.associateWith { SkillValue(0, 1) } + values)

    private val players = listOf(player(1, "Wren", 40), player(2, "alder", 40), player(3, "Birch", 55), player(4, "Cedar", 40))
    private val skills = listOf(
        skills(1, Skill.MINING to SkillValue(4000, 10)),
        skills(2, Skill.MINING to SkillValue(4000, 10), Skill.FISHING to SkillValue(400, 2)),
        skills(3, Skill.MINING to SkillValue(400, 2)),
        skills(4, Skill.MINING to SkillValue(8000, 16)),
    )

    @Test
    fun `a skill's board ranks by experience as players see it, ties sharing a rank`() {
        val board = Leaderboards.skill(Skill.MINING, players, skills)
        assertEquals(
            listOf(Triple(1, "Cedar", 2000L), Triple(2, "alder", 1000L), Triple(2, "Wren", 1000L), Triple(4, "Birch", 100L)),
            board.map { Triple(it.rank, it.player.name, it.experience) },
        )
        assertEquals(listOf(16, 10, 10, 2), board.map { it.level })
    }

    @Test
    fun `the overall board ranks by total level, then total experience`() {
        val board = Leaderboards.overall(players, skills)
        // Birch has the highest total level; alder, Wren and Cedar share 40, ordered by experience.
        assertEquals(
            listOf(Triple(1, "Birch", 100L), Triple(2, "Cedar", 2000L), Triple(3, "alder", 1100L), Triple(4, "Wren", 1000L)),
            board.map { Triple(it.rank, it.player.name, it.experience) },
        )
        assertEquals(listOf(55, 40, 40, 40), board.map { it.level })
    }

    @Test
    fun `players tied on both level and experience share an overall rank`() {
        val board = Leaderboards.overall(players.take(2), listOf(skills(1), skills(2)))
        assertEquals(listOf(1 to "alder", 1 to "Wren"), board.map { it.rank to it.player.name })
    }

    @Test
    fun `a player without skills is left off`() {
        assertEquals(listOf("Wren"), Leaderboards.skill(Skill.MINING, players, skills.take(1)).map { it.player.name })
    }

    @Test
    fun `skills for an id with no player are left off both boards`() {
        val board = Leaderboards.skill(Skill.MINING, players.take(1), skills)
        assertEquals(listOf(1 to "Wren"), board.map { it.rank to it.player.name })
        assertEquals(listOf("Wren"), Leaderboards.overall(players.take(1), skills).map { it.player.name })
    }

    @Test
    fun `no players make empty boards`() {
        assertEquals(emptyList(), Leaderboards.skill(Skill.MINING, emptyList(), emptyList()))
        assertEquals(emptyList(), Leaderboards.overall(emptyList(), emptyList()))
        assertEquals(emptyList(), Leaderboards.overall(emptyList(), skills))
    }

    @Test
    fun `the order players and skills come in doesn't change a board`() {
        for (skill in listOf(Skill.MINING, Skill.FISHING)) {
            assertEquals(Leaderboards.skill(skill, players, skills), Leaderboards.skill(skill, players.reversed(), skills.reversed()), skill.name)
        }
        assertEquals(Leaderboards.overall(players, skills), Leaderboards.overall(players.reversed(), skills.reversed()))
    }

    @Test
    fun `tied players are listed by name ignoring case, all at the first one's rank`() {
        val names = listOf("delta", "Bravo", "alpha", "Charlie")
        val tied = names.mapIndexed { index, name -> player(index + 1, name, 40) }
        val board = Leaderboards.skill(Skill.MINING, tied, tied.map { skills(it.id) })
        assertEquals(listOf("alpha", "Bravo", "Charlie", "delta"), board.map { it.player.name })
        assertEquals(listOf(1, 1, 1, 1), board.map { it.rank })
    }

    @Test
    fun `a skill's board ranks by experience alone, so equal experience at different levels is a tie`() {
        val board = Leaderboards.skill(
            Skill.MINING,
            players.take(2),
            listOf(skills(1, Skill.MINING to SkillValue(4000, 12)), skills(2, Skill.MINING to SkillValue(4000, 10))),
        )
        assertEquals(listOf(Triple(1, "alder", 10), Triple(1, "Wren", 12)), board.map { Triple(it.rank, it.player.name, it.level) })
    }

    @Test
    fun `the overall level is the player's total level, not a sum of the skills given`() {
        // Every skill at level 1 would sum to 20; the board shows the stored total level instead.
        val board = Leaderboards.overall(listOf(player(1, "Wren", 123)), listOf(skills(1)))
        assertEquals(listOf(123), board.map { it.level })
    }

    @Test
    fun `overall experience rounds each skill down before summing`() {
        // Stored 3 in two skills: each shows 0, so the total is 0, not 6 / 4 = 1.
        val board = Leaderboards.overall(
            players.take(1),
            listOf(skills(1, Skill.MINING to SkillValue(3, 1), Skill.FISHING to SkillValue(3, 1))),
        )
        assertEquals(listOf(0L), board.map { it.experience })
    }

    @Test
    fun `overall experience past the largest Int is summed without overflowing`() {
        // Stored -1 reads as unsigned, 1073741823 shown in each of 20 skills: 21474836460 in all.
        val most = PlayerSkills(1, Skill.entries.associateWith { SkillValue(-1, 99) })
        val board = Leaderboards.overall(players.take(1), listOf(most))
        assertEquals(listOf(21_474_836_460L), board.map { it.experience })
        assertEquals(listOf(1_073_741_823L), Leaderboards.skill(Skill.MINING, players.take(1), listOf(most)).map { it.experience })
    }
}
