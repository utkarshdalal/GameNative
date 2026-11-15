package app.gamenative.optimize

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.data.GameSource
import app.gamenative.powercontrol.metrics.CpuClusterLayout
import app.gamenative.powercontrol.metrics.SessionPerfSampler
import app.gamenative.service.SteamService
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.screen.support.SupportGameFiles
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.AppExitRecorder
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.OptimizeState
import app.gamenative.utils.SessionSeries
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The client half of Optimize: before a launch it asks the server for the next change and applies it, at exit it
 * judges the session against the one before, and it takes changes off again. The server decides what to try.
 */
object OptimizeController {

    private const val TAG = "Optimize"
    private const val NEXT_RUNG_LAUNCH_BUDGET_MS = 10_000L
    private const val NEXT_RUNG_EXIT_BUDGET_MS = 20_000L
    /** How long the exit check waits for the server to say whether it has a change before a first card is written. */
    private const val FIRST_OFFER_BUDGET_MS = 8_000L
    private const val DEFAULT_FPS_LIMIT = 60
    private const val MAX_NO_CHANGE_STREAK = 2
    /** Launches in a row a read-only file may be reset by the game before its rung is reported as not taking. */
    private const val MAX_RESETS = 3

    /** Answers after which a no_rule card is not shown again: the ladder already said it has nothing. */
    private val NOTHING_STATUSES = setOf(
        NextRungResponse.STATUS_NO_RULE, NextRungResponse.STATUS_NOT_SETTINGS, NextRungResponse.STATUS_EXHAUSTED,
    )

    val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Next-rung requests still running, by state directory, so a launch waits for one instead of asking again. */
    private val inflight = ConcurrentHashMap<String, Job>()

    data class CardRequest(val appId: String, val card: OptimizeCard, val entitlement: Entitlement?)

    /** The card to show on the next library or game screen; the host clears it once the user answers. */
    val cardRequest = MutableStateFlow<CardRequest?>(null)

    /** Bumped after every state change so open screens read the state again. */
    val revision = MutableStateFlow(0)

    private val entitlementLock = Any()
    private var lastEntitlementCache: Entitlement? = null
    private var lastEntitlementAtCache = 0L
    private var accountChangedAtCache = 0L
    private var lastEntitlementLoaded = false

    private fun loadEntitlementLocked() {
        if (lastEntitlementLoaded) return
        lastEntitlementCache = runCatching {
            PrefManager.optimizeLastEntitlementJson.takeIf { it.isNotEmpty() }?.let { Entitlement.fromJson(JSONObject(it)) }
        }.getOrNull()
        lastEntitlementAtCache = runCatching { PrefManager.optimizeLastEntitlementAt }.getOrDefault(0L)
        accountChangedAtCache = runCatching { PrefManager.optimizeAccountChangedAt }.getOrDefault(0L)
        lastEntitlementLoaded = true
    }

    /** The latest entitlement any game's next-rung or outcome answer carried, kept across restarts, for games that have not asked yet. */
    val lastEntitlement: Entitlement?
        get() = synchronized(entitlementLock) {
            loadEntitlementLocked()
            lastEntitlementCache
        }

    /** When the account last signed in, signed out or changed plan; 0 when never. */
    val accountChangedAt: Long
        get() = synchronized(entitlementLock) {
            loadEntitlementLocked()
            accountChangedAtCache
        }

    /** The entitlement a surface shows for [state]: the newer of the game's own and the app-wide one, from this account. */
    fun shownEntitlement(state: OptimizeLocalState?): Entitlement? = synchronized(entitlementLock) {
        loadEntitlementLocked()
        state?.entitlement.orAppWide(state?.entitlementCheckedAt ?: 0L, lastEntitlementCache, lastEntitlementAtCache, accountChangedAtCache)
    }

    private fun rememberEntitlement(entitlement: Entitlement?) {
        if (entitlement == null) return
        val now = System.currentTimeMillis()
        synchronized(entitlementLock) {
            loadEntitlementLocked()
            lastEntitlementCache = entitlement
            lastEntitlementAtCache = now
        }
        runCatching {
            PrefManager.optimizeLastEntitlementJson = entitlement.toJson().toString()
            PrefManager.optimizeLastEntitlementAt = now
        }.onFailure { Timber.tag(TAG).w(it, "Saving the last entitlement failed") }
    }

    /**
     * The account signed in or out: the app-wide entitlement is forgotten, and what any game learned of the plan before
     * now no longer applies until the server answers for the new account.
     */
    fun onAccountChanged() {
        markAccountChanged(forgetAppWide = true)
        runCatching { PrefManager.optimizeLastPlan = Membership.planKey().orEmpty() }
    }

    /** The account's plan was refreshed as [plan]; a change from the last plan seen counts as an account change. */
    fun onPlanRefreshed(plan: String) {
        val previous = runCatching { PrefManager.optimizeLastPlan }.getOrDefault("")
        if (previous == plan) return
        runCatching { PrefManager.optimizeLastPlan = plan }
        if (previous.isNotEmpty()) markAccountChanged(forgetAppWide = false)
    }

    private fun markAccountChanged(forgetAppWide: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(entitlementLock) {
            loadEntitlementLocked()
            accountChangedAtCache = now
            if (forgetAppWide) {
                lastEntitlementCache = null
                lastEntitlementAtCache = 0L
            }
        }
        runCatching {
            PrefManager.optimizeAccountChangedAt = now
            if (forgetAppWide) {
                PrefManager.optimizeLastEntitlementJson = ""
                PrefManager.optimizeLastEntitlementAt = 0L
            }
        }.onFailure { Timber.tag(TAG).w(it, "Saving the account change failed") }
        revision.value++
    }

    private val rulesVersionLock = Any()
    private var appRulesVersionCache: String? = null
    private var appRulesVersionLoaded = false

    /** The newest rules build any game's answer carried, kept across restarts: a held game is asked again once it changes. */
    private fun appRulesVersion(): String? = synchronized(rulesVersionLock) {
        if (!appRulesVersionLoaded) {
            appRulesVersionCache = runCatching { PrefManager.optimizeLastRulesVersion.ifEmpty { null } }.getOrNull()
            appRulesVersionLoaded = true
        }
        appRulesVersionCache
    }

    private fun rememberRulesVersion(version: String?) {
        if (version == null) return
        synchronized(rulesVersionLock) {
            if (appRulesVersionLoaded && appRulesVersionCache == version) return
            appRulesVersionCache = version
            appRulesVersionLoaded = true
        }
        runCatching { PrefManager.optimizeLastRulesVersion = version }
            .onFailure { Timber.tag(TAG).w(it, "Saving the last rules version failed") }
    }

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

    /** Fallback baseline when the ladder holds none: the newest run long enough to judge against; short runs are skipped. */
    fun baselineRun(container: Container): Metrics? {
        val files = File(container.rootDir, ".gamenative/runs").listFiles { f -> f.name.endsWith(".json") } ?: return null
        val runs = files.sortedByDescending { it.name }.asSequence().mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
        return OptimizeJudge.baselineOf(runs)
    }

