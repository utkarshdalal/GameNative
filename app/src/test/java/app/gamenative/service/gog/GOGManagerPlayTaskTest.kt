package app.gamenative.service.gog

import android.content.Context
import app.gamenative.db.dao.GOGGameDao
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGManagerPlayTaskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jazzArguments =
        "-conf \"..\\dosbox_jazz.conf\" -conf \"..\\dosbox_jazz_single.conf\" -noconsole -c \"exit\""

    private fun manager(): GOGManager {
        val context: Context = RuntimeEnvironment.getApplication()
        return GOGManager(mock<GOGGameDao>(), context)
    }

    private fun writeDosBoxGame(gameDir: File, arguments: String? = jazzArguments, workingDir: String? = "DOSBOX") {
        gameDir.resolve("DOSBOX").mkdirs()
        gameDir.resolve("DOSBOX/DOSBox.exe").createNewFile()
        val argumentsJson = if (arguments == null) "null" else "\"${arguments.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val workingDirJson = if (workingDir == null) "" else ",\n      \"workingDir\": \"$workingDir\""
        gameDir.resolve("goggame-1808582759.info").writeText(
            """
            {
              "playTasks": [
                {
                  "isPrimary": true,
                  "type": "FileTask",
                  "path": "DOSBOX\\DOSBox.exe",
                  "arguments": $argumentsJson$workingDirJson
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun findInstalledPlayTask_readsDosBoxArgumentsFromInstallRoot() {
        val installDir = tmp.newFolder("jazz")
        writeDosBoxGame(installDir)

        val task = manager().findInstalledPlayTask(installDir.absolutePath, "1808582759")

        assertNotNull(task)
        assertEquals("DOSBOX/DOSBox.exe", task!!.executablePath.replace('\\', '/'))
        assertEquals(jazzArguments, task.arguments)
        assertEquals("DOSBOX", task.workingDir)
    }

    @Test
    fun findInstalledPlayTask_readsDosBoxArgumentsFromGameSubdir() {
        val installDir = tmp.newFolder("jazz-v2")
        val gameDir = installDir.resolve("game_1808582759").apply { mkdirs() }
        writeDosBoxGame(gameDir)

        val task = manager().findInstalledPlayTask(installDir.absolutePath, "1808582759")

        assertNotNull(task)
        assertEquals("game_1808582759/DOSBOX/DOSBox.exe", task!!.executablePath.replace('\\', '/'))
        assertEquals(jazzArguments, task.arguments)
        assertEquals("game_1808582759/DOSBOX", task.workingDir.replace('\\', '/'))
    }

    @Test
    fun findInstalledPlayTask_treatsNullArgumentsAsEmpty() {
        val installDir = tmp.newFolder("no-args")
        writeDosBoxGame(installDir, arguments = null, workingDir = null)

        val task = manager().findInstalledPlayTask(installDir.absolutePath, "1808582759")

        assertNotNull(task)
        assertEquals("", task!!.arguments)
        assertEquals("", task.workingDir)
    }

    @Test
    fun resolveGogLaunchArguments_prefersUserArgsAndMatchesExecutable() {
        val task = GOGManager.GOGPlayTask("DOSBOX/DOSBox.exe", jazzArguments, "DOSBOX")
        val manager = manager()

        assertEquals(jazzArguments, manager.resolveGogLaunchArguments("", "dosbox\\DOSBOX.EXE", task))
        assertEquals("", manager.resolveGogLaunchArguments("-fullscreen", "DOSBOX/DOSBox.exe", task))
        assertEquals("", manager.resolveGogLaunchArguments("", "DOSBOX/Setup.exe", task))
        assertEquals("", manager.resolveGogLaunchArguments("", "DOSBOX/DOSBox.exe", null))
    }
}
