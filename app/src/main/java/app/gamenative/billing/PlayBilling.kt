package app.gamenative.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import app.gamenative.BuildConfig
import app.gamenative.api.AccountApi
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import kotlinx.coroutines.CancellationException
import timber.log.Timber

object PlayBilling {
    private const val TAG = "PlayBilling"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private val PRODUCT_TIERS = mapOf(
        "gn_basic_monthly" to "basic",
        "gn_pro_monthly" to "pro",
    )

    data class Plan(val tier: String, val price: String, val pkg: Package)

    sealed class PurchaseOutcome {
        data object Success : PurchaseOutcome()
        data object Cancelled : PurchaseOutcome()
        data class Failed(val message: String) : PurchaseOutcome()
    }

    val hasActivePlan = mutableStateOf(false)

    private var playInstall: Boolean? = null

    @Volatile
    private var configured = false

    fun isPlayInstall(context: Context): Boolean {
        playInstall?.let { return it }
        val installer = try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read the installer package")
            null
        }
        return (installer == PLAY_STORE_PACKAGE).also { playInstall = it }
    }

    fun enabled(context: Context): Boolean =
        !BuildConfig.XR_BUILD && BuildConfig.REVENUECAT_API_KEY.isNotEmpty() && isPlayInstall(context)

    fun init(app: Application) {
        if (!enabled(app)) return
        try {
            Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN
            Purchases.configure(PurchasesConfiguration.Builder(app, BuildConfig.REVENUECAT_API_KEY).build())
            Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { onCustomerInfo(it) }
            configured = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "RevenueCat setup failed")
        }
    }

    private fun onCustomerInfo(info: CustomerInfo) {
        hasActivePlan.value = info.activeSubscriptions.isNotEmpty()
    }

    suspend fun refresh() {
        if (!configured) return
        try {
            onCustomerInfo(Purchases.sharedInstance.awaitCustomerInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Customer info refresh failed")
        }
    }

    suspend fun plans(): List<Plan> {
        if (!configured) return emptyList()
        val offerings = Purchases.sharedInstance.awaitOfferings()
        return offerings.all.values
            .flatMap { it.availablePackages }
            .mapNotNull { pkg ->
                val tier = PRODUCT_TIERS[pkg.product.id.substringBefore(':')] ?: return@mapNotNull null
                Plan(tier = tier, price = pkg.product.price.formatted, pkg = pkg)
            }
            .distinctBy { it.tier }
    }

    suspend fun purchase(activity: Activity, plan: Plan): PurchaseOutcome {
        if (!configured) return PurchaseOutcome.Failed("Play billing is not available")
        return try {
            val result = Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, plan.pkg).build())
            onCustomerInfo(result.customerInfo)
            PurchaseOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) {
                PurchaseOutcome.Cancelled
            } else {
                Timber.tag(TAG).w(e, "Purchase failed")
                PurchaseOutcome.Failed(e.message ?: "Purchase failed")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Purchase failed")
            PurchaseOutcome.Failed(e.message ?: "Purchase failed")
        }
    }

    suspend fun linkAccount(accountId: String) {
        if (!configured || accountId.isEmpty()) return
        try {
            onCustomerInfo(Purchases.sharedInstance.awaitLogIn(accountId).customerInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "RevenueCat login failed")
            return
        }
        if (hasActivePlan.value) AccountApi.syncPlay()
    }

    suspend fun unlinkAccount() {
        if (!configured) return
        try {
            onCustomerInfo(Purchases.sharedInstance.awaitLogOut())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "RevenueCat logout failed")
        }
    }
}
