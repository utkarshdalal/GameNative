package app.gamenative.service.gog

import android.content.Context
import app.gamenative.db.dao.GOGGameDao
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class GOGManagerPlayTaskTest {
    @Test
    fun primaryPlayTask_preservesJazzJackrabbitDosBoxArguments() {
        val installDir = Files.createTempDirectory("gog-jazz-install").toFile()
        val dosboxDir = installDir.resolve("DOSBOX").apply { mkdirs() }
        dosboxDir.resolve("DOSBox.exe").createNewFile()
        installDir.resolve("goggame-1808582759.info").writeText(
            """
            {
              "playTasks": [
                {
                  "isPrimary": true,
                  "type": "FileTask",
                  "path": "DOSBOX\\DOSBox.exe",
                  "arguments": "-conf \"..\\dosbox_jazz.conf\" -conf \"..\\dosbox_jazz_single.conf\" -noconsole -c \"exit\""
                }
              ]
            }
            """.trimIndent(),
        )

        val context: Context = RuntimeEnvironment.getApplication()
        val manager = GOGManager(mock<GOGGameDao>(), context)

        val task = manager.getPrimaryPlayTaskFromGOGInfo(installDir, installDir.absolutePath).getOrThrow()

        assertEquals("DOSBOX/DOSBox.exe", task.executablePath.replace('\\', '/'))
        assertEquals(
            "-conf \"..\\dosbox_jazz.conf\" -conf \"..\\dosbox_jazz_single.conf\" -noconsole -c \"exit\"",
            task.arguments,
        )

        installDir.deleteRecursively()
    }
}
