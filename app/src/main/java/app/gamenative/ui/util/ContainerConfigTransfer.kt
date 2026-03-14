package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestInstaller
import java.io.IOException
import kotlin.text.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

object ContainerConfigTransfer {
    suspend fun exportConfig(
        context: Context,
        appId: String,
        uri: Uri,
    ): Boolean {
        return try {
            val jsonText =
                withContext(Dispatchers.IO) {
                    val container = ContainerUtils.getOrCreateContainer(context, appId)
                    JSONObject(container.containerJson).toString(2)
                }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonText.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
            }

            SnackbarManager.show(
                context.getString(R.string.base_app_exported),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SnackbarManager.show(
                context.getString(
                    R.string.base_app_export_failed,
                    e.message ?: "IO error",
                ),
            )
            false
        } catch (e: Exception) {
            SnackbarManager.show(
                context.getString(
                    R.string.base_app_export_failed,
                    e.message ?: "Unknown error",
                ),
            )
            false
        }
    }

    suspend fun importConfig(
        context: Context,
        appId: String,
        uri: Uri,
    ): Boolean {
        return try {
            val jsonText =
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.orEmpty()

            if (jsonText.isBlank()) {
                SnackbarManager.show(
                    context.getString(R.string.best_config_known_config_invalid),
                )
                return false
            }

            // Parse as BestConfig-style JSON
            val configJson: JsonObject =
                withContext(Dispatchers.Default) {
                    Json.parseToJsonElement(jsonText).jsonObject
                }

            val matchType = "exact_gpu_match"

            // 1) Parse config into a validated map of fields to apply
            val bestConfigMap = BestConfigService.parseConfigToContainerData(
                context = context,
                configJson = configJson,
                matchType = matchType,
                applyKnownConfig = true,
            ) ?: emptyMap()

            if (bestConfigMap.isEmpty()) {
                SnackbarManager.show(
                    context.getString(R.string.best_config_known_config_invalid),
                )
                return false
            }

            // 2) Install any missing manifest components (wine/proton, dxvk, drivers, etc.)
            val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
                context = context,
                configJson = configJson,
                matchType = matchType,
            )
            for (request in missingRequests) {
                val result = ManifestInstaller.installManifestEntry(
                    context = context,
                    entry = request.entry,
                    isDriver = request.isDriver,
                    contentType = request.contentType,
                    onProgress = { _ -> },
                )
                SnackbarManager.show(result.message)
                if (!result.success) {
                    return false
                }
            }

            // 3) Apply to container using the same mapping logic as BestConfig
            withContext(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val currentData = ContainerUtils.toContainerData(container)
                val updatedData = ContainerUtils.applyBestConfigMapToContainerData(currentData, bestConfigMap)
                ContainerUtils.applyToContainer(context, container, updatedData)
            }

            SnackbarManager.show(
                context.getString(R.string.best_config_applied_successfully),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    e.message ?: "IO error",
                ),
            )
            false
        } catch (e: Exception) {
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    e.message ?: "Unknown error",
                ),
            )
            false
        }
    }
}

