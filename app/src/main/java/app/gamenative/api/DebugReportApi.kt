package app.gamenative.api

import app.gamenative.utils.PlayIntegrity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

object DebugReportApi {

    const val RELAY_BASE_URL = "https://relay.gamenative.app"
    const val OAUTH_START_URL = "$RELAY_BASE_URL/oauth/start"

    sealed class SubmitResult {
        data class Success(val threadUrl: String) : SubmitResult()
        data class Forbidden(val reason: String) : SubmitResult()
        data class Failure(val message: String) : SubmitResult()
    }

    suspend fun submit(
        header: JSONObject,
        logFile: File,
        relayToken: String,
    ): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val headerString = header.toString()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "report",
                    null,
                    headerString.toRequestBody("application/json".toMediaType()),
                )
                .addFormDataPart(
                    "log",
                    "log.gz",
                    logFile.asRequestBody("application/gzip".toMediaType()),
                )
                .build()

            val integrityToken = PlayIntegrity.requestToken(headerString.toByteArray())

            val builder = Request.Builder()
                .url("$RELAY_BASE_URL/api/debug-report")
                .post(body)
                .header("Authorization", "Bearer $relayToken")

            if (integrityToken != null) {
                builder.header("X-Integrity-Token", integrityToken)
            }

            val response = GameNativeApi.httpClient.newCall(builder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    val threadUrl = try {
                        JSONObject(responseBody).optString("threadUrl")
                    } catch (_: Exception) {
                        ""
                    }
                    if (threadUrl.isBlank()) {
                        Timber.w("DebugReportApi: Success response without threadUrl: $responseBody")
                        SubmitResult.Failure("Missing thread URL")
                    } else {
                        SubmitResult.Success(threadUrl)
                    }
                }
                response.code == 403 -> {
                    val reason = try {
                        JSONObject(responseBody).optString("reason")
                    } catch (_: Exception) {
                        ""
                    }
                    Timber.w("DebugReportApi: Forbidden: $reason")
                    SubmitResult.Forbidden(reason)
                }
                else -> {
                    Timber.w("DebugReportApi: HTTP ${response.code}: $responseBody")
                    SubmitResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "DebugReportApi: Network error")
            SubmitResult.Failure(e.message ?: "Network error")
        } catch (e: Exception) {
            Timber.e(e, "DebugReportApi: Unexpected error")
            SubmitResult.Failure(e.message ?: "Unexpected error")
        }
    }
}
