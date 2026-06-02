package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.runtime.WebViewContainer
import app.gamenative.ui.screen.library.appscreen.BaseAppScreen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.BestConfigService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ManifestInstaller
import com.winlator.container.Container
import java.io.IOException
import kotlin.text.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import timber.log.Timber

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
                    val wineJson = JSONObject(container.containerJson)
                    unwrapNestedJsonString(wineJson, "gestureConfig")
                    val isHtml5 = container.containerVariant
                        .equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)
                    if (!isHtml5) {
                        // wine path: gestureConfig nested as object so editors see real keys, not "{\"...\":...}"
                        wineJson.toString(2)
                    } else {
                        // html5 wrapper format. html5 block is OPTIONAL -- if WebViewContainer JSON
                        // is missing on disk (race / older install), still emit `{"wine": ..., "html5": null}`
                        // so the variant signal survives import even without sidecar fields.
                        val webView = ContainerUtils.loadWebViewContainerForAppId(appId)
                        val wrapper = JSONObject()
                        wrapper.put("wine", wineJson)
                        if (webView != null) {
                            val webViewObj = JSONObject(WebViewContainer.encodeToJson(webView))
                            unwrapNestedJsonString(webViewObj, "gestureConfig")
                            wrapper.put("html5", webViewObj)
                        } else {
                            wrapper.put("html5", JSONObject.NULL)
                        }
                        wrapper.toString(2)
                    }
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
        onInstallStateChange: ((visible: Boolean, progress: Float, label: String) -> Unit)? = null,
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

            // Parse as BestConfig-style JSON. detect html5 wrapper format and unwrap to bare wine
            // block before BestConfigService -- parser only understands the bare wine shape.
            val rootObj: JsonObject =
                withContext(Dispatchers.Default) {
                    Json.parseToJsonElement(jsonText).jsonObject
                }

            // wrapper signal: BOTH "wine" and "html5" present at top level. legacy bare-wine input
            // is byte-identical (rootObj IS wineBlock) so existing JSONs keep importing.
            val isWrapped = rootObj.containsKey("wine") && rootObj.containsKey("html5")
            val wineBlock: JsonObject = if (isWrapped) rootObj["wine"]!!.jsonObject else rootObj
            val html5BlockText: String? = if (isWrapped) {
                rootObj["html5"]?.let { el ->
                    // null literal → no sidecar emitted at export. JsonObject → re-encode for decode.
                    // collapse gestureConfig back to a string literal so kotlinx (String field) can decode.
                    if (el is JsonNull) null else collapseToJsonString(el.jsonObject, "gestureConfig").toString()
                }
            } else {
                null
            }

            val configJson = wineBlock

            val matchType = "exact_gpu_match"

            // 1) Parse config into a validated map of fields to apply
            val bestConfigMap = BestConfigService.parseConfigToContainerData(
                context = context,
                configJson = configJson,
                matchType = matchType,
                applyKnownConfig = true,
            ) ?: emptyMap()

            val missingComponents = BestConfigService.consumeLastMissingComponents()
            if (bestConfigMap.isEmpty()) {
                if (missingComponents.isNotEmpty()) {
                    BaseAppScreen.showMissingComponentsDialog(appId, missingComponents) {
                        // "apply anyway" — re-parse with defaults, install manifest entries, apply
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val forced = BestConfigService.parseConfigToContainerData(
                                    context, configJson, matchType, true, forceApply = true,
                                ) ?: emptyMap()
                                if (forced.isEmpty()) {
                                    SnackbarManager.show(context.getString(R.string.best_config_known_config_invalid))
                                    return@launch
                                }

                                val requests = BestConfigService.resolveMissingManifestInstallRequests(
                                    context, configJson, matchType,
                                )
                                for (request in requests) {
                                    val result = ManifestInstaller.installManifestEntry(
                                        context = context,
                                        entry = request.entry,
                                        isDriver = request.isDriver,
                                        contentType = request.contentType,
                                        onProgress = { _ -> },
                                    )
                                    if (!result.success) {
                                        SnackbarManager.show(result.message)
                                        return@launch
                                    }
                                }

                                val container = ContainerUtils.getOrCreateContainer(context, appId)
                                val currentData = ContainerUtils.toContainerData(container)
                                val updatedData = ContainerUtils.applyBestConfigMapToContainerData(currentData, forced)
                                ContainerUtils.applyToContainer(context, container, updatedData)
                                SnackbarManager.show(context.getString(R.string.best_config_applied_with_defaults))
                            } catch (e: Exception) {
                                SnackbarManager.show(
                                    context.getString(R.string.best_config_apply_failed, e.message ?: "Unknown error"),
                                )
                            }
                        }
                    }
                } else {
                    SnackbarManager.show(
                        context.getString(R.string.best_config_known_config_invalid),
                    )
                }
                return false
            }

            // 2) Install any missing manifest components (wine/proton, dxvk, drivers, etc.)
            val missingRequests = BestConfigService.resolveMissingManifestInstallRequests(
                context = context,
                configJson = configJson,
                matchType = matchType,
            )
            if (missingRequests.isNotEmpty()) {
                onInstallStateChange?.invoke(
                    true,
                    -1f,
                    missingRequests.first().entry.name,
                )
            }
            for (request in missingRequests) {
                val label = request.entry.id
                onInstallStateChange?.invoke(true, -1f, label)
                val result = ManifestInstaller.installManifestEntry(
                    context = context,
                    entry = request.entry,
                    isDriver = request.isDriver,
                    contentType = request.contentType,
                    onProgress = { progress ->
                        onInstallStateChange?.invoke(
                            true,
                            progress.coerceIn(0f, 1f),
                            label,
                        )
                    },
                )
                SnackbarManager.show(result.message)
                if (!result.success) {
                    onInstallStateChange?.invoke(false, -1f, "")
                    return false
                }
            }
            onInstallStateChange?.invoke(false, -1f, "")

            // 3) Apply to container using the same mapping logic as BestConfig
            withContext(Dispatchers.IO) {
                val container = ContainerUtils.getOrCreateContainer(context, appId)
                val currentData = ContainerUtils.toContainerData(container)
                val updatedData = ContainerUtils.applyBestConfigMapToContainerData(currentData, bestConfigMap)
                ContainerUtils.applyToContainer(context, container, updatedData)
            }

            // 4) restore html5 sidecar verbatim when destination is html5 AND wrapper carried one.
            // wine destination ignores html5 block; bad slug → log + drop (resolveFingerprintPath
            // failed, install missing). cross-variant (bare-wine into html5) leaves sidecar untouched.
            if (html5BlockText != null) {
                withContext(Dispatchers.IO) {
                    val destContainer = ContainerUtils.getOrCreateContainer(context, appId)
                    val isHtml5 = destContainer.containerVariant
                        .equals(Container.CONTAINER_VARIANT_HTML5, ignoreCase = true)
                    val slug = ContainerUtils.webViewContainerSlugForAppId(appId)
                    val decoded = WebViewContainer.decodeFromJson(html5BlockText)
                    if (isHtml5 && slug != null && decoded != null) {
                        WebViewContainer.save(slug, decoded)
                    } else {
                        Timber.tag("ContainerConfigTransfer")
                            .w("html5 block dropped: isHtml5=$isHtml5 slug=$slug decoded=${decoded != null}")
                    }
                }
            }

            SnackbarManager.show(
                context.getString(R.string.best_config_applied_successfully),
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onInstallStateChange?.invoke(false, -1f, "")
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    e.message ?: "IO error",
                ),
            )
            false
        } catch (e: Exception) {
            onInstallStateChange?.invoke(false, -1f, "")
            SnackbarManager.show(
                context.getString(
                    R.string.best_config_apply_failed,
                    e.message ?: "Unknown error",
                ),
            )
            false
        }
    }

    // gestureConfig is persisted as a JSON-encoded String inside the container JSON, so the
    // outer stringify escapes every inner quote. unwrap on EXPORT so users editing the file
    // see real keys; no-op when the field is blank or not parseable.
    private fun unwrapNestedJsonString(obj: JSONObject, key: String) {
        val raw = obj.optString(key, "")
        if (raw.isBlank()) return
        runCatching { obj.put(key, JSONObject(raw)) }
}

    // inverse of unwrapNestedJsonString for the html5 IMPORT path: WebViewContainer.gestureConfig
    // is a String field; if the file has it as a nested object (post-fix exports), re-stringify
    // before decode. legacy escaped-string exports pass through untouched.
    private fun collapseToJsonString(obj: JsonObject, key: String): JsonObject {
        val value: JsonElement = obj[key] ?: return obj
        if (value !is JsonObject) return obj
        return JsonObject(obj.toMutableMap().apply { this[key] = JsonPrimitive(value.toString()) })
    }
}
