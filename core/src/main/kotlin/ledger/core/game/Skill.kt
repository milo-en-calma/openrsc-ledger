package ledger.core.game

/**
 * The 20 skills, in the server's column order, each with its column in the `experience`,
 * `maxstats` and `curstats` tables and the name the game shows. Harvesting and Runecraft are only used
 * on worlds that switch them on, but every database has their columns.
 */
enum class Skill(val column: String, val displayName: String) {
    ATTACK("attack", "Attack"),
    DEFENSE("defense", "Defense"),
    STRENGTH("strength", "Strength"),
    HITS("hits", "Hits"),
    RANGED("ranged", "Ranged"),
    PRAYER("prayer", "Prayer"),
    MAGIC("magic", "Magic"),
    COOKING("cooking", "Cooking"),
    WOODCUT("woodcut", "Woodcutting"),
    FLETCHING("fletching", "Fletching"),
    FISHING("fishing", "Fishing"),
    FIREMAKING("firemaking", "Firemaking"),
    CRAFTING("crafting", "Crafting"),
    SMITHING("smithing", "Smithing"),
    MINING("mining", "Mining"),
    HERBLAW("herblaw", "Herblaw"),
    AGILITY("agility", "Agility"),
    THIEVING("thieving", "Thieving"),
    HARVESTING("harvesting", "Harvesting"),
    RUNECRAFT("runecraft", "Runecraft"),
}

/** One skill's experience, as the server stores it, and its full (unboosted, undrained) level. */
data class SkillValue(val experience: Int, val level: Int)

/**
 * The experience players see for [stored], what the server stores. The server stores four
 * times the experience shown in game, and the game client divides what it is sent by 4,
 * rounding down, reading it as an unsigned 32-bit number.
 */
fun shownExperience(stored: Int): Int = (stored.toUInt() / 4u).toInt()

/** Every skill of one player. */
data class PlayerSkills(val playerId: Int, val skills: Map<Skill, SkillValue>)
