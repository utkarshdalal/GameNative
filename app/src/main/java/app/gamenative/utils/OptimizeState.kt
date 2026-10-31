package app.gamenative.utils

object OptimizeState {
    @Volatile var enabled: Boolean = false
    @Volatile var goal: String? = null
    @Volatile var step: String? = null
    @Volatile var profileId: String? = null
    @Volatile var templateId: String? = null
    @Volatile var applyResult: String? = null

    fun properties(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "goal" to goal,
        "step" to step,
        "profile_id" to profileId,
        "template_id" to templateId,
        "apply_result" to applyResult,
    )
}
