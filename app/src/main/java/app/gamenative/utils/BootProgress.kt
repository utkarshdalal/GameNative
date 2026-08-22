package app.gamenative.utils

import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import com.winlator.core.TarCompressorUtils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Weighted progress model behind the container boot splash.
 *
 * The splash used to be a single indeterminate bar with a handful of static labels, so a
 * multi-minute first boot was indistinguishable from a stalled one. Each phase here reports a
 * real fraction when the underlying work exposes one (download bytes, archive bytes) and a
 * creeping estimate plus elapsed time when it does not (wine msiexec has no progress output).
 *
 * Phase weights are estimates, not measurements: which phases run at all depends on the
 * container (first boot, changed variant, cached driver), so the bar moves unevenly by design.
 *
 * Nothing is emitted unless [start] has been called, so extractions and downloads that happen
 * outside a boot (library installs) can share the same hooks without popping the splash up.
 *
 * All of this sits behind PrefManager.verboseBootProgress and is off by default. With the setting
 * off, only the phases that reported something before this existed emit, with their original text
 * and an indeterminate bar, so the splash behaves exactly as it used to.
 */
object BootProgress {

    /** [legacy] is what the phase put on the splash before this existed, null if it said nothing. */
    enum class Phase(val label: String, val weight: Float, val legacy: String? = null) {
        PREPARING("Preparing container", 0.05f),
        WINE_FILES("Setting up Wine files", 0.25f),
        GRAPHICS("Setting up graphics driver", 0.18f),
        ENVIRONMENT("Starting Wine environment", 0.07f),
        MONO("Installing Mono", 0.18f, "Installing Mono..."),
        DRM("Handling DRM", 0.12f, "Handling DRM..."),
        PREREQS("Installing prerequisites", 0.10f, "Installing prerequisites..."),
        LAUNCH("Launching game", 0.05f, "Launching game..."),
    }

    /** Creep ceiling inside a phase with no measurable fraction. Never reaches the next phase. */
    private const val CREEP_CEILING = 0.85f

    /** How far creep may run past the last measured fraction, to cover gaps between reports. */
    private const val CREEP_OVERSHOOT = 0.15f
    private const val CREEP_RATE = 0.04f
    private const val TICK_MS = 500L

    /** Elapsed time is noise on a fast step; it only helps once a step visibly drags. */
    private const val ELAPSED_AFTER_MS = 5000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    @Volatile private var active = false

    @Volatile private var verbose = false
    private var phase: Phase = Phase.PREPARING
    private var base = 0f
    private var local = 0f
    private var measured = false
    private var measuredFloor = 0f
    private var detail: String? = null
    private var phaseStartedAt = 0L
    private var watchedDir: File? = null
    private var watchedBytes = 0L
    private var segmentKey: String? = null
    private var segmentStart = 0f
    private var segmentSpan = 0f
    private var ticks = 0

    private var lastText = ""
    private var lastProgress = 0f

    /** Marks the beginning of a boot. Safe to call more than once. */
    @Synchronized
    fun start() {
        verbose = PrefManager.verboseBootProgress
        active = true
        lastText = ""
        ticker?.cancel()
        ticker = null
        if (!verbose) return
        TarCompressorUtils.extractProgressListener = TarCompressorUtils.ExtractProgressListener(::extracting)
        base = 0f
        local = 0f
        measured = false
        measuredFloor = 0f
        detail = null
        watchedDir = null
        watchedBytes = 0L
        segmentKey = null
        lastProgress = 0f
        phase = Phase.PREPARING
        phaseStartedAt = System.currentTimeMillis()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
        emit()
    }

    /** Boot is over (game window is up, or the splash was dismissed). Stops all emission. */
    @Synchronized
    fun stop() {
        active = false
        ticker?.cancel()
        ticker = null
        watchedDir = null
        TarCompressorUtils.extractProgressListener = null
    }

    /**
     * Enters [next]. Phases may be skipped entirely, so the base only ever moves forward.
     */
    @Synchronized
    fun phase(next: Phase, detail: String? = null) {
        if (!verbose) {
            next.legacy?.let { emitLegacy(it) }
            return
        }
        if (!active) return
        phase = next
        base = maxOf(base, Phase.entries.takeWhile { it != next }.sumOf { it.weight.toDouble() }.toFloat())
        local = 0f
        measured = false
        measuredFloor = 0f
        this.detail = detail
        watchedDir = null
        watchedBytes = 0L
        segmentKey = null
        phaseStartedAt = System.currentTimeMillis()
        emit()
    }

