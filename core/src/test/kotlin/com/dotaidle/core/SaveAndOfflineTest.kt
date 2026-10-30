package com.dotaidle.core

import com.dotaidle.core.balance.Balance
import com.dotaidle.core.engine.GameEngine
import com.dotaidle.core.model.GameState
import com.dotaidle.core.save.SaveCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveAndOfflineTest {
    private fun engine() = GameEngine(GameState.newGame(), Random(1))

    @Test
    fun saveRoundTripKeepsEverything() {
        val e = engine()
        e.state.gold = 1e6
        e.upgradeLane(1)
        e.hireHero(1)
        e.buyItem("mango")
        e.equipItem(0, "mango")
        e.buyItem("iron_twig")
        repeat(100) { e.tick(0.1) }
        e.state.mmr = 42
        e.state.talents.add("g1")
        e.state.settings.language = "ru"
        e.state.lastSaveMillis = 123456789L

        val json = SaveCodec.encode(e.state)
        val loaded = assertNotNull(SaveCodec.decode(json))
        assertEquals(json, SaveCodec.encode(loaded))
        assertEquals(e.state.gold, loaded.gold)
        assertEquals(2, loaded.lanes[1].level)
        assertEquals("mango", loaded.heroes[0].items[0])
        assertEquals(listOf("iron_twig"), loaded.inventory)
        assertEquals(42, loaded.mmr)
        assertEquals("ru", loaded.settings.language)
        assertTrue(loaded.heroes[1].hired)
    }

    @Test
    fun corruptSaveReturnsNull() {
        assertNull(SaveCodec.decode(""))
        assertNull(SaveCodec.decode("{not json"))
        // unknown keys and missing fields are tolerated
        val s = assertNotNull(SaveCodec.decode("""{"gold": 77.0, "futureField": 1}"""))
        assertEquals(77.0, s.gold)
        GameEngine(s).normalize()
        assertTrue(s.heroes[0].hired)
    }

    @Test
    fun offlineGivesHalfOfOnlineRate() {
        val e = engine()
        val now = 1_000_000_000L
        e.state.lastSaveMillis = now
        val rate = e.goldPerSec(includeBuffs = false)
        val gold0 = e.state.gold
        val r = assertNotNull(e.applyOffline(now + 3_600_000L))
        assertEquals(3600.0, r.creditedSeconds)
        val laneGold = rate * 3600 * 0.5
        assertTrue(r.gold >= laneGold * 0.999, "lane gold ${r.gold} < $laneGold")
        assertTrue(e.state.gold - gold0 >= laneGold * 0.999)
        assertTrue(r.enemiesKilled > 0, "team should also push while offline")
        assertEquals(now + 3_600_000L, e.state.lastSaveMillis)
    }

    @Test
    fun offlineIsCappedAt8HoursAndExtendedByTalent() {
        val e = engine()
        e.state.lastSaveMillis = 1L
        val r = assertNotNull(e.applyOffline(1L + 48L * 3600 * 1000))
        assertEquals(8 * 3600.0, r.creditedSeconds)
        assertEquals(48 * 3600.0, r.awaySeconds)

        val e2 = engine()
        e2.state.talents.add("g3") // +4h
        e2.state.lastSaveMillis = 1L
        val r2 = assertNotNull(e2.applyOffline(1L + 48L * 3600 * 1000))
        assertEquals(12 * 3600.0, r2.creditedSeconds)
    }

    @Test
    fun noOfflineForFreshSaveOrClockRollback() {
        val e = engine()
        assertNull(e.applyOffline(5_000L)) // lastSave = 0 (new game)
        e.state.lastSaveMillis = 10_000_000L
        val gold = e.state.gold
        assertNull(e.applyOffline(9_000_000L)) // clock moved back
        assertEquals(gold, e.state.gold)
    }

    @Test
    fun formulasMatchSpec() {
        assertEquals(100.0, Balance.laneUpgradeCost(0))
        assertEquals(100.0 * Math.pow(1.15, 10.0), Balance.laneUpgradeCost(10), 1e-9)
        assertEquals(100.0 * Math.pow(1.12, 34.0), Balance.enemyHp(34), 1e-6)
        assertEquals(5.0 * 7 * 2.5, Balance.heroDps(7, 2.5))
        assertEquals(10.0 * Math.pow(Balance.WAVE_MULTIPLIER, 4.0) * 5 * 3.0, Balance.goldPerCreep(4, 5, 3.0), 1e-9)
    }

    @Test
    fun roshanGivesAegisAndAncientUnlocksRebirth() {
        val e = engine()
        e.state.battle.enemyIndex = com.dotaidle.core.content.Enemies.ROSHAN_LOCAL
        e.state.battle.enemyHp = 1.0
        e.dealDamage(1.0)
        assertEquals(Balance.AEGIS_SECONDS, e.state.battle.aegisLeft)
        val before = e.teamDps()
        assertEquals(e.teamDps(includeBuffs = false) * 2, before, 1e-9)

        e.state.battle.enemyIndex = com.dotaidle.core.content.Enemies.ANCIENT_LOCAL
        e.state.battle.enemyHp = 1.0
        e.dealDamage(1.0)
        assertTrue(e.canRebirth())
        e.state.runGold = 4e10 // sqrt(4e10 / 1e8) = 20
        val gain = e.rebirth()
        assertEquals(10 + 20, gain)
        assertEquals(30, e.state.mmr)
        assertEquals(0, e.state.battle.enemyIndex)
        assertEquals(0.0, e.state.gold)
        assertTrue(e.state.collection.isNotEmpty())
        assertEquals(2.5, e.goldMultiplier(), 1e-9) // +5% per MMR
    }

    @Test
    fun talentsRespectPrerequisitesAndMmr() {
        val e = engine()
        e.state.talentPoints = 100
        assertTrue(!e.buyTalent("g2")) // needs g1 + 20 MMR
        assertTrue(e.buyTalent("g1"))
        assertTrue(!e.buyTalent("g2"))
        e.state.mmr = 20
        assertTrue(e.buyTalent("g2"))
        e.state.mmr = 50
        assertTrue(e.buyTalent("u1") && e.buyTalent("u2") && e.buyTalent("u3"))
        assertEquals(6, e.state.heroes.size)
    }
}
