package app.gamenative.ui.component.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.powercontrol.LudashiPerformanceMode
import app.gamenative.powercontrol.autotuning.DeviceGate
import java.util.Locale

/**
 * LUDASHI's device-aware profile panel. It reports Android-provided telemetry and stores
 * a global auto-tuning strategy; it never writes undocumented kernel or thermal controls.
 */
@Composable
fun LudashiDeviceProfileCard(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var snapshot by remember(context) { mutableStateOf(readDeviceSnapshot(context)) }
    var selectedMode by rememberSaveable {
        mutableStateOf(PrefManager.ludashiPerformanceMode)
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "LUDASHI DEVICE PROFILE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { snapshot = readDeviceSnapshot(context) }) {
                    Text("Perbarui")
                }
            }

            Text(
                text = if (snapshot.isG99G100) {
                    "Helio G99 / G100 terdeteksi"
                } else {
                    "Perangkat Android terdeteksi"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            DeviceInfoRow("Model", snapshot.model)
            DeviceInfoRow("SoC", snapshot.soc)
            DeviceInfoRow("GPU", snapshot.gpu)
            DeviceInfoRow("CPU logical cores", snapshot.cpuCores.toString())
            DeviceInfoRow(
                "RAM tersedia / total",
                "${formatRam(snapshot.availableRamBytes)} / ${formatRam(snapshot.totalRamBytes)}",
            )
            DeviceInfoRow("Suhu baterai", snapshot.batteryTemperatureC?.let {
                String.format(Locale.US, "%.1f °C", it)
            } ?: "Tidak tersedia")
            DeviceInfoRow("Baterai", "${snapshot.batteryPercent?.let { "$it%" } ?: "—"} • ${if (snapshot.isCharging) "Mengisi daya" else "Tidak mengisi"}")
            DeviceInfoRow("Status termal Android", snapshot.thermalStatus)
            DeviceInfoRow("ABI", snapshot.abi)

            val thermalAdvice = when {
                snapshot.thermalLevel >= 3 || (snapshot.batteryTemperatureC ?: 0f) >= 43f ->
                    "Suhu tinggi: hentikan sesi sejenak dan biarkan perangkat mendingin."
                snapshot.thermalLevel >= 2 || (snapshot.batteryTemperatureC ?: 0f) >= 41f ->
                    "Suhu mulai meningkat: gunakan mode Hemat atau Seimbang dan pantau suhu."
                else -> "Status termal normal. Mulai pengujian dari 960 × 540 pada 30 FPS."
            }
            Text(
                text = "SARAN TERMAL",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(thermalAdvice, style = MaterialTheme.typography.bodySmall)

            Text(
                text = "MODE TUNING",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LudashiModeButton(
                    mode = LudashiPerformanceMode.POWER_SAVE,
                    selected = selectedMode == LudashiPerformanceMode.POWER_SAVE,
                    modifier = Modifier.weight(1f),
                ) {
                    selectedMode = it
                    PrefManager.ludashiPerformanceMode = it
                }
                LudashiModeButton(
                    mode = LudashiPerformanceMode.BALANCED,
                    selected = selectedMode == LudashiPerformanceMode.BALANCED,
                    modifier = Modifier.weight(1f),
                ) {
                    selectedMode = it
                    PrefManager.ludashiPerformanceMode = it
                }
                LudashiModeButton(
                    mode = LudashiPerformanceMode.PERFORMANCE,
                    selected = selectedMode == LudashiPerformanceMode.PERFORMANCE,
                    modifier = Modifier.weight(1f),
                ) {
                    selectedMode = it
                    PrefManager.ludashiPerformanceMode = it
                }
            }
            Text(
                text = selectedMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Saran awal: ${selectedMode.suggestedResolution} • ${selectedMode.suggestedFps} FPS. " +
                    "Resolusi dan batas FPS ini adalah rekomendasi untuk diatur pada konfigurasi game, bukan perubahan otomatis.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Mode ini mengubah strategi auto-tuning saat game berikutnya dimulai. " +
                    "Kontrol CPU/GPU hanya bekerja jika perangkat menyediakan driver yang kompatibel. " +
                    "Proteksi suhu Android tetap aktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LudashiModeButton(
    mode: LudashiPerformanceMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (LudashiPerformanceMode) -> Unit,
) {
    if (selected) {
        Button(
            onClick = { onSelected(mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 10.dp,
            ),
        ) {
            Text(mode.title, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(
            onClick = { onSelected(mode) },
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 10.dp,
            ),
        ) {
            Text(mode.title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private data class LudashiDeviceSnapshot(
    val model: String,
    val soc: String,
    val gpu: String,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val batteryTemperatureC: Float?,
    val thermalStatus: String,
    val thermalLevel: Int,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val abi: String,
    val isG99G100: Boolean,
)

@SuppressLint("UnspecifiedRegisterReceiverFlag")
private fun readDeviceSnapshot(context: Context): LudashiDeviceSnapshot {
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.orEmpty()
    } else {
        ""
    }
    val isG99G100 = DeviceGate.isMediaTekG99G100(
        socModel = socModel,
        hardware = Build.HARDWARE.orEmpty(),
        board = Build.BOARD.orEmpty(),
    )

    val memoryInfo = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.getMemoryInfo(memoryInfo)

    val batteryIntent = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    )
    val batteryTemperature = batteryIntent
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        ?.takeIf { it > 0 }
        ?.div(10f)
    val batteryLevel = batteryIntent?.let { intent ->
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    }
    val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
        batteryStatus == BatteryManager.BATTERY_STATUS_FULL

    var thermalLevel = 0
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val systemPowerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        thermalLevel = systemPowerManager?.currentThermalStatus ?: 0
        when (thermalLevel) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "Normal"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "Ringan"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "Sedang"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "Tinggi"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "Kritis"
            android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "Darurat"
            android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Tidak tersedia"
        }
    } else {
        "API thermal tidak tersedia"
    }

    val resolvedSoc = socModel.ifBlank {
        Build.HARDWARE.orEmpty().ifBlank { Build.BOARD.orEmpty() }
    }.ifBlank { "Tidak terdeteksi" }

    return LudashiDeviceSnapshot(
        model = Build.MODEL.orEmpty().ifBlank { "Android device" },
        soc = if (isG99G100) "MediaTek $resolvedSoc" else resolvedSoc,
        gpu = if (isG99G100) "Mali-G57 MC2 (keluarga SoC)" else "Belum diidentifikasi",
        cpuCores = Runtime.getRuntime().availableProcessors(),
        totalRamBytes = memoryInfo.totalMem,
        availableRamBytes = memoryInfo.availMem,
        batteryTemperatureC = batteryTemperature,
        thermalStatus = thermalStatus,
        thermalLevel = thermalLevel,
        batteryPercent = batteryLevel,
        isCharging = isCharging,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Tidak diketahui",
        isG99G100 = isG99G100,
    )
}

private fun formatRam(bytes: Long): String {
    if (bytes <= 0L) return "—"
    return String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
}
