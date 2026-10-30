package com.dotaidle.core.engine

import com.dotaidle.core.balance.Balance
import com.dotaidle.core.content.AbilityType
import com.dotaidle.core.content.Enemies
import com.dotaidle.core.content.Heroes
import com.dotaidle.core.content.Items
import com.dotaidle.core.content.Role
import com.dotaidle.core.content.TalentEffect
import com.dotaidle.core.content.Talents
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.model.GameState
import com.dotaidle.core.model.HeroSlotState
import com.dotaidle.core.util.fmt
import kotlin.random.Random

/**
 * Pure game rules. Not thread-safe: call everything from one thread (the UI thread on Android).
 * The host calls [tick] 10 times per second with dt = 0.1.
 */
class GameEngine(state: GameState, private val random: Random = Random.Default) {
    var state: GameState = state
        private set

    val events = ArrayDeque<GameEvent>()
    val log = ArrayDeque<LogEntry>()

    init {
        normalize()
    }

    // =============================================================================================
    // Derived stats
    // =============================================================================================

    fun talentSum(effect: TalentEffect): Double =
        state.talents.mapNotNull { Talents[it] }.filter { it.effect == effect }.sumOf { it.value }

    fun hasTalent(effect: TalentEffect): Boolean = talentSum(effect) > 0.0

    private fun equippedItems() = state.heroes.filter { it.hired }.flatMap { s -> s.items.mapNotNull { id -> id?.let { Items[it] } } }

    private val mmrMultiplier get() = 1.0 + Balance.MMR_BONUS_PER_POINT * state.mmr

    /** Global gold multiplier: items * talents * MMR * (rewarded x2). */
    fun goldMultiplier(includeBuffs: Boolean = true): Double {
        val items = 1.0 + equippedItems().sumOf { it.gold }
        val talents = 1.0 + talentSum(TalentEffect.GOLD_PCT)
        val ad = if (includeBuffs && state.buffs.goldX2Left > 0) 2.0 else 1.0
        return items * talents * mmrMultiplier * ad
    }

    /** Global damage multiplier: talents * MMR * collection * (Aegis, Rage). */
    fun damageMultiplier(includeBuffs: Boolean = true): Double {
        val talents = 1.0 + talentSum(TalentEffect.DMG_PCT)
        val collection = 1.0 + Balance.COLLECTION_BONUS_PER_HERO * state.collection.size
        var m = talents * mmrMultiplier * collection
        if (includeBuffs) {
            if (state.battle.aegisLeft > 0) m *= Balance.AEGIS_DAMAGE_MULT
            if (state.buffs.rageLeft > 0) m *= Balance.RAGE_MULT
        }
        return m
    }

    fun farmSpeed(includeBuffs: Boolean = true): Double {
        val items = 1.0 + equippedItems().sumOf { it.farm }
        val talents = 1.0 + talentSum(TalentEffect.FARM_PCT)
        val haste = if (includeBuffs && state.buffs.hasteLeft > 0) Balance.HASTE_MULT else 1.0
        return items * talents * haste
    }

    fun itemMultiplier(slot: HeroSlotState): Double = 1.0 + slot.items.sumOf { id -> id?.let { Items[it]?.dmg } ?: 0.0 }

    fun heroDps(slot: HeroSlotState, includeBuffs: Boolean = true): Double =
        if (!slot.hired) 0.0 else Balance.heroDps(slot.level, itemMultiplier(slot)) * Balance.heroSpikeMultiplier(slot.level) * damageMultiplier(includeBuffs)

    fun teamDps(includeBuffs: Boolean = true): Double = state.heroes.sumOf { heroDps(it, includeBuffs) }

    fun heroDpsMultiplier(): Double = Balance.heroDpsMultiplier(teamDps(includeBuffs = false))

    fun waveInterval(includeBuffs: Boolean = true): Double = Balance.BASE_WAVE_INTERVAL / farmSpeed(includeBuffs)

    fun goldPerCreep(lane: Int, includeBuffs: Boolean = true): Double {
        val l = state.lanes[lane]
        return Balance.goldPerCreep(l.wave, l.level, heroDpsMultiplier()) * goldMultiplier(includeBuffs)
    }

    fun laneGoldPerSec(lane: Int, includeBuffs: Boolean = true): Double =
        Balance.CREEPS_PER_WAVE * goldPerCreep(lane, includeBuffs) / waveInterval(includeBuffs)

    fun goldPerSec(includeBuffs: Boolean = true): Double = state.lanes.indices.sumOf { laneGoldPerSec(it, includeBuffs) }

    fun maxOfflineSeconds(): Double = (Balance.OFFLINE_BASE_HOURS + talentSum(TalentEffect.OFFLINE_HOURS)) * 3600.0

