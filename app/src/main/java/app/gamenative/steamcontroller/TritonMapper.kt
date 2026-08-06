package app.gamenative.steamcontroller

import android.content.Context
import android.os.Handler
import android.util.Log
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.XServer

/**
 * Drives a Steam Controller (2026 "Triton") into GameNative over **BLE** (no dongle, no root). [TritonBle]
 * connects the GATT service, un-lizards, and pushes decoded [TritonState]s through a [ProfileInterpreter] (which
 * applies the active [ScProfile] and feeds GameNative's injection seams: virtual XInput pad via WinHandler +
 * mouse/keys via XServer), and regenerates the trackpad haptics (TritonHaptics) since lizard-off disables the
 * firmware's.
 *
 * When a per-game [config] is supplied (from [ScConfigStore], keyed by container/game), the interpreter loads
 * it and runs config-driven action-set switching / layers / mode-shift live in the game. With no config it
 * falls back to the hardcoded [ScProfile.default]; swap [ProfileInterpreter.profile] to change bindings.
 */
class TritonMapper(
    private val context: Context,
    private val xServer: XServer,
    private val config: ScConfig? = null,
    /** Step-6 menu HUD; defaults to no-op so the driver runs without an attached overlay view. */
    private val menuOverlay: ScMenuOverlay = NoOpScMenuOverlay,
    /** Split-trackpad keyboard HUD; defaults to no-op. */
    private val keyboardOverlay: ScKeyboardOverlay = NoOpScKeyboardOverlay,
    /** [ScConfigStore] key (container/appId) this session was launched for. Enables live [reload] after an
     *  in-game edit re-resolves the config; null = use the passed [config] / built-in default with no reload. */
    private val configKey: String? = null,
    /** App-UI seam so the controller can open + navigate the QuickMenu / in-game editors. No-op by default. */
    private val uiBridge: ScUiBridge = NoOpScUiBridge,
) {
    companion object {
        private const val TAG = "TritonMapper"
        private const val MAX_BLE_RETRIES = 4
        private const val BLE_RETRY_MS = 1500L
        // The controller stops rumbling ~50ms after the last report (firmware safety), so refresh a held
        // rumble faster than that — matches SDL's TRITON_RUMBLE_RESEND_INTERVAL_MS.
        private const val RUMBLE_RESEND_MS = 40L
        /** Gamepad slot the SC's virtual XInput pad occupies (see [ScOutputSink]) — the only slot we own rumble for. */
        const val SC_GAMEPAD_SLOT = 0
    }

    // ble is read from the BLE binder thread (via the haptics writeOut lambda) but written on the main thread;
    // interpreter/bleRetries are touched from both BLE callbacks and the main handler — mark volatile for visibility.
    @Volatile private var ble: TritonBle? = null
    private var haptics: TritonHaptics? = null
    @Volatile private var interpreter: ProfileInterpreter? = null
    @Volatile private var running = false

    private val bleHandler = Handler(context.mainLooper)
    @Volatile private var bleRetries = 0

    // Game rumble forwarded from WinHandler's poller (another thread) → the controller's motors. Held on the
    // main looper alongside every other BLE write. The resend loop refreshes a non-zero rumble before the
    // firmware's ~50ms cutoff; it stops itself once rumble returns to zero.
    @Volatile private var rumbleLow = 0
    @Volatile private var rumbleHigh = 0
    private val rumbleResend = object : Runnable {
        override fun run() {
            if (!running || (rumbleLow == 0 && rumbleHigh == 0)) return
            haptics?.rumble(rumbleLow, rumbleHigh)
            bleHandler.postDelayed(this, RUMBLE_RESEND_MS)
        }
    }

    /** Claim/release the game's rumble output for [SC_GAMEPAD_SLOT] — the slot [ScOutputSink] feeds. */
    private fun setRumbleForwarder(claim: Boolean) {
        if (claim) {
            // Forward game rumble (poller thread) onto the main looper so motor writes serialize with the rest.
            WinHandler.setScRumbleForwarder(SC_GAMEPAD_SLOT) { low, high ->
                bleHandler.post { onGameRumble(low.toInt() and 0xFFFF, high.toInt() and 0xFFFF) }
            }
        } else {
            WinHandler.setScRumbleForwarder(SC_GAMEPAD_SLOT, null)
            rumbleLow = 0; rumbleHigh = 0
        }
    }

    private fun onGameRumble(low: Int, high: Int) {
        val wasActive = rumbleLow != 0 || rumbleHigh != 0
        rumbleLow = low; rumbleHigh = high
        haptics?.rumble(low, high)
        if ((low != 0 || high != 0) && !wasActive) bleHandler.postDelayed(rumbleResend, RUMBLE_RESEND_MS)
    }

    /** True once the BLE transport is live (onReady). The in-game UI gates the rich Steam-Controller editing
     *  section on this — GameNative's generic controller detector can't see the BLE Triton. */
    @Volatile var transportReady = false
        private set

    /** Start the BLE transport (the only transport). Feeds the [ProfileInterpreter] so action sets / layers /
     *  mode-shift / overlays / keyboard run in-game. */
    fun start() {
        startBle()
    }

    /**
     * BLE transport: [TritonBle] connects/un-lizards/decodes and pushes [TritonState]s via [onState] (it runs
     * its own lizard heartbeat, so no loop thread here). Haptics route back out over BLE via
     * [TritonBle.writeOutputReport]. BLE direct-connects are flaky (a first attempt can time out with no GATT
     * link), and the link can drop mid-game, so [onError] auto-retries with a fresh [TritonBle] — a successful
     * [onReady] resets the budget.
     */
    private fun startBle() {
        // Route haptics to whichever TritonBle is current (survives reconnects, which swap the [ble] instance).
        val h = TritonHaptics { report -> ble?.writeOutputReport(report) }
        haptics = h
        val interp = buildInterpreter(h)
        interpreter = interp
        running = true
        bleRetries = 0
        connectBle(interp)
    }

    private fun connectBle(interp: ProfileInterpreter) {
        val b = TritonBle(context)
        ble = b
        b.start(
            onState = { state -> if (running) interp.apply(state) },
            onReady = {
                bleRetries = 0; transportReady = true
                // Only claim the game's rumble output once a controller is REALLY there. Claiming it up-front (at
                // start) would silently kill rumble for everyone whose transport never connects, since the tap
                // makes WinHandler skip the device/phone path. Released again on any error below.
                setRumbleForwarder(true)
                Log.i(TAG, "started (BLE) — transport live")
            },
            onError = { reason ->
                Log.w(TAG, "BLE transport: $reason")
                transportReady = false
                setRumbleForwarder(false)
                runCatching { b.close() }
                if (ble === b) ble = null
                if (running && bleRetries < MAX_BLE_RETRIES) {
                    bleRetries++
                    Log.i(TAG, "BLE reconnect $bleRetries/$MAX_BLE_RETRIES in ${BLE_RETRY_MS}ms")
                    bleHandler.postDelayed({ if (running && ble == null) connectBle(interp) }, BLE_RETRY_MS)
                } else if (running) {
                    Log.w(TAG, "BLE giving up after $MAX_BLE_RETRIES retries")
                }
            },
        )
    }

    /** Build the interpreter for the active [config] (or [ScProfile.default]). */
    private fun buildInterpreter(haptics: TritonHaptics?): ProfileInterpreter {
        val cfg = config
        return ProfileInterpreter(
            XServerOutputSink(xServer),
            cfg?.defaultProfile() ?: ScProfile.default(),
            haptics,
            menuOverlay = menuOverlay,
            keyboardOverlay = keyboardOverlay,
            padDeadzone = ScTuningStore.deadzone(context),
            padSmoothing = ScTuningStore.smoothing(context),
            uiBridge = uiBridge,
        ).also {
            if (cfg != null) {
                it.setConfig(cfg)
                Log.i(TAG, "loaded per-game ScConfig: sets=${cfg.sets.keys} default=${cfg.defaultSetId}")
            } else {
                Log.i(TAG, "no per-game config; using ScProfile.default()")
            }
        }
    }

    /**
     * Re-resolve this session's config + tuning from the stores and push them into the running interpreter — so
     * an in-game edit (bindings / labels / menu commit / deadzone / smoothing) applies LIVE with no relaunch.
     * Safe to call from any thread (interpreter mutators are @Volatile / atomic field swaps).
     */
    fun reload() {
        val interp = interpreter ?: return
        val cfg = configKey?.let { ScConfigStore.forKey(context, it) }
        if (cfg != null) {
            interp.setConfig(cfg)  // recompute() inside sets the active profile
        } else {
            interp.setConfig(null)
            interp.profile = ScProfile.default()
        }
        interp.setPadTuning(ScTuningStore.deadzone(context), ScTuningStore.smoothing(context))
        (menuOverlay as? ScMenuOverlayView)?.refreshLayouts() // pick up in-game per-menu overlay placement edits
        Log.i(TAG, "reloaded (key=$configKey, cfg=${cfg != null}, sets=${cfg?.sets?.keys})")
    }

    fun stop() {
        transportReady = false
        running = false
        setRumbleForwarder(false)
        bleHandler.removeCallbacksAndMessages(null)
        ble?.close(); ble = null
        haptics = null
        interpreter = null
        Log.i(TAG, "stopped")
    }
}
