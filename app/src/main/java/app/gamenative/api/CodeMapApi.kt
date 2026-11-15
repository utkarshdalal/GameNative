package app.gamenative.api

import app.gamenative.utils.PlayIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/** Uploads one gzipped FEX code map with its metadata to POST /api/code-maps. */
object CodeMapApi {

    data class CodeMapResponse(val id: Long?, val key: String)

    fun upload(meta: JSONObject, gzMap: File): ApiResult<CodeMapResponse> {
        val metaString = meta.toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meta", null, metaString.toRequestBody("application/json".toMediaType()))
            .addFormDataPart("map", "map.bin.gz", gzMap.asRequestBody("application/gzip".toMediaType()))
            .build()

        val integrityToken = runBlocking(Dispatchers.IO) { PlayIntegrity.requestToken(metaString.toByteArray()) }

        val builder = Request.Builder()
            .url("${GameNativeApi.BASE_URL}/api/code-maps")
            .post(body)
        if (integrityToken != null) {
            builder.header("X-Integrity-Token", integrityToken)
        }
        runBlocking(Dispatchers.IO) { AccountApi.currentAccessTokenOrNull() }?.let {
            builder.header("Authorization", "Bearer $it")
        }

        return GameNativeApi.executeRequest(builder.build()) { responseBody ->
            val json = JSONObject(responseBody)
            CodeMapResponse(
                id = if (json.isNull("id")) null else json.optLong("id"),
                key = json.optString("key"),
            )
        }
    }
}
