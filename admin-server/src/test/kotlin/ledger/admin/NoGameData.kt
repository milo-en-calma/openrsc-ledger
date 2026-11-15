package ledger.admin

import ledger.core.game.GameData
import ledger.core.game.PlayerDetails
import ledger.core.game.PlayerSkills
import ledger.core.game.PlayerSummary

/** A game with no players, for tests that don't read game data. */
object NoGameData : GameData {
    override fun players(search: String?): List<PlayerSummary> = emptyList()
    override fun allSkills(): List<PlayerSkills> = emptyList()
    override fun player(id: Int): PlayerDetails? = null
}
