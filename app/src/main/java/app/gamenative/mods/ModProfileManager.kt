package app.gamenative.mods

import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.db.dao.ModDao

object ModProfileManager {
    const val DEFAULT_PROFILE_NAME = "Default"

    suspend fun ensureActiveProfile(dao: ModDao, appId: String): ModProfile {
        dao.getActiveProfileForApp(appId)?.let { return it }
        val existingDefault = dao.getProfilesForApp(appId).firstOrNull { it.name == DEFAULT_PROFILE_NAME }
        val profile = existingDefault?.copy(active = true, updatedAt = System.currentTimeMillis())
            ?: ModProfile(
                profileId = defaultProfileId(appId),
                appId = appId,
                name = DEFAULT_PROFILE_NAME,
                active = true,
            )
        dao.upsertProfile(profile)
        dao.activateProfile(appId, profile.profileId)
        return profile
    }

    suspend fun ensureStateForInstall(
        dao: ModDao,
        profile: ModProfile,
        installId: String,
        enabled: Boolean = true,
        priority: Int? = null,
    ): ModProfileInstallState {
        val existing = dao.getProfileInstallStates(profile.appId, profile.profileId)
            .firstOrNull { it.installId == installId }
        if (existing != null) return existing

        val nextPriority = priority ?: dao.getProfileInstallStates(profile.appId, profile.profileId)
            .maxOfOrNull { it.priority + 1 }
            ?: 0
        val state = ModProfileInstallState(
            profileId = profile.profileId,
            installId = installId,
            appId = profile.appId,
            enabled = enabled,
            priority = nextPriority,
        )
        dao.upsertProfileInstallState(state)
        return state
    }

    fun defaultProfileId(appId: String): String =
        "${appId.trim().ifBlank { "app" }}:default"
}
