package ledger.core.history

import ledger.core.game.Skill
import ledger.core.game.SkillValue
import ledger.core.game.shownExperience
import kotlin.time.Instant

/**
 * Everything the screens read from the ledger's own history. Reading goes through this
 * interface, as game data goes through `GameData`, so a test can hand in history of its own.
 */
interface HistoryReader {
    /** When the oldest recorded value was saved, or null if nothing is recorded yet. */
    fun recordedSince(): Instant?

    /**
     * How every player's every skill changed between [from] and [to]: from the newest value
     * at or before [from] to the newest at or before [to]. A skill first recorded after
     * [from] counts from that first value, not from zero, so what a player already had when
     * recording began is never a gain. Skills that didn't change are left out.
     */
    fun changes(from: Instant, to: Instant): List<SkillChange>

    /**
     * [changes], only for [playerId] and [skill] where they're given. A reader that can pick
     * them out as it reads, as [HistoryStore] does, answers without reading the rest.
     */
    fun changes(from: Instant, to: Instant, playerId: Int?, skill: Skill?): List<SkillChange> =
        changes(from, to).filter { (playerId == null || it.playerId == playerId) && (skill == null || it.skill == skill) }

    companion object {
        /** No history at all. */
        val NONE: HistoryReader = object : HistoryReader {
            override fun recordedSince(): Instant? = null
            override fun changes(from: Instant, to: Instant): List<SkillChange> = emptyList()
        }
    }
}

/** How one player's skill changed over a period. Values are as the server stores them. */
data class SkillChange(val playerId: Int, val skill: Skill, val start: SkillValue, val end: SkillValue) {
    /** Experience gained, as players see it. */
    val experienceGained: Int get() = shownExperience(end.experience) - shownExperience(start.experience)

    val levelsGained: Int get() = end.level - start.level
}
