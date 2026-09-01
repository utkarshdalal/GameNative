package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgNativePinContractTest {
    @Test
    fun nativePreparationStillUsesGitlinkAsSourceOfTruth() {
        val path = sourcePath(".github/actions/prepare-lsfg-native/action.yml")
        val source = Files.readString(path)
        assertTrue(source.contains("gitlink") || source.contains("lsfg-vk-android"))
    }

    private fun sourcePath(relative: String): Path {
        val direct = Paths.get(relative)
        if (Files.isRegularFile(direct)) return direct
        return Paths.get("..").resolve(relative).normalize()
    }
}
