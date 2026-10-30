package com.dotaidle.ancientwar.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dotaidle.ancientwar.data.SaveRepository
import com.dotaidle.ancientwar.services.AudioService
import com.dotaidle.ancientwar.services.BillingService
import com.dotaidle.ancientwar.services.StubAdService
import com.dotaidle.ancientwar.services.StubBillingService
import com.dotaidle.ancientwar.ui.theme.GameColors
import com.dotaidle.core.balance.Balance
import com.dotaidle.core.engine.GameEngine
import com.dotaidle.core.engine.GameEvent
import com.dotaidle.core.engine.IdleReport
import com.dotaidle.core.i18n.I18n
import com.dotaidle.core.i18n.tr
import com.dotaidle.core.model.GameState
import com.dotaidle.core.util.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Tab(val icon: String, val key: String) {
    LANES("🛣", "tab_lanes"), HEROES("🦸", "tab_heroes"), SHOP("🛒", "tab_shop"),
    BATTLE("⚔", "tab_battle"), REBIRTH("♻", "tab_rebirth"), TALENTS("🌟", "tab_talents"),
}

/** Where a floating text appears. Lanes use 0..2. */
object FloatArea {
    const val TOP = 10
    const val BATTLE = 11
}

data class Floater(val id: Long, val text: String, val color: Color, val area: Int, val bornNanos: Long, val xJitter: Float)

