package app.gamenative.optimize

import androidx.annotation.StringRes
import app.gamenative.R

enum class OptimizeProfile(val wireId: String, @StringRes val title: Int, @StringRes val description: Int) {
    OFF("off", R.string.optimize_profile_off, R.string.optimize_profile_off_description),
    BALANCED("balanced", R.string.optimize_profile_balanced, R.string.optimize_profile_balanced_description),
    QUALITY("quality", R.string.optimize_profile_quality, R.string.optimize_profile_quality_description),
    PERFORMANCE("performance", R.string.optimize_profile_performance, R.string.optimize_profile_performance_description),
    POWER("power", R.string.optimize_profile_power, R.string.optimize_profile_power_description),
    POTATO("potato", R.string.optimize_profile_potato, R.string.optimize_profile_potato_description),
    ;

    companion object {
        fun fromWire(id: String?): OptimizeProfile = entries.firstOrNull { it.wireId == id } ?: OFF
    }
}