    fun offlineRate(): Double = Balance.OFFLINE_RATE + talentSum(TalentEffect.OFFLINE_RATE)

    fun abilityCooldown(slot: HeroSlotState): Double {
        val def = slot.heroId?.let { Heroes[it] } ?: return 0.0
        return def.ability.cooldown * (1.0 - talentSum(TalentEffect.COOLDOWN_PCT))
    }

    fun autoCastEnabled(): Boolean = hasTalent(TalentEffect.AUTO_CAST)

    fun hiredCount(): Int = state.heroes.count { it.hired }

    // =============================================================================================
    // Tick
    // =============================================================================================

    fun tick(dt: Double) {
        if (dt <= 0) return
        state.playSeconds += dt
        state.runSeconds += dt
        state.secondsSinceInterstitial += dt
        val b = state.buffs
        b.goldX2Left = (b.goldX2Left - dt).coerceAtLeast(0.0)
        b.rageLeft = (b.rageLeft - dt).coerceAtLeast(0.0)
        b.hasteLeft = (b.hasteLeft - dt).coerceAtLeast(0.0)
        state.battle.aegisLeft = (state.battle.aegisLeft - dt).coerceAtLeast(0.0)

        // Lanes: a creep wave dies every waveInterval seconds.
        val interval = waveInterval()
        for (i in state.lanes.indices) {
            val lane = state.lanes[i]
            lane.progress += dt / interval
            while (lane.progress >= 1.0) {
                lane.progress -= 1.0
                lane.killed += Balance.CREEPS_PER_WAVE
                val g = Balance.CREEPS_PER_WAVE * goldPerCreep(i)
                addGold(g)
                events.addLast(GameEvent.LaneWave(i, g))
            }
        }

        // Abilities
        val auto = autoCastEnabled()
        state.heroes.forEachIndexed { idx, slot ->
            if (!slot.hired) return@forEachIndexed
            slot.cooldown = (slot.cooldown - dt).coerceAtLeast(0.0)
            if (auto && slot.cooldown <= 0.0) castAbility(idx)
        }

        // Battle (auto-fight)
        dealDamage(teamDps() * dt)
    }

    private fun addGold(amount: Double) {
        if (amount.isNaN() || amount <= 0) return
        state.gold += amount
        state.runGold += amount
        state.lifetimeGold += amount
    }

    private fun addLog(text: () -> String) {
        log.addFirst(LogEntry(state.runSeconds, text))
        while (log.size > 40) log.removeLast()
    }

    /** Applies damage to the enemy queue; overflow carries to the next enemy. Returns kills. */
    fun dealDamage(amount: Double, quiet: Boolean = false): Int {
        var left = amount
        var kills = 0
        val battle = state.battle
        while (left > 0 && !left.isNaN()) {
            if (left < battle.enemyHp) {
                battle.enemyHp -= left
                break
            }
            left -= battle.enemyHp
            kills++
            val idx = battle.enemyIndex
            val enemy = Enemies.at(idx)
            val bounty = Balance.bounty(idx) * goldMultiplier()
            addGold(bounty)
            if (!quiet) {
                events.addLast(GameEvent.EnemyKilled(idx, bounty))
                addLog { tr("log_killed", enemy.name.get(), bounty.fmt()) }
            }
            if (Enemies.isRoshan(idx)) {
                val duration = Balance.AEGIS_SECONDS * (1.0 + talentSum(TalentEffect.AEGIS_DURATION))
                battle.aegisLeft = duration
                if (!quiet) {
                    events.addLast(GameEvent.AegisGained)
                    addLog { tr("log_aegis") }
                }
            }
            if (Enemies.isAncient(idx)) {
                battle.ancientsKilledRun++
                state.totalAncients++
                if (!quiet) {
                    events.addLast(GameEvent.AncientDestroyed)
                    addLog { tr("log_ancient") }
                }
            }
            battle.enemyIndex++
            battle.enemyHp = Balance.enemyHp(battle.enemyIndex)
        }
        return kills
    }

    // =============================================================================================
    // Player actions — each returns true on success
    // =============================================================================================

    fun laneUpgradeCost(lane: Int): Double = Balance.laneUpgradeCost(state.lanes[lane].level)

    fun upgradeLane(lane: Int): Boolean {
        val cost = laneUpgradeCost(lane)
        if (!spend(cost)) return false
        state.lanes[lane].level++
        return true
    }

    fun heroLevelCost(slot: Int): Double = Balance.heroLevelCost(state.heroes[slot].level)

    fun levelUpHero(slot: Int): Boolean {
        val s = state.heroes.getOrNull(slot) ?: return false
        if (!s.hired || !spend(heroLevelCost(slot))) return false
        s.level++
        return true
    }