    /**
     * Reports a real fraction (0..1) inside the current phase. [legacy] is what this call site
     * used to put on the splash, emitted verbatim while detailed progress is off.
     */
    @Synchronized
    fun update(fraction: Float, detail: String? = null, legacy: String? = null) {
        if (!verbose) {
            legacy?.let { emitLegacy(it) }
            return
        }
        if (!active) return
        measured = true
        measuredFloor = fraction.coerceIn(0f, 1f)
        local = maxOf(local, measuredFloor)
        if (detail != null) this.detail = detail
        emit()
    }

    /** Replaces the sub-label without touching the fraction. */
    @Synchronized
    fun detail(text: String?, legacy: String? = null) {
        if (!verbose) {
            legacy?.let { emitLegacy(it) }
            return
        }
        if (!active) return
        detail = text
        emit()
    }

    /**
     * Watches a directory the current step writes into, so an opaque step (the Mono MSI) at
     * least reports how much it has produced so far.
     */
    @Synchronized
    fun watchOutput(dir: File?) {
        if (!active || !verbose) return
        watchedDir = dir
        watchedBytes = 0L
    }

    /**
     * Maps one item's own fraction into the room left in the phase. A phase can hold any number
     * of archives and downloads and none of them knows how many follow, so each takes half of
     * what is left: the bar keeps advancing and never claims the phase is done early.
     */
    @Synchronized
    private fun updateSegment(key: String, fraction: Float, detail: String) {
        if (!active || !verbose) return
        if (key != segmentKey) {
            segmentKey = key
            segmentStart = local
            segmentSpan = (1f - segmentStart) * 0.5f
        }
        update(segmentStart + segmentSpan * fraction.coerceIn(0f, 1f), detail)
    }

    /** Download progress hook for the component downloaders. */
    fun download(what: String, fraction: Float) {
        Timber.d("Downloading %s: %d%%", what, (fraction * 100).toInt())
        updateSegment(what, fraction, "downloading $what ${(fraction * 100).toInt()}%")
    }

    /**
     * Archive extraction hook, called from `TarCompressorUtils` for every archive in the app.
     * [totalBytes] is -1 when the source size is unknown (streams, compressed assets).
     */
    fun extracting(sourceName: String?, bytesRead: Long, totalBytes: Long) {
        if (!active || !verbose) return
        val name = sourceName?.substringAfterLast('/')?.substringBefore(".tzst")?.substringBefore(".tar")
        if (totalBytes > 0 && name != null) {
            updateSegment(name, bytesRead.toFloat() / totalBytes, "unpacking $name")
        } else if (name != null) {
            detail("unpacking $name")
        }
    }

    private fun tick() {
        val dir = synchronized(this) {
            if (!active) return
            ticks++
            watchedDir?.takeIf { ticks % 4 == 0 }
        }
        val bytes = dir?.let { runCatching { dirSize(it) }.getOrNull() }
        synchronized(this) {
            if (!active) return
            if (bytes != null && watchedDir === dir) watchedBytes = bytes
            val ceiling = if (measured) minOf(measuredFloor + CREEP_OVERSHOOT, 1f) else CREEP_CEILING
            if (local < ceiling) local += (ceiling - local) * CREEP_RATE
            emit()
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().maxDepth(6).filter { it.isFile }.sumOf { it.length() }

    /** Pre-existing behaviour: the original label, indeterminate bar, no sub-detail. */
    private fun emitLegacy(text: String) {
        if (text == lastText) return
        lastText = text
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text))
    }

    private fun emit() {
        if (!active) return
        val progress = maxOf(lastProgress, (base + phase.weight * local).coerceIn(0f, 0.99f))
        val text = buildString {
            append(phase.label)
            val parts = mutableListOf<String>()
            detail?.let { parts.add(it) }
            if (watchedBytes > 0) parts.add("${watchedBytes / (1024 * 1024)} MB")
            val elapsed = System.currentTimeMillis() - phaseStartedAt
            if (elapsed >= ELAPSED_AFTER_MS) {
                val seconds = elapsed / 1000
                parts.add("%d:%02d".format(seconds / 60, seconds % 60))
            }
            if (parts.isNotEmpty()) parts.joinTo(this, prefix = " (", postfix = ")")
        }
        // The extraction hook fires per tar entry; without this the splash would churn per file.
        if (text == lastText && progress - lastProgress < 0.005f) return
        lastText = text
        lastProgress = progress
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text, progress))
    }
}
