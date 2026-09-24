package com.winlator.core.envvars

import com.winlator.container.Container
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvVarsTest {

    @Test
    fun roundTripPlainValues() {
        val src = EnvVars().apply {
            put("DXVK_HUD", "1")
            put("WINEDEBUG", "fixme-all")
        }
        val parsed = EnvVars(src.toString())
        assertEquals("1", parsed.get("DXVK_HUD"))
        assertEquals("fixme-all", parsed.get("WINEDEBUG"))
    }

    // crash repro: spaces in value used to throw StringIndexOutOfBoundsException on re-parse
    @Test
    fun roundTripValueWithSpaces() {
        val src = EnvVars().apply {
            put("DXVK_CONFIG", "d3d9.presentInterval = 1;")
            put("WINEDEBUG", "fixme-all")
        }
        val parsed = EnvVars(src.toString())
        assertEquals("d3d9.presentInterval = 1;", parsed.get("DXVK_CONFIG"))
        assertEquals("fixme-all", parsed.get("WINEDEBUG"))
    }

    @Test
    fun roundTripValueWithMultipleSpacesEqualsAndSemicolons() {
        // multi-clause DXVK config: spaces, '=', ';' all inside one value
        val src = EnvVars().apply {
            put("DXVK_CONFIG", "d3d9.presentInterval = 1; d3d9.somethingElse = 2")
        }
        val parsed = EnvVars(src.toString())
        assertEquals("d3d9.presentInterval = 1; d3d9.somethingElse = 2", parsed.get("DXVK_CONFIG"))
    }

    @Test
    fun roundTripValueWithSemicolonOnly() {
        val src = EnvVars().apply { put("DXVK_CONFIG", "d3d9.presentInterval=1;") }
        val parsed = EnvVars(src.toString())
        assertEquals("d3d9.presentInterval=1;", parsed.get("DXVK_CONFIG"))
    }

    @Test
    fun roundTripValueWithBackslash() {
        val src = EnvVars().apply { put("WINEPATH", "C:\\Program Files\\foo") }
        val parsed = EnvVars(src.toString())
        assertEquals("C:\\Program Files\\foo", parsed.get("WINEPATH"))
    }

    @Test
    fun roundTripValueWithEscapedSpaceLiteral() {
        // value already contains a backslash followed by a space — must survive round-trip
        val src = EnvVars().apply { put("FOO", "a\\ b") }
        val parsed = EnvVars(src.toString())
        assertEquals("a\\ b", parsed.get("FOO"))
    }

    @Test
    fun roundTripEmptyValue() {
        val src = EnvVars().apply { put("EMPTY", "") }
        val parsed = EnvVars(src.toString())
        assertEquals("", parsed.get("EMPTY"))
        assertTrue(parsed.has("EMPTY"))
    }

    @Test
    fun toStringArrayReturnsRawValuesForExecve() {
        // execve envp must NOT contain backslash escapes — it's the actual env vector
        val src = EnvVars().apply { put("DXVK_CONFIG", "d3d9.presentInterval = 1;") }
        assertArrayEquals(arrayOf("DXVK_CONFIG=d3d9.presentInterval = 1;"), src.toStringArray())
    }

    @Test
    fun emptyAndNullParseSafely() {
        assertTrue(EnvVars("").isEmpty)
        assertTrue(EnvVars(null).isEmpty)
    }

    @Test
    fun multipleVarsPreserveOrderAndSpaces() {
        val src = EnvVars().apply {
            put("A", "with space")
            put("B", "no_space")
            put("C", "another with spaces")
        }
        val parsed = EnvVars(src.toString())
        assertEquals(listOf("A", "B", "C"), parsed.toList())
        assertEquals("with space", parsed.get("A"))
        assertEquals("no_space", parsed.get("B"))
        assertEquals("another with spaces", parsed.get("C"))
    }

    // The multi-select picker drops tokens it does not list on the first edit.
    @Test
    fun defaultDebugTokensAreOfferedByThePicker() {
        val defaults = EnvVars(Container.DEFAULT_ENV_VARS)
        for (name in listOf("ZINK_DEBUG", "TU_DEBUG")) {
            val offered = EnvVarInfo.KNOWN_ENV_VARS.getValue(name).possibleValues
            for (token in defaults.get(name).split(",")) {
                assertTrue("$name token '$token' is not offered by the picker", token in offered)
            }
        }
    }
}
