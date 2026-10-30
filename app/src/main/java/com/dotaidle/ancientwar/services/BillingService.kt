package com.dotaidle.ancientwar.services

/** In-app purchases abstraction. Only cosmetic / convenience products (no pay-to-win). */
interface BillingService {
    fun purchase(productId: String, onResult: (Boolean) -> Unit)

    companion object {
        const val REMOVE_ADS = "remove_ads"
    }
}

/** Always succeeds. Replace with a Google Play Billing implementation for production. */
class StubBillingService : BillingService {
    override fun purchase(productId: String, onResult: (Boolean) -> Unit) = onResult(true)
}
