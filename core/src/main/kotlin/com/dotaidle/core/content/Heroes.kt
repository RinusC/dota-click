package com.dotaidle.core.content

import com.dotaidle.core.i18n.Loc

enum class Role(val label: Loc) {
    CARRY(Loc("Carry", "Керри")),
    MID(Loc("Mid", "Мидер")),
    OFFLANE(Loc("Offlane", "Оффлейн")),
    SUPPORT(Loc("Support", "Саппорт")),
    FLEX(Loc("Flex", "Флекс")),
}

enum class AbilityType {
    /** Instant damage to the current enemy = team DPS * power seconds. */
    NUKE,
    /** Instant gold = lane gold/sec * power seconds. */
    GOLD,
    /** Team damage x2 for power seconds. */
    RAGE,
    /** Lane farm speed x2 for power seconds. */
    HASTE,
}

data class Ability(val name: Loc, val type: AbilityType, val power: Double, val cooldown: Double)

data class HeroDef(val id: String, val name: Loc, val role: Role, val ability: Ability)

/** Parody roster — no real trademarked names. */
object Heroes {
    val all: List<HeroDef> = listOf(
        HeroDef("anti_magus", Loc("Anti-Magus", "Анти-Магус"), Role.CARRY,
            Ability(Loc("Mana Snap", "Мана-щелчок"), AbilityType.NUKE, 12.0, 30.0)),
        HeroDef("jaggernot", Loc("Jaggernot", "Джаггернот"), Role.CARRY,
            Ability(Loc("Blade Whirl", "Вихрь клинков"), AbilityType.RAGE, 10.0, 45.0)),
        HeroDef("phantom_lass", Loc("Phantom Lass", "Призрачная Дева"), Role.CARRY,
            Ability(Loc("Lucky Crit", "Удачный крит"), AbilityType.NUKE, 10.0, 25.0)),

        HeroDef("shadow_fiendish", Loc("Shadow Fiendish", "Теневой Изверг"), Role.MID,
            Ability(Loc("Soul Raze", "Сожжение душ"), AbilityType.NUKE, 15.0, 35.0)),
        HeroDef("invokah", Loc("Invokah", "Инвоках"), Role.MID,
            Ability(Loc("Sun Smite", "Удар солнца"), AbilityType.NUKE, 30.0, 60.0)),
        HeroDef("storm_sprite", Loc("Storm Sprite", "Штормовой Дух"), Role.MID,
            Ability(Loc("Static Rush", "Статический рывок"), AbilityType.HASTE, 10.0, 40.0)),

        HeroDef("axe_man", Loc("Axe-Man", "Топор-Мэн"), Role.OFFLANE,
            Ability(Loc("Berserk Shout", "Крик берсерка"), AbilityType.RAGE, 10.0, 45.0)),
        HeroDef("tidehunger", Loc("Tidehunger", "Голодный Прилив"), Role.OFFLANE,
            Ability(Loc("Ravenous Wave", "Жадная волна"), AbilityType.NUKE, 20.0, 50.0)),
        HeroDef("marzz", Loc("Marzz", "Марзз"), Role.OFFLANE,
            Ability(Loc("Arena of Greed", "Арена жадности"), AbilityType.GOLD, 20.0, 50.0)),

        HeroDef("frost_maidan", Loc("Frost Maidan", "Ледяная Майдан"), Role.SUPPORT,
            Ability(Loc("Frost Aura", "Морозная аура"), AbilityType.HASTE, 10.0, 40.0)),
        HeroDef("lionel", Loc("Lionel", "Лайонел"), Role.SUPPORT,
            Ability(Loc("Finger of Greed", "Палец жадности"), AbilityType.GOLD, 15.0, 45.0)),
        HeroDef("witch_dok", Loc("Witch Dok", "Знахарь Док"), Role.SUPPORT,
            Ability(Loc("Voodoo Bounty", "Вуду-награда"), AbilityType.GOLD, 12.0, 35.0)),
        HeroDef("dazzlin", Loc("Dazzlin", "Ослепительный"), Role.SUPPORT,
            Ability(Loc("Shallow Bribe", "Мелкий подкуп"), AbilityType.RAGE, 8.0, 40.0)),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): HeroDef? = byId[id]

    const val STARTER = "anti_magus"

    /** Role for each team slot; slots 6-7 are unlocked by talents. */
    val slotRoles = listOf(Role.CARRY, Role.MID, Role.OFFLANE, Role.SUPPORT, Role.SUPPORT, Role.FLEX, Role.FLEX)

    fun candidates(role: Role): List<HeroDef> =
        if (role == Role.FLEX) all else all.filter { it.role == role }
}
