package app.gamenative.api

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SupportPatchRegistryTest {

    private val reg = """{"op":"regmerge","hive":"HKCU","key":"Software\\IO Interactive\\007 First Light",
        "values":[{"name":"ResolutionScale","type":"dword","data":"64"},{"name":"Foo","type":"string","data":"bar"},
        {"name":"","type":"binary","data":"0aff"},{"name":"Big","type":"qword","data":"1ffffffff"},{"name":"Old","delete":true}]}"""

    private val file = """{"path":"a.ini","op":"replace","originalSha256":"${"a".repeat(64)}","sha256":"${"b".repeat(64)}",
        "size":3,"artifactKey":"artifacts/x/0-a.ini","artifactUrl":"https://example.com/a"}"""

    private fun parse(vararg ops: String): SupportPatch {
        val patch = SupportPatch.parse(
            JSONObject("""{"v":1,"patchsetId":"0b6f1c2e-7d1a-4c3b-9e8f-1a2b3c4d5e6f","summary":"s","rerun":true,"ops":[${ops.joinToString(",")}]}"""),
        )
        assertNotNull(patch)
        return patch!!
    }

    @Test
    fun parsesARegmergeOp() {
        val patch = parse(reg)
        assertTrue(patch.applicable)
        val op = patch.ops.single()
        assertTrue(op.isRegistry)
        assertEquals("HKCU", op.hive)
        assertEquals("Software\\IO Interactive\\007 First Light", op.key)
        assertEquals("HKCU\\Software\\IO Interactive\\007 First Light", op.path)
        assertEquals(5, op.values.size)
        assertTrue(op.values.last().delete)
        assertTrue(parse(file, reg).applicable)
    }

    @Test
    fun refusesBadRegmergeOps() {
        assertFalse(parse(reg, reg).applicable)
        assertFalse(parse(reg.replace("HKCU", "HKCR")).applicable)
        assertFalse(parse(reg.replace("Software\\\\", "System\\\\")).applicable)
        assertFalse(parse(reg.replace("\"64\"", "\"100000000\"")).applicable)
        assertFalse(parse(reg.replace("\"0aff\"", "\"0af\"")).applicable)
        assertFalse(parse(reg.replace("\"delete\":true", "\"delete\":true,\"type\":\"dword\"")).applicable)
        assertFalse(parse(reg.replace("\"Foo\"", "\"resolutionscale\"")).applicable)
        assertFalse(parse(reg.replace("\"bar\"", "\"a\\tb\"")).applicable)
        assertFalse(parse(reg.replace("\"values\"", "\"vals\"")).applicable)
    }

    @Test
    fun refusesFileOpsOnTheWinePrefix() {
        assertFalse(parse(file.replace("a.ini", "%WINEPREFIX%/user.reg")).applicable)
        assertFalse(parse(file.replace("a.ini", "%wineprefix%/drive_c/x.dll")).applicable)
    }
}
