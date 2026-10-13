package app.gamenative.filedetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class RuleSetTest {
    @Test
    fun `all rules compile with java util regex`() {
        val sections = RuleSet.parseIni(Fixtures.iniText)
        assertEquals(
            listOf("Evidence", "Engine", "Container", "Emulator", "AntiCheat", "SDK", "Launcher"),
            sections.keys.toList(),
        )
        val failures = ArrayList<String>()
        var total = 0
        for ((type, rules) in sections) {
            for ((name, regexes) in rules) {
                for (regex in regexes) {
                    total++
                    try {
                        Pattern.compile(asciiLower(regex))
                    } catch (e: Exception) {
                        failures.add("$type.$name: $regex -> ${e.message?.lineSequence()?.firstOrNull()}")
                    }
                }
            }
        }
        assertEquals(401, total)
        assertTrue("Rules needing translation:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertEquals(401, Fixtures.ruleSet.rules.size)
    }

    @Test
    fun `multi valued keys are collected`() {
        val sections = RuleSet.parseIni(Fixtures.iniText)
        assertEquals(3, sections["Evidence"]!!["Build"]!!.size)
        assertEquals(listOf("""\.arc$"""), sections["Evidence"]!!["ARC"])
    }

    @Test
    fun `extension bucketing follows the php rules`() {
        val rs = Fixtures.ruleSet
        val byName = rs.rules.associateBy { it.fullName }
        assertEquals(listOf("arc"), byName["Evidence.ARC"]!!.extensions)
        assertEquals(listOf("dll", "wdf"), byName["Engine.3D_GameStudio"]!!.extensions)
        assertTrue(byName["Engine.3D_GameStudio"]!!.hasCommonPrefix)
        assertEquals(null, byName["Evidence.VSWAP"]!!.extensions)
        assertEquals(null, byName["SDK.cURL"]!!.extensions)
        assertTrue(rs.byExtension.containsKey("dll"))
        assertTrue(rs.anyExtension.isNotEmpty())
        val prefixGroup = rs.byExtension["dll"]!!.first().rules
        for (i in 1 until prefixGroup.size) {
            assertTrue(prefixGroup[i - 1].sortLength >= prefixGroup[i].sortLength)
        }
    }
}

class RequiredLiteralTest {
    @Test
    fun `required literal extraction is conservative`() {
        assertEquals("adobe air", RequiredLiteral.of("(?:^|/)adobe air(?:$|/)"))
        assertEquals("python", RequiredLiteral.of("(?:^|/)python[0-9]{0,3}+\\.dll$"))
        assertEquals(".stream", RequiredLiteral.of("(?:^|/)[a-f0-9]{16}\\.stream$"))
        assertEquals("mus_", RequiredLiteral.of("(?:^|/)mus\\_\\w++\\.ogg$"))
        assertEquals("vswap.", RequiredLiteral.of("(?:^|/)vswap\\."))
        assertEquals("curl", RequiredLiteral.of("curl(?:module|lib)?\\.(?:dll|exe)$"))
        assertEquals("ab", RequiredLiteral.of("abc?d"))
        assertEquals(null, RequiredLiteral.of("a|b"))
        assertEquals(null, RequiredLiteral.of("(?:a|b)"))
        assertEquals(".u", RequiredLiteral.of("\\.u$"))
    }

    @Test
    fun `every rule literal is a substring of its own type fixtures`() {
        val byName = Fixtures.ruleSet.rules.groupBy { it.fullName }
        val withLiteral = Fixtures.ruleSet.rules.count { it.requiredLiteral != null }
        println("rules with a required literal: $withLiteral / ${Fixtures.ruleSet.rules.size}")
        for (file in Fixtures.set("types")) {
            val rules = byName[file.name] ?: continue
            for (path in file.lines) {
                val lower = asciiLower(path)
                val matchedByRegex = rules.filter { it.pattern.matcher(lower).find() }
                for (rule in matchedByRegex) {
                    val lit = rule.requiredLiteral ?: continue
                    assertTrue("${rule.fullName}: literal \"$lit\" missing from \"$path\" (regex ${rule.regex})", lower.contains(lit))
                }
            }
        }
    }
}
