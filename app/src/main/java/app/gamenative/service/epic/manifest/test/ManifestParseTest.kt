package app.gamenative.service.epic.manifest.test

import app.gamenative.service.epic.manifest.ManifestTestSerializer
import app.gamenative.service.epic.manifest.ManifestUtils
import java.io.File

/**
 * Simple test to verify Kotlin manifest parsing works correctly
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: ManifestParseTest <manifest_file>")
        return
    }

    val manifestFile = File(args[0])
    if (!manifestFile.exists()) {
        println("Error: File not found: ${args[0]}")
        return
    }

    try {
        println("Parsing manifest: ${manifestFile.name}")
        println("File size: ${manifestFile.length()} bytes")
        println()

        // Parse the manifest
        val manifest = ManifestUtils.loadFromFile(manifestFile)

        // Create summary
        val summary = ManifestTestSerializer.createManifestSummary(manifest)

        // Output as JSON
        println(summary.toString(2))
    } catch (e: Exception) {
        println("Error parsing manifest: ${e.message}")
        e.printStackTrace()
    }
}
