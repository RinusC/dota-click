package com.dotaidle.ancientwar.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ads abstraction. Swap [StubAdService] for a real SDK adapter (AdMob, AppLovin, ...)
 * without touching game code: only these two calls are used.
 */
interface AdService {
    /** Shows a rewarded video; [onReward] is called only if the user watched it to the end. */
    fun showRewarded(onReward: () -> Unit)

    fun showInterstitial()
}

/** Placeholder ads rendered by the app itself as a full-screen countdown (see AdOverlay). */
class StubAdService : AdService {
    data class StubAd(val rewarded: Boolean, val seconds: Int, val onDone: () -> Unit)

    var current by mutableStateOf<StubAd?>(null)
        private set

    override fun showRewarded(onReward: () -> Unit) {
        if (current == null) current = StubAd(rewarded = true, seconds = 5, onDone = onReward)
    }

    override fun showInterstitial() {
        if (current == null) current = StubAd(rewarded = false, seconds = 3, onDone = {})
    }

    /** Called by the overlay when the countdown finishes. */
    fun complete() {
        val ad = current ?: return
        current = null
        ad.onDone()
    }
}