    fun hireCost(): Double = Balance.hireCost(hiredCount())

    fun hireHero(slot: Int): Boolean {
        val s = state.heroes.getOrNull(slot) ?: return false
        if (s.hired) return false
        val inTeam = state.heroes.mapNotNull { it.heroId }.toSet()
        val pool = Heroes.candidates(Heroes.slotRoles[slot]).filter { it.id !in inTeam }
        if (pool.isEmpty() || !spend(hireCost())) return false
        // Prefer heroes not yet in the collection so hiring grows it.
        val fresh = pool.filter { it.id !in state.collection }
        val pick = (fresh.ifEmpty { pool }).random(random)
        s.heroId = pick.id
        s.level = 1
        s.cooldown = 0.0
        s.items = MutableList(Items.SLOTS_PER_HERO) { null }
        state.collection.add(pick.id)
        addLog { tr("log_hired", pick.name.get()) }
        return true
    }

    fun itemCost(itemId: String): Double {
        val def = Items[itemId] ?: return Double.MAX_VALUE
        return Balance.itemCost(def.baseCost, state.itemsBought[itemId] ?: 0)
    }

    fun buyItem(itemId: String): Boolean {
        if (Items[itemId] == null || !spend(itemCost(itemId))) return false
        state.itemsBought[itemId] = (state.itemsBought[itemId] ?: 0) + 1
        state.inventory.add(itemId)
        return true
    }

    fun equipItem(slot: Int, itemId: String): Boolean {
        val s = state.heroes.getOrNull(slot) ?: return false
        if (!s.hired || itemId !in state.inventory) return false
        val free = s.items.indexOfFirst { it == null }
        if (free < 0) return false
        state.inventory.remove(itemId)
        s.items[free] = itemId
        return true
    }

    fun unequipItem(slot: Int, itemSlot: Int): Boolean {
        val s = state.heroes.getOrNull(slot) ?: return false
        val id = s.items.getOrNull(itemSlot) ?: return false
        s.items[itemSlot] = null
        state.inventory.add(id)
        return true
    }

    fun castAbility(slot: Int): Boolean {
        val s = state.heroes.getOrNull(slot) ?: return false
        val def = s.heroId?.let { Heroes[it] } ?: return false
        if (s.cooldown > 0.0) return false
        val a = def.ability
        when (a.type) {
            AbilityType.NUKE -> dealDamage(teamDps() * a.power)
            AbilityType.GOLD -> {
                val g = goldPerSec() * a.power
                addGold(g)
                events.addLast(GameEvent.GoldGained(g))
            }
            AbilityType.RAGE -> state.buffs.rageLeft = maxOf(state.buffs.rageLeft, a.power)
            AbilityType.HASTE -> state.buffs.hasteLeft = maxOf(state.buffs.hasteLeft, a.power)
        }
        s.cooldown = abilityCooldown(s)
        events.addLast(GameEvent.AbilityCast(slot, def.id))
        addLog { tr("log_cast", def.name.get(), a.name.get()) }
        return true
    }

    /** Manual "Attack!" button: a quarter second of team DPS (at least 1). */
    fun tapAttack(): Double {
        val dmg = maxOf(1.0, teamDps() * Balance.TAP_DPS_FRACTION)
        dealDamage(dmg)
        return dmg
    }

    // --- Rebirth & talents ----------------------------------------------------------------------

    fun canRebirth(): Boolean = state.battle.ancientsKilledRun >= 1

    fun mmrGainPreview(): Int = Balance.mmrGain(state.battle.ancientsKilledRun, state.runGold)

    fun rebirth(): Int {
        if (!canRebirth()) return 0
        val gain = mmrGainPreview()
        state.mmr += gain
        state.talentPoints += gain
        state.rebirths++
        state.resetRun(startGold = talentSum(TalentEffect.START_GOLD))
        normalize()
        log.clear()
        addLog { tr("log_reborn", gain) }
        return gain
    }

    enum class TalentStatus { OWNED, AVAILABLE, NO_POINTS, LOCKED }

    fun talentStatus(id: String): TalentStatus {
        val t = Talents[id] ?: return TalentStatus.LOCKED
        if (id in state.talents) return TalentStatus.OWNED
        val pre = t.prerequisite
        if (state.mmr < t.mmrRequired || (pre != null && pre !in state.talents)) return TalentStatus.LOCKED
        if (state.talentPoints < t.cost) return TalentStatus.NO_POINTS
        return TalentStatus.AVAILABLE
    }

    fun buyTalent(id: String): Boolean {
        if (talentStatus(id) != TalentStatus.AVAILABLE) return false
        val t = Talents[id]!!
        state.talentPoints -= t.cost
        state.talents.add(id)
        normalize()
        return true
    }

