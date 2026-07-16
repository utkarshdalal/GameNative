package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object NexusImportState {
    private const val WEBSITE_AUTHORIZATION_REQUIRED_KEY = "websiteAuthorizationRequired"
    private const val DOWNLOAD_COMPLETE_KEY = "downloadComplete"
    private const val DOWNLOAD_COMPLETE_BYTES_KEY = "downloadCompleteBytes"
    private const val BARE_EXPIRED_AUTHORIZATION_MESSAGE = "The Nexus website download authorization expired"
    private const val EXPIRED_AUTHORIZATION_MESSAGE =
        "$BARE_EXPIRED_AUTHORIZATION_MESSAGE. Open Nexus Mods and authorize the file again."

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

    fun pauseForWebsiteAuthorization(
        install: ModInstall,
        message: String,
        now: Long = System.currentTimeMillis(),
    ): ModInstall {
        val metadata = runCatching { JSONObject(install.metadataJson) }.getOrElse { JSONObject() }
        val summary = metadata.optString("summary")
        metadata
            .put("summary", summary)
            .put("error", message)
            .put(WEBSITE_AUTHORIZATION_REQUIRED_KEY, true)
            .put(DOWNLOAD_COMPLETE_KEY, false)
        metadata.remove(DOWNLOAD_COMPLETE_BYTES_KEY)
        return install.copy(
            status = ModInstallStatus.PAUSED.name,
            updatedAt = now,
            metadataJson = metadata.toString(),
        )
    }

    fun isWaitingForWebsiteAuthorization(install: ModInstall): Boolean =
        runCatching {
            JSONObject(install.metadataJson).optBoolean(WEBSITE_AUTHORIZATION_REQUIRED_KEY, false)
        }.getOrDefault(false)

    fun markDownloadComplete(install: ModInstall, downloadedBytes: Long): ModInstall {
        val metadata = runCatching { JSONObject(install.metadataJson) }.getOrElse { JSONObject() }
            .put(DOWNLOAD_COMPLETE_KEY, true)
            .put(DOWNLOAD_COMPLETE_BYTES_KEY, downloadedBytes)
            .put(WEBSITE_AUTHORIZATION_REQUIRED_KEY, false)
        return install.copy(
            updatedAt = System.currentTimeMillis(),
            metadataJson = metadata.toString(),
        )
    }

    fun hasCompletedDownload(install: ModInstall?, archiveBytes: Long): Boolean =
        install != null && runCatching {
            val metadata = JSONObject(install.metadataJson)
            metadata.optBoolean(DOWNLOAD_COMPLETE_KEY, false) &&
                archiveBytes > 0L &&
                metadata.optLong(DOWNLOAD_COMPLETE_BYTES_KEY, -1L) == archiveBytes
        }.getOrDefault(false)

    fun userMessage(
        error: Throwable,
        fallback: String = "Failed to import Nexus mod",
        expiredAuthorizationMessage: String? = null,
    ): String {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase()
        if (error is ModImportPausedException) {
            return raw.ifBlank { "Import paused because Wi-Fi/LAN-only downloads are enabled." }
        }
        if (error is ModImportCanceledException) {
            return raw.ifBlank { "Import canceled." }
        }
        apiException(error)?.let {
            return apiMessage(it, fallback, expiredAuthorizationMessage)
        }
        return when (error) {
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

    fun apiException(error: Throwable): NexusApiException? {
        var current: Throwable? = error
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is NexusApiException) return current
            current = current.cause
        }
        return null
    }

    fun requiresWebsiteAuthorization(error: Throwable): Boolean =
        apiException(error)?.reason in setOf(
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_REQUIRED,
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_INVALID,
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED,
        )

    private fun apiMessage(
        error: NexusApiException,
        fallback: String,
        expiredAuthorizationMessage: String?,
    ): String {
        val quota = if (error.reason == NexusApiErrorReason.RATE_LIMITED || error.statusCode == 429) {
            buildString {
                error.hourlyRemaining?.let { append(" Hourly remaining: $it.") }
                error.dailyRemaining?.let { append(" Daily remaining: $it.") }
            }
        } else {
            ""
        }
        val base = when (error.reason) {
            NexusApiErrorReason.AUTHENTICATION -> "Nexus rejected the API key. Reconnect the Nexus account and try again."
            NexusApiErrorReason.FORBIDDEN -> "Nexus denied access to this resource for the current account."
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_REQUIRED ->
                "Free Nexus accounts must authorize this file on the Nexus Mods website before GameNative can download it."
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_INVALID ->
                "Nexus rejected the website download authorization. Make sure the browser and GameNative use the same Nexus account, then try again."
            NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED ->
                expiredAuthorizationMessage
                    ?: error.message
                    ?.takeUnless { it.trimEnd('.') == BARE_EXPIRED_AUTHORIZATION_MESSAGE }
                    ?: EXPIRED_AUTHORIZATION_MESSAGE
            NexusApiErrorReason.NOT_FOUND -> error.message ?: "Nexus could not find this mod, file, or collection revision."
            NexusApiErrorReason.RATE_LIMITED -> "Nexus API rate limit reached."
            NexusApiErrorReason.OTHER -> when (error.statusCode) {
                401 -> "Nexus rejected the API key. Reconnect the Nexus account and try again."
                403 -> "Nexus denied access to this resource for the current account."
                404 -> error.message ?: "Nexus could not find this mod, file, or collection revision."
                429 -> "Nexus API rate limit reached."
                else -> error.message ?: fallback
            }
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
