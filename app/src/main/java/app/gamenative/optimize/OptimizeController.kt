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
import app.gamenative.service.SteamService
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.screen.support.SupportGameFiles
import app.gamenative.ui.util.SnackbarManager
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private const val MAX_NO_CHANGE_STREAK = 2

    val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class CardRequest(val appId: String, val card: OptimizeCard, val entitlement: Entitlement?)

    /** The card to show on the next library or game screen; the host clears it once the user answers. */
    val cardRequest = MutableStateFlow<CardRequest?>(null)

    /** Bumped after every state change so open screens read the state again. */
    val revision = MutableStateFlow(0)

    /** The latest entitlement any next-rung answer carried, for games that have not asked yet. */
    @Volatile
    var lastEntitlement: Entitlement? = null
        private set

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

    private fun gameName(appId: String): String = runCatching { ContainerUtils.resolveGameName(appId) }.getOrDefault("Unknown")

    private fun gameJson(appId: String): JSONObject = JSONObject().apply {
        put("store", storeId(appId))
        put("app_id", gameId(appId))
        put("name", gameName(appId))
    }

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

    private fun nextRungBody(
        context: Context,
        appId: String,
        container: Container,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        ladder: ProfileLadder,
        baseline: Metrics?,
    ): JSONObject {
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
            put("profile", profile.wireId)
            put("card_action", state.cardAction ?: JSONObject.NULL)
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
                    put("state", ladder.state.wireId)
                    put(
                        "kept",
                        JSONArray().apply {
                            ladder.kept.forEach { put(JSONObject().put("rung_id", it.rungId).put("version", it.version)) }
                        },
                    )
                    put("tried", JSONArray().apply { ladder.tried.forEach { put(it.toJson()) } })
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

    private fun ProfileLadder.triedWith(rungId: String, version: String, result: String): ProfileLadder =
        copy(tried = (tried + TriedRung(rungId, version, result)).takeLast(OptimizeLocalState.MAX_TRIED))

    private fun undoReport(appId: String, profile: OptimizeProfile, rungId: String, version: String, baseline: Metrics?, levers: Int): JSONObject =
        outcomeBody(appId, profile, rungId, version, baseline, null, OptimizeJudge.UNDO, levers, emptyList())

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
                context, container, roots(context, appId, container), store.rungDir(target, rungId), rung, reapply = true,
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
            }
        }
        return s to ok
    }

    /** Runs before a game starts, off the main thread. Never throws; on any failure the game launches unchanged. */
    suspend fun beforeLaunch(context: Context, appId: String, container: Container) = withContext(Dispatchers.IO) {
        resetTelemetry()
        try {
            val store = OptimizeStore(container.rootDir)
            mutex.withLock {
                var state = store.read()
                scope.launch { flush(context.applicationContext, store) }
                val profile = state.activeProfile
                val (reconciled, ok) = reconcile(context, appId, container, store, state, profile)
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

                var ladder = state.ladder(profile)
                val flagged = ladder.pending?.takeIf { it.revert != null }
                if (flagged != null) {
                    if (!OptimizeReverter.revertLevers(context, container, flagged.levers)) {
                        Timber.tag(TAG).w("Taking ${flagged.rung.rungId} off failed; trying again next launch")
                        store.write(state)
                        telemetry(profile, null, "failed")
                        return@withLock
                    }
                    store.dropRungDir(profile, flagged.rung.rungId)
                    ladder = ladder.copy(pending = null, baselineOverride = flagged.baseline ?: ladder.baselineOverride)
                }

                var applyResult = if (ladder.pending != null) "ok" else "none"
                val ask = OptimizeCards.shouldAskAfterTrial(
                    ladder, state.entitlement, state.entitlementCheckedAt, Membership.isMember(), System.currentTimeMillis(),
                )
                if (ladder.pending == null && ladder.state == LadderState.TRYING && ask) {
                    val baseline = ladder.baselineOverride ?: latestRun(container)?.let { Metrics.fromRun(it) }
                    val body = nextRungBody(context, appId, container, state, profile, ladder, baseline)
                    when (val result = OptimizeApi.nextRung(body, NEXT_RUNG_TIMEOUT_MS)) {
                        null, NextRungResult.Failed -> Timber.tag(TAG).i("No next-rung answer; launching with the kept changes")
                        NextRungResult.SignedOut -> Timber.tag(TAG).i("Signed out; launching with the kept changes")
                        is NextRungResult.Answered -> {
                            val response = result.response
                            response.entitlement?.let { lastEntitlement = it }
                            val previousStatus = ladder.lastStatus
                            state = state.copy(
                                cardAction = null,
                                entitlement = response.entitlement ?: state.entitlement,
                                entitlementCheckedAt = System.currentTimeMillis(),
                            )
                            ladder = ladder.copy(lastStatus = response.status)
                            when (response.status) {
                                NextRungResponse.STATUS_EXHAUSTED -> {
                                    ladder = ladder.copy(state = LadderState.EXHAUSTED)
                                    state = state.copy(card = OptimizeCard(OptimizeCard.EXHAUSTED, System.currentTimeMillis(), gameName(appId), profile))
                                }
                                NextRungResponse.STATUS_ENTITLEMENT_REQUIRED -> if (previousStatus != response.status) {
                                    state = state.copy(card = OptimizeCard(OptimizeCard.ENTITLEMENT, System.currentTimeMillis(), gameName(appId), profile))
                                }
                                NextRungResponse.STATUS_RUNG -> {
                                    val rung = response.rung?.takeIf { it.rungId !in ladder.bad }
                                    if (rung != null) {
                                        val outcome = OptimizeApplier.apply(
                                            context, container, roots(context, appId, container), store.rungDir(profile, rung.rungId), rung,
                                        )
                                        when (outcome) {
                                            is ApplyOutcome.Applied -> {
                                                ladder = ladder.copy(
                                                    pending = PendingRung(rung, System.currentTimeMillis(), baseline, 0, outcome.levers),
                                                    baselineOverride = null,
                                                )
                                                applyResult = "ok"
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
                                                Timber.tag(TAG).w("Applying ${rung.rungId} failed: ${outcome.reason}")
                                            }
                                            is ApplyOutcome.Deferred -> Timber.tag(TAG).i("Not applying ${rung.rungId}: ${outcome.reason}")
                                        }
                                    }
                                }
                            }
                        }
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
     * Judges the session that just ended; [props] are its game_exited properties. Changes that must come off are
     * taken off before the next launch, once Wine has written its registry. Leaves the card for the next screen.
     */
    suspend fun afterExit(context: Context?, appId: String, container: Container, props: Map<String, Any>) = withContext(Dispatchers.IO) {
        try {
            val store = OptimizeStore(container.rootDir)
            val sessionRung = (props["optimize_state"] as? Map<*, *>)?.get("template_id") as? String
            val after = Metrics.fromRun(JSONObject(props))
            val refresh = context?.let { refreshHz(it) }
            val cap = fpsLimit(container)
            val request = mutex.withLock {
                var state = store.read()
                val now = System.currentTimeMillis()
                val profile = state.activeProfile
                if (profile == null) {
                    if (OptimizeCards.offerFirst(state, after, now)) {
                        state = state.copy(
                            card = OptimizeCard(OptimizeCard.FIRST, now, gameName(appId), cls = after.cls, metrics = after),
                            suppression = null,
                        )
                        store.write(state)
                    }
                } else {
                    val ladder = state.ladder(profile)
                    val pending = ladder.pending
                    if (pending != null && pending.revert == null && sessionRung == pending.rung.rungId) {
                        state = when (val decision = OptimizeJudge.judge(after, pending.baseline, pending.rung.isFpsCap, pending.shortSessions)) {
                            is OptimizeJudge.Decision.Short ->
                                state.withLadder(profile, ladder.copy(pending = pending.copy(shortSessions = decision.shortSessions)))
                            is OptimizeJudge.Decision.Crash -> finish(appId, state, profile, ladder, pending, OptimizeJudge.CRASH, after, refresh, cap)
                            is OptimizeJudge.Decision.Final -> finish(appId, state, profile, ladder, pending, decision.result, after, refresh, cap)
                        }
                        store.write(state)
                    }
                }
                state.card?.let { CardRequest(appId, it, state.entitlement ?: lastEntitlement) }
            }
            request?.let { cardRequest.value = it }
            revision.value++
            if (context != null) scope.launch { flush(context.applicationContext, store) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Optimize judging failed")
        }
    }

    private fun finish(
        appId: String,
        state: OptimizeLocalState,
        profile: OptimizeProfile,
        ladder: ProfileLadder,
        pending: PendingRung,
        result: String,
        after: Metrics,
        refreshHz: Double?,
        fpsCap: Int,
    ): OptimizeLocalState {
        val rung = pending.rung
        val now = System.currentTimeMillis()
        val report = outcomeBody(
            appId, profile, rung.rungId, rung.version, pending.baseline, after, result, pending.levers.size, emptyList(),
            crash = after.takeIf { result == OptimizeJudge.CRASH },
        )
        val keep = result == OptimizeJudge.HELPED || result == OptimizeJudge.NOT_COMPARABLE
        var l = ladder.triedWith(rung.rungId, rung.version, result)
        l = if (keep) {
            l.copy(kept = l.kept + pending.toKept(after), pending = null, baselineOverride = null)
        } else {
            l.copy(
                pending = pending.copy(revert = result),
                bad = if (result == OptimizeJudge.CRASH) (l.bad + rung.rungId).distinct() else l.bad,
            )
        }
        l = l.copy(noChangeStreak = if (result == OptimizeJudge.NO_CHANGE) l.noChangeStreak + 1 else 0)
        val comparable = result == OptimizeJudge.HELPED || result == OptimizeJudge.NO_CHANGE
        val name = gameName(appId)
        val card = when {
            comparable && OptimizeTarget.met(profile, after, refreshHz, fpsCap) -> {
                l = l.copy(state = LadderState.DONE)
                OptimizeCard(OptimizeCard.DONE, now, name, profile, rungId = rung.rungId)
            }
            result == OptimizeJudge.HELPED -> OptimizeCard(
                OptimizeCard.HELPED, now, name, profile,
                rungId = rung.rungId,
                outcome = OptimizeCards.helpedOutcome(rung.outcome, profile),
                fpsGainPct = OptimizeCards.fpsGainPct(pending.baseline, after),
                stutterDropPct = OptimizeCards.stutterDropPct(pending.baseline, after),
            )
            result == OptimizeJudge.NO_CHANGE -> {
                if (l.noChangeStreak >= MAX_NO_CHANGE_STREAK) l = l.copy(state = LadderState.PAUSED)
                OptimizeCard(OptimizeCard.NO_CHANGE, now, name, profile, rungId = rung.rungId)
            }
            result == OptimizeJudge.WORSE || result == OptimizeJudge.CRASH ->
                OptimizeCard(OptimizeCard.WORSE, now, name, profile, rungId = rung.rungId)
            else -> null
        }
        val s = state.queued(report).withLadder(profile, l)
        return if (card != null) s.copy(card = card) else s
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

    /** Publishes a card the game still has waiting, for when its screen is shown again. */
    suspend fun surfaceCard(context: Context, appId: String) {
        if (cardRequest.value?.appId == appId) return
        val state = readState(context, appId) ?: return
        val card = state.card ?: return
        cardRequest.value = CardRequest(appId, card, state.entitlement ?: lastEntitlement)
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
            val ok = mutex.withLock {
                val (next, worked) = block(container, store, store.read())
                store.write(next)
                worked
            }
            revision.value++
            scope.launch { flush(context.applicationContext, store) }
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
            s = s.withLadder(target, if (ladder.state == LadderState.IDLE) ladder.copy(state = LadderState.TRYING) else ladder)
        }
        return s to true
    }

    /**
     * Switches the active profile: the current profile's changes come off and the new one's kept changes go back on.
     * No profile's ladder is reset. [OptimizeProfile.OFF] takes everything off and keeps every ladder.
     */
    suspend fun setProfile(context: Context, appId: String, profile: OptimizeProfile): ChangeResult =
        change(context, appId, touchesFiles = true, what = "changing the profile") { container, store, state ->
            switchTo(context, appId, container, store, state, profile.takeIf { it != OptimizeProfile.OFF })
        }

    /** Takes the active profile's changes off and forgets what it kept; what it tried stays, and the profile stays active. */
    private suspend fun restore(
        context: Context,
        appId: String,
        container: Container,
        store: OptimizeStore,
        state: OptimizeLocalState,
        nextState: LadderState,
    ): Pair<OptimizeLocalState, Boolean> {
        val profile = state.activeProfile ?: return (state to true)
        var s = state
        var ladder = s.ladder(profile)
        var ok = true
        ladder.pending?.let { pending ->
            if (OptimizeReverter.revertLevers(context, container, pending.levers)) {
                store.dropRungDir(profile, pending.rung.rungId)
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
            ladder = ladder.copy(kept = ladder.kept - kept)
            s = s.queued(undoReport(appId, profile, kept.rungId, kept.version, kept.baseline, kept.levers.size))
        }
        if (ok) ladder = ladder.copy(state = nextState, noChangeStreak = 0, baselineOverride = null)
        return s.withLadder(profile, ladder) to ok
    }

    /** "Restore original settings". */
    suspend fun restoreOriginal(context: Context, appId: String): ChangeResult =
        change(context, appId, touchesFiles = true, what = "restoring original settings") { container, store, state ->
            restore(context, appId, container, store, state, LadderState.PAUSED)
        }

    /** Resumes a paused ladder: the next launch asks for the next step again. */
    suspend fun continueLadder(context: Context, appId: String): ChangeResult =
        change(context, appId, touchesFiles = false, what = "continuing") { _, _, state ->
            val profile = state.activeProfile ?: return@change (state to true)
            state.withLadder(profile, state.ladder(profile).copy(state = LadderState.TRYING, noChangeStreak = 0)) to true
        }

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

    /** [profile] is the profile chosen with Optimize. */
    private suspend fun answerCard(context: Context, appId: String, card: OptimizeCard, action: String, profile: OptimizeProfile?): ChangeResult {
        val touchesFiles = action == OptimizeCards.ACTION_OPTIMIZE || action == OptimizeCards.ACTION_UNDO || action == OptimizeCards.ACTION_UNDO_ALL
        return change(context, appId, touchesFiles, what = "answering a card") { container, store, current ->
            val now = System.currentTimeMillis()
            val state = current.copy(card = null, cardAction = action)
            val active = state.activeProfile
            fun setLadder(next: LadderState, only: Set<LadderState>? = null): OptimizeLocalState {
                if (active == null) return state
                val ladder = state.ladder(active)
                if (only != null && ladder.state !in only) return state
                return state.withLadder(active, ladder.copy(state = next, noChangeStreak = 0))
            }
            when (action) {
                OptimizeCards.ACTION_OPTIMIZE ->
                    switchTo(context, appId, container, store, state.copy(suppression = null), profile ?: OptimizeProfile.BALANCED)
                OptimizeCards.ACTION_NOT_NOW -> state.copy(suppression = OptimizeCards.notNow(card, now)) to true
                OptimizeCards.ACTION_NEVER -> state.copy(never = true) to true
                OptimizeCards.ACTION_KEEP_GOING -> setLadder(LadderState.TRYING) to true
                OptimizeCards.ACTION_HAPPY -> setLadder(LadderState.DONE) to true
                OptimizeCards.ACTION_STOP -> setLadder(LadderState.PAUSED) to true
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
