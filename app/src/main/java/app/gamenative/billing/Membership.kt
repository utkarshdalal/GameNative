package app.gamenative.billing

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.api.AccountApi

object Membership {
    private val PAID_TIERS = setOf("basic", "pro", "patron")

    fun isPaidTier(tier: String?): Boolean = tier in PAID_TIERS

    fun isMember(): Boolean =
        PrefManager.tipped ||
            BuildConfig.GOLD ||
            isPaidTier(AccountApi.account.value?.tier) ||
            PlayBilling.hasActivePlan.value

    suspend fun refresh() {
        PlayBilling.refresh()
        if (AccountApi.loadSignedInState()) AccountApi.fetchAccount()
    }
}
