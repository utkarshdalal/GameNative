package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateInfo(
    val updateAvailable: Boolean,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String? = null
)

object UpdateChecker {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "${Constants.Misc.UPDATE_CHECK_URL}?versionCode=${BuildConfig.VERSION_CODE}&versionName=${BuildConfig.VERSION_NAME}"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val updateInfo = Json.decodeFromString<UpdateInfo>(body)
                    Timber.i("Update check: updateAvailable=${updateInfo.updateAvailable}, versionCode=${updateInfo.versionCode}")
                    return@withContext updateInfo
                }
            } else {
                Timber.w("Update check failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking for updates")
        }
        return@withContext null
    }
}

