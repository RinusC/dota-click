package com.dotaidle.core.balance

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * All balance formulas in one place. Every value is a Double so numbers can grow up to ~1e308.
 * See README.md, "Balance formulas".
 */
object Balance {
    // --- Lanes ---------------------------------------------------------------------------------
    const val BASE_CREEP_GOLD = 10.0
    const val WAVE_MULTIPLIER = 1.03
    const val CREEPS_PER_WAVE = 4
    const val BASE_WAVE_INTERVAL = 6.0 // seconds
    const val LANE_COST_BASE = 100.0
    const val LANE_COST_GROWTH = 1.15

    /** Gold per creep = base(10) * waveMultiplier^wave * lineLevel * heroDPSMultiplier (* global gold mult). */
    fun goldPerCreep(wave: Int, lineLevel: Int, heroDpsMultiplier: Double): Double =
        BASE_CREEP_GOLD * WAVE_MULTIPLIER.pow(wave) * lineLevel * heroDpsMultiplier

    /** Lane upgrade cost = 100 * 1.15^level. */
    fun laneUpgradeCost(level: Int): Double = LANE_COST_BASE * LANE_COST_GROWTH.pow(level)

    /** Heroes farm the lanes: stronger team => richer last hits. */
    fun heroDpsMultiplier(teamDps: Double): Double = 1.0 + log10(1.0 + teamDps.coerceAtLeast(0.0))

    // --- Heroes ---------------------------------------------------------------------------------
    const val HERO_DPS_BASE = 5.0
    const val HERO_LEVEL_COST_BASE = 20.0
    const val HERO_LEVEL_COST_GROWTH = 1.13
    const val BASE_HERO_SLOTS = 5
    private val hireCosts = doubleArrayOf(0.0, 100.0, 1_000.0, 8_000.0, 60_000.0, 400_000.0, 3_000_000.0)

    /** Hero DPS = 5 * heroLevel * itemMultiplier (global multipliers are applied by the engine). */
    fun heroDps(level: Int, itemMultiplier: Double): Double = HERO_DPS_BASE * level * itemMultiplier

    /** Every 25 levels a hero's damage doubles ("power spike"), on top of the base DPS formula. */
    const val HERO_SPIKE_LEVELS = 25

    fun heroSpikeMultiplier(level: Int): Double = 2.0.pow(level / HERO_SPIKE_LEVELS)

    fun heroLevelCost(level: Int): Double = HERO_LEVEL_COST_BASE * HERO_LEVEL_COST_GROWTH.pow(level - 1)

    /** Cost of hiring when [hiredCount] heroes are already in the team. */
    fun hireCost(hiredCount: Int): Double =
        if (hiredCount < hireCosts.size) hireCosts[hiredCount] else hireCosts.last() * 10.0.pow(hiredCount - hireCosts.size + 1)

    // --- Items -----------------------------------------------------------------------------------
    const val ITEM_COST_GROWTH = 2.2

    fun itemCost(baseCost: Double, alreadyBought: Int): Double = baseCost * ITEM_COST_GROWTH.pow(alreadyBought)

    // --- Battle ----------------------------------------------------------------------------------
    const val ENEMY_HP_BASE = 100.0
    const val ENEMY_HP_GROWTH = 1.12
    const val BOUNTY_RATIO = 0.5
    const val AEGIS_SECONDS = 60.0
    const val AEGIS_DAMAGE_MULT = 2.0
    const val TAP_DPS_FRACTION = 0.25

    /** Enemy HP = 100 * 1.12^enemyIndex. */
    fun enemyHp(index: Int): Double = ENEMY_HP_BASE * ENEMY_HP_GROWTH.pow(index)

    fun bounty(index: Int): Double = enemyHp(index) * BOUNTY_RATIO

    // --- Buffs / abilities -----------------------------------------------------------------------
    const val RAGE_MULT = 2.0
    const val HASTE_MULT = 2.0

    // --- Meta ------------------------------------------------------------------------------------
    const val MMR_BONUS_PER_POINT = 0.05
    const val COLLECTION_BONUS_PER_HERO = 0.02

    /** MMR for a rebirth: 10 per Ancient killed this run + sqrt(run gold / 100M). */
    fun mmrGain(ancientsKilledRun: Int, runGold: Double): Int =
        if (ancientsKilledRun <= 0) 0 else 10 * ancientsKilledRun + floor(sqrt(runGold / 1e8)).toInt()

    // --- Offline / ads ---------------------------------------------------------------------------
    const val OFFLINE_RATE = 0.5
    const val OFFLINE_BASE_HOURS = 8.0
    const val OFFLINE_MIN_SECONDS = 10.0
    const val AD_GOLD_X2_SECONDS = 300.0
    const val AD_GOLD_X2_MAX_SECONDS = 3600.0
    const val AD_FARM_HOURS = 4.0
    const val AD_FARM_COOLDOWN_MS = 60L * 60 * 1000
    const val INTERSTITIAL_INTERVAL_SECONDS = 600.0
}
