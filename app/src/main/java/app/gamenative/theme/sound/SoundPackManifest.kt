package app.gamenative.theme.sound

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Audio Loader `pack.json`. Keys of [ignore] and [mappings] are Steam sound file names such as `deck_ui_navigation.wav`. */
data class SoundPackManifest(
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val version: String? = null,
    val manifestVersion: Int = 1,
    val music: Boolean = false,
    val ignore: Set<String> = emptySet(),
    val mappings: Map<String, List<String>> = emptyMap(),
    val storeId: String? = null,
) {
    companion object {
        const val FILE = "pack.json"

        fun parse(text: String): SoundPackManifest {
            val obj = Json.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject
                ?: throw IOException("pack.json is not a JSON object")
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val name = str("name") ?: throw IOException("pack.json has no name")
            val music = (obj["music"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: false
            val manifestVersion = (obj["manifest_version"] as? JsonPrimitive)
                ?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() } ?: 1
            val ignore = (obj["ignore"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
            val mappings = LinkedHashMap<String, List<String>>()
            (obj["mappings"] as? JsonObject)?.forEach { (key, value) ->
                val files = when (value) {
                    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    is JsonPrimitive -> listOfNotNull(value.takeIf { it.isString }?.content)
                    else -> emptyList()
                }.filter { it.isNotBlank() }
                if (files.isNotEmpty()) mappings[key] = files
            }
            return SoundPackManifest(
                name = name,
                description = str("description"),
                author = str("author"),
                version = str("version"),
                manifestVersion = manifestVersion,
                music = music,
                ignore = ignore,
                mappings = mappings,
                storeId = str("id"),
            )
        }

        fun load(dir: File): SoundPackManifest = parse(File(dir, FILE).readText())
    }
}
