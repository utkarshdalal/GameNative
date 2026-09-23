package app.gamenative.utils

import app.gamenative.PrefManager
import com.posthog.PostHog
import java.util.UUID

object ConversionTracker {

    fun featuredConversion(campaignId: String, actionType: String, appId: Int?, source: String) {
        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "action_type" to actionType,
            "source" to source,
        )
        appId?.let { properties["app_id"] = it }

        capture("featured_conversion", properties)
    }

    /** Fired when the booting splash hides, with how long the sponsor card was on screen. */
    fun bootAdShown(campaignId: String, dwellSeconds: Long) {
        capture(
            "boot_ad_shown",
            mutableMapOf(
                "campaign_id" to campaignId,
                "dwell_seconds" to dwellSeconds,
            ),
        )
    }

    private fun capture(event: String, properties: MutableMap<String, Any>) {
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(event = event, properties = properties)
        } else {
            properties["\$process_person_profile"] = false
            PostHog.capture(
                event = event,
                distinctId = UUID.randomUUID().toString(),
                properties = properties,
            )
        }
    }
}
