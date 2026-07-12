package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.db.dao.ModDao
import java.util.Locale

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
    ): ModProfileInstallState =
        dao.ensureProfileInstallState(profile, installId, enabled, priority)

    suspend fun ensureStatesForInstalls(
        dao: ModDao,
        profile: ModProfile,
        installs: List<ModInstall>,
    ): List<ModProfileInstallState> {
        val existingStates = dao.getProfileInstallStates(profile.appId, profile.profileId)
        val existingByInstallId = existingStates.associateBy { it.installId }
        val profileHasExistingStates = existingStates.isNotEmpty()
        var changed = false

        installs.forEach { install ->
            val existing = existingByInstallId[install.installId]
            when {
                existing == null -> {
                    dao.ensureProfileInstallState(
                        profile = profile,
                        installId = install.installId,
                        enabled = defaultMissingStateEnabled(profile, install, profileHasExistingStates),
                    )
                    changed = true
                }
                install.status == ModInstallStatus.DISABLED.name && existing.enabled -> {
                    dao.upsertProfileInstallState(existing.copy(enabled = false, updatedAt = System.currentTimeMillis()))
                    changed = true
                }
            }
        }

        return if (changed) {
            dao.getProfileInstallStates(profile.appId, profile.profileId)
        } else {
            existingStates
        }.let { states -> normalizePriorities(dao, states, installs) }
    }

    internal fun defaultMissingStateEnabled(
        profile: ModProfile,
        install: ModInstall,
        profileHasExistingStates: Boolean,
    ): Boolean =
        !profileHasExistingStates &&
            install.status != ModInstallStatus.DISABLED.name &&
            install.createdAt <= profile.createdAt

    internal suspend fun normalizePriorities(
        dao: ModDao,
        states: List<ModProfileInstallState>,
        installs: List<ModInstall>,
    ): List<ModProfileInstallState> {
        val normalizedPriorities = normalizedPriorityByInstallId(states, installs)
        if (normalizedPriorities.isEmpty()) return states

        var changed = false
        states.forEach { state ->
            val normalized = normalizedPriorities[state.installId] ?: return@forEach
            if (state.priority != normalized) {
                dao.upsertProfileInstallState(state.copy(priority = normalized, updatedAt = System.currentTimeMillis()))
                changed = true
            }
        }
        return if (changed) dao.getProfileInstallStates(states.first().appId, states.first().profileId) else states
    }

    internal fun normalizedPriorityByInstallId(
        states: List<ModProfileInstallState>,
        installs: List<ModInstall>,
    ): Map<String, Int> {
        val installById = installs.associateBy { it.installId }
        val orderedTopToBottom = states
            .filter { it.installId in installById }
            .sortedWith(
                compareByDescending<ModProfileInstallState> { it.priority }
                    .thenBy { installById[it.installId]?.modName?.lowercase(Locale.US).orEmpty() }
                    .thenBy { installById[it.installId]?.fileName?.lowercase(Locale.US).orEmpty() }
                    .thenBy { it.installId },
            )
        return orderedTopToBottom
            .mapIndexed { index, state -> state.installId to orderedTopToBottom.lastIndex - index }
            .toMap()
    }

    fun defaultProfileId(appId: String): String =
        "${appId.trim().ifBlank { "app" }}:default"
}
