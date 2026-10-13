package app.gamenative.filedetect

import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureTest {
    private val detector get() = Fixtures.detector

    @Test
    fun `types fixtures`() {
        val failures = Fixtures.set("types").mapNotNull { file ->
            runCatching { checkTypeFile(file) }.exceptionOrNull()?.let { "${file.name}: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkTypeFile(file: Fixture) {
        val paths = file.lines
        assertTrue("File is empty: ${file.name}", paths.isNotEmpty())
        val expected = file.name.takeIf { it != "_NonMatchingTests" }
        val failures = ArrayList<String>()
        val seen = HashSet<String>()
        for (path in paths) {
            if (!seen.add(path)) {
                failures.add("Path \"$path\" is defined more than once")
                continue
            }
            val actual = detector.matches(listOf(path), filterEvidence = false)
            if (expected == null) {
                for (match in actual.keys) {
                    if (!match.startsWith("Evidence.")) {
                        failures.add("Path \"$path\" returned \"$match\" but it should not have matched anything")
                    }
                }
            } else if (!actual.containsKey(expected)) {
                failures.add("Path \"$path\" does not match for \"$expected\" (got ${actual.keys})")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `filelists fixtures`() {
        val failures = Fixtures.set("filelists").mapNotNull { file ->
            runCatching { checkFileList(file) }.exceptionOrNull()?.let { "${file.name}: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkFileList(file: Fixture) {
        val paths = file.lines
        assertTrue("File is empty: ${file.name}", paths.isNotEmpty())
        val bits = file.name.split('.', limit = 3)
        val expected = bits[0] + "." + bits[1]
        val matches = detector.matches(paths, filterEvidence = false)
        assertTrue("Failed to match $expected for ${file.name} (matched as ${matches.keys})", matches.containsKey(expected))
        val falsePositives = matches.keys.filter { it != expected && it.startsWith("Engine.") }
        assertTrue("FALSE positive? $expected for ${file.name} (matched as ${matches.keys})", falsePositives.isEmpty())

        val detection = detector.detect(paths)
        val type = bits[0]
        val name = bits[1]
        val bucket = when (type) {
            "Engine" -> detection.engines
            "AntiCheat" -> detection.antiCheat
            "SDK" -> detection.sdks
            "Launcher" -> detection.launchers
            "Emulator" -> detection.emulators
            "Container" -> detection.containers
            else -> error("Unexpected type $type")
        }
        assertTrue("detect() did not list $name under $type: $detection", name in bucket)
    }
}
