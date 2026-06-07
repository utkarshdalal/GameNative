package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object NexusImportState {
    val reusableStatuses = setOf(
        ModInstallStatus.READY.name,
        ModInstallStatus.APPLIED.name,
        ModInstallStatus.DISABLED.name,
    )

    val resumableImportStatuses = setOf(
        ModInstallStatus.IMPORTING.name,
        ModInstallStatus.PAUSED.name,
    )

    fun restorablePreviousInstall(previousInstall: ModInstall?): ModInstall? =
        previousInstall
            ?.takeIf { it.status in reusableStatuses }
            ?: previousInstall?.metadataPreviousInstall()

    fun terminalInstall(
        importing: ModInstall,
        summary: String,
        status: ModInstallStatus,
        message: String,
        previousInstall: ModInstall?,
        now: Long = System.currentTimeMillis(),
    ): ModInstall =
        if (status != ModInstallStatus.PAUSED && previousInstall != null) {
            previousInstall.copy(updatedAt = now)
        } else {
            importing.copy(
                status = status.name,
                updatedAt = now,
                metadataJson = errorMetadata(summary, message, previousInstall),
            )
        }

    fun importMetadata(summary: String, previousInstall: ModInstall? = null): String =
        JSONObject()
            .put("summary", summary)
            .apply {
                previousInstall?.let { put("previousInstall", it.toMetadataJson()) }
            }
            .toString()

    fun errorMetadata(summary: String, error: String, previousInstall: ModInstall? = null): String =
        JSONObject()
            .put("summary", summary)
            .put("error", error)
            .apply {
                previousInstall?.let { put("previousInstall", it.toMetadataJson()) }
            }
            .toString()

    fun userMessage(error: Throwable, fallback: String = "Failed to import Nexus mod"): String {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase()
        return when (error) {
            is ModImportPausedException -> raw.ifBlank { "Import paused because Wi-Fi/LAN-only downloads are enabled." }
            is ModImportCanceledException -> raw.ifBlank { "Import canceled." }
            is NexusApiException -> apiMessage(error, fallback)
            is UnknownHostException -> "Network connection failed. Check your connection and try again."
            is SocketTimeoutException -> "Network request timed out. Check your connection and try again."
            is IOException -> when {
                "nexus did not return a download link" in normalized ->
                    "Nexus did not return a download link for this file. It may require manual download or different account access."
                "download ended early" in normalized ->
                    "Download was interrupted before it finished. Retry to resume or redownload the file."
                "no space left" in normalized || "enospc" in normalized ->
                    "Storage ran out while importing this mod. Free space and retry."
                "unsupported archive type" in normalized ->
                    raw.ifBlank { "Unsupported archive type. Try a ZIP, 7Z, or RAR archive." }
                "failed to move extracted mod files" in normalized ->
                    "Could not finalize extracted mod files. Check storage and retry."
                else -> raw.ifBlank { fallback }
            }
            else -> raw.ifBlank { fallback }
        }
    }

    private fun apiMessage(error: NexusApiException, fallback: String): String {
        val quota = buildString {
            error.hourlyRemaining?.let { append(" Hourly remaining: $it.") }
            error.dailyRemaining?.let { append(" Daily remaining: $it.") }
        }
        val base = when (error.statusCode) {
            401, 403 -> "Nexus API authentication failed. Check the API key and account access."
            404 -> error.message ?: "Nexus could not find this mod, file, or collection revision."
            429 -> "Nexus API rate limit reached."
            else -> error.message ?: fallback
        }
        return base + quota
    }

    private fun ModInstall.metadataPreviousInstall(): ModInstall? =
        runCatching {
            JSONObject(metadataJson)
                .optJSONObject("previousInstall")
                ?.toModInstall()
        }.getOrNull()

    private fun ModInstall.toMetadataJson(): JSONObject =
        JSONObject()
            .put("installId", installId)
            .put("appId", appId)
            .put("source", source)
            .put("nexusGameDomain", nexusGameDomain)
            .put("nexusModId", nexusModId)
            .put("nexusFileId", nexusFileId)
            .put("modName", modName)
            .put("fileName", fileName)
            .put("version", version)
            .put("sizeBytes", sizeBytes)
            .put("archivePath", archivePath)
            .put("extractedPath", extractedPath)
            .put("enabled", enabled)
            .put("status", status)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("downloadedAt", downloadedAt)
            .put("metadataJson", metadataJson)

    private fun JSONObject.toModInstall(): ModInstall? {
        val installId = optString("installId").takeIf(String::isNotBlank) ?: return null
        val appId = optString("appId").takeIf(String::isNotBlank) ?: return null
        val gameDomain = optString("nexusGameDomain").takeIf(String::isNotBlank) ?: return null
        val modId = optLong("nexusModId", 0L).takeIf { it > 0L } ?: return null
        val fileId = optLong("nexusFileId", 0L).takeIf { it > 0L } ?: return null
        return ModInstall(
            installId = installId,
            appId = appId,
            source = optString("source", "NEXUS"),
            nexusGameDomain = gameDomain,
            nexusModId = modId,
            nexusFileId = fileId,
            modName = optString("modName", "Nexus mod $modId"),
            fileName = optString("fileName"),
            version = optString("version"),
            sizeBytes = optLong("sizeBytes", 0L),
            archivePath = optString("archivePath"),
            extractedPath = optString("extractedPath"),
            enabled = optBoolean("enabled", true),
            status = optString("status", ModInstallStatus.READY.name),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            downloadedAt = optLong("downloadedAt", 0L),
            metadataJson = optString("metadataJson"),
        )
    }
}