    // --- Offline & rewarded ads -----------------------------------------------------------------

    /**
     * Simulates [seconds] of idle farming at [rate] of the steady (buff-free) online speed:
     * lane gold and battle damage. Used for offline progress and the "farm 4h" reward.
     */
    fun simulateIdle(seconds: Double, rate: Double): IdleReport {
        val goldBefore = state.lifetimeGold
        addGold(goldPerSec(includeBuffs = false) * seconds * rate)
        val lanesKilled = (seconds * rate / waveInterval(includeBuffs = false)).toLong() * Balance.CREEPS_PER_WAVE
        state.lanes.forEach { it.killed += lanesKilled }
        val kills = dealDamage(teamDps(includeBuffs = false) * seconds * rate, quiet = true)
        if (kills > 0) addLog { tr("log_idle_kills", kills) }
        return IdleReport(seconds, seconds, state.lifetimeGold - goldBefore, kills)
    }

    /** Called on app start/resume with wall-clock time. Returns null if nothing worth reporting. */
    fun applyOffline(nowMillis: Long): IdleReport? {
        val last = state.lastSaveMillis
        state.lastSaveMillis = nowMillis
        if (last <= 0 || nowMillis <= last) return null // fresh save or clock moved backwards
        val away = (nowMillis - last) / 1000.0
        if (away < Balance.OFFLINE_MIN_SECONDS) return null
        val credited = away.coerceAtMost(maxOfflineSeconds())
        // Timed buffs ran out while away; cooldowns are ready again.
        state.buffs.goldX2Left = (state.buffs.goldX2Left - away).coerceAtLeast(0.0)
        state.buffs.rageLeft = 0.0
        state.buffs.hasteLeft = 0.0
        state.battle.aegisLeft = 0.0
        state.heroes.forEach { it.cooldown = 0.0 }
        val r = simulateIdle(credited, offlineRate())
        return r.copy(awaySeconds = away, creditedSeconds = credited)
    }

    fun grantGold(amount: Double) {
        addGold(amount)
        events.addLast(GameEvent.GoldGained(amount))
    }

    fun activateGoldX2() {
        state.buffs.goldX2Left = (state.buffs.goldX2Left + Balance.AD_GOLD_X2_SECONDS)
            .coerceAtMost(Balance.AD_GOLD_X2_MAX_SECONDS)
    }

    fun farmAdReady(nowMillis: Long): Boolean = nowMillis >= state.farmAdReadyAtMillis

    fun claimFarmAd(nowMillis: Long): IdleReport? {
        if (!farmAdReady(nowMillis)) return null
        state.farmAdReadyAtMillis = nowMillis + Balance.AD_FARM_COOLDOWN_MS
        val r = simulateIdle(Balance.AD_FARM_HOURS * 3600.0, offlineRate())
        events.addLast(GameEvent.GoldGained(r.gold))
        return r
    }

    // =============================================================================================

    private fun spend(cost: Double): Boolean {
        if (cost.isNaN() || state.gold < cost) return false
        state.gold -= cost
        return true
    }

    /** Repairs/upgrades loaded saves (missing slots, unknown ids, NaN values). */
    fun normalize() {
        val s = state
        val slots = (Balance.BASE_HERO_SLOTS + talentSum(TalentEffect.HERO_SLOT).toInt()).coerceAtMost(Heroes.slotRoles.size)
        while (s.heroes.size < slots) s.heroes.add(HeroSlotState())
        while (s.lanes.size < 3) s.lanes.add(com.dotaidle.core.model.LaneState())
        s.heroes.forEach { h ->
            if (h.heroId != null && Heroes[h.heroId!!] == null) { h.heroId = null; h.level = 0 }
            while (h.items.size < Items.SLOTS_PER_HERO) h.items.add(null)
            h.items.replaceAll { id -> id?.takeIf { Items[it] != null } }
            if (h.hired && h.level < 1) h.level = 1
        }
        if (s.heroes.none { it.hired }) {
            s.heroes[0].heroId = Heroes.STARTER
            s.heroes[0].level = 1
        }
        s.inventory.removeAll { Items[it] == null }
        s.talents.removeAll { Talents[it] == null }
        if (!s.gold.isFinite() || s.gold < 0) s.gold = 0.0
        if (!s.battle.enemyHp.isFinite() || s.battle.enemyHp <= 0) s.battle.enemyHp = Balance.enemyHp(s.battle.enemyIndex)
        s.lanes.forEach { if (it.level < 1) it.level = 1 }
    }

    /** Swaps in a different save (e.g. after "reset progress"). */
    fun replaceState(newState: GameState) {
        state = newState
        events.clear()
        log.clear()
        normalize()
    }

    fun roleOf(slot: Int): Role = Heroes.slotRoles[slot]
}
