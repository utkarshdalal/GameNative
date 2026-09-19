package app.gamenative.html5.fingerprint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

object PackageJsonProbe {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // description carries engine hints file anchors miss (Tyrano's electron template:
    // "TyranoScript｜...Ver5"). dependencies are NOT a hint -- see TyranoSignature.
    data class Probe(
        val main: String?,
        val name: String?,
        val productName: String?,
        val description: String?,
    )

    fun parse(text: String?): Probe? {
        if (text.isNullOrBlank()) return null
        // some NW.js bundlers emit a UTF-8 BOM.
        val cleaned = if (text.startsWith('﻿')) text.substring(1) else text
        val obj: JsonObject = runCatching { json.parseToJsonElement(cleaned).jsonObject }
            .onFailure { Timber.tag("PackageJsonProbe").v(it, "parse failed") }
            .getOrNull() ?: return null
        return Probe(
            main = obj["main"]?.jsonPrimitive?.contentOrNull,
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            productName = obj["productName"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // "" when main is at root or absent.
    fun mainDir(main: String?): String {
        if (main.isNullOrBlank()) return ""
        val norm = main.replace('\\', '/').trimStart('/')
        val slash = norm.lastIndexOf('/')
        return if (slash <= 0) "" else norm.substring(0, slash)
    }
}
