package app.gamenative.ui.screen.library

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ConversionTracker
import com.posthog.PostHog
import kotlinx.coroutines.launch

@Composable
internal fun FeaturedCtaButton(
    action: FeaturedCta,
    campaignId: String,
    recSource: String,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val cta = remember(action) { InAppCta.forAction(action, campaignId) }
    var done by remember(action) { mutableStateOf<Boolean?>(null) }
    var busy by remember(action) { mutableStateOf(false) }

    LaunchedEffect(action) {
        if (cta != null) {
            done = cta.isDone()
        }
    }

    val openUrl = { context.startActivity(Intent(Intent.ACTION_VIEW, action.url.toUri())) }

    val inert = cta != null && (busy || done == true)

    val onClick: () -> Unit = onClick@{
        if (inert) return@onClick

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
                    ConversionTracker.featuredConversion(
                        campaignId = campaignId,
                        actionType = action.type,
                        appId = action.appId,
                        source = recSource,
                    )
                } else {
                    SnackbarManager.show(context.getString(R.string.featured_action_failed))
                    openUrl()
                }
            }
        }
    }

    val label = if (cta != null && done == true) stringResource(cta.doneLabelRes) else action.label
    val shape = RoundedCornerShape(12.dp)
    val buttonModifier = Modifier
        .fillMaxWidth()
        .focusRing(interactionSource, shape, width = 2.dp)
        .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }

    val contentAlpha = if (inert) 0.6f else 1f

    if (action.primary) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            shape = shape,
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            ),
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            shape = shape,
            interactionSource = interactionSource,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            ),
        ) {
            Text(text = label, fontWeight = FontWeight.SemiBold)
        }
    }
}

private sealed class InAppCta(
    val appId: Int,
    @StringRes val doneLabelRes: Int,
) {
    abstract suspend fun isDone(): Boolean?

    abstract suspend fun run(context: Context): Boolean

    private class Wishlist(appId: Int, private val campaignId: String) : InAppCta(appId, R.string.featured_action_wishlisted) {
        override suspend fun isDone(): Boolean? = SteamWishlistService.isWishlisted(appId)

        override suspend fun run(context: Context): Boolean =
            SteamWishlistService.addToWishlistAttributed(context, appId, campaignId) is SteamWishlistService.Outcome.Success
    }

    private class GetDemo(appId: Int) : InAppCta(appId, R.string.featured_action_in_library) {
        override suspend fun isDone(): Boolean = SteamService.isAppInLibrary(appId)

        override suspend fun run(context: Context): Boolean = SteamService.requestFreeLicense(appId)
    }

    companion object {
        fun forAction(action: FeaturedCta, campaignId: String): InAppCta? {
            val appId = action.appId ?: return null
            return when (action.type) {
                "WISHLIST" -> Wishlist(appId, campaignId)
                "GET_DEMO" -> GetDemo(appId)
                else -> null
            }
        }
    }
}
