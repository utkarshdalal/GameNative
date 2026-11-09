package app.gamenative.billing

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.R
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

    fun pitchMessage(context: Context): String {
        val price = PlayBilling.cachedPlans.value.firstOrNull { it.tier == "basic" }?.price
        return if (price != null && PlayBilling.enabled(context)) {
            context.getString(R.string.main_thank_you_message_play, price)
        } else {
            context.getString(R.string.main_thank_you_message)
        }
    }

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