    private fun storeName(appId: String): String = when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
        GameSource.STEAM -> "steam"
        GameSource.GOG -> "gog"
        GameSource.EPIC -> "epic"
        GameSource.AMAZON -> "amazon"
        GameSource.CUSTOM_GAME -> "custom"
    }

    private fun gameId(appId: String): String =
        runCatching { ContainerUtils.extractGameIdFromContainerId(appId).toString() }.getOrDefault(appId)

    private fun gameName(appId: String): String = runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault("Unknown")

    /** The store's own id for the game; app_id stays the container's id, which is a local row id for Epic and Amazon. */
    private fun storeId(appId: String): String? = runCatching {
        OptimizeIdentity.storeId(ContainerUtils.extractGameSourceFromContainerId(appId), ContainerUtils.extractGameIdFromContainerId(appId), appId)
    }.getOrNull()

    private fun gameBuild(appId: String, install: File?): String? = runCatching {
        OptimizeIdentity.build(ContainerUtils.extractGameSourceFromContainerId(appId), ContainerUtils.extractGameIdFromContainerId(appId), install)
    }.getOrNull()

    private fun gameJson(appId: String): JSONObject = JSONObject().apply {
        put("store", storeName(appId))
        storeId(appId)?.let { put("store_id", it) }
        put("app_id", gameId(appId))
        put("name", gameName(appId))
    }

    private data class Hint(val engine: String?, val meta: EngineHint.Meta?)

    /** Engine guesses by install folder; the app stores no engine of its own, so this is the only one it can send. */
    private val engineHints = ConcurrentHashMap<String, Hint>()

    private fun engineHint(install: File?): Hint? {
        if (install == null) return null
        engineHints[install.absolutePath]?.let { return it }
        val engine = runCatching { EngineHint.detect(install) }.getOrNull()
        val meta = if (engine == EngineHint.UNITY) runCatching { EngineHint.unityMeta(install) }.getOrNull() else null
        return Hint(engine, meta).also { engineHints[install.absolutePath] = it }
    }

    private fun eventDevice(): JSONObject = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("soc_model", HardwareUtils.getSOCName() ?: JSONObject.NULL)
        put("device_id_hash", deviceIdHash())
    }

    /** Queues a funnel event for [appId]; never throws and never blocks the caller. */
    private fun event(
        appId: String,
        name: String,
        state: OptimizeLocalState?,
        profile: OptimizeProfile?,
        cls: String? = null,
        cardKind: String? = null,
        rungId: String? = null,
        unverified: Boolean = false,
        extra: JSONObject? = null,
    ) {
        val rulesVersion = state?.lastRulesVersion
        val now = System.currentTimeMillis()
        scope.launch {
            try {
                val ev = OptimizeEvents.event(name, gameJson(appId), profile, cls, cardKind, rungId, rulesVersion ?: appRulesVersion(), now, unverified, extra)
                OptimizeEvents.record(ev) { eventDevice() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Optimize: recording $name failed")
            }
        }
    }

    private fun cardEvent(appId: String, name: String, state: OptimizeLocalState?, card: OptimizeCard, profile: OptimizeProfile? = card.profile) =
        event(appId, name, state, profile, card.cls ?: card.metrics?.cls, card.kind, card.rungId, card.unverified)

    /** The waiting card [request] is on screen. */
    fun cardShown(request: CardRequest) = cardEvent(request.appId, OptimizeEvents.CARD_SHOWN, null, request.card)

    /** An Optimize action waits for the user to sign in. */
    fun signInRequired(appId: String) = event(appId, OptimizeEvents.SIGNIN_REQUIRED, null, null)

    private fun refreshHz(context: Context): Double? = runCatching {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.toDouble()
    }.getOrNull()

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

    /** The user's own limiter, not a cap Optimize put on: what the profile targets are measured against. */
    private fun userFpsLimit(container: Container, ladder: ProfileLadder?): Int =
        OptimizeTarget.userFpsCap(ladder, fpsLimit(container), DEFAULT_FPS_LIMIT)

    private fun nextRungBody(
        context: Context,
        appId: String,
        container: Container,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        ladder: ProfileLadder,
        baseline: Metrics?,
        dryRun: Boolean = false,
        series: SessionSeries? = null,
    ): JSONObject {
        val data = ContainerUtils.toContainerData(container)
        val install = runCatching { SupportGameFiles.installRoot(context, appId) }.getOrNull()
        val hint = engineHint(install)
        return JSONObject().apply {
            put("schema", 1)
            put("app_version_code", BuildConfig.VERSION_CODE)
            if (dryRun) put("dry_run", true)
            put(
                "game",
                gameJson(appId).apply {
                    put("build", gameBuild(appId, install) ?: JSONObject.NULL)
                    put("exe", container.executablePath.orEmpty())
                    put("anticheat", JSONArray(OptimizeFiles.anticheat(container).orEmpty()))
                    hint?.engine?.let { put("engine", it) }
                    hint?.meta?.let { meta ->
                        put(
                            "engine_meta",
                            JSONObject().apply {
                                meta.company?.let { put("company", it) }
                                meta.product?.let { put("product", it) }
                            },
                        )
                    }
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
                    put("refresh_hz", baseline?.refreshHz ?: refreshHz(context) ?: JSONObject.NULL)
                    put("device_id_hash", deviceIdHash())
                    runCatching { CpuClusterLayout.device() }.getOrNull()?.let { layout ->
                        put(
                            "cpu_clusters",
                            JSONArray().apply { layout.forEach { put(JSONObject().put("cores", JSONArray(it.cores)).put("kind", it.kind)) } },
                        )
                    }
                },
            )
            put("profile", profile.wireId)
            put("card_action", state.cardAction?.takeIf { !dryRun } ?: JSONObject.NULL)
            put("verdict", baseline?.toVerdictJson()?.apply { state.gpuWorkScaling?.let { put("gpu_work_scaling", it) } } ?: JSONObject.NULL)
            // The raw measurements the server reads its own verdict from (verdict_server); verdict above is the local one.
            put("metrics", metricsBlock(baseline, state, container, context, series))
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
                    put("env_vars", JSONObject().apply { EnvVars(data.envVars).let { env -> env.forEach { put(it, env.get(it)) } } })
                    put("dxwrapper_config", JSONObject().apply { KeyValueSet(data.dxwrapperConfig).forEach { put(it[0], it[1]) } })
                    put("graphics_driver_config", data.graphicsDriverConfig)
                },
            )
            put(
                "ladder",
                JSONObject().apply {
                    put("state", ladder.state.wireId)
                    put(
                        "kept",
                        JSONArray().apply {
                            ladder.kept.forEach { put(JSONObject().put("rung_id", it.rungId).put("version", it.version)) }
                        },
                    )
                    put("tried", JSONArray().apply { ladder.tried.forEach { put(it.toJson()) } })
                    if (state.capPreferred) put("prefer", OptimizeLocalState.PREFER_CAP)
                },
            )
        }
    }

    /**
     * The session's raw measurements for the server: what outcome before/after carry, plus refresh rate, cap and
     * scaling, and with [series] (the session that just ended) its per-second records, from which the server reads
     * its own verdict.
     */
    private fun metricsBlock(baseline: Metrics?, state: OptimizeLocalState, container: Container, context: Context, series: SessionSeries?): Any {
        val m = baseline ?: return JSONObject.NULL
        return m.toReportJson().apply {
            put("refresh_hz", m.refreshHz ?: refreshHz(context) ?: JSONObject.NULL)
            put("cap", fpsLimit(container))
            put("gameplay_s", m.gameplaySec)
            state.gpuWorkScaling?.let { put("gpu_work_scaling", it) }
            series?.takeIf { it.gameplaySec == m.gameplaySec }?.putInto(this)
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
        appliedOps: Int? = null,
        skippedOps: Int? = null,
        judgeReason: String? = null,
        excludedWindows: List<Int> = emptyList(),
        gpuWorkScaling: Double? = null,
        fpsLimit: Int? = null,
        userFpsCap: Int? = null,
    ): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("app_version_code", BuildConfig.VERSION_CODE)
        put("outcome_id", UUID.randomUUID().toString())
        put("rung_id", rungId)
        put("version", version)
        put("profile", profile.wireId)
        put("game", gameJson(appId))
        put("before", before?.toReportJson() ?: JSONObject.NULL)
        put("after", after?.toReportJson()?.apply { if (excludedWindows.isNotEmpty()) put("excluded_windows", JSONArray(excludedWindows)) } ?: JSONObject.NULL)
        put("result", result)
        judgeReason?.let { put("judge_reason", it) }
        gpuWorkScaling?.let { put("gpu_work_scaling", it) }
        OptimizeServer.putLimits(this, fpsLimit, userFpsCap)
        put(
            "apply",
            JSONObject().apply {
                put("result", if (leversFailed.isEmpty()) "ok" else "failed")
                put("levers_ok", leversOk)
                put("levers_failed", JSONArray(leversFailed))
                appliedOps?.let { put("applied_ops", it) }
                skippedOps?.let { put("skipped_ops", it) }
            },
        )
        put(
            "crash",
            crash?.let {
                JSONObject().put("exit_reason", it.exitReason ?: JSONObject.NULL).put("gameplay_s", it.gameplaySec).apply {
                    it.crashKind?.let { kind -> put("crash_kind", kind) }
                    it.crashCode?.let { code -> put("crash_code", code) }
                    it.crashHash?.let { hash -> put("crash_hash", hash) }
                }
            } ?: JSONObject.NULL,
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

    private fun ProfileLadder.triedWith(rungId: String, version: String, result: String, reason: String? = null): ProfileLadder =
        copy(tried = (tried + TriedRung(rungId, version, result, reason)).takeLast(OptimizeLocalState.MAX_TRIED))

    private fun undoReport(appId: String, profile: OptimizeProfile, rungId: String, version: String, baseline: Metrics?, levers: Int): JSONObject =
        outcomeBody(appId, profile, rungId, version, baseline, null, OptimizeJudge.UNDO, levers, emptyList())

    /**
     * Sends the queued outcome reports. The entitlement the last acknowledged one carried is stored for the game and
     * app-wide, and replaces the one on the game's waiting card, so the card counts the trial slot just taken.
     */
    private suspend fun flush(context: Context, appId: String, store: OptimizeStore, graphicsDriverConfig: String? = null) {
        try {
            val queue = mutex.withLock { store.read().outcomeQueue }
            if (queue.isEmpty()) return
            val device = deviceJson(context)
            val sent = mutableSetOf<String>()
            val judged = mutableListOf<Pair<JSONObject, ServerJudgement>>()
            var entitlement: Entitlement? = null
            for (item in queue) {
                val body = JSONObject(item.toString()).put("device", device)
                if (graphicsDriverConfig != null && !body.has("container")) {
                    body.put("container", JSONObject().put("graphics_driver_config", graphicsDriverConfig))
                }
                val ack = OptimizeApi.postOutcome(body) ?: break
                sent += item.optString("outcome_id")
                ack.entitlement?.let { entitlement = it }
                ack.judgement?.let { judged += item to it }
            }
            if (sent.isEmpty()) return
            rememberEntitlement(entitlement)
            var request: CardRequest? = null
            mutex.withLock {
                var state = store.read()
                val cardBefore = state.card
                // The server's judgement stands in for the local one; a local crash revert has already happened.
                judged.forEach { (report, judgement) ->
                    val profile = OptimizeProfile.fromWire(report.optStringOrNull("profile")).takeIf { it != OptimizeProfile.OFF } ?: return@forEach
                    val rungId = report.optStringOrNull("rung_id") ?: return@forEach
                    if (report.optString("result") == OptimizeJudge.UNDO || report.optString("result") == OptimizeJudge.DID_NOT_TAKE) return@forEach
                    state = OptimizeServer.applyJudgement(state, profile, rungId, judgement, OptimizeServer.afterOf(report))
                    Timber.tag(TAG).i("Optimize: server judged $rungId ${judgement.result} (${judgement.reason ?: "no reason"}), local ${report.optString("result")}")
                }
                store.write(
                    state.copy(
                        outcomeQueue = state.outcomeQueue.filter { it.optString("outcome_id") !in sent },
                        entitlement = entitlement ?: state.entitlement,
                        entitlementCheckedAt = if (entitlement != null) System.currentTimeMillis() else state.entitlementCheckedAt,
                    ),
                )
                if (state.card != null && state.card != cardBefore) {
                    request = CardRequest(appId, state.card!!, entitlement ?: shownEntitlement(state))
                }
            }
            request?.let {
                cardRequest.value = it
                revision.value++
            }
            entitlement?.let { e ->
                cardRequest.value?.takeIf { it.appId == appId && it.entitlement != e }?.let { cardRequest.compareAndSet(it, it.copy(entitlement = e)) }
                revision.value++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Flushing outcome reports failed")
        }
    }

    private fun prefetchKey(container: Container): PrefetchKey {
        val data = ContainerUtils.toContainerData(container)
        return PrefetchKey(data.executablePath, data.dxwrapper, fpsLimit(container))
    }

    private fun wouldAsk(state: OptimizeLocalState, ladder: ProfileLadder, nowMillis: Long): Boolean =
        OptimizeLadders.wouldAsk(state, ladder, Membership.isMember(), nowMillis, accountChangedAt)

    /** Before the first step: a done card when the game already meets [profile]'s target; null otherwise. */
    private fun doneAtBaseline(
        appId: String,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        baseline: Metrics?,
        refreshHz: Double?,
        fpsCap: Int,
        withCard: Boolean = true,
    ): OptimizeLocalState? {
        val ladder = state.ladder(profile)
        if (!OptimizeTarget.metAtBaseline(profile, ladder, baseline, refreshHz, fpsCap, state.cardAction)) return null
        Timber.tag(TAG).i("Optimize: ${profile.wireId} target already met at the baseline (${baseline?.steadyFps} fps); not asking")
        var done = state.withLadder(profile, ladder.copy(state = LadderState.DONE))
        if (withCard) done = done.copy(card = OptimizeCard(OptimizeCard.DONE, System.currentTimeMillis(), gameName(appId), profile, metrics = baseline))
        event(appId, OptimizeEvents.DONE, done, profile, baseline?.cls, OptimizeCard.DONE.takeIf { withCard })
        return done
    }

    /** Runs [block] on the controller's scope, registered so a launch of the same game waits for it. */
    private fun track(store: OptimizeStore, what: String, block: suspend () -> Unit) {
        val path = store.dir.absolutePath
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Optimize: $what failed")
            }
        }
        inflight[path] = job
        job.invokeOnCompletion { inflight.remove(path, job) }
        job.start()
    }

    /** Keeps a rung answer for the next launch when the game still waits for one and the answer still fits it. */
    private suspend fun storePrefetch(
        appId: String,
        store: OptimizeStore,
        result: NextRungResult,
        profile: OptimizeProfile,
        key: PrefetchKey,
        kept: List<String>,
        sentCardAction: String?,
        sentPrefer: Boolean,
        source: String,
    ) {
        val response = (result as? NextRungResult.Answered)?.response ?: return
        rememberEntitlement(response.entitlement)
        rememberRulesVersion(response.effectiveRulesVersion)
        mutex.withLock {
            val now = System.currentTimeMillis()
            var state = store.read()
            state = state.copy(
                cardAction = state.cardAction.takeIf { it != sentCardAction },
                entitlement = response.entitlement ?: state.entitlement,
                entitlementCheckedAt = now,
                lastRulesVersion = response.effectiveRulesVersion ?: state.lastRulesVersion,
            )
            // An answer asked for before the cap question was answered yes is not the cap rung.
            val waitsForCap = state.capPreferred && !sentPrefer
            if (sentPrefer) state = state.copy(capPreferred = false)
            val ladder = state.ladder(profile)
            val rung = response.rung?.takeIf { response.status == NextRungResponse.STATUS_RUNG }
            val prefetched = rung?.let {
                PrefetchedRung(it, now, profile, key, kept, response.effectiveRulesVersion, capAsked = sentPrefer)
            }
            if (prefetched != null && !waitsForCap && state.activeProfile == profile && wouldAsk(state, ladder, now) &&
                prefetched.isUsable(now, profile, key, ladder, state.lastRulesVersion)
            ) {
                state = state.copy(prefetched = prefetched)
                Timber.tag(TAG).i("stored next rung ${prefetched.rung.rungId} v${prefetched.rung.version} for the next launch ($source)")
            } else if (rung != null) {
                Timber.tag(TAG).i("Dropping next rung ${rung.rungId} ($source): the game no longer waits for it")
            } else if (response.hasNothing && state.activeProfile == profile && wouldAsk(state, ladder, now)) {
                // Nothing for the game: the ladder waits now, so the next launch does not ask again.
                val hold = NoRuleHold.of(response, now)
                Timber.tag(TAG).i("Optimize: nothing to try (${hold.status}, $source); not asking again before ${hold.untilMillis} or new rules")
                state = state.copy(noRule = hold).withLadder(profile, ladder.copy(state = LadderState.WAITING, lastStatus = hold.status))
                if (ladder.lastStatus !in NOTHING_STATUSES && state.card == null) {
                    state = state.copy(card = OptimizeCard(OptimizeCard.NO_RULE, now, gameName(appId), profile))
                }
            }
            store.write(state)
        }
    }

    /**
     * Makes the game files match [target]: every other profile's changes come off and [target]'s kept changes go
     * back on from the rungs it stored, without asking the server. The second value is false when something could
     * not come off; nothing is put back on then, and the state records exactly what is still on.
     */
    private suspend fun reconcile(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        target: OptimizeProfile?,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Pair<OptimizeLocalState, Boolean> {
        val plan = SwitchPlan.of(state, target)
        if (plan.isEmpty) return state to true
        var s = state
        var ok = true
        for (profile in plan.revertPending) {
            val ladder = s.ladder(profile)
            val pending = ladder.pending ?: continue
            if (!OptimizeReverter.revertLevers(context, container, pending.levers)) {
                ok = false
                continue
            }
            store.dropRungDir(profile, pending.rung.rungId)
            val baseline = if (pending.revert != null) pending.baseline ?: ladder.baselineOverride else ladder.baselineOverride
            s = s.withLadder(profile, ladder.copy(pending = null, baselineOverride = baseline))
        }
        for ((profile, rungId) in plan.revertKept) {
            val ladder = s.ladder(profile)
            val kept = ladder.kept.firstOrNull { it.rungId == rungId && it.applied } ?: continue
            if (!OptimizeReverter.revertLevers(context, container, kept.levers)) {
                ok = false
                continue
            }
            store.dropRungDir(profile, rungId)
            s = s.withLadder(profile, ladder.copy(kept = ladder.kept.map { if (it === kept) it.copy(applied = false) else it }))
        }
        if (!ok || target == null) return s to ok
        for (rungId in plan.reapply) {
            val ladder = s.ladder(target)
            val kept = ladder.kept.firstOrNull { it.rungId == rungId && !it.applied } ?: continue
            val rung = Rung.parse(kept.rung)
            if (rung == null) {
                s = s.withLadder(target, ladder.copy(kept = ladder.kept - kept))
                continue
            }
            val outcome = OptimizeApplier.apply(
                context, container, roots(context, appId, container), store.rungDir(target, rungId), rung,
                reapply = true, onProgress = onProgress,
            )
            when (outcome) {
                is ApplyOutcome.Applied -> {
                    val back = kept.copy(levers = outcome.levers, applied = true, appliedAt = System.currentTimeMillis())
                    s = s.withLadder(target, ladder.copy(kept = ladder.kept.map { if (it === kept) back else it }))
                }
                is ApplyOutcome.Failed -> {
                    Timber.tag(TAG).w("Putting ${rung.rungId} back failed: ${outcome.reason}; dropping it")
                    s = s.withLadder(target, ladder.copy(kept = ladder.kept - kept))
                }
                is ApplyOutcome.Deferred -> {
                    Timber.tag(TAG).i("Putting ${rung.rungId} back deferred: ${outcome.reason}")
                    ok = false
                }
                is ApplyOutcome.NoOp -> {
                    Timber.tag(TAG).i("Putting ${rung.rungId} back wrote nothing: values already at or below target or not in the file")
                    val back = kept.copy(levers = emptyList(), applied = true, appliedAt = System.currentTimeMillis())
                    s = s.withLadder(target, ladder.copy(kept = ladder.kept.map { if (it === kept) back else it }))
                }
            }
        }
        return s to ok
    }

    /**
     * Runs before a game starts, off the main thread. Never throws; on any failure the game launches unchanged.
     * [onProgress] reports component downloads a change needs; only the next-rung request has a time limit.
     */
    suspend fun beforeLaunch(
        context: Context,
        appId: String,
        container: Container,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        resetTelemetry()
        try {
            val store = OptimizeStore(container.rootDir)
            val waitStart = System.currentTimeMillis()
            val stillAsking = inflight[store.dir.absolutePath]?.let { job ->
                withTimeoutOrNull(NEXT_RUNG_LAUNCH_BUDGET_MS) { job.join() } == null
            } ?: false
            val budgetMs = NEXT_RUNG_LAUNCH_BUDGET_MS - (System.currentTimeMillis() - waitStart)
            mutex.withLock {
                var state = store.read()
                scope.launch { flush(context.applicationContext, appId, store, container.graphicsDriverConfig) }
                val profile = state.activeProfile
                val (reconciled, ok) = reconcile(context, appId, container, store, state, profile, onProgress)
                val changed = reconciled != state
                state = reconciled
                if (profile == null) {
                    if (changed) store.write(state)
                    return@withLock
                }
                telemetry(profile, null, "none")
                if (!ok) {
                    store.write(state)
                    telemetry(profile, null, "failed")
                    return@withLock
                }

                // A game the server had nothing for waits until its hold lets go; then the ladder tries again.
                state = OptimizeLadders.held(state, profile, System.currentTimeMillis(), appRulesVersion())

                // A step whose last trial session was never judged (the app died, or exit never ran) is judged now.
                state = judgeLostSession(context, appId, container, state, profile)

                // The flagged step comes off first, so the kept steps' keys it wrote over are not read as reset by the game.
                if (state.ladder(profile).pending?.revert != null) {
                    val off = takeOffFlagged(context, container, store, state, profile, "before launch")
                    if (off == null) {
                        store.write(state)
                        telemetry(profile, null, "failed")
                        return@withLock
                    }
                    state = off
                }
                var ladder = state.ladder(profile)
                val (reverified, resetReports) = reverifyKept(appId, container, profile, ladder)
                ladder = reverified
                resetReports.forEach { state = state.queued(it) }

                var applyResult = if (ladder.pending != null) "ok" else "none"
                val now = System.currentTimeMillis()
                val prefetched = state.prefetched
                state = state.copy(prefetched = null)
                if (ladder.pending == null && !state.capPreferred && wouldAsk(state, ladder, now)) {
                    val baseline = ladder.baselineOverride ?: baselineRun(container)
                    val userCap = userFpsLimit(container, ladder)
                    doneAtBaseline(appId, state.withLadder(profile, ladder), profile, baseline, refreshHz(context), userCap)?.let {
                        state = it
                        ladder = it.ladder(profile)
                    }
                }
                if (ladder.pending == null && wouldAsk(state, ladder, now)) {
                    val baseline = ladder.baselineOverride ?: baselineRun(container)
                    val key = prefetchKey(container)
                    val usable = prefetched?.takeIf { !state.capPreferred && it.isUsable(now, profile, key, ladder, state.lastRulesVersion) }
                    if (prefetched != null && usable == null && prefetched.staleRules(state.lastRulesVersion)) {
                        Timber.tag(TAG).i(
                            "Optimize: dropping ${prefetched.rung.rungId} v${prefetched.rung.version}, " +
                                "fetched from rules ${prefetched.rulesVersion ?: "unknown"}, " +
                                "server now ${state.lastRulesVersion ?: "unknown"}; asking the server",
                        )
                    }
                    val deadline = System.currentTimeMillis() + budgetMs
                    suspend fun ask(): NextRungResult? {
                        val body = nextRungBody(context, appId, container, state, profile, ladder, baseline)
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                        val call = OptimizeApi.nextRung(body, remaining)
                        val answer = withTimeoutOrNull(remaining) { call.await() }
                        if (answer == null) keepLateAnswer(appId, store, call, profile, key, PrefetchedRung.keptIds(ladder), state.cardAction, state.capPreferred)
                        return answer
                    }
                    suspend fun askSkippingRepeat(): NextRungResult? {
                        val first = ask()
                        val repeated = (first as? NextRungResult.Answered)?.response
                            ?.takeIf { it.status == NextRungResponse.STATUS_RUNG }?.rung?.takeIf { ladder.repeats(it) }
                        if (repeated == null || deadline <= System.currentTimeMillis()) return first
                        Timber.tag(TAG).i("Optimize: skipping ${repeated.rungId} v${repeated.version}: ${ladder.repeatReason(repeated)}")
                        return ask()
                    }

                    /**
                     * Takes one answer; true when its rung changed nothing and the next one may be asked for. [capAsked]: the
                     * request said ladder.prefer "cap". A cap the user did not ask for ends the ladder; the cap stays a question.
                     */
                    suspend fun take(result: NextRungResult?, fromPrefetch: PrefetchedRung?, capAsked: Boolean): Boolean {
                        when (result) {
                            null -> Timber.tag(TAG).i("No next-rung answer within ${NEXT_RUNG_LAUNCH_BUDGET_MS / 1000} s; launching with the kept changes")
                            NextRungResult.Failed -> Timber.tag(TAG).i("No next-rung answer; launching with the kept changes")
                            NextRungResult.SignedOut -> {
                                Timber.tag(TAG).i("Signed out; launching with the kept changes")
                                event(appId, OptimizeEvents.SIGNIN_REQUIRED, state, profile)
                            }
                            is NextRungResult.Answered -> {
                                val response = result.response
                                if (fromPrefetch == null) {
                                    rememberEntitlement(response.entitlement)
                                    rememberRulesVersion(response.effectiveRulesVersion)
                                    state = state.copy(
                                        cardAction = null,
                                        capPreferred = false,
                                        entitlement = response.entitlement ?: state.entitlement,
                                        entitlementCheckedAt = System.currentTimeMillis(),
                                        lastRulesVersion = response.effectiveRulesVersion ?: state.lastRulesVersion,
                                    )
                                }
                                val previousStatus = ladder.lastStatus
                                val at = System.currentTimeMillis()
                                val unaskedCap = response.rung?.takeIf { response.status == NextRungResponse.STATUS_RUNG && it.isFpsCap && !capAsked }
                                val capChoice = unaskedCap?.let { OptimizeCards.unaskedCap(state, it, at) }
                                if (response.status != NextRungResponse.STATUS_RUNG) {
                                    Timber.tag(TAG).i("Optimize: next rung status ${response.status} (message ${response.messageId}); nothing applied")
                                }
                                when {
                                    unaskedCap != null && capChoice == OptimizeCards.UnaskedCap.ASK -> {
                                        // The cap stays a question: the ladder waits for the answer instead of ending.
                                        val capFps = OptimizeCards.capFps(unaskedCap.raw)
                                        Timber.tag(TAG).i("Optimize: the next step is the cap ${unaskedCap.rungId} at $capFps fps; asking first")
                                        ladder = ladder.copy(state = LadderState.PAUSED, lastStatus = NextRungResponse.STATUS_CAP_QUESTION)
                                        val card = OptimizeCard(
                                            OptimizeCard.CAP, at, gameName(appId), profile, cls = baseline?.cls, metrics = baseline, capFps = capFps,
                                        )
                                        state = state.copy(card = card)
                                        cardEvent(appId, OptimizeEvents.CAP_ASKED, state, card)
                                    }
                                    response.hasNothing || response.status == NextRungResponse.STATUS_EXHAUSTED || capChoice == OptimizeCards.UnaskedCap.END -> {
                                        if (unaskedCap != null) Timber.tag(TAG).i("Optimize: only the cap ${unaskedCap.rungId} is left and was not wanted; the ladder ends here")
                                        val endKind = if (response.hasNothing) OptimizeCard.NO_RULE else OptimizeCards.endCard(ladder)
                                        if (endKind == OptimizeCard.EXHAUSTED) {
                                            ladder = ladder.copy(state = LadderState.EXHAUSTED, lastStatus = NextRungResponse.STATUS_EXHAUSTED)
                                            state = state.copy(card = OptimizeCard(OptimizeCard.EXHAUSTED, at, gameName(appId), profile))
                                            event(appId, OptimizeEvents.EXHAUSTED, state, profile, baseline?.cls, OptimizeCard.EXHAUSTED)
                                        } else {
                                            val hold = if (unaskedCap != null) {
                                                NoRuleHold(at + NoRuleHold.DEFAULT_RETRY_SEC * 1000L, response.effectiveRulesVersion, NextRungResponse.STATUS_EXHAUSTED)
                                            } else {
                                                NoRuleHold.of(response, at)
                                            }
                                            Timber.tag(TAG).i("Optimize: nothing to try (${hold.status}); not asking again before ${hold.untilMillis} or new rules")
                                            ladder = ladder.copy(state = LadderState.WAITING, lastStatus = hold.status)
                                            state = state.copy(noRule = hold)
                                            if (previousStatus !in NOTHING_STATUSES) {
                                                state = state.copy(card = OptimizeCard(OptimizeCard.NO_RULE, at, gameName(appId), profile, cls = baseline?.cls))
                                            }
                                        }
                                    }
                                    response.status == NextRungResponse.STATUS_ENTITLEMENT_REQUIRED -> {
                                        ladder = ladder.copy(lastStatus = response.status)
                                        if (previousStatus != response.status) {
                                            state = state.copy(card = OptimizeCard(OptimizeCard.ENTITLEMENT, at, gameName(appId), profile))
                                        }
                                    }
                                    response.status != NextRungResponse.STATUS_RUNG -> {
                                        ladder = ladder.copy(lastStatus = response.status)
                                    }
                                    else -> {
                                        ladder = ladder.copy(lastStatus = response.status)
                                        val offered = response.rung?.takeIf { it.rungId !in ladder.bad }
                                        val rung = offered?.takeUnless { ladder.repeats(it) }
                                        if (offered != null && rung == null) {
                                            Timber.tag(TAG).i("Optimize: skipping ${offered.rungId} v${offered.version}: ${ladder.repeatReason(offered)}")
                                        }
                                        if (rung != null) {
                                            val outcome = OptimizeApplier.apply(
                                                context, container, roots(context, appId, container), store.rungDir(profile, rung.rungId), rung,
                                                onProgress = onProgress,
                                            )
                                            when (outcome) {
                                                is ApplyOutcome.Applied -> {
                                                    ladder = ladder.copy(
                                                        pending = PendingRung(
                                                            rung, System.currentTimeMillis(), baseline, 0, outcome.levers,
                                                            appliedOps = outcome.appliedOps, skippedOps = outcome.skippedOps,
                                                        ),
                                                        baselineOverride = null,
                                                    )
                                                    applyResult = "ok"
                                                    event(appId, OptimizeEvents.APPLY_OK, state, profile, baseline?.cls, rungId = rung.rungId)
                                                    val types = rung.levers.map { it.type }.distinct().joinToString(", ")
                                                    val skipped = if (outcome.skippedOps > 0) ", ${outcome.skippedOps} ops skipped" else ""
                                                    Timber.tag(TAG).i("applied ${rung.rungId} v${rung.version} order ${rung.order} ($types$skipped)")
                                                }
                                                is ApplyOutcome.NoOp -> {
                                                    ladder = ladder.triedWith(rung.rungId, rung.version, OptimizeJudge.NO_OP)
                                                    state = state.queued(
                                                        outcomeBody(
                                                            appId, profile, rung.rungId, rung.version, baseline, null, OptimizeJudge.NO_OP,
                                                            0, emptyList(), appliedOps = 0, skippedOps = outcome.skippedOps,
                                                        ),
                                                    )
                                                    Timber.tag(TAG).i(
                                                        "Optimize: ${rung.rungId} changed nothing (all ${outcome.skippedOps} ops at or below target or not in the file), asking for the next step",
                                                    )
                                                    return true
                                                }
                                                is ApplyOutcome.Failed -> {
                                                    ladder = ladder.triedWith(rung.rungId, rung.version, OptimizeJudge.APPLY_FAILED)
                                                    state = state.queued(
                                                        outcomeBody(
                                                            appId, profile, rung.rungId, rung.version, baseline, null, OptimizeJudge.APPLY_FAILED,
                                                            outcome.leversOk, listOf(outcome.failedIndex),
                                                        ),
                                                    )
                                                    applyResult = "failed"
                                                    event(appId, OptimizeEvents.APPLY_FAILED, state, profile, baseline?.cls, rungId = rung.rungId)
                                                    Timber.tag(TAG).w("Applying ${rung.rungId} failed: ${outcome.reason}")
                                                    // A prefetched rung can be stale; the server is asked again with this one in tried.
                                                    if (fromPrefetch != null) return true
                                                }
                                                is ApplyOutcome.Deferred -> {
                                                    if (fromPrefetch != null) state = state.copy(prefetched = fromPrefetch)
                                                    Timber.tag(TAG).i("Not applying ${rung.rungId}: ${outcome.reason}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return false
                    }

                    val firstAsksCap = usable?.capAsked ?: state.capPreferred
                    val first = when {
                        usable != null -> {
                            Timber.tag(TAG).i("Using the next rung fetched ${(now - usable.fetchedAt) / 60_000} min ago")
                            NextRungResult.Answered(NextRungResponse(NextRungResponse.STATUS_RUNG, null, usable.rung))
                        }
                        stillAsking -> {
                            Timber.tag(TAG).i("An earlier next-rung request is still running; its answer is kept for the next launch")
                            null
                        }
                        else -> askSkippingRepeat()
                    }
                    if (take(first, usable, firstAsksCap) && deadline > System.currentTimeMillis()) {
                        val asksCap = state.capPreferred
                        take(askSkippingRepeat(), null, asksCap)
                    }
                }
                state = state.withLadder(profile, ladder)
                telemetry(profile, ladder.pending, applyResult)
                store.write(state)
            }
            revision.value++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize before launch failed; launching unchanged")
        }
    }

    /**
     * Takes the flagged step of [profile] (pending with a revert result) off from its backup records: file keys,
     * registry values and container settings such as fexcorePreset. Null when something could not come off; the step
     * stays flagged and the next launch tries again.
     */
    private suspend fun takeOffFlagged(
        context: Context,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        moment: String,
    ): OptimizeLocalState? {
        val ladder = state.ladder(profile)
        val flagged = ladder.pending?.takeIf { it.revert != null } ?: return state
        if (!OptimizeReverter.revertLevers(context, container, flagged.levers)) {
            Timber.tag(TAG).w("Taking ${flagged.rung.rungId} off failed; trying again next launch")
            return null
        }
        store.dropRungDir(profile, flagged.rung.rungId)
        Timber.tag(TAG).i("Optimize: reverted ${flagged.rung.rungId} v${flagged.rung.version} (${flagged.revert}) $moment")
        return state.withLadder(profile, ladder.copy(pending = null, baselineOverride = flagged.baseline ?: ladder.baselineOverride))
    }

    /** The newest run record written since [sinceMillis] for a session that had [rungId] on trial, or null. */
    private fun trialRun(container: Container, sinceMillis: Long, rungId: String): Metrics? {
        val files = File(container.rootDir, ".gamenative/runs").listFiles { f -> f.name.endsWith(".json") } ?: return null
        return files.filter { (it.name.removeSuffix(".json").toLongOrNull() ?: 0L) >= sinceMillis }
            .sortedByDescending { it.name }
            .firstNotNullOfOrNull { file ->
                val run = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val sessionRung = run.optJSONObject("optimize_state")?.optStringOrNull("template_id")
                if (sessionRung == rungId) Metrics.fromRun(run) else null
            }
    }

    /**
     * A pending step whose trial session started ([PendingRung.trialStartedAt]) but was never judged: its run record
     * is judged for a crash if it was written; with none, a hard death of the app since the session started, or a
     * second unjudged launch, counts as a crash. A crash is finished like one judged at exit (reported, marked bad,
     * a worse card) and flagged to come off.
     */
    private fun judgeLostSession(
        context: Context,
        appId: String,
        container: Container,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
    ): OptimizeLocalState {
        val ladder = state.ladder(profile)
        val pending = ladder.pending?.takeIf { it.revert == null } ?: return state
        val started = pending.trialStartedAt ?: return state
        val run = trialRun(container, started, pending.rung.rungId)
        val hardExit = if (run == null) {
            runCatching { AppExitRecorder.newestHardExit(context, started)?.optStringOrNull("reason") }.getOrNull()
        } else {
            null
        }
        return when (val lost = OptimizeJudge.lostSession(pending.unjudged, run, hardExit)) {
            is OptimizeJudge.Lost.Crash -> {
                Timber.tag(TAG).w(
                    "Optimize: the trial session of ${pending.rung.rungId} was never judged (${lost.after.exitReason}, app exit ${hardExit ?: "none"}); treating it as a crash",
                )
                val cleared = pending.copy(trialStartedAt = null, unjudged = 0)
                finish(
                    appId, state, profile, ladder.copy(pending = cleared), cleared, OptimizeJudge.CRASH, null, lost.after,
                    refreshHz(context), userFpsLimit(container, ladder), fpsLimit(container),
                )
            }
            is OptimizeJudge.Lost.Again -> {
                Timber.tag(TAG).i("Optimize: the trial session of ${pending.rung.rungId} was never judged; trying it again (${lost.unjudged} unjudged)")
                state.withLadder(profile, ladder.copy(pending = pending.copy(trialStartedAt = null, unjudged = lost.unjudged)))
            }
        }
    }

    /**
     * Called when a game session starts: marks the step on trial, so a session that is never judged (the app is
     * killed, or exit never runs) is still caught at the next launch.
     */
    suspend fun trialStarted(container: Container) = withContext(Dispatchers.IO) {
        val rungId = OptimizeState.templateId ?: return@withContext
        try {
            mutex.withLock {
                val store = OptimizeStore(container.rootDir)
                val state = store.read()
                val profile = state.activeProfile ?: return@withLock
                val ladder = state.ladder(profile)
                val pending = ladder.pending?.takeIf { it.revert == null && it.rung.rungId == rungId } ?: return@withLock
                store.write(state.withLadder(profile, ladder.copy(pending = pending.copy(trialStartedAt = System.currentTimeMillis()))))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Optimize: marking the trial session failed")
        }
    }

    /**
     * Once the game's environment is down after a session judged a crash, takes the step off straight away instead of
     * at the next launch, so the container settings already read as before (and a launch that skips Optimize does not
     * run with it). Anything that cannot come off now is taken off before the next launch.
     */
    suspend fun revertCrashed(context: Context, container: Container) = withContext(Dispatchers.IO) {
        try {
            val tookOff = mutex.withLock {
                val store = OptimizeStore(container.rootDir)
                val state = store.read()
                val profile = state.activeProfile ?: return@withLock false
                if (state.ladder(profile).pending?.revert != OptimizeJudge.CRASH) return@withLock false
                val off = takeOffFlagged(context, container, store, state, profile, "after the crash") ?: return@withLock false
                store.write(off)
                true
            }
            if (tookOff) revision.value++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Optimize: taking the crashed step off at exit failed; the next launch does it")
        }
    }

    /**
     * Before a launch: puts back the file keys and registry values of applied kept rungs that the game reset since
     * the last one. A rung reset on [MAX_RESETS] launches in a row despite a read-only file stays kept, flagged
     * unstable, and is reported once as did_not_take. A key a newer kept step or the step on trial also writes is
     * left to that step. Returns the ladder and the reports to queue.
     */
    private fun reverifyKept(appId: String, container: Container, profile: OptimizeProfile, ladder: ProfileLadder): Pair<ProfileLadder, List<JSONObject>> {
        val reports = mutableListOf<JSONObject>()
        val shadowed = OptimizeDrift.shadowedKeys(ladder.kept, ladder.pending?.takeIf { it.revert == null })
        val kept = ladder.kept.mapIndexed kept@{ i, k ->
            if (!k.applied || k.levers.none { it.optString("type") == Lever.TYPE_ENGINE_FILE || it.optString("type") == Lever.TYPE_REGISTRY }) {
                return@kept k
            }
            val rung = Rung.parse(k.rung)
            var reset = 0
            var readOnlyReset = false
            val levers = k.levers.map { record ->
                val lever = rung?.levers?.getOrNull(record.optInt("index", -1))
                val checked = try {
                    OptimizeApplier.reverify(container, record, lever, shadowed[i])
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Optimize: re-checking ${k.rungId} lever ${record.optInt("index", -1)} failed")
                    null
                } ?: return@map record
                reset += checked.reset
                if (checked.reset > 0 && checked.readOnly) readOnlyReset = true
                checked.record
            }
            if (reset == 0) return@kept if (k.resetCount != 0) k.copy(resetCount = 0) else k
            Timber.tag(TAG).i("Optimize: re-applied ${k.rungId} v${k.version}: $reset keys were reset by the game")
            var next = k.copy(levers = levers, resetCount = k.resetCount + 1)
            if (readOnlyReset && next.resetCount >= MAX_RESETS && !k.unstable) {
                Timber.tag(TAG).w("Optimize: ${k.rungId} v${k.version} reset on ${next.resetCount} launches in a row despite a read-only file")
                next = next.copy(unstable = true)
                reports += outcomeBody(appId, profile, k.rungId, k.version, k.baseline, null, OptimizeJudge.DID_NOT_TAKE, k.levers.size, emptyList())
            }
            next
        }
        return ladder.copy(kept = kept) to reports
    }

    /** Stores an answer that came after the launch stopped waiting, for the next launch. */
    private fun keepLateAnswer(
        appId: String,
        store: OptimizeStore,
        call: Deferred<NextRungResult>,
        profile: OptimizeProfile,
        key: PrefetchKey,
        kept: List<String>,
        cardAction: String?,
        prefer: Boolean,
    ) {
        track(store, "keeping a late next-rung answer") { storePrefetch(appId, store, call.await(), profile, key, kept, cardAction, prefer, "late answer") }
    }

    /**
     * perf_verdict.card as far as it is known before the session is judged: the first-card decision when no profile is
     * active, "pending" when judging may leave a card. Reads state.json without the lock; null when it cannot.
     */
    fun cardBeforeJudging(container: Container, props: Map<String, Any>): String? = try {
        val state = OptimizeStore(container.rootDir).read()
        OptimizeCards.cardBeforeJudging(state, Metrics.fromRun(JSONObject(props)), System.currentTimeMillis())
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Reading the card state at exit failed")
        null
    }

    /**
     * The menu-like windows the judge will leave out of the session that just ended, for perf_verdict.windows_excluded;
     * null when no GPU-bound step is on trial. Telemetry only: the verdict class stays as measured.
     */
    private fun windowsExcluded(context: Context?, container: Container, props: Map<String, Any>): Int? {
        val state = OptimizeStore(container.rootDir).read()
        val pending = state.active?.pending?.takeIf { it.revert == null } ?: return null
        val sessionRung = (props["optimize_state"] as? Map<*, *>)?.get("template_id") as? String
        if (sessionRung != pending.rung.rungId) return null
        val baseline = pending.baseline ?: return null
        val judgedCap = if (pending.rung.isFpsCap) userFpsLimit(container, state.active) else fpsLimit(container)
        val fpsTarget = OptimizeTarget.fpsTarget(context?.let { refreshHz(it) }, judgedCap)
        return OptimizeJudge.comparableWindows(baseline, Metrics.fromRun(JSONObject(props)), fpsTarget)?.excluded?.size
    }

    /** game_exited properties with perf_verdict.card and, while a GPU-bound step is on trial, perf_verdict.windows_excluded. */
    fun exitProperties(context: Context?, container: Container, props: Map<String, Any>): Map<String, Any> {
        val withCard = OptimizeCards.withCard(props, cardBeforeJudging(container, props))
        val excluded = try {
            windowsExcluded(context, container, props)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Counting excluded windows at exit failed")
            null
        }
        return OptimizeCards.withVerdictField(withCard, "windows_excluded", excluded)
    }

    /**
     * Judges the session that just ended; [props] are its game_exited properties. Changes that must come off are
     * taken off before the next launch, once Wine has written its registry. Leaves the card for the next screen.
     * Returns the session's perf_verdict.card value ("shown" or "suppressed:<reason>"), null when judging failed.
     */
    suspend fun afterExit(context: Context?, appId: String, container: Container, props: Map<String, Any>): String? = withContext(Dispatchers.IO) {
        try {
            val store = OptimizeStore(container.rootDir)
            val sessionRung = (props["optimize_state"] as? Map<*, *>)?.get("template_id") as? String
            val after = Metrics.fromRun(JSONObject(props))
            val refresh = context?.let { refreshHz(it) }
            val cap = fpsLimit(container)
            var prefetch: (() -> Unit)? = null
            var cardField: String? = null
            var offer: FirstOffer? = null
            var request = mutex.withLock {
                var state = store.read()
                val now = System.currentTimeMillis()
                val profile = state.activeProfile
                val cardBefore = state.card
                // The judge compares against the limiter the game ran under; the targets against the user's own.
                val userCap = userFpsLimit(container, profile?.let { state.ladder(it) })
                if (profile == null) {
                    val rulesVersion = appRulesVersion()
                    val block = OptimizeCards.firstCardBlock(state, after, now, rulesVersion, cap)
                    cardField = OptimizeCards.cardField(block == null, block)
                    // An unsure or unlisted verdict on a real session still asks the server once per rules build;
                    // its own verdict may leave the card. The user's holds and short sessions never ask.
                    val hold = OptimizeCards.userHold(state, after, now, rulesVersion)
                    val asks = block == null ||
                        (context != null && OptimizeServer.asksAtExit(block, after.gameplaySec, hold, state.exitAskedRulesVersion, rulesVersion))
                    if (asks && block != null) {
                        state = state.copy(exitAskedRulesVersion = rulesVersion ?: OptimizeServer.UNKNOWN_RULES)
                        store.write(state)
                    }
                    if (asks) {
                        val suggested = OptimizeCards.SUGGESTED_PROFILE
                        val capFps = OptimizeCards.capQuestion(state, after, cap, now, refresh)
                        val card = OptimizeCard(
                            if (capFps != null) OptimizeCard.CAP else OptimizeCard.FIRST, now, gameName(appId),
                            profile = suggested,
                            cls = after.cls,
                            metrics = after,
                            capFps = capFps,
                        )
                        if (context != null) {
                            // The card is only written once the server says it has a change for the game.
                            val body = nextRungBody(
                                context.applicationContext, appId, container, state, suggested, state.ladder(suggested), after, dryRun = true,
                                series = SessionPerfSampler.seriesFor(after.gameplaySec),
                            )
                            offer = FirstOffer(card, body, block)
                        } else {
                            val unverified = card.copy(unverified = true)
                            state = state.copy(card = unverified, suppression = null)
                            store.write(state)
                            if (unverified.kind == OptimizeCard.CAP) cardEvent(appId, OptimizeEvents.CAP_ASKED, state, unverified)
                        }
                    }
                } else {
                    val ladder = state.ladder(profile)
                    // The session was judged, so the trial mark comes off whatever the decision.
                    val pending = ladder.pending?.let {
                        if (it.revert == null && sessionRung == it.rung.rungId) it.copy(trialStartedAt = null, unjudged = 0) else it
                    }
                    if (pending != null && pending.revert == null && sessionRung == pending.rung.rungId) {
                        // A cap step is judged against the user's own limiter, so its own cap never reads as menu windows.
                        val fpsTarget = OptimizeTarget.fpsTarget(refresh, if (pending.rung.isFpsCap) userCap else cap)
                        state = when (val decision = OptimizeJudge.judge(after, pending.baseline, pending.rung.isFpsCap, pending.shortSessions, fpsTarget)) {
                            is OptimizeJudge.Decision.Short ->
                                state.withLadder(profile, ladder.copy(pending = pending.copy(shortSessions = decision.shortSessions)))
                            is OptimizeJudge.Decision.Crash -> {
                                if (OptimizeJudge.frozen(after)) {
                                    Timber.tag(TAG).w("Optimize: the game froze on ${pending.rung.rungId} (${after.framesStoppedSec} s without a frame); treating it as a crash")
                                }
                                finish(appId, state, profile, ladder, pending, OptimizeJudge.CRASH, null, after, refresh, userCap, cap)
                            }
                            is OptimizeJudge.Decision.Final -> {
                                if (decision.excludedWindows.isNotEmpty()) {
                                    Timber.tag(TAG).i(
                                        "Optimize: judging ${pending.rung.rungId} without menu-like windows ${decision.excludedWindows} (${decision.result})",
                                    )
                                }
                                finish(
                                    appId, state, profile, ladder, pending, decision.result, decision.reason, after, refresh, userCap, cap,
                                    judged = decision.judged ?: after, excludedWindows = decision.excludedWindows,
                                )
                            }
                        }
                        store.write(state)
                    }
                    val current = state.ladder(profile)
                    if (current.pending == null && !state.capPreferred) {
                        doneAtBaseline(appId, state, profile, current.baselineOverride ?: baselineRun(container), refresh, userCap)?.let {
                            state = it
                            store.write(state)
                        }
                    }
                    capCard(appId, state, after, userFpsLimit(container, state.ladder(profile)), now, refresh)?.let {
                        state = it
                        store.write(state)
                    }
                    OptimizeLadders.held(state, profile, now, appRulesVersion()).takeIf { it != state }?.let {
                        state = it
                        store.write(state)
                    }
                    if (context != null) prefetch = exitPrefetch(context.applicationContext, appId, container, store, state, profile, now)
                    cardField = OptimizeCards.cardField(state.card != null && state.card != cardBefore, OptimizeCards.SUPPRESSED_PROFILE)
                }
                state.card?.let { CardRequest(appId, it, shownEntitlement(state)) }
            }
            offer?.let { o ->
                val (offered, field) = offerFirst(appId, store, o)
                cardField = field
                if (offered != null) request = offered
            }
            request?.let { cardRequest.value = it }
            revision.value++
            if (context != null) scope.launch { flush(context.applicationContext, appId, store, container.graphicsDriverConfig) }
            prefetch?.invoke()
            cardField
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize judging failed")
            null
        }
    }

    /** A first or cap card the session would leave, and the dry-run request that decides whether it is written. */
    private class FirstOffer(val card: OptimizeCard, val body: JSONObject, val localBlock: String? = null)

    /**
     * Asks the server, without applying or recording anything, whether it has a change for the game before the first
     * card is written. A rung writes the card; a cap it wants asked first writes the cap question; no_rule and
     * not_settings write none and hold the game until retry_after_s passes or the rules change. With no answer the
     * card is written as before, marked unverified. Returns the card to show and perf_verdict.card.
     */
    private suspend fun offerFirst(appId: String, store: OptimizeStore, offer: FirstOffer): Pair<CardRequest?, String> {
        val result = withTimeoutOrNull(FIRST_OFFER_BUDGET_MS) { OptimizeApi.nextRung(offer.body, FIRST_OFFER_BUDGET_MS).await() }
        val response = (result as? NextRungResult.Answered)?.response
        if (response != null) {
            rememberEntitlement(response.entitlement)
            rememberRulesVersion(response.effectiveRulesVersion)
        }
        val choice = OptimizeCards.offerOf(response)
        val server = response?.verdictServer
        Timber.tag(TAG).i(
            "Optimize: first offer ${choice.name.lowercase()} (${response?.status ?: "no answer"}), server verdict ${server?.cls ?: "none"} from ${server?.source ?: "none"} show ${server?.showCard}",
        )
        return mutex.withLock {
            val read = store.read()
            var state = read
            val now = System.currentTimeMillis()
            if (response != null) {
                state = state.copy(
                    entitlement = response.entitlement ?: state.entitlement,
                    entitlementCheckedAt = if (response.entitlement != null) now else state.entitlementCheckedAt,
                    lastRulesVersion = response.effectiveRulesVersion ?: state.lastRulesVersion,
                )
            }
            // The user may have picked a profile or answered something while the server was asked, and the server's
            // verdict never overrules a hold or snooze of the user's own.
            OptimizeCards.userHold(state, offer.card.metrics, now, appRulesVersion())?.let { why ->
                if (state != read) store.write(state)
                return@withLock null to OptimizeCards.cardField(false, why)
            }
            val capFromRung = OptimizeCards.capFps(response?.rung?.raw)
            // The server's verdict decides the card when it gives one; otherwise the local block stands.
            val decision = OptimizeServer.firstDecision(offer.localBlock, offer.card, server, capFromRung)
            // A first card names the frame rate the server expects, when it sent one.
            val base = decision.card?.let { c -> if (c.kind == OptimizeCard.FIRST) c.copy(expectedFps = response?.expected?.steadyFps) else c }
            if (base == null) {
                if (state != read) store.write(state)
                return@withLock null to OptimizeCards.cardField(false, decision.block)
            }
            val card = when (choice) {
                OptimizeCards.Offer.CARD -> base
                OptimizeCards.Offer.UNVERIFIED -> base.copy(unverified = true)
                OptimizeCards.Offer.CAP -> when {
                    now < state.capSnoozedUntil -> null
                    base.kind == OptimizeCard.CAP -> base
                    capFromRung != null -> base.copy(kind = OptimizeCard.CAP, capFps = capFromRung)
                    else -> base
                }
                OptimizeCards.Offer.HOLD -> null
            }
            val field = when {
                card != null -> OptimizeCards.cardField(true, null)
                choice == OptimizeCards.Offer.HOLD -> OptimizeCards.cardField(false, OptimizeCards.SUPPRESSED_NO_RULE)
                else -> OptimizeCards.cardField(false, OptimizeCards.SUPPRESSED_CAP_SNOOZED)
            }
            state = if (card != null) {
                state.copy(card = card, suppression = null, noRule = null)
            } else if (choice == OptimizeCards.Offer.HOLD && response != null) {
                state.copy(noRule = NoRuleHold.of(response, now))
            } else {
                state
            }
            if (state != read) store.write(state)
            if (card?.kind == OptimizeCard.CAP) cardEvent(appId, OptimizeEvents.CAP_ASKED, state, card)
            card?.let { CardRequest(appId, it, shownEntitlement(state)) } to field
        }
    }

    /**
     * At exit the game is closed, so the next rung can be asked for without holding up a launch. Returns the request
     * to start once the lock is released, or null when the next launch would not ask or already has an answer.
     */
    private fun exitPrefetch(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        nowMillis: Long,
    ): (() -> Unit)? {
        val ladder = state.ladder(profile)
        if (!wouldAsk(state, ladder, nowMillis)) return null
        val key = prefetchKey(container)
        if (state.prefetched?.isUsable(nowMillis, profile, key, ladder, state.lastRulesVersion) == true) return null
        val flagged = ladder.pending?.takeIf { it.revert != null }
        val override = if (flagged != null) flagged.baseline ?: ladder.baselineOverride else ladder.baselineOverride
        val baseline = override ?: baselineRun(container)
        val body = nextRungBody(context, appId, container, state, profile, ladder, baseline, series = SessionPerfSampler.seriesFor(baseline?.gameplaySec))
        val kept = PrefetchedRung.keptIds(ladder)
        val cardAction = state.cardAction
        val prefer = state.capPreferred
        return {
            track(store, "asking for the next rung at exit") {
                val result = withTimeoutOrNull(NEXT_RUNG_EXIT_BUDGET_MS) { OptimizeApi.nextRung(body, NEXT_RUNG_EXIT_BUDGET_MS).await() }
                if (result == null) {
                    Timber.tag(TAG).i("No next-rung answer within ${NEXT_RUNG_EXIT_BUDGET_MS / 1000} s of exit; the next launch asks again")
                } else {
                    storePrefetch(appId, store, result, profile, key, kept, cardAction, prefer, "exit")
                }
            }
        }
    }

    private fun finish(
        appId: String,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        ladder: ProfileLadder,
        pending: PendingRung,
        judgedResult: String,
        judgeReason: String?,
        after: Metrics,
        refreshHz: Double?,
        fpsCap: Int,
        sessionFpsLimit: Int,
        judged: Metrics = after,
        excludedWindows: List<Int> = emptyList(),
    ): OptimizeLocalState {
        val rung = pending.rung
        val now = System.currentTimeMillis()
        val settled = OptimizeTarget.settle(profile, judgedResult, judgeReason, judged, refreshHz, fpsCap, rung.isFpsCap)
        val result = settled.result
        val scaling = if (result == OptimizeJudge.CRASH) null else OptimizeJudge.gpuWorkScaling(rung, pending.levers, pending.baseline, judged)
        val report = outcomeBody(
            appId, profile, rung.rungId, rung.version, pending.baseline, after, result, pending.levers.size, emptyList(),
            crash = after.takeIf { result == OptimizeJudge.CRASH },
            appliedOps = pending.appliedOps,
            skippedOps = pending.skippedOps,
            judgeReason = settled.reason,
            excludedWindows = excludedWindows,
            gpuWorkScaling = scaling,
            fpsLimit = sessionFpsLimit,
            userFpsCap = fpsCap,
        )
        var l = OptimizeLadders.afterJudged(ladder, pending, result, settled.reason, judged)
        val name = gameName(appId)
        val capFps = OptimizeCards.capFps(rung.raw)
        // After-cards carry both sessions the judge compared: before is the step's baseline, metrics the session after it.
        val before = pending.baseline
        val fpsGain = OptimizeCards.fpsGainPct(before, judged)
        val stutterDrop = OptimizeCards.stutterDropPct(before, judged)
        val card = when {
            settled.done -> {
                l = l.copy(state = LadderState.DONE)
                OptimizeCard(
                    OptimizeCard.DONE, now, name, profile,
                    rungId = rung.rungId,
                    capFps = capFps,
                    fpsGainPct = fpsGain,
                    stutterDropPct = stutterDrop,
                    metrics = judged,
                    before = before,
                )
            }
            result == OptimizeJudge.HELPED -> OptimizeCard(
                OptimizeCard.HELPED, now, name, profile,
                rungId = rung.rungId,
                capFps = capFps,
                outcome = OptimizeCards.helpedOutcome(rung.outcome, profile, fpsGain, stutterDrop),
                fpsGainPct = fpsGain,
                stutterDropPct = stutterDrop,
                metrics = judged,
                before = before,
            )
            result == OptimizeJudge.NO_CHANGE -> {
                if (l.noChangeStreak >= MAX_NO_CHANGE_STREAK) l = l.copy(state = LadderState.PAUSED)
                OptimizeCard(OptimizeCard.NO_CHANGE, now, name, profile, rungId = rung.rungId, capFps = capFps, metrics = judged, before = before)
            }
            result == OptimizeJudge.CRASH -> OptimizeCard(
                OptimizeCard.WORSE, now, name, profile,
                rungId = rung.rungId, capFps = capFps, outcome = OptimizeCard.OUTCOME_CRASH, metrics = judged, before = before,
            )
            result == OptimizeJudge.WORSE ->
                OptimizeCard(OptimizeCard.WORSE, now, name, profile, rungId = rung.rungId, capFps = capFps, metrics = judged, before = before)
            else -> null
        }
        val s = state.queued(report).withLadder(profile, l).let { if (scaling != null) it.copy(gpuWorkScaling = scaling) else it }
        OptimizeEvents.judged(result)?.let {
            event(appId, it, s, profile, judged.cls ?: before?.cls, card?.kind, rung.rungId, extra = OptimizeEvents.judgedExtra(before, judged, scaling))
        }
        if (settled.done) event(appId, OptimizeEvents.DONE, s, profile, judged.cls ?: before?.cls, OptimizeCard.DONE, rung.rungId)
        return if (card != null) s.copy(card = card) else s
    }

    /** The cap question for the session that just ended, or null when it is not asked. */
    private fun capCard(
        appId: String,
        state: OptimizeLocalState,
        after: Metrics,
        fpsCap: Int,
        nowMillis: Long,
        refreshHz: Double?,
    ): OptimizeLocalState? {
        val profile = state.activeProfile ?: return null
        if (after.confident != true || after.gameplaySec < OptimizeCards.MIN_GAMEPLAY_SEC) return null
        val waiting = state.card
        if (waiting?.kind == OptimizeCard.CAP || waiting?.kind == OptimizeCard.ENTITLEMENT) return null
        if (waiting == null && state.ladder(profile).state !in setOf(LadderState.EXHAUSTED, LadderState.DONE, LadderState.WAITING)) return null
        val capFps = OptimizeCards.capQuestion(state, after, fpsCap, nowMillis, refreshHz) ?: return null
        Timber.tag(TAG).i("Optimize: heat is the limit, asking to hold ${profile.wireId} at $capFps fps")
        val card = OptimizeCard(OptimizeCard.CAP, nowMillis, gameName(appId), profile, cls = after.cls, metrics = after, capFps = capFps)
        return state.copy(card = card).also { cardEvent(appId, OptimizeEvents.CAP_ASKED, it, card) }
    }

    /**
     * Yes to the cap question: the next request sends ladder.prefer "cap", so the server serves the cap rung first.
     * With no active profile the card's suggested one is picked; the ladder tries again either way.
     */
    private suspend fun holdCap(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        card: OptimizeCard,
    ): Pair<OptimizeLocalState, Boolean> {
        val base = state.copy(capPreferred = true, prefetched = null, suppression = null, noRule = null)
        val (switched, ok) = if (base.activeProfile == null) {
            switchTo(context, appId, container, store, base, card.profile ?: OptimizeProfile.BALANCED, card.metrics)
        } else {
            base to true
        }
        val profile = switched.activeProfile ?: return switched to ok
        val trying = switched.ladder(profile).copy(state = LadderState.TRYING, noChangeStreak = 0)
        return switched.withLadder(profile, trying).copy(card = switched.card?.takeIf { it.kind != OptimizeCard.DONE }) to ok
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

    /** The goal suggested for the game, and whether heat held its newest judged session back. */
    data class Suggestion(val profile: OptimizeProfile, val hot: Boolean)

    suspend fun suggestion(context: Context, appId: String): Suggestion? = withContext(Dispatchers.IO) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext null
            val container = ContainerUtils.getContainer(context, appId)
            val baseline = baselineRun(container)?.takeIf { it.confident == true && it.cls in OptimizeCards.CLASSES } ?: return@withContext null
            val cap = userFpsLimit(container, OptimizeStore(container.rootDir).read().active)
            val refresh = refreshHz(context)
            Suggestion(OptimizeCards.SUGGESTED_PROFILE, OptimizeTarget.hot(baseline, refresh, cap))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Reading the optimize suggestion failed")
            null
        }
    }

    /** Publishes a card the game still has waiting, for when its screen is shown again. */
    suspend fun surfaceCard(context: Context, appId: String) {
        if (cardRequest.value?.appId == appId) return
        val state = readState(context, appId) ?: return
        val card = state.card ?: return
        cardRequest.value = CardRequest(appId, card, shownEntitlement(state))
    }

    enum class ChangeResult { DONE, GAME_RUNNING, NO_CONTAINER, FAILED }

    /** Runs [block] on the game's state under the lock; it returns the new state and whether everything worked. */
    private suspend fun change(
        context: Context,
        appId: String,
        touchesFiles: Boolean,
        what: String,
        block: suspend (Container, OptimizeStore, OptimizeLocalState) -> Pair<OptimizeLocalState, Boolean>,
    ): ChangeResult = withContext(Dispatchers.IO) {
        if (touchesFiles && isGameRunning()) return@withContext ChangeResult.GAME_RUNNING
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return@withContext ChangeResult.NO_CONTAINER
            val container = ContainerUtils.getContainer(context, appId)
            val store = OptimizeStore(container.rootDir)
            var request: CardRequest? = null
            val ok = mutex.withLock {
                val current = store.read()
                val (next, worked) = block(container, store, current)
                store.write(next)
                request = next.card?.takeIf { it != current.card }?.let { CardRequest(appId, it, shownEntitlement(next)) }
                worked
            }
            request?.let { cardRequest.value = it }
            revision.value++
            scope.launch { flush(context.applicationContext, appId, store, container.graphicsDriverConfig) }
            if (ok) ChangeResult.DONE else ChangeResult.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize: $what failed")
            ChangeResult.FAILED
        }
    }

    private suspend fun switchTo(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        target: OptimizeProfile?,
        cardSession: Metrics? = null,
        doneCard: Boolean = true,
        doneAtPick: Boolean = true,
    ): Pair<OptimizeLocalState, Boolean> {
        val (reconciled, ok) = reconcile(context, appId, container, store, state, target)
        if (!ok) return reconciled to false
        var s = reconciled.copy(
            activeProfile = target,
            never = if (target != null) false else reconciled.never,
            card = reconciled.card?.takeIf { it.kind != OptimizeCard.FIRST || target == null },
        )
        if (target != null) {
            val ladder = s.ladder(target)
            s = s.withLadder(target, OptimizeLadders.accepted(OptimizeLadders.picked(ladder), cardSession))
            s = OptimizeLadders.held(s, target, System.currentTimeMillis(), appRulesVersion())
            val baseline = s.ladder(target).baselineOverride ?: baselineRun(container)
            if (doneAtPick) {
                s = doneAtBaseline(appId, s, target, baseline, refreshHz(context), userFpsLimit(container, s.ladder(target)), doneCard) ?: s
            }
        }
        return s to true
    }

    /**
     * Switches the active profile: the current profile's changes come off and the new one's kept changes go back on.
     * No profile's ladder is reset. [OptimizeProfile.OFF] takes everything off and keeps every ladder.
     * The goal picked reads as tuning on the next launch; whether it is already met is judged at that launch.
     */
    suspend fun setProfile(context: Context, appId: String, profile: OptimizeProfile): ChangeResult =
        change(context, appId, touchesFiles = true, what = "changing the profile") { container, store, state ->
            switchTo(context, appId, container, store, state, profile.takeIf { it != OptimizeProfile.OFF }, doneCard = false, doneAtPick = false)
        }

    /**
     * Takes the active profile's changes off and forgets what it kept, and the profile stays active. What it tried stays,
     * unless [fresh]: then, once everything came off, every goal starts over ([OptimizeLadders.restarted]).
     */
    private suspend fun restore(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        nextState: LadderState,
        fresh: Boolean = false,
    ): Pair<OptimizeLocalState, Boolean> {
        val profile = state.activeProfile ?: return (state to true)
        var s = state
        var ladder = s.ladder(profile)
        val original = OptimizeLadders.baselineAfterRestore(ladder)
        var ok = true
        val reverted = mutableListOf<String>()
        ladder.pending?.let { pending ->
            if (OptimizeReverter.revertLevers(context, container, pending.levers)) {
                store.dropRungDir(profile, pending.rung.rungId)
                reverted += "${pending.rung.rungId} v${pending.rung.version}"
                if (pending.revert == null) {
                    s = s.queued(undoReport(appId, profile, pending.rung.rungId, pending.rung.version, pending.baseline, pending.levers.size))
                }
                ladder = ladder.copy(pending = null)
            } else {
                ok = false
            }
        }
        for (kept in ladder.kept.asReversed()) {
            if (kept.applied && !OptimizeReverter.revertLevers(context, container, kept.levers)) {
                ok = false
                continue
            }
            store.dropRungDir(profile, kept.rungId)
            reverted += "${kept.rungId} v${kept.version}"
            ladder = ladder.copy(kept = ladder.kept - kept)
            s = s.queued(undoReport(appId, profile, kept.rungId, kept.version, kept.baseline, kept.levers.size))
        }
        if (ok) ladder = ladder.copy(state = nextState, noChangeStreak = 0, baselineOverride = original)
        if (reverted.isNotEmpty()) event(appId, OptimizeEvents.RESTORED, s, profile)
        Timber.tag(TAG).i(
            "Optimize: restore ${profile.wireId} reverted ${reverted.ifEmpty { listOf("nothing") }.joinToString()}" +
                if (ok) "; ladder ${nextState.wireId}${if (fresh) ", starting over" else ""}" else "; some changes failed to come off",
        )
        if (ok && fresh) return OptimizeLadders.restarted(s.withLadder(profile, ladder), profile, nextState, original) to true
        return s.withLadder(profile, ladder) to ok
    }

    /**
     * "Restore original settings": the profile stays active and paused, so Continue picks it up again, and every goal
     * starts over. With nothing on to take off, the ladder is left as it is.
     */
    suspend fun restoreOriginal(context: Context, appId: String): ChangeResult =
        change(context, appId, touchesFiles = true, what = "restoring original settings") { container, store, state ->
            if (!OptimizeLadders.hasRestorable(state.active)) {
                state to true
            } else {
                restore(context, appId, container, store, state, LadderState.PAUSED, fresh = true)
            }
        }

    /** Resumes a paused or done ladder: the next launch asks for the next step again. */
    suspend fun continueLadder(context: Context, appId: String): ChangeResult =
        change(context, appId, touchesFiles = false, what = "continuing") { _, _, state -> OptimizeLadders.resumed(state) to true }

    /** Takes off the step a helped card was about; the ladder keeps trying, so the next launch asks again. */
    private suspend fun undoStep(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        rungId: String?,
    ): Pair<OptimizeLocalState, Boolean> {
        val profile = state.activeProfile ?: return (state to true)
        val ladder = state.ladder(profile)
        val kept = ladder.kept.lastOrNull { it.rungId == rungId } ?: return (state to true)
        if (kept.applied && !OptimizeReverter.revertLevers(context, container, kept.levers)) return state to false
        store.dropRungDir(profile, kept.rungId)
        val tried = ladder.tried.map { if (it.rungId == kept.rungId && it.version == kept.version) it.copy(result = OptimizeJudge.UNDO) else it }
        val next = ladder.copy(kept = ladder.kept - kept, tried = tried, state = LadderState.TRYING, noChangeStreak = 0, baselineOverride = kept.before)
        return state.queued(undoReport(appId, profile, kept.rungId, kept.version, kept.baseline, kept.levers.size)).withLadder(profile, next) to true
    }

    /** Applies the user's answer to [card] on the controller's scope, so closing the card does not cancel it. */
    fun answer(context: Context, appId: String, card: OptimizeCard, action: String, profile: OptimizeProfile? = null) {
        if (cardRequest.value?.appId == appId) cardRequest.value = null
        val appContext = context.applicationContext
        scope.launch {
            when (answerCard(appContext, appId, card, action, profile)) {
                ChangeResult.GAME_RUNNING -> SnackbarManager.show(appContext.getString(R.string.optimize_game_running))
                ChangeResult.FAILED -> SnackbarManager.show(appContext.getString(R.string.optimize_undo_failed))
                ChangeResult.DONE, ChangeResult.NO_CONTAINER -> Unit
            }
        }
    }

    /** The funnel event an answer to [card] is, if any. */
    private fun answerEvent(appId: String, state: OptimizeLocalState, card: OptimizeCard, action: String, profile: OptimizeProfile?) {
        val name = when {
            card.kind == OptimizeCard.CAP -> if (action == OptimizeCards.ACTION_CAP) OptimizeEvents.CAP_ACCEPTED else OptimizeEvents.CAP_DECLINED
            action == OptimizeCards.ACTION_OPTIMIZE -> OptimizeEvents.CARD_ACCEPTED
            action == OptimizeCards.ACTION_NOT_NOW -> OptimizeEvents.CARD_NOT_NOW
            action == OptimizeCards.ACTION_DISMISSED && card.kind == OptimizeCard.FIRST -> OptimizeEvents.CARD_NOT_NOW
            action == OptimizeCards.ACTION_NEVER -> OptimizeEvents.CARD_NEVER
            action == OptimizeCards.ACTION_HAPPY -> OptimizeEvents.DONE
            else -> null
        } ?: return
        cardEvent(appId, name, state, card, profile ?: card.profile)
    }

    /** [profile] is the profile chosen with Optimize. */
    private suspend fun answerCard(context: Context, appId: String, card: OptimizeCard, action: String, profile: OptimizeProfile?): ChangeResult {
        val touchesFiles = action == OptimizeCards.ACTION_OPTIMIZE || action == OptimizeCards.ACTION_UNDO ||
            action == OptimizeCards.ACTION_UNDO_ALL || action == OptimizeCards.ACTION_CAP
        return change(context, appId, touchesFiles, what = "answering a card") { container, store, current ->
            val now = System.currentTimeMillis()
            answerEvent(appId, current, card, action, profile)
            val state = current.copy(card = null, cardAction = OptimizeCards.wireAction(action, card.kind))
            val active = state.activeProfile
            fun setLadder(next: LadderState, only: Set<LadderState>? = null): OptimizeLocalState {
                if (active == null) return state
                val ladder = state.ladder(active)
                if (only != null && ladder.state !in only) return state
                return state.withLadder(active, ladder.copy(state = next, noChangeStreak = 0))
            }
            when {
                card.kind == OptimizeCard.CAP && action == OptimizeCards.ACTION_CAP -> holdCap(context, appId, container, store, state, card)
                card.kind == OptimizeCard.CAP -> OptimizeCards.capDeclined(state, now) to true
                else -> null
            }?.let { return@change it }
            when (action) {
                OptimizeCards.ACTION_OPTIMIZE ->
                    switchTo(context, appId, container, store, state.copy(suppression = null), profile ?: OptimizeProfile.BALANCED, card.metrics)
                OptimizeCards.ACTION_NOT_NOW -> state.copy(suppression = OptimizeCards.notNow(card, now)) to true
                OptimizeCards.ACTION_NEVER -> state.copy(never = true) to true
                OptimizeCards.ACTION_KEEP_GOING -> setLadder(LadderState.TRYING) to true
                OptimizeCards.ACTION_HAPPY -> setLadder(LadderState.DONE) to true
                OptimizeCards.ACTION_STOP -> setLadder(LadderState.PAUSED) to true
                OptimizeCards.WIRE_CONTINUE -> OptimizeLadders.resumed(state) to true
                OptimizeCards.ACTION_UNDO -> undoStep(context, appId, container, store, state, card.rungId)
                OptimizeCards.ACTION_UNDO_ALL -> restore(context, appId, container, store, state, LadderState.EXHAUSTED)
                OptimizeCards.ACTION_DISMISSED -> when (card.kind) {
                    OptimizeCard.FIRST -> state.copy(suppression = OptimizeCards.notNow(card, now)) to true
                    OptimizeCard.ENTITLEMENT -> state to true
                    else -> setLadder(LadderState.PAUSED, only = setOf(LadderState.TRYING)) to true
                }
                else -> state to true
            }
        }
    }
}
