package ledger.core.game

/**
 * The 20 skills, in the server's column order, each named by its column in the
 * `experience`, `maxstats` and `curstats` tables. Harvesting and Runecraft are only used
 * on worlds that switch them on, but every database has their columns.
 */
enum class Skill(val column: String) {
    ATTACK("attack"),
    DEFENSE("defense"),
    STRENGTH("strength"),
    HITS("hits"),
    RANGED("ranged"),
    PRAYER("prayer"),
    MAGIC("magic"),
    COOKING("cooking"),
    WOODCUT("woodcut"),
    FLETCHING("fletching"),
    FISHING("fishing"),
    FIREMAKING("firemaking"),
    CRAFTING("crafting"),
    SMITHING("smithing"),
    MINING("mining"),
    HERBLAW("herblaw"),
    AGILITY("agility"),
    THIEVING("thieving"),
    HARVESTING("harvesting"),
    RUNECRAFT("runecraft"),
}

/** One skill's experience, as the server stores it, and its full (unboosted, undrained) level. */
data class SkillValue(val experience: Int, val level: Int)

/** Every skill of one player. */
data class PlayerSkills(val playerId: Int, val skills: Map<Skill, SkillValue>)
