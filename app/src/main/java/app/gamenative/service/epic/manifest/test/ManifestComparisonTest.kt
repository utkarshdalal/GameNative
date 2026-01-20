package app.gamenative.service.epic.manifest.test

import app.gamenative.service.epic.manifest.ManifestTestSerializer
import app.gamenative.service.epic.manifest.ManifestUtils
import java.io.File
import org.json.JSONObject

/**
 * Test utility to compare Kotlin manifest parsing with Python implementation
 */
object ManifestComparisonTest {

    /**
     * Parse manifest with Kotlin implementation and output JSON
     */
    fun parseAndSerializeKotlin(manifestFile: File, summaryOnly: Boolean = true): String {
        val manifest = ManifestUtils.loadFromFile(manifestFile)

        val json = if (summaryOnly) {
            ManifestTestSerializer.createManifestSummary(manifest)
        } else {
            ManifestTestSerializer.serializeManifest(manifest)
        }

        return json.toString(2)
    }

    /**
     * Run Python parser and get output
     */
    fun parseAndSerializePython(manifestFile: File, summaryOnly: Boolean = true): String {
        val pythonScript = File("app/src/main/python/manifest_test_python.py")
        val outputFormat = if (summaryOnly) "summary" else "full"

        val process = ProcessBuilder(
            "python3",
            pythonScript.absolutePath,
            manifestFile.absolutePath,
            outputFormat,
        ).redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Python script failed with exit code $exitCode:\n$output")
        }

        return output
    }

    /**
     * Compare Kotlin and Python outputs
     */
    fun compareImplementations(manifestFile: File, summaryOnly: Boolean = true): ComparisonResult {
        println("Parsing with Kotlin...")
        val kotlinOutput = parseAndSerializeKotlin(manifestFile, summaryOnly)

        println("Parsing with Python...")
        val pythonOutput = parseAndSerializePython(manifestFile, summaryOnly)

        println("\n=== Kotlin Output ===")
        println(kotlinOutput)

        println("\n=== Python Output ===")
        println(pythonOutput)

        // Parse both as JSON and compare
        val kotlinJson = JSONObject(kotlinOutput)
        val pythonJson = JSONObject(pythonOutput)

        return compareJsonObjects(kotlinJson, pythonJson)
    }

    /**
     * Deep comparison of two JSON objects
     */
    private fun compareJsonObjects(
        obj1: JSONObject,
        obj2: JSONObject,
        path: String = "root",
    ): ComparisonResult {
        val differences = mutableListOf<String>()

        // Check all keys in obj1
        obj1.keys().forEach { key ->
            val fullPath = "$path.$key"

            if (!obj2.has(key)) {
                differences.add("Missing key in Python output: $fullPath")
                return@forEach
            }

            val value1 = obj1.get(key)
            val value2 = obj2.get(key)

            when {
                value1 is JSONObject && value2 is JSONObject -> {
                    val subResult = compareJsonObjects(value1, value2, fullPath)
                    differences.addAll(subResult.differences)
                }
                value1 is org.json.JSONArray && value2 is org.json.JSONArray -> {
                    if (value1.length() != value2.length()) {
                        differences.add("Array length mismatch at $fullPath: ${value1.length()} vs ${value2.length()}")
                    } else {
                        for (i in 0 until value1.length()) {
                            val item1 = value1.get(i)
                            val item2 = value2.get(i)

                            if (item1 is JSONObject && item2 is JSONObject) {
                                val subResult = compareJsonObjects(item1, item2, "$fullPath[$i]")
                                differences.addAll(subResult.differences)
                            } else if (item1.toString() != item2.toString()) {
                                differences.add("Array item mismatch at $fullPath[$i]: $item1 vs $item2")
                            }
                        }
                    }
                }
                value1.toString() != value2.toString() -> {
                    differences.add("Value mismatch at $fullPath: $value1 vs $value2")
                }
            }
        }

        // Check for keys in obj2 not in obj1
        obj2.keys().forEach { key ->
            if (!obj1.has(key)) {
                differences.add("Extra key in Python output: $path.$key")
            }
        }

        return ComparisonResult(
            kotlinOutput = obj1.toString(2),
            pythonOutput = obj2.toString(2),
            matches = differences.isEmpty(),
            differences = differences,
        )
    }

    /**
     * Run a comprehensive test suite
     */
    fun runTestSuite(manifestFiles: List<File>) {
        println("=".repeat(80))
        println("MANIFEST COMPARISON TEST SUITE")
        println("=".repeat(80))
        println()

        val results = manifestFiles.mapIndexed { index, file ->
            println("Test ${index + 1}/${manifestFiles.size}: ${file.name}")
            println("-".repeat(80))

            try {
                val result = compareImplementations(file, summaryOnly = true)

                if (result.matches) {
                    println("✅ PASSED - Outputs match!")
                } else {
                    println("❌ FAILED - Found ${result.differences.size} differences:")
                    result.differences.take(10).forEach { diff ->
                        println("  - $diff")
                    }
                    if (result.differences.size > 10) {
                        println("  ... and ${result.differences.size - 10} more")
                    }
                }

                result
            } catch (e: Exception) {
                println("❌ ERROR: ${e.message}")
                e.printStackTrace()
                ComparisonResult(
                    kotlinOutput = "",
                    pythonOutput = "",
                    matches = false,
                    differences = listOf("Exception: ${e.message}"),
                )
            } finally {
                println()
            }
        }

        // Summary
        println("=".repeat(80))
        println("SUMMARY")
        println("=".repeat(80))
        val passed = results.count { it.matches }
        val failed = results.count { !it.matches }
        println("Total: ${manifestFiles.size}")
        println("Passed: $passed")
        println("Failed: $failed")
        println("Success Rate: ${(passed * 100.0 / manifestFiles.size).toInt()}%")
    }
}

/**
 * Result of comparing Kotlin and Python implementations
 */
data class ComparisonResult(
    val kotlinOutput: String,
    val pythonOutput: String,
    val matches: Boolean,
    val differences: List<String>,
)

/**
 * Main test runner
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        val defaultManifests = listOf(
            File("app/src/androidTest/assets/test-manifest.json"),
            File("app/src/androidTest/assets/test-v3-manifest.json"),
        ).filter { it.exists() && it.isFile }

        if (defaultManifests.isEmpty()) {
            println("Usage: ManifestComparisonTest <manifest_file> [<manifest_file2> ...]")
            println()
            println("Examples:")
            println("  Single file: ManifestComparisonTest game.manifest")
            println("  Multiple files: ManifestComparisonTest *.manifest")
            println("  Directory: ManifestComparisonTest /path/to/manifests/*.manifest")
            return
        }

        println("No args provided; running default V4/V3 test manifests.")
        ManifestComparisonTest.runTestSuite(defaultManifests)
        return
    }

    val manifestFiles = args.map { File(it) }.filter { it.exists() && it.isFile }

    if (manifestFiles.isEmpty()) {
        println("Error: No valid manifest files found")
        return
    }

    if (manifestFiles.size == 1) {
        // Single file - show detailed comparison
        val result = ManifestComparisonTest.compareImplementations(manifestFiles[0], summaryOnly = false)

        if (result.matches) {
            println("\n✅ SUCCESS - Both implementations produce identical output!")
        } else {
            println("\n❌ FAILURE - Found ${result.differences.size} differences:")
            result.differences.forEach { diff ->
                println("  - $diff")
            }
        }
    } else {
        // Multiple files - run test suite
        ManifestComparisonTest.runTestSuite(manifestFiles)
    }
}
