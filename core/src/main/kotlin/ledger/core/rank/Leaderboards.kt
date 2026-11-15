package ledger.core.rank

import ledger.core.game.PlayerSkills
import ledger.core.game.PlayerSummary
import ledger.core.game.Skill
import ledger.core.game.shownExperience

/** One player's place on a board. Experience is as players see it. */
data class Standing(val rank: Int, val player: PlayerSummary, val level: Int, val experience: Long)

/**
 * Classic ranks: a board per skill, ranked by experience, and an overall board, ranked by
 * total level and then total experience. Tied players share a rank (1, 2, 2, 4) and are
 * listed by name. The classic hiscores broke ties by who got there first, which the
 * ledger can't know for gains made before it began recording history.
 */
object Leaderboards {
    /** Every player ranked by their experience in [skill]. */
    fun skill(skill: Skill, players: List<PlayerSummary>, skills: List<PlayerSkills>): List<Standing> =
        rank(join(players, skills).map { (player, playerSkills) ->
            val value = playerSkills.skills.getValue(skill)
            Unranked(player, level = value.level, experience = shownExperience(value.experience).toLong())
        }, compareByDescending { it.experience })

    /** Every player ranked by total level, then by total experience. */
    fun overall(players: List<PlayerSummary>, skills: List<PlayerSkills>): List<Standing> =
        rank(join(players, skills).map { (player, playerSkills) ->
            val experience = playerSkills.skills.values.sumOf { shownExperience(it.experience).toLong() }
            Unranked(player, level = player.totalLevel, experience = experience)
        }, compareByDescending<Unranked> { it.level }.thenByDescending { it.experience })

    private data class Unranked(val player: PlayerSummary, val level: Int, val experience: Long)

    /** Each player with their skills; a player missing from either list is left out. */
    private fun join(players: List<PlayerSummary>, skills: List<PlayerSkills>): List<Pair<PlayerSummary, PlayerSkills>> {
        val skillsById = skills.associateBy { it.playerId }
        return players.mapNotNull { player -> skillsById[player.id]?.let { player to it } }
    }

    /** Ranks [entries] best first by [order]; entries it calls equal share the rank of the first of them. */
    private fun rank(entries: List<Unranked>, order: Comparator<Unranked>): List<Standing> {
        val sorted = entries.sortedWith(order.thenBy(String.CASE_INSENSITIVE_ORDER) { it.player.name })
        var rank = 0
        return sorted.mapIndexed { index, entry ->
            if (index == 0 || order.compare(sorted[index - 1], entry) != 0) { rank = index + 1 }
            Standing(rank = rank, player = entry.player, level = entry.level, experience = entry.experience)
        }
    }
}
