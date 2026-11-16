package app.gamenative.optimize

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests run on the desktop JVM, which accepts regex syntax that Android's engine refuses at class load. */
class AndroidRegexSyntaxTest {
    @Test
    fun mainSourcesDoNotUseTheInlineUnicodeClassFlag() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file -> file.useLines { lines -> lines.any { "\"(?U)" in it } } }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("Android cannot compile a pattern with (?U): $offenders", offenders.isEmpty())
    }
}
