package com.dotaidle.core.content

import com.dotaidle.core.i18n.Loc

enum class EnemyType(val label: Loc, val icon: String) {
    CREEP(Loc("Creep", "Крип"), "🗡"),
    TOWER(Loc("Tower", "Башня"), "🗼"),
    BARRACKS(Loc("Barracks", "Казармы"), "🏯"),
    HERO(Loc("Enemy Hero", "Вражеский герой"), "🦹"),
    ROSHAN(Loc("Rosh-Khan", "Рош-Хан"), "🐲"),
    ANCIENT(Loc("Ancient", "Древний"), "🏛"),
}

data class Enemy(val index: Int, val match: Int, val local: Int, val type: EnemyType, val name: Loc)

/**
 * The push is an endless sequence of enemies. Every "match" is [Enemies.PER_MATCH] enemies long:
 * creeps -> towers -> enemy heroes -> Rosh-Khan -> ... -> Ancient (the last one).
 */
object Enemies {
    private val melee = Loc("Melee Creep", "Ближний крип")
    private val ranged = Loc("Ranged Creep", "Дальний крип")
    private val siege = Loc("Siege Creep", "Осадный крип")
    private val mega = Loc("Mega Creep", "Мега-крип")

    private fun tower(tier: Int) = Loc("Tier $tier Tower", "Башня T$tier")

    private val layout: List<Pair<EnemyType, Loc?>> = buildList {
        repeat(27) { add(EnemyType.CREEP to if (it % 3 == 2) ranged else melee) }
        add(EnemyType.TOWER to tower(1))
        repeat(18) { add(EnemyType.CREEP to if (it % 3 == 0) siege else ranged) }
        repeat(10) { add(EnemyType.HERO to null) }
        add(EnemyType.TOWER to tower(2))
        repeat(30) { add(if (it % 3 == 0) EnemyType.CREEP to siege else EnemyType.HERO to null) }
        add(EnemyType.ROSHAN to null)
        repeat(16) { add(EnemyType.HERO to null) }
        add(EnemyType.TOWER to tower(3))
        repeat(2) { add(EnemyType.BARRACKS to null) }
        repeat(28) { add(if (it % 2 == 0) EnemyType.CREEP to mega else EnemyType.HERO to null) }
        repeat(2) { add(EnemyType.TOWER to tower(4)) }
        add(EnemyType.ANCIENT to null)
    }

    /** Enemies per match; the Ancient is the last one. Tuned so the first Ancient falls at ~35 min. */
    val PER_MATCH: Int = layout.size
    val ROSHAN_LOCAL: Int = layout.indexOfFirst { it.first == EnemyType.ROSHAN }
    val ANCIENT_LOCAL: Int = PER_MATCH - 1

    fun at(index: Int): Enemy {
        val match = index / PER_MATCH
        val local = index % PER_MATCH
        val (type, fixedName) = layout[local]
        val name = fixedName ?: when (type) {
            EnemyType.HERO -> {
                val h = Heroes.all[(index * 7 + match * 3) % Heroes.all.size]
                Loc("Enemy ${h.name.en}", "Вражеский ${h.name.ru}")
            }
            else -> type.label
        }
        return Enemy(index, match, local, type, name)
    }

    /** Key milestones of a match (first creep, each tower, first hero, Rosh-Khan, barracks, Ancient). */
    val milestones: List<Int> = layout.indices.filter { i ->
        val t = layout[i].first
        i == 0 || t == EnemyType.TOWER && layout[i - 1].first != EnemyType.TOWER ||
            t == EnemyType.HERO && layout.subList(0, i).none { it.first == EnemyType.HERO } ||
            t == EnemyType.ROSHAN || t == EnemyType.ANCIENT ||
            t == EnemyType.BARRACKS && layout[i - 1].first != EnemyType.BARRACKS
    }

    fun isRoshan(index: Int) = index % PER_MATCH == ROSHAN_LOCAL
    fun isAncient(index: Int) = index % PER_MATCH == ANCIENT_LOCAL
}
