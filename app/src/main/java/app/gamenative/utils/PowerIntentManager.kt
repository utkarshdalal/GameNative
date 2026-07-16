package app.gamenative.utils

import android.content.Intent
import app.gamenative.powercontrol.PowerManager
import org.json.JSONObject
import timber.log.Timber

/**
 * Debug-only hook for the OEM tuning harness: applies a power state through PServer so a
 * host-side sweep can characterize the device (perf/heat per GPU pwrlevel + CPU floor).
 *
 * NOT a product feature — gated to debuggable builds by the caller (MainActivity). The
 * only path to write kgsl pwrlevel / cpufreq on retail is PServer root, which is why this
 * goes through PowerManager rather than letting the harness write sysfs directly.
 *
 * Intent: action app.gamenative.SET_POWER, extra "power_state" = JSON, e.g.
 *   {"gpuMinLevel":8,"gpuMaxLevel":8,"governor":"performance",
 *    "cpuMinKhz":1344000,"cpuMaxKhz":2016000,
 *    "sysfs":{"/sys/devices/system/cpu/bus_dcvs/DDR/hw_min_freq":"2736000",
 *             "/sys/class/qcom-battery/usb_charge_now":"0"}}
 * Power-level semantics match PowerManager (UI-friendly: higher = faster). Omitted fields
 * are left untouched. cpuMin/Max are applied uniformly to all clusters for now; per-cluster
 * prime/mid floors are a pending PServerDriver extension (see harness PLAN.md).
 *
 * "sysfs" entries are raw root writes for knobs PowerManager has no typed API for yet —
 * DDR/L3/LLCC bus floors (bus_dcvs) and charge-bypass (usb_charge_now=0 discharges the
 * battery with USB data alive → true on-battery power measurement while adb-connected).
 * Paths are restricted to SYSFS_ALLOWED_PREFIXES; anything else is refused.
 */
object PowerIntentManager {

    const val ACTION_SET_POWER = "app.gamenative.SET_POWER"
    private const val EXTRA_POWER_STATE = "power_state"

    private val SYSFS_ALLOWED_PREFIXES = listOf(
        "/sys/devices/system/cpu/bus_dcvs/",
        "/sys/class/kgsl/kgsl-3d0/",
        "/sys/class/qcom-battery/usb_charge_now",
    )

    fun isSetPowerIntent(intent: Intent): Boolean = intent.action == ACTION_SET_POWER

    /** Returns a short result string for logging; applies immediately (no game launch). */
    fun handle(intent: Intent): String {
        val json = intent.getStringExtra(EXTRA_POWER_STATE)
            ?: return "SET_POWER: missing power_state extra"

        if (!PowerManager.isPServerAvailable()) {
            return "SET_POWER: PServer not available on this device"
        }

        return try {
            val o = JSONObject(json)
            val applied = mutableListOf<String>()
            PowerManager.update {
                if (o.has("governor")) {
                    governor(o.getString("governor")); applied += "gov=${o.getString("governor")}"
                }
                if (o.has("cpuMinKhz")) {
                    minCpuValue(o.getLong("cpuMinKhz")); applied += "cpuMin=${o.getLong("cpuMinKhz")}"
                }
                if (o.has("cpuMaxKhz")) {
                    maxCpuValue(o.getLong("cpuMaxKhz")); applied += "cpuMax=${o.getLong("cpuMaxKhz")}"
                }
                if (o.has("gpuMinLevel")) {
                    minGpuPowerLevel(o.getInt("gpuMinLevel")); applied += "gpuMin=${o.getInt("gpuMinLevel")}"
                }
                if (o.has("gpuMaxLevel")) {
                    maxGpuPowerLevel(o.getInt("gpuMaxLevel")); applied += "gpuMax=${o.getInt("gpuMaxLevel")}"
                }
            }
            o.optJSONObject("sysfs")?.let { sysfs ->
                for (path in sysfs.keys()) {
                    if (SYSFS_ALLOWED_PREFIXES.none { path.startsWith(it) }) {
                        applied += "REFUSED:$path"
                        continue
                    }
                    val ok = PowerManager.writeSysfs(path, sysfs.getString(path))
                    applied += "${if (ok) "sysfs" else "FAILED"}:$path=${sysfs.getString(path)}"
                }
            }
            val msg = "SET_POWER applied: ${applied.joinToString(" ")}"
            Timber.tag("PowerIntentManager").i(msg)
            msg
        } catch (e: Exception) {
            val msg = "SET_POWER failed to parse/apply: ${e.message}"
            Timber.tag("PowerIntentManager").e(e, msg)
            msg
        }
    }
}
