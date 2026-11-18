package app.gamenative.optimize

import app.gamenative.ui.screen.support.WineRegistryText
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizeKeepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var roots: PathRoots
    private lateinit var ini: File

    private val original = "[SystemSettings]\r\nr.ShadowQuality=3\r\nr.Bar=1\r\n\r\n[Other]\r\nKeep=yes\r\n"

    @Before
    fun setUp() {
        home = tmp.newFolder("home")
        roots = PathRoots(null, null, home)
        ini = File(home, "Saved/Config/Windows/Engine.ini").apply {
            parentFile!!.mkdirs()
            writeText(original)
        }
    }

    private fun set(section: String, key: String, value: String) = FileOp("set", section, key, value)

    private fun lever(readOnly: Boolean) = Lever.EngineFile(
        "{userProfilePath}/Saved/Config/Windows/Engine.ini", "ini", "default", false,
        listOf(set("SystemSettings", "r.ShadowQuality", "1"), set("SystemSettings", "r.Foo", "2"), FileOp("delete", "SystemSettings", "r.Bar", null)),
        readOnly = readOnly,
    )

    private fun apply(readOnly: Boolean): JSONObject =
        OptimizeApplier.applyFile(0, lever(readOnly), roots, tmp.newFolder("rung"), false, OptimizeApplier.OpCount())!!

    private fun rung(): Rung = Rung.parse(JSONObject("{\"rung_id\":\"r1\",\"version\":\"v1\",\"levers\":[]}"))!!

    @Test
    fun rulesVersionIsReadFromTheResponseOrTheRung() {
        val top = NextRungResponse.parse(JSONObject("{\"status\":\"rung\",\"rules_version\":\"2026-11-08a\",\"rung\":{\"rung_id\":\"r\",\"rules_version\":\"old\"}}"))
        assertEquals("2026-11-08a", top.effectiveRulesVersion)
        val onRung = NextRungResponse.parse(JSONObject("{\"status\":\"rung\",\"rung\":{\"rung_id\":\"r\",\"rules_version\":\"b7\"}}"))
        assertEquals("b7", onRung.effectiveRulesVersion)
        assertNull(NextRungResponse.parse(JSONObject("{\"status\":\"exhausted\"}")).effectiveRulesVersion)
    }

    @Test
    fun aPrefetchedRungFromOtherRulesIsNotUsed() {
        val now = 1_800_000_000_000L
        val key = PrefetchKey("C:\\Game\\Game.exe", "dxvk", 60)
        val ladder = ProfileLadder(state = LadderState.TRYING)
        val prefetched = PrefetchedRung(rung(), now, OptimizeProfile.BALANCED, key, emptyList(), "rules-1")
        val state = OptimizeLocalState(activeProfile = OptimizeProfile.BALANCED, prefetched = prefetched, lastRulesVersion = "rules-2")
        val back = OptimizeLocalState.fromJson(JSONObject(state.toJson().toString()))
        assertEquals("rules-2", back.lastRulesVersion)
        assertEquals("rules-1", back.prefetched!!.rulesVersion)

        assertFalse(back.prefetched!!.isUsable(now, OptimizeProfile.BALANCED, key, ladder, back.lastRulesVersion))
        assertTrue(back.prefetched!!.isUsable(now, OptimizeProfile.BALANCED, key, ladder, "rules-1"))
        assertTrue(back.prefetched!!.isUsable(now, OptimizeProfile.BALANCED, key, ladder, null))
        assertFalse(prefetched.copy(rulesVersion = null).isUsable(now, OptimizeProfile.BALANCED, key, ladder, "rules-2"))
        // The Odin 3 case: a rung fetched before the server said a rules build, used before any answer said one.
        assertFalse(prefetched.copy(rulesVersion = null).isUsable(now, OptimizeProfile.BALANCED, key, ladder, null))
        assertTrue(prefetched.copy(rulesVersion = null).staleRules(null))
        assertFalse(prefetched.staleRules(null))
        assertNull(OptimizeLocalState.fromJson(JSONObject(OptimizeLocalState().toJson().toString())).lastRulesVersion)
    }

    @Test
    fun aPrefetchedCapIsOnlyUsedWhenAskedFor() {
        val now = 1_800_000_000_000L
        val key = PrefetchKey("C:\\Game\\Game.exe", "dxvk", 60)
        val ladder = ProfileLadder(state = LadderState.TRYING)
        val cap = Rung.parse(JSONObject("{\"rung_id\":\"cap45\",\"version\":\"v1\",\"is_fps_cap\":true,\"levers\":[]}"))!!
        val prefetched = PrefetchedRung(cap, now, OptimizeProfile.PERFORMANCE, key, emptyList(), "rules-1")
        assertFalse(prefetched.isUsable(now, OptimizeProfile.PERFORMANCE, key, ladder, "rules-1"))
        val asked = PrefetchedRung.fromJson(JSONObject(prefetched.copy(capAsked = true).toJson().toString()))!!
        assertTrue(asked.capAsked)
        assertTrue(asked.isUsable(now, OptimizeProfile.PERFORMANCE, key, ladder, "rules-1"))
    }

    @Test
    fun keptResetCountAndUnstableRoundTrip() {
        val kept = KeptRung("k", "1", 1, emptyList(), 0L, emptyList(), null, resetCount = 2, unstable = true)
        val back = KeptRung.fromJson(JSONObject(kept.toJson().toString()))!!
        assertEquals(2, back.resetCount)
        assertTrue(back.unstable)
        val plain = KeptRung.fromJson(JSONObject(kept.copy(resetCount = 0, unstable = false).toJson().toString()))!!
        assertEquals(0, plain.resetCount)
        assertFalse(plain.unstable)
    }

    @Test
    fun readOnlyPayloadParses() {
        val raw = JSONObject("{\"lever_type\":\"engine_file\",\"payload\":{\"path\":\"{gameInstallPath}/a.ini\",\"format\":\"ini\",\"read_only\":true,\"ops\":[{\"op\":\"set\",\"section\":\"S\",\"key\":\"k\",\"value\":\"1\"}]}}")
        assertTrue((Lever.parse(raw) as Lever.EngineFile).readOnly)
        raw.getJSONObject("payload").remove("read_only")
        assertFalse((Lever.parse(raw) as Lever.EngineFile).readOnly)
    }

    @Test
    fun readOnlyLeverClearsWriteBitsAndRevertRestoresThem() {
        val record = apply(readOnly = true)
        assertFalse(ini.canWrite())
        assertTrue(record.getBoolean("read_only"))
        assertTrue(record.getBoolean("was_writable"))
        assertTrue(ini.readText().contains("r.ShadowQuality=1"))

        OptimizeReverter.revertFile(record)
        assertTrue(ini.canWrite())
        assertEquals(original, ini.readText())
    }

    @Test
    fun aFileThatWasReadOnlyStaysReadOnlyAfterRevert() {
        ini.setWritable(false, false)
        val record = apply(readOnly = false)
        assertFalse(record.getBoolean("was_writable"))
        assertTrue(ini.readText().contains("r.Foo=2"))
        assertFalse(ini.canWrite())

        OptimizeReverter.revertFile(record)
        assertFalse(ini.canWrite())
        assertEquals(original, ini.readText())
    }

    @Test
    fun aWritableLeverLeavesTheFileWritable() {
        val record = apply(readOnly = false)
        assertTrue(ini.canWrite())
        assertTrue(record.getBoolean("was_writable"))
    }

    @Test
    fun iniDriftListsTheKeysTheGameReset() {
        val written = listOf(set("SystemSettings", "r.ShadowQuality", "1"), set("SystemSettings", "r.Foo", "2"), FileOp("delete", "SystemSettings", "r.Bar", null))
        val rewritten = "[SystemSettings]\r\nr.ShadowQuality=1\r\nr.Bar=1\r\n"
        assertEquals(listOf(written[1], written[2]), OptimizeDrift.fileDrift(IniFormat, rewritten, written))
        assertEquals(emptyList<FileOp>(), OptimizeDrift.fileDrift(IniFormat, "[SystemSettings]\nr.ShadowQuality=1\nr.Foo=2\n", written))
        assertEquals(listOf(written[0], written[1]), OptimizeDrift.fileDrift(IniFormat, "", written))
    }

    @Test
    fun writtenOpsComeFromTheRecordOrTheRungForOlderRecords() {
        val record = apply(readOnly = false)
        assertEquals(lever(false).ops, OptimizeDrift.writtenOps(record, null))
        val old = JSONObject(record.toString())
        old.getJSONArray("keys").let { keys -> (0 until keys.length()).forEach { keys.getJSONObject(it).remove("op") } }
        assertNull(OptimizeDrift.writtenOps(old, null))
        assertEquals(lever(false).ops, OptimizeDrift.writtenOps(old, lever(false)))
    }

    @Test
    fun reverifyPutsBackResetKeysAndKeepsTheBackup() {
        val record = apply(readOnly = true)
        assertEquals(0, OptimizeApplier.reverifyFile(record, null)!!.reset)

        ini.setWritable(true)
        ini.writeText(original)
        val checked = OptimizeApplier.reverifyFile(record, null)!!
        assertEquals(3, checked.reset)
        assertTrue(checked.readOnly)
        assertFalse(ini.canWrite())
        val text = ini.readText()
        assertTrue(text.contains("r.ShadowQuality=1") && text.contains("r.Foo=2") && !text.contains("r.Bar") && text.contains("Keep=yes"))
        assertEquals(record.getString("backup"), checked.record.getString("backup"))
        assertEquals(OptimizeFiles.sha256(ini.readBytes()), checked.record.getString("sha_after"))

        OptimizeReverter.revertFile(checked.record)
        assertEquals(original, ini.readText())
        assertTrue(ini.canWrite())
    }

    @Test
    fun theFlaggedStepComesOffBeforeKeptStepsAreRechecked() {
        val kept = apply(readOnly = false)
        val trial = Lever.EngineFile(
            "{userProfilePath}/Saved/Config/Windows/Engine.ini", "ini", "default", false,
            listOf(set("SystemSettings", "r.ShadowQuality", "0")),
        )
        val pending = OptimizeApplier.applyFile(0, trial, roots, tmp.newFolder("trial"), false, OptimizeApplier.OpCount())!!
        // Checked while the trial step is still on, its value reads as the game resetting the kept key.
        val withTrial = ini.readBytes()
        assertEquals(1, OptimizeApplier.reverifyFile(JSONObject(kept.toString()), null)!!.reset)
        ini.writeBytes(withTrial)

        // beforeLaunch order: the flagged step comes off first, then the kept step finds nothing reset.
        OptimizeReverter.revertFile(pending)
        assertTrue(ini.readText().contains("r.ShadowQuality=1"))
        assertEquals(0, OptimizeApplier.reverifyFile(kept, null)!!.reset)
    }

    private fun shadowQuality(value: String, dir: String): JSONObject {
        val lever = Lever.EngineFile(
            "{userProfilePath}/Saved/Config/Windows/Engine.ini", "ini", "default", false,
            listOf(set("SystemSettings", "r.ShadowQuality", value)),
        )
        return OptimizeApplier.applyFile(0, lever, roots, tmp.newFolder(dir), false, OptimizeApplier.OpCount())!!
    }

    private fun keptOf(id: String, record: JSONObject, applied: Boolean = true) =
        KeptRung(id, "1", 1, emptyList(), 0L, listOf(record), null, applied = applied)

    @Test
    fun aNewerKeptStepOnTheSameKeyIsNotRevertedToTheOlderValue() {
        val older = apply(readOnly = false)
        val newer = shadowQuality("0", "newer")
        val kept = listOf(keptOf("old", older), keptOf("new", newer))
        val shadowed = OptimizeDrift.shadowedKeys(kept, null)
        assertTrue(OptimizeDrift.fileKeyId(older.getString("path"), set("SystemSettings", "r.ShadowQuality", "")) in shadowed[0])
        assertTrue(shadowed[1].isEmpty())

        // Launch after launch nothing reads as reset, so the reset counter never climbs.
        repeat(3) {
            assertEquals(0, OptimizeApplier.reverifyFile(older, null, shadowed[0])!!.reset)
            assertEquals(0, OptimizeApplier.reverifyFile(newer, null, shadowed[1])!!.reset)
        }
        assertTrue(ini.readText().contains("r.ShadowQuality=0"))

        // A key only the older step writes is still put back when the game resets it.
        ini.writeText(ini.readText().replace("r.Foo=2", "r.Foo=5"))
        assertEquals(1, OptimizeApplier.reverifyFile(older, null, shadowed[0])!!.reset)
        val text = ini.readText()
        assertTrue(text.contains("r.Foo=2") && text.contains("r.ShadowQuality=0"))

        // The newest writer still puts its own value back.
        ini.writeText(text.replace("r.ShadowQuality=0", "r.ShadowQuality=3"))
        assertEquals(1, OptimizeApplier.reverifyFile(newer, null, shadowed[1])!!.reset)
        assertTrue(ini.readText().contains("r.ShadowQuality=0"))
    }

    @Test
    fun theStepOnTrialShadowsKeptKeysItWrites() {
        val older = apply(readOnly = false)
        val trial = shadowQuality("0", "trial")
        val trialRung = Rung.parse(JSONObject("{\"rung_id\":\"t\",\"version\":\"v2\",\"levers\":[]}"))!!
        val pending = PendingRung(trialRung, 0L, null, 1, listOf(trial))
        val shadowed = OptimizeDrift.shadowedKeys(listOf(keptOf("old", older)), pending)
        assertEquals(0, OptimizeApplier.reverifyFile(older, null, shadowed[0])!!.reset)
        assertTrue(ini.readText().contains("r.ShadowQuality=0"))
        // Without the trial step in the picture the kept value would be written back over it.
        assertEquals(1, OptimizeApplier.reverifyFile(JSONObject(older.toString()), null)!!.reset)
    }

    @Test
    fun aNewerStepThatIsOffDoesNotShadow() {
        val older = apply(readOnly = false)
        val newer = shadowQuality("0", "newer")
        val shadowed = OptimizeDrift.shadowedKeys(listOf(keptOf("old", older), keptOf("new", newer, applied = false)), null)
        assertTrue(shadowed[0].isEmpty())
    }

    private val regKey = "Software\\Epic\\Game"

    private fun hive(vararg changes: WineRegistryText.Change): String =
        WineRegistryText.merge("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n", regKey, changes.toList(), 1_700_000_000_000L).text

    private fun regRecord(): JSONObject {
        val x = OptimizeFiles.encodeRegistryValue("dword", "3")
        val y = OptimizeFiles.encodeRegistryValue("sz", "low")
        return JSONObject().put("type", Lever.TYPE_REGISTRY).put("key", regKey).put(
            "values",
            org.json.JSONArray()
                .put(JSONObject().put("name", "X").put("applied", WineRegistryText.canonical(x)).put("raw", x))
                .put(JSONObject().put("name", "Y").put("applied", WineRegistryText.canonical(y)).put("raw", y))
                .put(JSONObject().put("name", "Z").put("applied", JSONObject.NULL).put("raw", JSONObject.NULL)),
        )
    }

    @Test
    fun registryDriftListsTheValuesTheGameReset() {
        val held = hive(
            WineRegistryText.Change("X", OptimizeFiles.encodeRegistryValue("dword", "3")),
            WineRegistryText.Change("Y", OptimizeFiles.encodeRegistryValue("sz", "low")),
        )
        assertEquals(emptyList<String>(), OptimizeDrift.registryDrift(held, regRecord()))
        val reset = hive(
            WineRegistryText.Change("X", OptimizeFiles.encodeRegistryValue("dword", "4")),
            WineRegistryText.Change("Y", OptimizeFiles.encodeRegistryValue("sz", "low")),
            WineRegistryText.Change("Z", OptimizeFiles.encodeRegistryValue("dword", "1")),
        )
        assertEquals(listOf("X", "Z"), OptimizeDrift.registryDrift(reset, regRecord()))
        assertEquals(listOf("X", "Y"), OptimizeDrift.registryDrift(hive(), regRecord()))
    }

    @Test
    fun reverifyRegistryPutsBackResetValues() {
        val file = tmp.newFile("user.reg")
        file.writeText(
            hive(
                WineRegistryText.Change("X", OptimizeFiles.encodeRegistryValue("dword", "4")),
                WineRegistryText.Change("Y", OptimizeFiles.encodeRegistryValue("sz", "low")),
                WineRegistryText.Change("Z", OptimizeFiles.encodeRegistryValue("dword", "1")),
            ),
            Charsets.ISO_8859_1,
        )
        assertEquals(2, OptimizeApplier.reverifyRegistry(file, regRecord(), null)!!.reset)
        assertEquals(emptyList<String>(), OptimizeDrift.registryDrift(OptimizeFiles.readHive(file), regRecord()))
    }

    @Test
    fun reverifyRegistryLeavesValuesANewerStepWrites() {
        val file = tmp.newFile("user.reg")
        file.writeText(
            hive(
                WineRegistryText.Change("X", OptimizeFiles.encodeRegistryValue("dword", "4")),
                WineRegistryText.Change("Y", OptimizeFiles.encodeRegistryValue("sz", "low")),
                WineRegistryText.Change("Z", OptimizeFiles.encodeRegistryValue("dword", "1")),
            ),
            Charsets.ISO_8859_1,
        )
        val newer = JSONObject().put("type", Lever.TYPE_REGISTRY).put("key", regKey.uppercase())
            .put("values", org.json.JSONArray().put(JSONObject().put("name", "x").put("applied", "x")))
        val skip = OptimizeDrift.keyIds(newer, null)
        assertEquals(1, OptimizeApplier.reverifyRegistry(file, regRecord(), null, skip)!!.reset)
        assertEquals(listOf("X"), OptimizeDrift.registryDrift(OptimizeFiles.readHive(file), regRecord()))
    }

    @Test
    fun onlyExistingOpsSkipKeysTheFileLacks() {
        val op = FileOp.fromJson(JSONObject("{\"op\":\"set\",\"section\":\"SystemSettings\",\"key\":\"r.Foo\",\"value\":\"2\",\"only_existing\":true}"))!!
        assertTrue(op.onlyExisting)
        assertFalse(FileOp.fromJson(JSONObject("{\"op\":\"set\",\"key\":\"k\",\"value\":\"1\"}"))!!.onlyExisting)

        // A Next-Gen file has ShadowQuality but none of the Classic-only keys.
        val lever = Lever.EngineFile(
            "{userProfilePath}/Saved/Config/Windows/Engine.ini", "ini", "default", false,
            listOf(
                set("SystemSettings", "r.ShadowQuality", "1").copy(onlyExisting = true),
                op,
                set("Classic", "Grass", "0").copy(onlyExisting = true),
                FileOp("delete", "Other", "Missing", null, onlyExisting = true),
            ),
        )
        val count = OptimizeApplier.OpCount()
        val record = OptimizeApplier.applyFile(0, lever, roots, tmp.newFolder("rung"), false, count)!!
        assertEquals(1, count.applied)
        assertEquals(3, count.skipped)
        assertEquals(1, record.getJSONArray("keys").length())
        assertEquals("[SystemSettings]\r\nr.ShadowQuality=1\r\nr.Bar=1\r\n\r\n[Other]\r\nKeep=yes\r\n", ini.readText())

        val absent = lever.copy(ops = lever.ops.drop(1))
        val none = OptimizeApplier.OpCount()
        assertNull(OptimizeApplier.applyFile(0, absent, roots, tmp.newFolder("rung2"), false, none))
        assertEquals(0, none.applied)
        assertEquals(3, none.skipped)
    }
}
