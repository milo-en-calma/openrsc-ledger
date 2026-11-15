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
}
