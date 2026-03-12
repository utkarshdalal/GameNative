package app.gamenative.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object GameRunApi {

    data class GameRunResponse(val id: Long?, val created: Boolean)

    suspend fun submit(
        gameName: String,
        deviceModel: String,
        deviceManufacturer: String,
        gpuName: String,
        socName: String?,
        androidVersion: String,
        appVersion: String,
        configs: JSONObject,
        rating: Int,
        tags: List<String>,
        notes: String?,
        avgFps: Float?,
        sessionLengthSec: Int?,
    ): ApiResult<GameRunResponse> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("gameName", gameName)
            put("deviceModel", deviceModel)
            put("deviceManufacturer", deviceManufacturer)
            put("gpuName", gpuName)
            put("socName", socName)
            put("androidVersion", androidVersion)
            put("appVersion", appVersion)
            put("configs", configs)
            put("rating", rating)
            put("tags", JSONArray(tags))
            put("notes", notes)
            if (avgFps != null) put("avgFps", avgFps.toDouble()) else put("avgFps", JSONObject.NULL)
            if (sessionLengthSec != null) put("sessionLengthSec", sessionLengthSec) else put("sessionLengthSec", JSONObject.NULL)
        }

        val request = GameNativeApi.buildPostRequest(
            "${GameNativeApi.BASE_URL}/api/game-run",
            body,
        )

        GameNativeApi.executeRequest(request) { responseBody ->
            val json = JSONObject(responseBody)
            GameRunResponse(
                id = if (json.isNull("id")) null else json.optLong("id"),
                created = json.optBoolean("created", false),
            )
        }
    }
}
