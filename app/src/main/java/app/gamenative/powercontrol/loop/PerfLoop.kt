package app.gamenative.powercontrol.loop

import android.content.Context
import app.gamenative.powercontrol.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

object PerfLoop {

    private const val TAG = "PerfLoop"
    private const val DEFAULT_INTERVAL_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var job: Job? = null
    private var appContext: Context? = null

    fun isRunning(): Boolean = job?.isActive == true

    @Synchronized
    fun start(context: Context, preset: PerfPreset = PerfPreset.BALANCED, policy: Policy = HoldTopPolicy()) {
        if (isRunning()) {
            Timber.tag(TAG).d("Already running, ignoring start")
            return
        }

        val ctx = context.applicationContext
        appContext = ctx

        val params = DeviceParamsLoader.load(ctx)
        val intervalMs = params?.reaction?.loopIntervalMs?.toLong() ?: DEFAULT_INTERVAL_MS

        Timber.tag(TAG).i("Starting perf loop preset=$preset interval=${intervalMs}ms policy=${policy::class.simpleName}")

        job = scope.launch {
            while (isActive) {
                try {
                    val gpuInfo = PowerManager.getGpuInfo()
                    val snapshot = Snapshot(
                        gpuBusyPercent = Sensors.readGpuBusyPercent(),
                        gpuClkHz = Sensors.readGpuClkHz(),
                        frameStats = Sensors.readFrameStats(ctx),
                        battTempC = Sensors.readBatteryTempC(params),
                        boardTempC = Sensors.readBoardTempC(params),
                        gpuNumLevels = gpuInfo?.numGpuPowerLevels ?: 0,
                    )
                    val decision = policy.decide(snapshot, params)
                    applyDecision(decision, snapshot.gpuNumLevels)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Perf loop iteration failed")
                }
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        val active = isRunning()
        job?.cancel()
        job = null
        if (!active) {
            Timber.tag(TAG).d("Not running, nothing to stop")
            return
        }
        Timber.tag(TAG).i("Stopping perf loop, restoring full clock range")
        restoreFullRange()
    }

    private fun applyDecision(decision: Decision, numGpuLevels: Int) {
        if (decision.isEmpty) return

        PowerManager.update {
            decision.cpuMinKhz?.let { minCpuValue(it) }
            decision.cpuMaxKhz?.let { maxCpuValue(it) }
            if (PowerManager.isGpuSupported() && numGpuLevels > 0 &&
                (decision.gpuMinLevel != null || decision.gpuMaxLevel != null)) {
                minGpuPowerLevel(0)
                decision.gpuMaxLevel?.let { maxGpuPowerLevel(it.coerceIn(0, numGpuLevels - 1)) }
                decision.gpuMinLevel?.let { minGpuPowerLevel(it.coerceIn(0, numGpuLevels - 1)) }
            }
        }

        for ((path, value) in decision.sysfs) {
            PowerManager.writeSysfs(path, value)
        }
    }

    private fun restoreFullRange() {
        try {
            val cpuFreqs = PowerManager.getAvailableCpuFrequencies()
            val numGpuLevels = PowerManager.getGpuInfo()?.numGpuPowerLevels ?: 0
            PowerManager.update {
                if (cpuFreqs.isNotEmpty()) {
                    minCpuValue(cpuFreqs.first())
                    maxCpuValue(cpuFreqs.last())
                }
                if (PowerManager.isGpuSupported() && numGpuLevels > 0) {
                    minGpuPowerLevel(0)
                    maxGpuPowerLevel(numGpuLevels - 1)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to restore full clock range")
        }
    }
}
