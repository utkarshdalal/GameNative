package app.gamenative.optimize

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.screen.support.SupportGameFiles
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.OptimizeState
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The client half of Optimize: before a launch it asks the server for the next change and applies it, at exit it
 * judges the session against the one before, and it takes changes off again. The server decides what to try.
 */
object OptimizeController {

    private const val TAG = "Optimize"
    private const val NEXT_RUNG_TIMEOUT_MS = 3_000L
    private const val DEFAULT_FPS_LIMIT = 60

    val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isGameRunning(): Boolean = PluviaApp.xEnvironment != null

    fun resetTelemetry() {
        OptimizeState.enabled = false
        OptimizeState.goal = null
        OptimizeState.step = null
        OptimizeState.profileId = null
        OptimizeState.templateId = null
        OptimizeState.applyResult = null
    }

    private fun telemetry(profile: OptimizeProfile, pending: PendingRung?, applyResult: String) {
        OptimizeState.enabled = true
        OptimizeState.goal = profile.wireId
        OptimizeState.profileId = profile.wireId
        val active = pending?.takeIf { it.revert == null }
        OptimizeState.step = active?.rung?.order?.toString()
        OptimizeState.templateId = active?.rung?.rungId
        OptimizeState.applyResult = applyResult
    }

    fun roots(context: Context, appId: String, container: Container): PathRoots {
        val prefix = File(container.rootDir, ".wine")
        val accountId = if (ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.STEAM) {
            runCatching { SteamService.userSteamId?.accountID?.toLong() }.getOrNull()
        } else {
            null
        }
        return PathRoots(
            install = runCatching { SupportGameFiles.installRoot(context, appId) }.getOrNull(),
            prefix = prefix,
            userHome = SupportGameFiles.wineUserHome(prefix),
            steamAccountId = accountId,
        )
    }

    fun latestRun(container: Container): JSONObject? {
        val dir = File(container.rootDir, ".gamenative/runs")
        val newest = dir.listFiles { f -> f.name.endsWith(".json") }?.maxByOrNull { it.name } ?: return null
        return runCatching { JSONObject(newest.readText()) }.getOrNull()
    }

