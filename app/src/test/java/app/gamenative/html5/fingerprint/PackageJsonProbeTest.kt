package app.gamenative.html5.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageJsonProbeTest {

    @Test fun parse_validJson_extractsMainAndName() {
        val text = """{"name":"my-game","main":"index.html","version":"1.0.0"}"""
        val p = PackageJsonProbe.parse(text)
        assertEquals("index.html", p?.main)
        assertEquals("my-game", p?.name)
        assertNull(p?.productName)
    }

    @Test fun parse_validJsonWithProductName_extractsAll() {
        val text = """{"name":"my-game","productName":"My Game","main":"a/b.html"}"""
        val p = PackageJsonProbe.parse(text)
        assertEquals("a/b.html", p?.main)
        assertEquals("My Game", p?.productName)
    }

    @Test fun parse_emptyOrBlank_returnsNull() {
        assertNull(PackageJsonProbe.parse(""))
        assertNull(PackageJsonProbe.parse("   "))
        assertNull(PackageJsonProbe.parse(null))
    }

    @Test fun parse_malformedJson_returnsNull() {
        assertNull(PackageJsonProbe.parse("not json {"))
        assertNull(PackageJsonProbe.parse("[]")) // array, not object
        assertNull(PackageJsonProbe.parse("\"just a string\""))
    }

    @Test fun parse_utf8Bom_stripped() {
        val text = "﻿" + """{"main":"index.html"}"""
        val p = PackageJsonProbe.parse(text)
        assertEquals("index.html", p?.main)
    }

    @Test fun parse_mainAbsent_returnsNullMain() {
        val text = """{"name":"foo"}"""
        val p = PackageJsonProbe.parse(text)
        assertNull(p?.main)
        assertEquals("foo", p?.name)
    }

    @Test fun parse_mainNotString_returnsNullMain() {
        val text = """{"main":42}"""
        val p = PackageJsonProbe.parse(text)
        // jsonPrimitive.contentOrNull returns the number as a string per kotlinx-serialization semantics;
        // we accept any primitive content. signature consumers downstream filter by .endsWith(".html").
        // lock this: numeric main returns "42" (string) — Generic sig will reject (not .html).
        assertEquals("42", p?.main)
    }

    @Test fun mainDir_rootEntry_isEmpty() {
        assertEquals("", PackageJsonProbe.mainDir("index.html"))
    }

    @Test fun mainDir_singleSubdir_returnsDir() {
        assertEquals("assets", PackageJsonProbe.mainDir("assets/node-webkit.html"))
    }

    @Test fun mainDir_nestedPath_returnsFullDirPrefix() {
        assertEquals("a/b/c", PackageJsonProbe.mainDir("a/b/c/start.html"))
    }

    @Test fun mainDir_backslashes_normalizedToForward() {
        assertEquals("assets", PackageJsonProbe.mainDir("assets\\node-webkit.html"))
    }

    @Test fun mainDir_leadingSlash_stripped() {
        assertEquals("assets", PackageJsonProbe.mainDir("/assets/index.html"))
    }

    @Test fun mainDir_nullOrBlank_returnsEmpty() {
        assertEquals("", PackageJsonProbe.mainDir(null))
        assertEquals("", PackageJsonProbe.mainDir(""))
        assertEquals("", PackageJsonProbe.mainDir("   "))
    }

    @Test fun parse_extractsDescriptionAndDependencies() {
        // Welcome to Maison Chichigami (2914480) package.json shape — Tyrano-on-Electron.
        val text =
            """{"name":"Chichigamike","main":"main.js","description":"TyranoScript｜ティラノスクリプト Ver5",""" +
                """"dependencies":{"fs-extra":"^8.1.0","adm-zip":"^0.4.13"},"window":{"title":"x"}}"""
        val p = PackageJsonProbe.parse(text)
        assertEquals("TyranoScript｜ティラノスクリプト Ver5", p?.description)
        // dependencies normalized to lowercase set.
        assertEquals(setOf("fs-extra", "adm-zip"), p?.dependencies)
    }

    @Test fun parse_dependenciesAbsent_emptySet() {
        val text = """{"name":"foo","main":"index.html"}"""
        val p = PackageJsonProbe.parse(text)
        assertEquals(emptySet<String>(), p?.dependencies)
    }

    @Test fun parse_descriptionAbsent_null() {
        val text = """{"name":"foo","main":"index.html"}"""
        val p = PackageJsonProbe.parse(text)
        assertNull(p?.description)
    }
}
