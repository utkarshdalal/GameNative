package app.gamenative.billing

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.R
import app.gamenative.PrefManager
import app.gamenative.api.AccountApi
import app.gamenative.api.ApiResult
import app.gamenative.optimize.OptimizeController
import androidx.compose.runtime.mutableStateOf

object Membership {
    val plansRequested = mutableStateOf(false)
    private val PAID_TIERS = setOf("basic", "pro", "patron")

    fun isPaidTier(tier: String?): Boolean = tier in PAID_TIERS

    fun isMember(): Boolean =
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

    /** The occasional membership pitch; the "ask for tip" switch turns only this off. */
    fun canPitch(): Boolean = tierKnown() && !isMember() && !PrefManager.tipped

    /** The account's tier, "none" when signed out, for noticing a plan change; null while the tier is not loaded. */
    fun planKey(): String? = if (tierKnown()) AccountApi.account.value?.tier ?: "none" else null

    /** Whether the free AI help game can be offered: signed out, or the account says it is unused. */
    fun aiTrialOffered(): Boolean =
        !isMember() && (!PrefManager.gameNativeSignedIn.value || AccountApi.account.value?.trialAvailable == true)

    /** Whether the account is known to have used its free AI help game and has no plan; false while not loaded. */
    fun aiTrialUsed(): Boolean =
        PrefManager.gameNativeSignedIn.value && AccountApi.account.value?.trialAvailable == false && !isMember()

    /** The AI help plan for analytics, from what is already loaded: "unknown" while the account is not. */
    fun aiPlan(): String {
        if (!PrefManager.gameNativeSignedIn.value) return "none"
        val account = AccountApi.account.value ?: return "unknown"
        return when (account.tier) {
            "basic" -> "basic"
            "pro", "patron" -> "pro"
            "none" -> if (account.trialAvailable) "trial_available" else "trial_used"
            else -> "unknown"
        }
    }

    suspend fun refresh() {
        PlayBilling.refresh()
        if (AccountApi.loadSignedInState()) {
            val result = AccountApi.fetchAccount()
            if (result is ApiResult.Success) PlayBilling.linkAccount(result.data.id)
        }
        planKey()?.let { OptimizeController.onPlanRefreshed(it) }
    }
}
