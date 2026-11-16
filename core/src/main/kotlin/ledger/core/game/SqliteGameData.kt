package ledger.core.game

import ledger.core.sqlite.openReadOnly
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Path
import kotlin.time.Instant

/**
 * Reads the game's data from [file], the copy of the server's SQLite database.
 *
 * Every read opens a fresh read-only connection. Each new copy is renamed over the last,
 * and a connection keeps reading the file it opened, so a connection held open would go
 * on reading an old copy.
 *
 * [equipmentTab] says where worn items are, as the server's `want_equipment_tab` does: off,
 * in the inventory marked worn; on, in the `equipped` table, and nothing in the inventory
 * counts as worn, whatever its row says, just as the server reads it.
 */
class SqliteGameData(file: Path, private val equipmentTab: Boolean = false) : GameData {
    // Exposed sets each connection's read-only flag when a transaction starts, and the
    // SQLite driver refuses to change it once open, so the two must agree: read-only.
    private val database = Database.connect(
        getNewConnection = { openReadOnly(file) },
        databaseConfig = DatabaseConfig { defaultReadOnly = true },
    )

    override fun players(search: String?): List<PlayerSummary> = transaction(database) {
        val query = Players.selectAll()
        if (!search.isNullOrBlank()) {
            query.where { Players.username.lowerCase() like containing(search.trim().lowercase()) }
        }
        query.orderBy(Players.username.lowerCase()).map { it.toPlayerSummary() }
    }

    override fun allSkills(): List<PlayerSkills> = transaction(database) {
        Experience.join(MaxStats, JoinType.INNER, Experience.playerId, MaxStats.playerId)
            .selectAll()
            .orderBy(Experience.playerId)
            .map { row ->
                PlayerSkills(
                    playerId = row[Experience.playerId],
                    skills = Skill.entries.associateWith { skill ->
                        SkillValue(experience = row[Experience.skill.getValue(skill)], level = row[MaxStats.skill.getValue(skill)])
                    },
                )
            }
    }

    override fun player(id: Int): PlayerDetails? = transaction(database) {
        val row = Players
            .join(Experience, JoinType.INNER, Players.id, Experience.playerId)
            .join(MaxStats, JoinType.INNER, Players.id, MaxStats.playerId)
            .join(CurStats, JoinType.INNER, Players.id, CurStats.playerId)
            .selectAll()
            .where { Players.id eq id }
            .singleOrNull() ?: return@transaction null

        val quests = Quests.selectAll()
            .where { Quests.playerId eq id }
            .mapNotNull { quest ->
                val questId = quest[Quests.questId] ?: return@mapNotNull null
                QuestProgress(questId = questId, stage = quest[Quests.stage] ?: 0)
            }
            .sortedBy { it.questId }

        val carried = heldItems(InvItems, id)
        val inventory = if (equipmentTab) carried.map { it.copy(worn = false) } else carried

        PlayerDetails(
            summary = row.toPlayerSummary(),
            questPoints = row[Players.questPoints] ?: 0,
            skills = Skill.entries.associateWith { skill ->
                SkillStanding(
                    level = row[CurStats.skill.getValue(skill)],
                    fullLevel = row[MaxStats.skill.getValue(skill)],
                    storedExperience = row[Experience.skill.getValue(skill)],
                )
            },
            quests = quests,
            equipment = if (equipmentTab) equippedItems(id) else inventory.filter { it.worn },
            inventory = inventory,
            bank = heldItems(BankItems, id),
        )
    }

    override fun allHeldItems(): List<HoldingRecord> = transaction(database) {
        val slotted = listOf(InvItems to Place.INVENTORY, BankItems to Place.BANK).flatMap { (table, place) ->
            table.join(ItemStatuses, JoinType.INNER, table.itemStatusId, ItemStatuses.id)
                .select(table.playerId, ItemStatuses.catalogId, ItemStatuses.amount)
                .map { row -> HoldingRecord(row[table.playerId], place, row[ItemStatuses.catalogId], row[ItemStatuses.amount]) }
        }
        if (!equipmentTab) { return@transaction slotted }

        // Worn items are carried, as they are on a server without an equipment tab.
        slotted + Equipped.join(ItemStatuses, JoinType.INNER, Equipped.itemStatusId, ItemStatuses.id)
            .select(Equipped.playerId, ItemStatuses.catalogId, ItemStatuses.amount)
            .map { row -> HoldingRecord(row[Equipped.playerId], Place.INVENTORY, row[ItemStatuses.catalogId], row[ItemStatuses.amount]) }
    }

    /**
     * What one player wears, from the `equipped` table, numbered from 0 in the order the
     * server stored the items, since the table has no slots. Call inside a transaction.
     */
    private fun equippedItems(playerId: Int): List<HeldItem> =
        Equipped.join(ItemStatuses, JoinType.INNER, Equipped.itemStatusId, ItemStatuses.id)
            .selectAll()
            .where { Equipped.playerId eq playerId }
            .orderBy(ItemStatuses.id)
            .mapIndexed { slot, row ->
                HeldItem(
                    slot = slot,
                    itemId = row[ItemStatuses.catalogId],
                    amount = row[ItemStatuses.amount],
                    noted = row[ItemStatuses.noted] == 1,
                    // Everything here is worn. The flag isn't trusted: the server's MySQL save
                    // sets it, but its SQLite save copies it from the item as it stood.
                    worn = true,
                )
            }

    /** The items in one player's slots of [table], by slot. Call inside a transaction. */
    private fun heldItems(table: ItemSlotTable, playerId: Int): List<HeldItem> =
        table.join(ItemStatuses, JoinType.INNER, table.itemStatusId, ItemStatuses.id)
            .selectAll()
            .where { table.playerId eq playerId }
            .orderBy(table.slot)
            .map { row ->
                HeldItem(
                    slot = row[table.slot],
                    itemId = row[ItemStatuses.catalogId],
                    amount = row[ItemStatuses.amount],
                    noted = row[ItemStatuses.noted] == 1,
                    worn = row[ItemStatuses.wielded] == 1,
                )
            }

    private fun ResultRow.toPlayerSummary() = PlayerSummary(
        id = this[Players.id],
        name = this[Players.username],
        combatLevel = this[Players.combat] ?: 0,
        totalLevel = this[Players.skillTotal] ?: 0,
        lastLogin = this[Players.loginDate]?.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
        online = this[Players.online] == 1,
        staff = (this[Players.groupId] ?: FIRST_PLAYER_GROUP) < FIRST_PLAYER_GROUP,
    )

    private companion object {
        const val ESCAPE = '\\'

        /** A LIKE pattern matching names that contain [text], with its own `%` and `_` taken literally. */
        fun containing(text: String): LikePattern {
            val escaped = text
                .replace("$ESCAPE", "$ESCAPE$ESCAPE")
                .replace("%", "$ESCAPE%")
                .replace("_", "${ESCAPE}_")
            return LikePattern("%$escaped%", ESCAPE)
        }
    }
}
