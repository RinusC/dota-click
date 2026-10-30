package com.dotaidle.core.model

import com.dotaidle.core.balance.Balance
import com.dotaidle.core.content.Heroes
import com.dotaidle.core.content.Items
import kotlinx.serialization.Serializable

/** Everything that is persisted. Plain mutable data; all rules live in GameEngine. */
@Serializable
class GameState(
    var saveVersion: Int = CURRENT_VERSION,
    var gold: Double = 0.0,
    /** Gold earned during the current run (used for MMR gain). */
    var runGold: Double = 0.0,
    var lifetimeGold: Double = 0.0,
    var lanes: MutableList<LaneState> = MutableList(3) { LaneState() },
    var heroes: MutableList<HeroSlotState> = MutableList(Balance.BASE_HERO_SLOTS) { HeroSlotState() },
    /** Bought, not yet equipped item ids. */
    var inventory: MutableList<String> = mutableListOf(),
    /** Purchase count per item id this run (drives cost growth). */
    var itemsBought: MutableMap<String, Int> = mutableMapOf(),
    var battle: BattleState = BattleState(),
    var buffs: BuffState = BuffState(),
    var mmr: Int = 0,
    var talentPoints: Int = 0,
    var talents: MutableSet<String> = mutableSetOf(),
    /** Every hero ever hired. Survives rebirth, +2% damage each. */
    var collection: MutableSet<String> = mutableSetOf(),
    var rebirths: Int = 0,
    var totalAncients: Int = 0,
    var playSeconds: Double = 0.0,
    var runSeconds: Double = 0.0,
    var secondsSinceInterstitial: Double = 0.0,
    var farmAdReadyAtMillis: Long = 0,
    var lastSaveMillis: Long = 0,
    var settings: Settings = Settings(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        fun newGame(): GameState = GameState().also { it.resetRun() }
    }

    /** Resets run-scoped progress (used for new game and rebirth). */
    fun resetRun(startGold: Double = 0.0) {
        gold = startGold
        runGold = 0.0
        runSeconds = 0.0
        lanes = MutableList(3) { LaneState() }
        val slots = heroes.size.coerceAtLeast(Balance.BASE_HERO_SLOTS)
        heroes = MutableList(slots) { HeroSlotState() }
        heroes[0].heroId = Heroes.STARTER
        heroes[0].level = 1
        collection.add(Heroes.STARTER)
        inventory = mutableListOf()
        itemsBought = mutableMapOf()
        battle = BattleState()
        buffs = BuffState()
    }
}

@Serializable
class LaneState(
    var level: Int = 1,
    /** 0..1 progress toward the next creep wave. */
    var progress: Double = 0.0,
    var killed: Long = 0,
) {
    /** Wave tier used by the gold formula: each lane upgrade sends a stronger wave. */
    val wave: Int get() = level - 1
}

@Serializable
class HeroSlotState(
    var heroId: String? = null,
    var level: Int = 0,
    var items: MutableList<String?> = MutableList(Items.SLOTS_PER_HERO) { null },
    var cooldown: Double = 0.0,
) {
    val hired: Boolean get() = heroId != null
}

@Serializable
class BattleState(
    var enemyIndex: Int = 0,
    var enemyHp: Double = Balance.enemyHp(0),
    var ancientsKilledRun: Int = 0,
    var aegisLeft: Double = 0.0,
)

@Serializable
class BuffState(
    var goldX2Left: Double = 0.0,
    var rageLeft: Double = 0.0,
    var hasteLeft: Double = 0.0,
)

@Serializable
class Settings(
    var language: String = "en",
    var sound: Boolean = true,
    var music: Boolean = true,
    var noAds: Boolean = false,
)
