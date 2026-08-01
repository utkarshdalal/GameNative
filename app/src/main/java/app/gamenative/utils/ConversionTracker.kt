package app.gamenative.utils

import app.gamenative.PrefManager
import com.posthog.PostHog
import java.util.UUID

/**
 * Billing-grade conversion counting for sponsored campaigns.
 *
 * A confirmed conversion (wishlist added, demo granted) is always captured, unlike behavioral
 * events, because campaign billing needs a complete count. Consent still decides what the event
 * carries: opted-in users send a normal identified event; opted-out users send a personless event
 * under a single-use random id, so no person profile is created and nothing links it to a device
 * or to other events. Region reporting works for both, via PostHog's server-side GeoIP enrichment.
 */
object ConversionTracker {

    fun featuredConversion(campaignId: String, actionType: String, appId: Int?, source: String) {
        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "action_type" to actionType,
            "source" to source,
        )
        appId?.let { properties["app_id"] = it }

        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(event = "featured_conversion", properties = properties)
        } else {
            properties["\$process_person_profile"] = false
            PostHog.capture(
                event = "featured_conversion",
                distinctId = UUID.randomUUID().toString(),
                properties = properties,
            )
        }
    }
}