    private fun storeId(appId: String): String = when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
        GameSource.STEAM -> "steam"
        GameSource.GOG -> "gog"
        GameSource.EPIC -> "epic"
        GameSource.AMAZON -> "amazon"
        GameSource.CUSTOM_GAME -> "custom"
    }

    private fun gameId(appId: String): String =
        runCatching { ContainerUtils.extractGameIdFromContainerId(appId).toString() }.getOrDefault(appId)

    private fun gameJson(appId: String): JSONObject = JSONObject().apply {
        put("store", storeId(appId))
        put("app_id", gameId(appId))
        put("name", runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault("Unknown"))
    }

    private fun ramMb(context: Context): Long {
        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024L * 1024L)
    }

    private fun gpuName(context: Context): String = runCatching { GPUInformation.getRenderer(context) }.getOrNull().orEmpty()

    private fun fpsLimit(container: Container): Int {
        val enabled = parseBooleanExtra(container.getExtra(OptimizeFiles.FPS_LIMITER_ENABLED_EXTRA)) ?: true
        if (!enabled) return 0
        return parsePositiveFpsLimit(container.getExtra(OptimizeFiles.FPS_LIMITER_TARGET_EXTRA)) ?: DEFAULT_FPS_LIMIT
    }

    private fun nextRungBody(context: Context, appId: String, container: Container, state: OptimizeLocalState, baseline: Metrics?): JSONObject {
        val data = ContainerUtils.toContainerData(container)
        return JSONObject().apply {
            put("schema", 1)
            put("app_version_code", BuildConfig.VERSION_CODE)
            put(
                "game",
                gameJson(appId).apply {
                    put("exe", container.executablePath.orEmpty())
                    put("anticheat", JSONArray(OptimizeFiles.anticheat(container).orEmpty()))
                },
            )
            put(
                "device",
                JSONObject().apply {
                    put("model", Build.MODEL)
                    put("manufacturer", Build.MANUFACTURER)
                    put("soc_model", HardwareUtils.getSOCName() ?: JSONObject.NULL)
                    put("gpu_name", gpuName(context))
                    put("ram_mb", ramMb(context))
                    put("android_sdk", Build.VERSION.SDK_INT)
                    put("proc_stat_real", baseline?.procStatReal ?: false)
                    put("thermal_reporting", baseline?.thermalReporting ?: false)
                    put("device_id_hash", deviceIdHash())
                },
            )
            put("profile", state.profile.wireId)
            put("verdict", baseline?.toVerdictJson() ?: JSONObject.NULL)
            put(
                "container",
                JSONObject().apply {
                    put("display_server", data.displayServer)
                    put("dxwrapper", data.dxwrapper)
                    put("executable_path", data.executablePath)
                    put("screen_size", container.screenSize)
                    put("fps_limit", fpsLimit(container))
                    put("emulator", data.emulator)
                    put("fex_preset", data.fexcorePreset)
                },
            )
            put(
                "ladder",
                JSONObject().apply {
                    put(
                        "kept",
                        JSONArray().apply {
                            state.kept.forEach { put(JSONObject().put("rung_id", it.rungId).put("version", it.version)) }
                        },
                    )
                    put("tried", JSONArray().apply { state.tried.forEach { put(it.toJson()) } })
                },
            )
        }
    }

    private fun outcomeBody(
        appId: String,
        profile: OptimizeProfile,
        rungId: String,
        version: String,
        before: Metrics?,
        after: Metrics?,
        result: String,
        leversOk: Int,
        leversFailed: List<Int>,
        crash: Metrics? = null,
    ): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("outcome_id", UUID.randomUUID().toString())
        put("rung_id", rungId)
        put("version", version)
        put("profile", profile.wireId)
        put("game", gameJson(appId))
        put("before", before?.toReportJson() ?: JSONObject.NULL)
        put("after", after?.toReportJson() ?: JSONObject.NULL)
        put("result", result)
        put(
            "apply",
            JSONObject().apply {
                put("result", if (leversFailed.isEmpty()) "ok" else "failed")
                put("levers_ok", leversOk)
                put("levers_failed", JSONArray(leversFailed))
            },
        )
        put(
            "crash",
            crash?.let { JSONObject().put("exit_reason", it.exitReason ?: JSONObject.NULL).put("gameplay_s", it.gameplaySec) } ?: JSONObject.NULL,
        )
    }

    private fun deviceIdHash(): String = OptimizeFiles.sha256(PrefManager.optimizeDeviceId.toByteArray())

    private fun deviceJson(context: Context): JSONObject = JSONObject().apply {
        put("model", Build.MODEL)
        put("soc_model", HardwareUtils.getSOCName() ?: JSONObject.NULL)
        put("gpu_name", gpuName(context))
        put("device_id_hash", deviceIdHash())
    }

    private fun OptimizeLocalState.queued(report: JSONObject): OptimizeLocalState =
        copy(outcomeQueue = (outcomeQueue + report).takeLast(OptimizeLocalState.MAX_QUEUE))

    private fun OptimizeLocalState.triedWith(rungId: String, version: String, result: String): OptimizeLocalState =
        copy(tried = (tried + TriedRung(rungId, version, result)).takeLast(OptimizeLocalState.MAX_TRIED))

    private suspend fun flush(context: Context, store: OptimizeStore) {
        try {
            val queue = mutex.withLock { store.read().outcomeQueue }
            if (queue.isEmpty()) return
            val device = deviceJson(context)
            val sent = mutableSetOf<String>()
            for (item in queue) {
                val body = JSONObject(item.toString()).put("device", device)
                if (!OptimizeApi.postOutcome(body)) break
                sent += item.optString("outcome_id")
            }
            if (sent.isEmpty()) return
            mutex.withLock {
                val state = store.read()
                store.write(state.copy(outcomeQueue = state.outcomeQueue.filter { it.optString("outcome_id") !in sent }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Flushing outcome reports failed")
        }
    }

    /** Runs before a game starts, off the main thread. Never throws; on any failure the game launches unchanged. */
    suspend fun beforeLaunch(context: Context, appId: String, container: Container) = withContext(Dispatchers.IO) {
        resetTelemetry()
        try {
            val store = OptimizeStore(container.rootDir)
            mutex.withLock {
                var state = store.read()
                if (state.profile == OptimizeProfile.OFF) return@withLock
                telemetry(state.profile, null, "none")
                state = state.copy(started = true)
                scope.launch { flush(context.applicationContext, store) }

                val flagged = state.pending?.takeIf { it.revert != null }
                if (flagged != null) {
                    if (!OptimizeReverter.revertLevers(context, container, flagged.levers)) {
                        Timber.tag(TAG).w("Taking ${flagged.rung.rungId} off failed; trying again next launch")
                        store.write(state)
                        telemetry(state.profile, null, "failed")
                        return@withLock
                    }
                    store.rungDir(flagged.rung.rungId).deleteRecursively()
                    state = state.copy(pending = null, baselineOverride = flagged.baseline ?: state.baselineOverride)
                }

                var applyResult = if (state.pending != null) "ok" else "none"
                if (state.pending == null) {
                    val baseline = state.baselineOverride ?: latestRun(container)?.let { Metrics.fromRun(it) }
                    val response = OptimizeApi.nextRung(nextRungBody(context, appId, container, state, baseline), NEXT_RUNG_TIMEOUT_MS)
                    if (response == null) {
                        Timber.tag(TAG).i("No next-rung answer; launching unchanged")
                        if (flagged != null) store.write(state)
                        return@withLock
                    }
                    state = state.copy(lastStatus = response.status)
                    val rung = response.rung?.takeIf { response.status == NextRungResponse.STATUS_RUNG && it.rungId !in state.bad }
                    if (rung != null) {
                        when (val outcome = OptimizeApplier.apply(context, container, roots(context, appId, container), store, rung)) {
                            is ApplyOutcome.Applied -> {
                                state = state.copy(
                                    pending = PendingRung(rung, System.currentTimeMillis(), baseline, 0, outcome.levers),
                                    baselineOverride = null,
                                )
                                applyResult = "ok"
                            }
                            is ApplyOutcome.Failed -> {
                                state = state.triedWith(rung.rungId, rung.version, OptimizeJudge.APPLY_FAILED).queued(
                                    outcomeBody(
                                        appId, state.profile, rung.rungId, rung.version, baseline, null, OptimizeJudge.APPLY_FAILED,
                                        outcome.leversOk, listOf(outcome.failedIndex),
                                    ),
                                )
                                applyResult = "failed"
                                Timber.tag(TAG).w("Applying ${rung.rungId} failed: ${outcome.reason}")
                            }
                            is ApplyOutcome.Deferred -> Timber.tag(TAG).i("Not applying ${rung.rungId}: ${outcome.reason}")
                        }
                    }
                }
                telemetry(state.profile, state.pending, applyResult)
                store.write(state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize before launch failed; launching unchanged")
        }
    }

    /** Judges the session that just ended; [props] are its game_exited properties. Changes that must come off are taken off before the next launch, once Wine has written its registry. */
    suspend fun afterExit(context: Context?, appId: String, container: Container, props: Map<String, Any>) = withContext(Dispatchers.IO) {
        try {
            val store = OptimizeStore(container.rootDir)
            val sessionRung = (props["optimize_state"] as? Map<*, *>)?.get("template_id") as? String
            mutex.withLock {
                val state = store.read()
                val pending = state.pending ?: return@withLock
                if (pending.revert != null || sessionRung != pending.rung.rungId) return@withLock
                val after = Metrics.fromRun(JSONObject(props))
                val rung = pending.rung
                val next = when (val decision = OptimizeJudge.judge(after, pending.baseline, rung.isFpsCap, pending.shortSessions)) {
                    is OptimizeJudge.Decision.Short -> state.copy(pending = pending.copy(shortSessions = decision.shortSessions))
                    is OptimizeJudge.Decision.Crash -> finish(appId, state, pending, OptimizeJudge.CRASH, after)
                    is OptimizeJudge.Decision.Final -> finish(appId, state, pending, decision.result, after)
                }
                store.write(next)
            }
            if (context != null) scope.launch { flush(context.applicationContext, store) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize judging failed")
        }
    }

    private fun finish(appId: String, state: OptimizeLocalState, pending: PendingRung, result: String, after: Metrics): OptimizeLocalState {
        val rung = pending.rung
        val keep = result == OptimizeJudge.HELPED || result == OptimizeJudge.NOT_COMPARABLE
        val report = outcomeBody(
            appId, state.profile, rung.rungId, rung.version, pending.baseline, after, result, pending.levers.size, emptyList(),
            crash = after.takeIf { result == OptimizeJudge.CRASH },
        )
        val card = JSONObject().apply {
            put("rung_id", rung.rungId)
            put("result", result)
            put("at", System.currentTimeMillis())
            rung.visualCostId?.let { put("visual_cost_id", it) }
            rung.visualCostEn?.let { put("visual_cost_en", it) }
        }
        val base = state.triedWith(rung.rungId, rung.version, result).queued(report).copy(lastCard = card)
        return if (keep) {
            base.copy(kept = base.kept + pending.toKept(after), pending = null, baselineOverride = null)
        } else {
            base.copy(
                pending = pending.copy(revert = result),
                bad = if (result == OptimizeJudge.CRASH) (base.bad + rung.rungId).distinct() else base.bad,
            )
        }
    }

    /**
     * Takes every change off, the newest first, reporting undo for each one the user had not seen judged yet.
     * Returns the state left behind; entries whose revert failed stay in it.
     */
    private suspend fun revertAll(context: Context, appId: String, container: Container, store: OptimizeStore, state: OptimizeLocalState): OptimizeLocalState {
        var s = state
        s.pending?.let { pending ->
            if (OptimizeReverter.revertLevers(context, container, pending.levers)) {
                store.rungDir(pending.rung.rungId).deleteRecursively()
                s = s.copy(pending = null)
                if (pending.revert == null) {
                    s = s.queued(
                        outcomeBody(appId, s.profile, pending.rung.rungId, pending.rung.version, pending.baseline, null, OptimizeJudge.UNDO, pending.levers.size, emptyList()),
                    )
                }
            }
        }
        for (kept in s.kept.asReversed()) {
            if (!OptimizeReverter.revertLevers(context, container, kept.levers)) continue
            store.rungDir(kept.rungId).deleteRecursively()
            s = s.copy(kept = s.kept - kept).queued(
                outcomeBody(appId, s.profile, kept.rungId, kept.version, kept.baseline, null, OptimizeJudge.UNDO, kept.levers.size, emptyList()),
            )
        }
        return s
    }

    suspend fun readState(context: Context, appId: String): OptimizeLocalState? = withContext(Dispatchers.IO) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext null
            val container = ContainerUtils.getContainer(context, appId)
            mutex.withLock { OptimizeStore(container.rootDir).read() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Reading optimize state failed")
            null
        }
    }

    enum class ChangeResult { DONE, GAME_RUNNING, NO_CONTAINER, FAILED }

    /** Switching profiles takes the old profile's changes off first. */
    suspend fun setProfile(context: Context, appId: String, profile: OptimizeProfile): ChangeResult = withContext(Dispatchers.IO) {
        if (isGameRunning()) return@withContext ChangeResult.GAME_RUNNING
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext ChangeResult.NO_CONTAINER
            val container = ContainerUtils.getContainer(context, appId)
            val store = OptimizeStore(container.rootDir)
            val result = mutex.withLock {
                val state = store.read()
                if (state.profile == profile) return@withLock ChangeResult.DONE
                val left = revertAll(context, appId, container, store, state)
                if (left.hasChanges) {
                    store.write(left)
                    return@withLock ChangeResult.FAILED
                }
                store.write(
                    OptimizeLocalState(profile = profile, bad = left.bad, outcomeQueue = left.outcomeQueue),
                )
                ChangeResult.DONE
            }
            scope.launch { flush(context.applicationContext, store) }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Changing the optimize profile failed")
            ChangeResult.FAILED
        }
    }

    /** "Restore original settings": every change comes off and the ladder starts over; the profile stays. */
    suspend fun restoreOriginal(context: Context, appId: String): ChangeResult = withContext(Dispatchers.IO) {
        if (isGameRunning()) return@withContext ChangeResult.GAME_RUNNING
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext ChangeResult.NO_CONTAINER
            val container = ContainerUtils.getContainer(context, appId)
            val store = OptimizeStore(container.rootDir)
            val result = mutex.withLock {
                val left = revertAll(context, appId, container, store, store.read())
                if (left.hasChanges) {
                    store.write(left)
                    return@withLock ChangeResult.FAILED
                }
                store.write(OptimizeLocalState(profile = left.profile, bad = left.bad, outcomeQueue = left.outcomeQueue))
                ChangeResult.DONE
            }
            scope.launch { flush(context.applicationContext, store) }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Restoring original settings failed")
            ChangeResult.FAILED
        }
    }
}
