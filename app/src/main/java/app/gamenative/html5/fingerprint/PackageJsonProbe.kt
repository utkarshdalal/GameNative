package app.gamenative.html5.fingerprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

// reads package.json (NW.js / electron / generic) to extract the `main` entrypoint.
// pure parser -- no signature logic, no filesystem assumptions. tests cover malformed JSON,
// missing main, non-string main, BOM-prefixed UTF-8 (some bundlers emit this).
object PackageJsonProbe {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // description + dependencies surface engine hints that file-anchor matching misses.
    // Tyrano's Electron template ships `description: "TyranoScript｜...Ver5"` and
    // `dependencies: { "adm-zip": ..., "fs-extra": ... }`. adm-zip is Tyrano-specific
    // (used by `.tpatch` apply). dependencies is a lowercased set of keys; absent → empty.
    data class Probe(
        val main: String?,
        val name: String?,
        val productName: String?,
        val description: String?,
        val dependencies: Set<String> = emptySet(),
    )

    fun parse(text: String?): Probe? {
        if (text.isNullOrBlank()) return null
        // strip UTF-8 BOM if present -- encountered in some NW.js bundler outputs.
        val cleaned = if (text.startsWith('﻿')) text.substring(1) else text
        val obj: JsonObject = runCatching { json.parseToJsonElement(cleaned).jsonObject }
            .onFailure { Timber.tag("PackageJsonProbe").v(it, "parse failed") }
            .getOrNull() ?: return null
        val depsObj = obj["dependencies"] as? JsonObject
        val deps: Set<String> = depsObj?.keys?.map { it.lowercase() }?.toSet() ?: emptySet()
        return Probe(
            main = obj["main"]?.jsonPrimitive?.contentOrNull,
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            productName = obj["productName"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            dependencies = deps,
        )
    }

    // dirname of `main`, normalized to forward-slashes. "" when main is at root or absent.
    // examples: "assets/node-webkit.html" → "assets", "index.html" → "", "a/b/c.html" → "a/b"
    fun mainDir(main: String?): String {
        if (main.isNullOrBlank()) return ""
        val norm = main.replace('\\', '/').trimStart('/')
        val slash = norm.lastIndexOf('/')
        return if (slash <= 0) "" else norm.substring(0, slash)
    }
}