/**
 * Glue between the pure engine (Core) and Compose (UI): runs the 10 Hz tick loop, autosaves,
 * applies offline progress, forwards engine events to audio and floating texts.
 * Everything runs on the main thread, so the engine needs no locking.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SaveRepository(app)
    val audio = AudioService(app)
    val ads = StubAdService()
    private val billing: BillingService = StubBillingService()
    val engine = GameEngine(repo.load() ?: GameState.newGame())
    val state: GameState get() = engine.state

    /** Bumped after every tick / action; UI reads it to recompose (10 Hz). */
    var version by mutableLongStateOf(0L)
        private set

    /** System.nanoTime() of the last tick, used by the UI to interpolate bars at 60 FPS. */
    var lastTickNanos: Long = System.nanoTime()
        private set

    var tab by mutableStateOf(Tab.LANES)
        private set
    var offlineReport by mutableStateOf<IdleReport?>(null)
    var tooltip by mutableStateOf<String?>(null)
    var banner by mutableStateOf<Pair<String, Long>?>(null)
    var showSettings by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    val floaters = mutableStateListOf<Floater>()
    var tooltipNonce by mutableIntStateOf(0)
        private set

    private var loop: Job? = null
    private var autosaveAcc = 0.0
    private var floaterId = 0L

    init {
        I18n.language = state.settings.language
        audio.soundOn = state.settings.sound
        audio.musicOn = state.settings.music
    }

    // ---------------------------------------------------------------------------- lifecycle

    fun onResume() {
        applyOffline()
        startLoop()
        audio.onResume()
    }

    fun onPause() {
        loop?.cancel()
        loop = null
        saveNow(sync = true)
        audio.onPause()
    }

    override fun onCleared() {
        audio.release()
    }

    private fun applyOffline() {
        val report = engine.applyOffline(System.currentTimeMillis())
        if (report != null && report.awaySeconds >= 60) offlineReport = report
        bump()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            var last = SystemClock.elapsedRealtimeNanos()
            var acc = 0.0
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtimeNanos()
                acc = (acc + (now - last) / 1e9).coerceAtMost(MAX_CATCH_UP)
                last = now
                var ticks = 0
                while (acc >= DT) {
                    engine.tick(DT)
                    acc -= DT
                    ticks++
                }
                if (ticks == 0) continue
                lastTickNanos = System.nanoTime()
                drainEvents()
                autosaveAcc += ticks * DT
                if (autosaveAcc >= AUTOSAVE_SECONDS) {
                    autosaveAcc = 0.0
                    saveNow(sync = false)
                }
                maybeInterstitial()
                bump()
            }
        }
    }

    private fun bump() {
        version++
    }

    fun saveNow(sync: Boolean) {
        state.lastSaveMillis = System.currentTimeMillis()
        val json = com.dotaidle.core.save.SaveCodec.encode(state)
        if (sync) repo.write(json) else viewModelScope.launch(Dispatchers.IO) { repo.write(json) }
    }

    // ---------------------------------------------------------------------------- events

    private fun drainEvents() {
        val now = System.nanoTime()
        while (true) {
            val e = engine.events.removeFirstOrNull() ?: break
            when (e) {
                is GameEvent.LaneWave -> {
                    addFloater("+${e.gold.fmt()}", GameColors.Gold, e.lane, now)
                    audio.play(AudioService.Sfx.GOLD, 250)
                }
                is GameEvent.EnemyKilled -> {
                    addFloater("+${e.bounty.fmt()}", GameColors.Gold, FloatArea.BATTLE, now)
                    audio.play(AudioService.Sfx.KILL, 200)
                }
                is GameEvent.AbilityCast -> audio.play(AudioService.Sfx.CAST, 150)
                is GameEvent.GoldGained -> addFloater("+${e.gold.fmt()}", GameColors.Gold, FloatArea.TOP, now)
                GameEvent.AegisGained -> showBanner(tr("aegis_gained"))
                GameEvent.AncientDestroyed -> showBanner(tr("ancient_down"))
            }
        }
        floaters.removeAll { now - it.bornNanos > FLOATER_LIFE_NANOS }
        while (floaters.size > 40) floaters.removeAt(0)
    }

    private fun addFloater(text: String, color: Color, area: Int, now: Long) {
        floaters.add(Floater(floaterId++, text, color, area, now, ((floaterId * 37) % 100) / 100f))
    }

    private fun showBanner(text: String) {
        banner = text to System.nanoTime()
        audio.play(AudioService.Sfx.BIG, 500)
    }

    private fun maybeInterstitial() {
        if (state.secondsSinceInterstitial < Balance.INTERSTITIAL_INTERVAL_SECONDS) return
        if (state.settings.noAds) {
            state.secondsSinceInterstitial = 0.0
            return
        }
        if (ads.current != null || offlineReport != null) return
        state.secondsSinceInterstitial = 0.0
        ads.showInterstitial()
    }

    // ---------------------------------------------------------------------------- actions

    /** Runs a player action; plays a click on success and refreshes the UI immediately. */
    fun act(action: GameEngine.() -> Boolean) {
        if (engine.action()) audio.play(AudioService.Sfx.CLICK, 30)
        drainEvents()
        bump()
    }

    fun selectTab(t: Tab) {
        tab = t
        audio.play(AudioService.Sfx.CLICK, 30)
        audio.setTrack(if (t == Tab.BATTLE) AudioService.Track.BATTLE else AudioService.Track.MENU)
    }

    fun tapAttack() {
        val dmg = engine.tapAttack()
        addFloater("-${dmg.fmt()}", GameColors.RedBright, FloatArea.BATTLE, System.nanoTime())
        audio.play(AudioService.Sfx.CLICK, 30)
        drainEvents()
        bump()
    }

    fun showTooltip(text: String) {
        tooltip = text
        tooltipNonce++
    }

    fun rebirth() {
        val gain = engine.rebirth()
        if (gain > 0) {
            showBanner("+$gain MMR")
            saveNow(sync = false)
        }
        bump()
    }

    fun watchGoldX2() = ads.showRewarded {
        engine.activateGoldX2()
        bump()
    }

    fun watchFarm() {
        if (!engine.farmAdReady(System.currentTimeMillis())) return
        ads.showRewarded {
            engine.claimFarmAd(System.currentTimeMillis())?.let { r ->
                toast = tr("farm_reward", r.gold.fmt(), r.enemiesKilled)
            }
            drainEvents()
            bump()
        }
    }

    fun doubleOffline() {
        val r = offlineReport ?: return
        offlineReport = null
        ads.showRewarded {
            engine.grantGold(r.gold)
            drainEvents()
            bump()
        }
    }

    fun setLanguage(lang: String) {
        state.settings.language = lang
        I18n.language = lang
        bump()
    }

    fun setSound(on: Boolean) {
        state.settings.sound = on
        audio.soundOn = on
        bump()
    }

    fun setMusic(on: Boolean) {
        state.settings.music = on
        audio.musicOn = on
        bump()
    }

    fun removeAds() = billing.purchase(BillingService.REMOVE_ADS) { ok ->
        if (ok) {
            state.settings.noAds = true
            saveNow(sync = false)
            bump()
        }
    }

    fun resetProgress() {
        val settings = state.settings
        repo.delete()
        engine.replaceState(GameState.newGame().also { it.settings = settings })
        state.lastSaveMillis = System.currentTimeMillis()
        floaters.clear()
        offlineReport = null
        saveNow(sync = true)
        bump()
    }

    companion object {
        const val DT = 0.1 // 10 ticks per second
        const val TICK_MS = 100L
        const val MAX_CATCH_UP = 5.0
        const val AUTOSAVE_SECONDS = 15.0
        const val FLOATER_LIFE_NANOS = 1_200_000_000L
    }
}
