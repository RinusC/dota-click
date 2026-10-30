package com.dotaidle.core

import com.dotaidle.core.content.Items
import com.dotaidle.core.engine.GameEngine
import com.dotaidle.core.model.GameState
import com.dotaidle.core.util.NumberFormat
import com.dotaidle.core.util.fmt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plays the game with a simple greedy bot and checks the progression curve:
 * first Ancient in ~30-40 min of active play, ~2-3 h for a mostly-offline player.
 */
class BalanceSimulationTest {

    /** Buys the cheapest useful upgrade until nothing is affordable. */
    private fun spendGreedy(e: GameEngine) {
        while (true) {
            val options = mutableListOf<Pair<Double, () -> Boolean>>()
            e.state.lanes.indices.forEach { i -> options += e.laneUpgradeCost(i) to { e.upgradeLane(i) } }
            e.state.heroes.forEachIndexed { i, h ->
                if (h.hired) options += e.heroLevelCost(i) to { e.levelUpHero(i) }
            }
            val emptySlot = e.state.heroes.indexOfFirst { !it.hired }
            if (emptySlot >= 0) options += e.hireCost() to { e.hireHero(emptySlot) }
            val hired = e.state.heroes.indices.filter { e.state.heroes[it].hired }
            val target = hired.firstOrNull { e.state.heroes[it].items.any { s -> s == null } }
            if (target != null) {
                Items.all.forEach { item ->
                    options += e.itemCost(item.id) * 1.5 to { e.buyItem(item.id) && e.equipItem(target, item.id) }
                }
            } else if (hired.isNotEmpty()) {
                // All slots full: replace the weakest equipped item with a stronger one.
                val (hero, worstSlot) = hired.flatMap { h -> e.state.heroes[h].items.indices.map { h to it } }
                    .minBy { (h, i) -> Items[e.state.heroes[h].items[i]!!]!!.let { it.dmg + it.gold + it.farm } }
                val worst = Items[e.state.heroes[hero].items[worstSlot]!!]!!
                Items.all.filter { it.dmg + it.gold + it.farm > (worst.dmg + worst.gold + worst.farm) * 1.5 }.forEach { item ->
                    options += e.itemCost(item.id) * 3.0 to {
                        e.buyItem(item.id) && e.unequipItem(hero, worstSlot) && e.equipItem(hero, item.id)
                    }
                }
            }
            val best = options.filter { it.first <= e.state.gold }.minByOrNull { it.first } ?: return
            if (!best.second()) return
        }
    }

    private fun castAll(e: GameEngine) = e.state.heroes.indices.forEach { e.castAbility(it) }

    private fun report(tag: String, e: GameEngine, t: Double) {
        val s = e.state
        println(
            "[$tag] t=${NumberFormat.duration(t)} enemy=${s.battle.enemyIndex} gold/s=${e.goldPerSec(false).fmt()} " +
                "dps=${e.teamDps(false).fmt()} lanes=${s.lanes.map { it.level }} heroes=${s.heroes.filter { it.hired }.map { it.level }} " +
                "items=${s.heroes.sumOf { h -> h.items.count { it != null } }} runGold=${s.runGold.fmt()}"
        )
    }

    private fun activeRun(e: GameEngine, limitSeconds: Double, tag: String): Double {
        var t = 0.0
        var nextReport = 0.0
        while (t < limitSeconds && e.state.battle.ancientsKilledRun == 0) {
            e.tick(0.1)
            t += 0.1
            if ((t * 10).toLong() % 10 == 0L) { // once per second: spend + cast
                spendGreedy(e)
                castAll(e)
            }
            if (t >= nextReport) { report(tag, e, t); nextReport += 300.0 }
        }
        report(tag, e, t)
        return t
    }

    @Test
    fun firstAncientActive() {
        val e = GameEngine(GameState.newGame(), Random(42))
        val t = activeRun(e, 3 * 3600.0, "active")
        println("First Ancient (active): ${NumberFormat.duration(t)}")
        assertTrue(t in 25 * 60.0..45 * 60.0, "active first Ancient took ${NumberFormat.duration(t)}")
    }

    @Test
    fun firstAncientMostlyOffline() {
        val e = GameEngine(GameState.newGame(), Random(7))
        // 5 minutes of initial play, then: away 30 min, open the app for 1 minute to spend.
        var clock = 1_000_000_000L
        var t = 0.0
        var activeTime = 0.0
        fun play(seconds: Double) {
            var s = 0.0
            while (s < seconds && e.state.battle.ancientsKilledRun == 0) {
                e.tick(0.1); s += 0.1
                if ((s * 10).toLong() % 10 == 0L) { spendGreedy(e); castAll(e) }
            }
            t += s; activeTime += s; clock += (s * 1000).toLong()
        }
        play(300.0)
        while (e.state.battle.ancientsKilledRun == 0 && t < 24 * 3600) {
            e.state.lastSaveMillis = clock
            clock += 30 * 60 * 1000L
            t += 30 * 60.0
            e.applyOffline(clock)
            report("idle", e, t)
            if (e.state.battle.ancientsKilledRun > 0) break
            play(60.0)
        }
        println("First Ancient (mostly offline): ${NumberFormat.duration(t)}, active ${NumberFormat.duration(activeTime)}")
        assertTrue(t in 1.5 * 3600..3.5 * 3600, "offline first Ancient took ${NumberFormat.duration(t)}")
    }

    @Test
    fun secondRunIsFasterAfterRebirth() {
        val e = GameEngine(GameState.newGame(), Random(3))
        val t1 = activeRun(e, 3 * 3600.0, "run1")
        val gain = e.rebirth()
        e.state.talents // spend talent points on whatever is available
        listOf("g1", "d1", "u1", "g2", "d2", "u2").forEach { e.buyTalent(it) }
        val t2 = activeRun(e, 3 * 3600.0, "run2")
        println("Run1 ${NumberFormat.duration(t1)}, MMR +$gain, Run2 ${NumberFormat.duration(t2)}")
        assertTrue(gain >= 10)
        assertTrue(t2 < t1 * 0.85, "rebirth should speed things up")
    }
}
