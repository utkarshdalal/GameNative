package app.gamenative.powercontrol.metrics

import app.gamenative.powercontrol.metrics.KernelThermal.CoolingDevice
import app.gamenative.powercontrol.metrics.KernelThermal.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KernelThermalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = KernelThermal.resetCache()

    @Test
    fun classifiesRetroidPocket6CoolingDevices() {
        // Types as listed by /sys/class/thermal/cooling_device*/type on a Retroid Pocket 6 (Snapdragon 8 Gen 2).
        val frequency = listOf(
            "cpufreq-cpu0", "cpufreq-cpu3", "cpufreq-cpu7", "cpu-cluster0", "cpu-cluster1", "cpu-cluster2",
            "thermal-cluster-1-2", "gpu", "devfreq-3d00000.qcom,kgsl-3d0",
        )
        val offline = listOf("thermal-pause-8", "thermal-pause-F8", "pause-cpu4", "cpu-hotplug1")
        val ignored = listOf("panel0-backlight", "battery", "display-fps", "ufs", "ddr-cdev", "modem_lte_dsc")
        frequency.forEach { assertEquals(it, Kind.FREQUENCY, KernelThermal.kindOf(it)) }
        offline.forEach { assertEquals(it, Kind.CORE_OFFLINE, KernelThermal.kindOf(it)) }
        ignored.forEach { assertNull(it, KernelThermal.kindOf(it)) }
    }

    @Test
    fun noThrottlingIsNone() {
        val devices = listOf(CoolingDevice("cpufreq-cpu7", 0, 20), CoolingDevice("gpu", 0, 8), CoolingDevice("thermal-pause-8", 0, 1))
        assertEquals(KernelThermal.STATUS_NONE, KernelThermal.statusOf(devices))
    }

    @Test
    fun fullBrightnessBacklightIsNotThrottling() {
        // panel0-backlight reads 255/255 at full brightness; it must not count.
        val devices = listOf(CoolingDevice("panel0-backlight", 255, 255), CoolingDevice("cpufreq-cpu0", 0, 15))
        assertEquals(KernelThermal.STATUS_NONE, KernelThermal.statusOf(devices))
    }

    @Test
    fun frequencyCapsMapToLightModerateAndSevere() {
        fun status(cur: Int, max: Int) = KernelThermal.statusOf(listOf(CoolingDevice("cpufreq-cpu7", cur, max)))
        assertEquals(KernelThermal.STATUS_LIGHT, status(1, 20))
        assertEquals(KernelThermal.STATUS_LIGHT, status(3, 20))
        assertEquals(KernelThermal.STATUS_MODERATE, status(4, 20))
        assertEquals(KernelThermal.STATUS_MODERATE, status(9, 20))
        assertEquals(KernelThermal.STATUS_SEVERE, status(10, 20))
        assertEquals(KernelThermal.STATUS_SEVERE, status(20, 20))
    }

    @Test
    fun mostThrottledDeviceWins() {
        val devices = listOf(CoolingDevice("cpufreq-cpu0", 1, 15), CoolingDevice("gpu", 5, 8))
        assertEquals(KernelThermal.STATUS_SEVERE, KernelThermal.statusOf(devices))
    }

    @Test
    fun anyCoreTakenOfflineIsSevere() {
        val devices = listOf(CoolingDevice("cpufreq-cpu7", 0, 20), CoolingDevice("thermal-pause-80", 1, 1))
        assertEquals(KernelThermal.STATUS_SEVERE, KernelThermal.statusOf(devices))
    }

    @Test
    fun outOfRangeStateIsClamped() {
        assertEquals(KernelThermal.STATUS_SEVERE, KernelThermal.statusOf(listOf(CoolingDevice("gpu", 12, 8))))
        assertEquals(KernelThermal.STATUS_NONE, KernelThermal.statusOf(listOf(CoolingDevice("gpu", -1, 8))))
    }

    @Test
    fun noCpuOrGpuDeviceIsUnknownNotCool() {
        assertNull(KernelThermal.statusOf(emptyList()))
        assertNull(KernelThermal.statusOf(listOf(CoolingDevice("battery", 3, 13), CoolingDevice("ufs", 0, 2))))
        // A frequency device without a usable range says nothing either.
        assertNull(KernelThermal.statusOf(listOf(CoolingDevice("cpufreq-cpu0", 0, 0))))
    }

    @Test
    fun readsCoolingDevicesFromSysfs() {
        val root = tmp.newFolder("thermal")
        device(root, 0, "cpufreq-cpu0", cur = 0, max = 15)
        device(root, 2, "cpufreq-cpu7", cur = 6, max = 20)
        device(root, 34, "devfreq-3d00000.qcom,kgsl-3d0", cur = 0, max = 8)
        device(root, 35, "panel0-backlight", cur = 255, max = 255)
        File(root, "thermal_zone0").mkdirs()

        val devices = KernelThermal.readDevices(root)
        assertEquals(listOf("cpufreq-cpu0", "cpufreq-cpu7", "devfreq-3d00000.qcom,kgsl-3d0"), devices.map { it.type }.sorted())
        assertEquals(KernelThermal.STATUS_MODERATE, KernelThermal.read(root))
    }

    @Test
    fun rereadsCurrentStateButCachesTheDeviceList() {
        val root = tmp.newFolder("thermal")
        device(root, 2, "cpufreq-cpu7", cur = 0, max = 20)
        assertEquals(KernelThermal.STATUS_NONE, KernelThermal.read(root))

        File(root, "cooling_device2/cur_state").writeText("12\n")
        assertEquals(KernelThermal.STATUS_SEVERE, KernelThermal.read(root))
    }

    @Test
    fun unreadableStateIsSkipped() {
        val root = tmp.newFolder("thermal")
        device(root, 0, "cpufreq-cpu0", cur = 0, max = 15)
        val gpu = device(root, 36, "gpu", cur = 0, max = 8)
        KernelThermal.readDevices(root)
        assertTrue(File(gpu, "cur_state").delete())
        assertEquals(listOf("cpufreq-cpu0"), KernelThermal.readDevices(root).map { it.type })
    }

    @Test
    fun missingThermalClassIsUnknown() {
        assertNull(KernelThermal.read(File(tmp.root, "does-not-exist")))
    }

    private fun device(root: File, index: Int, type: String, cur: Int, max: Int): File =
        File(root, "cooling_device$index").apply {
            mkdirs()
            File(this, "type").writeText("$type\n")
            File(this, "cur_state").writeText("$cur\n")
            File(this, "max_state").writeText("$max\n")
        }
}
