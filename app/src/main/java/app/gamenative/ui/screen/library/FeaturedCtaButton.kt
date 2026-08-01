package app.gamenative.ui.screen.library

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.FeaturedCta
import app.gamenative.service.SteamService
import app.gamenative.service.SteamWishlistService
import app.gamenative.ui.util.SnackbarManager
import com.posthog.PostHog
import kotlinx.coroutines.launch

/**
 * One featured call-to-action. Types with an in-app handler ([InAppCta]) run on-device and render
 * a done state; every other type deep-links to the action URL, which is also the fallback when an
 * in-app handler fails. New in-app types only need a new [InAppCta] entry.
 */
@Composable
internal fun FeaturedCtaButton(action: FeaturedCta, campaignId: String, recSource: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cta = remember(action) { InAppCta.forAction(action) }
    // null = unknown (e.g. wishlist private); the button then stays in its idle state.
    var done by remember(action) { mutableStateOf<Boolean?>(null) }
    var busy by remember(action) { mutableStateOf(false) }

    LaunchedEffect(action) {
        if (cta != null) {
            done = cta.isDone()
        }
    }

    val openUrl = { context.startActivity(Intent(Intent.ACTION_VIEW, action.url.toUri())) }

    val onClick: () -> Unit = {
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(
                event = "featured_action_clicked",
                properties = mapOf(
                    "campaign_id" to campaignId,
                    "action_label" to action.label,
                    "url" to action.url,
                    "source" to recSource,
                ),
            )
        }
        if (cta == null) {
            openUrl()
        } else {
            busy = true
            scope.launch {
                val ok = cta.run(context)
                busy = false
                if (ok) {
                    done = true
                } else {
                    SnackbarManager.show(context.getString(cta.failedTextRes))
                    openUrl()
                }
            }
        }
    }

    val label = if (cta != null && done == true) stringResource(cta.doneLabelRes) else action.label
    val enabled = cta == null || (!busy && done != true)

    if (action.primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** An action type the app can complete itself instead of deep-linking. */
private sealed class InAppCta(
    val appId: Int,
    @StringRes val doneLabelRes: Int,
    @StringRes val failedTextRes: Int,
) {
    /** True/false when the state is known, null when it cannot be determined. */
    abstract suspend fun isDone(): Boolean?

    /** Runs the action; true on success. */
    abstract suspend fun run(context: Context): Boolean

    private class Wishlist(appId: Int) : InAppCta(
        appId,
        R.string.featured_action_wishlisted,
        R.string.featured_wishlist_failed,
    ) {
        override suspend fun isDone(): Boolean? = SteamWishlistService.isWishlisted(appId)

        override suspend fun run(context: Context): Boolean =
            SteamWishlistService.addToWishlist(appId) is SteamWishlistService.Outcome.Success
    }

    private class GetDemo(appId: Int) : InAppCta(
        appId,
        R.string.featured_action_in_library,
        R.string.featured_demo_failed,
    ) {
        override suspend fun isDone(): Boolean = SteamService.isAppInLibrary(appId)

        override suspend fun run(context: Context): Boolean =
            SteamService.requestFreeLicense(appId).also { granted ->
                if (granted) {
                    SnackbarManager.show(context.getString(R.string.featured_demo_added))
                }
            }
    }

    companion object {
        fun forAction(action: FeaturedCta): InAppCta? {
            val appId = action.appId ?: return null
            return when (action.type) {
                "WISHLIST" -> Wishlist(appId)
                "GET_DEMO" -> GetDemo(appId)
                else -> null
            }
        }
    }
}
