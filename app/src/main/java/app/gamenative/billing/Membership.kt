package app.gamenative.billing

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import androidx.compose.runtime.mutableStateOf

object Membership {
    val plansRequested = mutableStateOf(false)
    private val PAID_TIERS = setOf("basic", "pro", "patron")

    fun isPaidTier(tier: String?): Boolean = tier in PAID_TIERS

    fun isMember(): Boolean =
        PrefManager.tipped ||
            BuildConfig.GOLD ||
            isPaidTier(AccountApi.account.value?.tier) ||
            PlayBilling.hasActivePlan.value

    fun tierKnown(): Boolean = !PrefManager.gameNativeSignedIn.value || AccountApi.account.value != null

    fun canPitch(): Boolean = tierKnown() && !isMember()

    suspend fun refresh() {
        PlayBilling.refresh()
        if (AccountApi.loadSignedInState()) {
            val result = AccountApi.fetchAccount()
            if (result is ApiResult.Success) PlayBilling.linkAccount(result.data.id)
        }
    }
}
