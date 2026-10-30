package com.dotaidle.core.content

import com.dotaidle.core.i18n.Loc

enum class TalentEffect {
    GOLD_PCT, DMG_PCT, FARM_PCT, OFFLINE_HOURS, OFFLINE_RATE, AUTO_CAST, HERO_SLOT,
    START_GOLD, COOLDOWN_PCT, AEGIS_DURATION,
}

data class TalentDef(
    val id: String,
    val name: Loc,
    val column: Int,
    val tier: Int,
    val cost: Int,
    val mmrRequired: Int,
    val effect: TalentEffect,
    val value: Double,
) {
    /** Nodes are unlocked top to bottom within their column. */
    val prerequisite: String? get() = Talents.all.firstOrNull { it.column == column && it.tier == tier - 1 }?.id
}

/** 15 nodes: 3 branches (Greed / War / Utility) x 5 tiers. Paid with talent points, gated by MMR. */
object Talents {
    private val mmrByTier = listOf(0, 20, 50, 100, 200)
    private val costByTier = listOf(1, 2, 4, 6, 10)

    private fun node(id: String, en: String, ru: String, column: Int, tier: Int, effect: TalentEffect, value: Double) =
        TalentDef(id, Loc(en, ru), column, tier, costByTier[tier], mmrByTier[tier], effect, value)

    val all: List<TalentDef> = listOf(
        node("g1", "Greed I: +25% gold", "Жадность I: +25% золота", 0, 0, TalentEffect.GOLD_PCT, 0.25),
        node("g2", "Greed II: +50% gold", "Жадность II: +50% золота", 0, 1, TalentEffect.GOLD_PCT, 0.50),
        node("g3", "Long Nap: +4h offline", "Долгий сон: +4ч оффлайна", 0, 2, TalentEffect.OFFLINE_HOURS, 4.0),
        node("g4", "Greed III: +100% gold", "Жадность III: +100% золота", 0, 3, TalentEffect.GOLD_PCT, 1.00),
        node("g5", "Dream Farm: offline 75%", "Сонный фарм: оффлайн 75%", 0, 4, TalentEffect.OFFLINE_RATE, 0.25),

        node("d1", "Might I: +25% damage", "Мощь I: +25% урона", 1, 0, TalentEffect.DMG_PCT, 0.25),
        node("d2", "Might II: +50% damage", "Мощь II: +50% урона", 1, 1, TalentEffect.DMG_PCT, 0.50),
        node("d3", "Auto-Cast", "Авто-каст способностей", 1, 2, TalentEffect.AUTO_CAST, 1.0),
        node("d4", "Might III: +100% damage", "Мощь III: +100% урона", 1, 3, TalentEffect.DMG_PCT, 1.00),
        node("d5", "Immortal: Aegis x2 time", "Бессмертный: Аегис x2 время", 1, 4, TalentEffect.AEGIS_DURATION, 1.0),

        node("u1", "Swift Waves: +25% farm speed", "Быстрые волны: +25% скорость", 2, 0, TalentEffect.FARM_PCT, 0.25),
        node("u2", "Head Start: 5K start gold", "Фора: 5K стартового золота", 2, 1, TalentEffect.START_GOLD, 5_000.0),
        node("u3", "Sixth Slot: +1 hero", "Шестой слот: +1 герой", 2, 2, TalentEffect.HERO_SLOT, 1.0),
        node("u4", "Quick Hands: -25% cooldowns", "Быстрые руки: -25% кулдаун", 2, 3, TalentEffect.COOLDOWN_PCT, 0.25),
        node("u5", "Seventh Slot: +1 hero", "Седьмой слот: +1 герой", 2, 4, TalentEffect.HERO_SLOT, 1.0),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): TalentDef? = byId[id]
}
