package com.dotaidle.core.content

import com.dotaidle.core.i18n.Loc

enum class Rarity(val label: Loc) {
    COMMON(Loc("Common", "Обычный")),
    RARE(Loc("Rare", "Редкий")),
    EPIC(Loc("Epic", "Эпический")),
    LEGENDARY(Loc("Legendary", "Легендарный")),
}

/**
 * Bonuses are additive fractions: dmg = +0.25 means +25% to the wearer's DPS (itemMultiplier),
 * gold / farm apply globally to lane gold and wave speed.
 */
data class ItemDef(
    val id: String,
    val name: Loc,
    val rarity: Rarity,
    val baseCost: Double,
    val dmg: Double = 0.0,
    val gold: Double = 0.0,
    val farm: Double = 0.0,
)

object Items {
    val all: List<ItemDef> = listOf(
        ItemDef("mango", Loc("Mango", "Манго"), Rarity.COMMON, 150.0, farm = 0.05, dmg = 0.10),
        ItemDef("iron_twig", Loc("Iron Twig", "Железная Ветка"), Rarity.COMMON, 200.0, dmg = 0.25),
        ItemDef("quelling_blayde", Loc("Quelling Blayde", "Лесорубный Клинок"), Rarity.COMMON, 300.0, gold = 0.10),
        ItemDef("speedy_boots", Loc("Speedy Boots", "Шустрые Сапоги"), Rarity.COMMON, 400.0, farm = 0.10, dmg = 0.10),

        ItemDef("battle_furry", Loc("Battle Furry", "Боевой Пушистик"), Rarity.RARE, 2_500.0, dmg = 0.50, gold = 0.15),
        ItemDef("blinky_dagger", Loc("Blinky Dagger", "Мигающий Кинжал"), Rarity.RARE, 3_500.0, farm = 0.25, dmg = 0.25),
        ItemDef("maelstorm", Loc("Maelstorm", "Мальшторм"), Rarity.RARE, 5_000.0, dmg = 0.90),

        ItemDef("manta_styl", Loc("Manta Styl", "Манта-Стиль"), Rarity.EPIC, 30_000.0, dmg = 1.60),
        ItemDef("black_king_bars", Loc("Black King Bars", "Батончики Чёрного Короля"), Rarity.EPIC, 45_000.0, dmg = 1.20, gold = 0.25),
        ItemDef("aghas_scepter", Loc("Agha's Scepter", "Скипетр Аги"), Rarity.EPIC, 60_000.0, dmg = 1.00, farm = 0.40),

        ItemDef("radiance_blade", Loc("Radiance Blade", "Клинок Сияния"), Rarity.LEGENDARY, 500_000.0, dmg = 3.00, gold = 0.40),
        ItemDef("hand_of_midaz", Loc("Hand of Midaz", "Рука Мидаза"), Rarity.LEGENDARY, 750_000.0, gold = 1.00),
        ItemDef("divine_rapiere", Loc("Divine Rapiere", "Божественная Рапира"), Rarity.LEGENDARY, 1_000_000.0, dmg = 6.00),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): ItemDef? = byId[id]

    const val SLOTS_PER_HERO = 6
}
