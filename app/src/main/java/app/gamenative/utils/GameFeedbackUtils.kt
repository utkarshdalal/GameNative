package app.gamenative.utils

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.xenvironment.ImageFs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

import timber.log.Timber
import java.io.File

object GameFeedbackUtils {


    @Serializable
    data class GameRunInsert(
        @SerialName("game_id") val gameId: Long,
        @SerialName("device_id") val deviceId: Long,
        @SerialName("app_version_id") val appVersionId: Long,
        val configs: JsonObject,
        val rating: Int,
        val tags: List<String> = emptyList(),
        val notes: String? = null,
    )


    /**
     * Submits game feedback to Supabase
     */
    suspend fun submitGameFeedback(
        context: Context,
        supabase: SupabaseClient,
        appId: Int,
        rating: Int,
        tags: List<String>,
        notes: String?,
    ) = withContext(Dispatchers.IO) {
        Timber.d("GameFeedbackUtils: Starting submitGameFeedback method with rating=$rating")
        try {
            val container = ContainerUtils.getContainer(context, appId)
            val configJson = Json.parseToJsonElement(FileUtils.readString(container.getConfigFile()).replace("\\u0000", "").replace("\u0000", "")).jsonObject
            Timber.d("config string is: " + FileUtils.readString(container.getConfigFile()).replace("\\u0000", "").replace("\u0000", ""))
            Timber.d("configJson: $configJson")
            // Get the game name from container or use a fallback
            val appInfo = SteamService.getAppInfoOf(appId)!!
            val gameName = appInfo.name
            Timber.d("GameFeedbackUtils: Game name: $gameName")

            // Get device model
            val deviceModel = HardwareUtils.getMachineName()
            Timber.d("GameFeedbackUtils: Device model: $deviceModel")

            // Get GPU info if available
            val gpu = try {
                Timber.d("GameFeedbackUtils: About to get GPU info")
                val gpuInfo = GPUInformation.getRenderer(context)
                Timber.d("GameFeedbackUtils: GPU info: $gpuInfo")
                gpuInfo
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils: Failed to get GPU info: ${e.message}")
                e.printStackTrace()
                "Unknown GPU"  // Provide a default value instead of null
            }

            // Get Android version
            val androidVer = Build.VERSION.RELEASE
            Timber.d("GameFeedbackUtils: Android version: $androidVer")

            // Get app version
            val appVersion = BuildConfig.VERSION_NAME
            Timber.d("GameFeedbackUtils: App version: $appVersion")

            // Log the submission
            Timber.i("GameFeedbackUtils: Submitting game feedback: game=$gameName, device=$deviceModel, rating=$rating, tags=${tags.joinToString()}")

            // Submit to Supabase
            try {
                Timber.d("GameFeedbackUtils: About to call logRun on supabase client")
                supabase.logRun(
                    gameName = gameName,
                    deviceModel = deviceModel,
                    gpu = gpu,
                    androidVer = androidVer,
                    appVersion = appVersion,
                    configs = configJson,
                    rating = rating,
                    tags = tags,
                    notes = notes,
                )
                Timber.i("GameFeedbackUtils: Game feedback submitted successfully")
                true
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils: Exception during Supabase submission: ${e.message}")
                e.printStackTrace()
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "GameFeedbackUtils: Failed to prepare game feedback: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    @Serializable private data class IdRow(val id: Long)

    /**
     * Helper extension to submit game run data to Supabase
     */
    suspend fun SupabaseClient.logRun(
        gameName: String,
        deviceModel: String,
        gpu: String? = null,
        androidVer: String? = null,
        appVersion: String,
        configs: JsonObject,
        rating: Int,
        tags: List<String> = emptyList(),
        notes: String? = null,
    ) {
        Timber.d("GameFeedbackUtils.logRun: Starting with game=$gameName, rating=$rating")

        try {
            // Create game or get its ID
            Timber.d("GameFeedbackUtils.logRun: Creating/getting game record for: $gameName")
            val gameData = mapOf("name" to gameName)
            val gameId = try {
                val result = from("games")
                    .upsert(listOf(gameData)) {
                        onConflict="name"
                        select(columns = Columns.list("id"))
                    }
                    .decodeSingle<IdRow>()
                Timber.d("GameFeedbackUtils.logRun: Game upsert successful, id: ${result.id}")
                result.id
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils.logRun: Failed to upsert game: ${e.message}")
                throw e
            }

            // Create device or get its ID
            Timber.d("GameFeedbackUtils.logRun: Creating/getting device record")
            val deviceData = mapOf(
                "model" to deviceModel,
                "gpu" to (gpu ?: ""),
                "android_ver" to (androidVer ?: ""),
            )
            val deviceId = try {
                val result = from("devices")
                    .upsert(listOf(deviceData)) {
                        onConflict="model,gpu,android_ver"
                        select(columns = Columns.list("id"))
                    }
                    .decodeSingle<IdRow>()
                Timber.d("GameFeedbackUtils.logRun: Device upsert successful, id: ${result.id}")
                result.id
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils.logRun: Failed to upsert device: ${e.message}")
                throw e
            }

            // Create app version or get its ID
            Timber.d("GameFeedbackUtils.logRun: Creating/getting app version record for: $appVersion")
            val appVersionData = mapOf("semver" to appVersion)
            val appVersionId = try {
                val result = from("app_versions")
                    .upsert(listOf(appVersionData)) {
                        onConflict="semver"
                        select(columns = Columns.list("id"))
                    }
                    .decodeSingle<IdRow>()
                Timber.d("GameFeedbackUtils.logRun: App version upsert successful, id: ${result.id}")
                result.id
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils.logRun: Failed to upsert app version: ${e.message}")
                throw e
            }

            // Create the game run record
            Timber.d("GameFeedbackUtils.logRun: Creating game run record with gameId: $gameId, deviceId: $deviceId, appVersionId: $appVersionId")
            try {
                val run = GameRunInsert(
                    gameId = gameId,
                    deviceId = deviceId,
                    appVersionId = appVersionId,
                    configs = configs,
                    rating = rating,
                    tags = tags,
                    notes = notes,
                )

                from("game_runs").insert(run)
                Timber.d("GameFeedbackUtils.logRun: Game run record created successfully")
            } catch (e: Exception) {
                Timber.e(e, "GameFeedbackUtils.logRun: Failed to create game run record: ${e.message}")
                throw e
            }
        } catch (e: Exception) {
            Timber.e(e, "GameFeedbackUtils.logRun: Error in logRun: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
