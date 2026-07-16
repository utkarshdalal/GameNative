package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

data class FomodInstaller(
    val moduleName: String,
    val requiredFiles: List<FomodFileMapping>,
    val steps: List<FomodStep>,
    val conditionalFileInstalls: List<FomodConditionalFileInstall> = emptyList(),
    val unsupportedWarnings: List<String> = emptyList(),
    val basePath: String = "",
)

data class FomodStep(
    val name: String,
    val groups: List<FomodGroup>,
)

data class FomodGroup(
    val name: String,
    val type: FomodGroupType,
    val plugins: List<FomodPlugin>,
)

data class FomodPlugin(
    val name: String,
    val description: String,
    val imagePath: String,
    val type: FomodPluginType,
    val files: List<FomodFileMapping>,
    val conditionFlags: Map<String, String> = emptyMap(),
    val typePatterns: List<FomodTypePattern> = emptyList(),
)

data class FomodFileMapping(
    val source: String,
    val destination: String,
    val priority: Int,
    val directory: Boolean,
)

data class FomodConditionalFileInstall(
    val dependencies: FomodDependencyExpression,
    val files: List<FomodFileMapping>,
)

data class FomodTypePattern(
    val dependencies: FomodDependencyExpression,
    val type: FomodPluginType,
)

data class FomodDependencyExpression(
    val operator: FomodDependencyOperator = FomodDependencyOperator.AND,
    val flagDependencies: List<FomodFlagDependency> = emptyList(),
    val childGroups: List<FomodDependencyExpression> = emptyList(),
    val unsupportedDependencyCount: Int = 0,
) {
    fun matches(flags: Map<String, String>): Boolean {
        val results = flagDependencies.map { dependency ->
            flags[dependency.flag]?.equals(dependency.value, ignoreCase = true) == true
        } + childGroups.map { it.matches(flags) }

        if (results.isEmpty()) return unsupportedDependencyCount == 0
        return when (operator) {
            FomodDependencyOperator.AND -> unsupportedDependencyCount == 0 && results.all { it }
            FomodDependencyOperator.OR -> results.any { it }
        }
    }

    fun unsupportedCount(): Int =
        unsupportedDependencyCount + childGroups.sumOf { it.unsupportedCount() }
}

data class FomodFlagDependency(
    val flag: String,
    val value: String,
)

enum class FomodDependencyOperator {
    AND,
    OR,
}

enum class FomodGroupType {
    SELECT_EXACTLY_ONE,
    SELECT_AT_MOST_ONE,
    SELECT_AT_LEAST_ONE,
    SELECT_ANY,
}

enum class FomodPluginType {
    REQUIRED,
    RECOMMENDED,
    OPTIONAL,
    NOT_USABLE,
    COULD_BE_USABLE,
}

fun FomodPlugin.effectiveType(flags: Map<String, String>): FomodPluginType =
    typePatterns.firstOrNull { it.dependencies.matches(flags) }?.type ?: type

data class FomodRecipeGenerationResult(
    val recipes: List<ModPlacementRecipe>,
    val unsupportedMappings: List<FomodFileMapping>,
)

object FomodInstallerDetector {
    fun moduleConfigFile(extractedRoot: File): File? =
        extractedRoot.childIgnoreCase("fomod")?.childIgnoreCase("ModuleConfig.xml")?.takeIf { it.isFile }
            ?: extractedRoot.walkTopDown()
                .maxDepth(8)
                .firstOrNull { it.isFile && it.name.equals("ModuleConfig.xml", ignoreCase = true) && it.parentFile?.name.equals("fomod", ignoreCase = true) }

    fun hasCSharpInstaller(extractedRoot: File): Boolean =
        extractedRoot.childIgnoreCase("fomod")?.childIgnoreCase("script.cs")?.isFile == true ||
            extractedRoot.walkTopDown()
                .maxDepth(8)
                .any { it.isFile && it.name.equals("script.cs", ignoreCase = true) && it.parentFile?.name.equals("fomod", ignoreCase = true) }

    private fun File.childIgnoreCase(name: String): File? =
        listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

object FomodParser {
    private const val MAX_DOCTYPE_SCAN_BYTES = 1024 * 1024

    fun parse(moduleConfigXml: File, extractedRoot: File? = null): FomodInstaller {
        rejectDoctype(moduleConfigXml)
        val document = secureFactory().newDocumentBuilder().parse(moduleConfigXml)
        val root = document.documentElement
        val fomodBase = moduleConfigXml.parentFile?.parentFile ?: moduleConfigXml.parentFile ?: moduleConfigXml
        val basePath = extractedRoot?.let { rootDir ->
            runCatching {
                fomodBase.canonicalFile
                    .relativeToOrNull(rootDir.canonicalFile)
                    ?.path
                    ?.replace(File.separatorChar, '/')
                    .orEmpty()
            }.getOrDefault("")
        }.orEmpty()
        val unsupported = mutableListOf<String>()
        if (FomodInstallerDetector.hasCSharpInstaller(fomodBase)) {
            unsupported += "C# FOMOD scripts are not supported"
        }
        val conditionalFileInstalls = root.firstChildElement("conditionalFileInstalls")
            ?.firstChildElement("patterns")
            ?.childElements("pattern")
            ?.mapNotNull { parseConditionalPattern(it) }
            .orEmpty()
        val steps = root.firstChildElement("installSteps")
            ?.childElements("installStep")
            ?.map { step ->
                FomodStep(
                    name = step.attr("name").ifBlank { step.firstChildElement("name")?.textContent?.trim().orEmpty() },
                    groups = step.firstChildElement("optionalFileGroups")
                        ?.childElements("group")
                        ?.map { group ->
                            FomodGroup(
                                name = group.attr("name"),
                                type = groupType(group.attr("type")),
                                plugins = group.firstChildElement("plugins")
                                    ?.childElements("plugin")
                                    ?.map { plugin -> parsePlugin(plugin) }
                                    .orEmpty(),
                            )
                        }
                        .orEmpty(),
                )
            }
            .orEmpty()
        unsupported += unsupportedFeatureWarnings(root, conditionalFileInstalls, steps)

        return FomodInstaller(
            moduleName = root.firstChildElement("moduleName")?.textContent?.trim().orEmpty(),
            requiredFiles = root.firstChildElement("requiredInstallFiles")?.fileMappings().orEmpty(),
            steps = steps,
            conditionalFileInstalls = conditionalFileInstalls,
            unsupportedWarnings = unsupported,
            basePath = basePath,
        )
    }

    private fun parsePlugin(plugin: Element): FomodPlugin {
        val typeDescriptor = plugin.firstChildElement("typeDescriptor")
        return FomodPlugin(
            name = plugin.attr("name"),
            description = plugin.firstChildElement("description")?.textContent?.trim().orEmpty(),
            imagePath = plugin.firstChildElement("image")?.attr("path").orEmpty(),
            type = pluginType(typeDescriptor),
            files = plugin.firstChildElement("files")?.fileMappings().orEmpty(),
            conditionFlags = plugin.firstChildElement("conditionFlags")
                ?.childElements("flag")
                ?.mapNotNull { flag ->
                    val name = flag.attr("name").trim()
                    val value = flag.textContent?.trim().orEmpty()
                    if (name.isBlank()) null else name to value
                }
                ?.toMap()
                .orEmpty(),
            typePatterns = pluginTypePatterns(typeDescriptor),
        )
    }

    private fun parseConditionalPattern(pattern: Element): FomodConditionalFileInstall? {
        val files = pattern.firstChildElement("files")?.fileMappings().orEmpty()
        if (files.isEmpty()) return null
        return FomodConditionalFileInstall(
            dependencies = parseDependencies(pattern.firstChildElement("dependencies")),
            files = files,
        )
    }

    private fun parseDependencies(dependencies: Element?): FomodDependencyExpression {
        if (dependencies == null) return FomodDependencyExpression()
        val flagDependencies = mutableListOf<FomodFlagDependency>()
        val childGroups = mutableListOf<FomodDependencyExpression>()
        var unsupportedCount = 0

        dependencies.childElements().forEach { child ->
            when {
                child.tagName.equals("flagDependency", ignoreCase = true) -> {
                    val flag = child.attr("flag").trim()
                    val value = child.attr("value").trim()
                    if (flag.isBlank()) unsupportedCount++ else flagDependencies += FomodFlagDependency(flag, value)
                }
                child.tagName.equals("dependencies", ignoreCase = true) -> childGroups += parseDependencies(child)
                child.tagName.endsWith("Dependency", ignoreCase = true) -> unsupportedCount++
            }
        }

        return FomodDependencyExpression(
            operator = dependencyOperator(dependencies.attr("operator")),
            flagDependencies = flagDependencies,
            childGroups = childGroups,
            unsupportedDependencyCount = unsupportedCount,
        )
    }

    private fun Element.fileMappings(): List<FomodFileMapping> =
        childElements()
            .filter { it.tagName.equals("file", ignoreCase = true) || it.tagName.equals("folder", ignoreCase = true) }
            .mapNotNull { element ->
                val source = element.attr("source").trim().trim('/', '\\')
                if (source.isBlank()) return@mapNotNull null
                FomodFileMapping(
                    source = source.replace('\\', '/'),
                    destination = element.attr("destination").trim().trim('/', '\\').replace('\\', '/'),
                    priority = element.attr("priority").toIntOrNull() ?: 0,
                    directory = element.tagName.equals("folder", ignoreCase = true),
                )
            }

    private fun groupType(value: String): FomodGroupType = when (value.lowercase()) {
        "selectexactlyone" -> FomodGroupType.SELECT_EXACTLY_ONE
        "selectatmostone" -> FomodGroupType.SELECT_AT_MOST_ONE
        "selectatleastone" -> FomodGroupType.SELECT_AT_LEAST_ONE
        else -> FomodGroupType.SELECT_ANY
    }

    private fun dependencyOperator(value: String): FomodDependencyOperator = when (value.lowercase()) {
        "or" -> FomodDependencyOperator.OR
        else -> FomodDependencyOperator.AND
    }

    private fun pluginType(typeDescriptor: Element?): FomodPluginType {
        val direct = typeDescriptor?.firstChildElement("type")?.attr("name")
        val default = typeDescriptor
            ?.firstChildElement("dependencyType")
            ?.firstChildElement("defaultType")
            ?.attr("name")
        return pluginTypeFromName(direct ?: default)
    }

    private fun pluginTypePatterns(typeDescriptor: Element?): List<FomodTypePattern> =
        typeDescriptor
            ?.firstChildElement("dependencyType")
            ?.firstChildElement("patterns")
            ?.childElements("pattern")
            ?.mapNotNull { pattern ->
                val typeName = pattern.firstChildElement("type")?.attr("name")
                FomodTypePattern(
                    dependencies = parseDependencies(pattern.firstChildElement("dependencies")),
                    type = pluginTypeFromName(typeName),
                )
            }
            .orEmpty()

    private fun pluginTypeFromName(typeName: String?): FomodPluginType =
        when (typeName.orEmpty().lowercase()) {
            "required" -> FomodPluginType.REQUIRED
            "recommended" -> FomodPluginType.RECOMMENDED
            "notusable" -> FomodPluginType.NOT_USABLE
            "couldbeusable" -> FomodPluginType.COULD_BE_USABLE
            else -> FomodPluginType.OPTIONAL
        }

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
        }

    private fun rejectDoctype(file: File) {
        val scanLength = minOf(file.length().coerceAtLeast(0L), MAX_DOCTYPE_SCAN_BYTES.toLong()).toInt()
        if (scanLength <= 0) return
        val buffer = ByteArray(scanLength)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < scanLength) {
                val count = input.read(buffer, offset, scanLength - offset)
                if (count <= 0) break
                offset += count
            }
            offset
        }
        if (read <= 0) return
        if (String(buffer, 0, read, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            throw IOException("FOMOD ModuleConfig.xml DOCTYPE is not supported")
        }
    }

    private fun DocumentBuilderFactory.trySetFeature(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
            .onFailure { Timber.w(it, "FOMOD XML parser does not support feature %s", feature) }
    }
}

object FomodRecipeGenerator {
    fun pluginKey(stepIndex: Int, groupIndex: Int, pluginIndex: Int): String =
        "$stepIndex:$groupIndex:$pluginIndex"

    fun generate(
        installId: String,
        installer: FomodInstaller,
        selectedPluginNames: Set<String>,
        targetRoot: String = ModTargetRoot.GAME_DIR.name,
        targetRelativePath: String = "Data",
        mode: String = ModPlacementMode.OVERWRITE_COPY.name,
    ): FomodRecipeGenerationResult {
        val pluginEntries = installer.steps.flatMapIndexed { stepIndex, step ->
            step.groups.flatMapIndexed { groupIndex, group ->
                group.plugins.mapIndexed { pluginIndex, plugin ->
                    pluginKey(stepIndex, groupIndex, pluginIndex) to plugin
                }
            }
        }
        selectedPluginNames.forEach { name ->
            val matches = pluginEntries.count { (_, plugin) -> plugin.name == name }
            require(matches <= 1) { "FOMOD option name is ambiguous: $name. Use generateForPluginKeys." }
        }
        val selectedPluginKeys = pluginEntries
            .mapNotNull { (key, plugin) -> if (plugin.name in selectedPluginNames) key else null }
            .toSet()
        return generateForPluginKeys(installId, installer, selectedPluginKeys, targetRoot, targetRelativePath, mode)
    }

    fun generateForPluginKeys(
        installId: String,
        installer: FomodInstaller,
        selectedPluginKeys: Set<String>,
        targetRoot: String = ModTargetRoot.GAME_DIR.name,
        targetRelativePath: String = "Data",
        mode: String = ModPlacementMode.OVERWRITE_COPY.name,
    ): FomodRecipeGenerationResult {
        val selectedPlugins = selectedPluginsForKeys(installer, selectedPluginKeys)
        val selectedFiles = selectedFiles(installer, selectedPlugins)

        return generateFromFiles(installId, installer.basePath, selectedFiles, targetRoot, targetRelativePath, mode)
    }

    fun selectedPluginsForKeys(
        installer: FomodInstaller,
        selectedPluginKeys: Set<String>,
    ): List<FomodPlugin> {
        val pluginEntries = installer.steps.flatMapIndexed { stepIndex, step ->
            step.groups.flatMapIndexed { groupIndex, group ->
                group.plugins.mapIndexed { pluginIndex, plugin ->
                    pluginKey(stepIndex, groupIndex, pluginIndex) to plugin
                }
            }
        }
        var includedKeys = pluginEntries
            .filter { (key, plugin) -> key in selectedPluginKeys || plugin.type == FomodPluginType.REQUIRED }
            .mapTo(mutableSetOf()) { it.first }

        while (true) {
            val flags = pluginEntries
                .filter { (key, _) -> key in includedKeys }
                .flatMap { (_, plugin) -> plugin.conditionFlags.entries }
                .associate { it.key to it.value }
            val requiredKeys = pluginEntries
                .filter { (_, plugin) -> plugin.effectiveType(flags) == FomodPluginType.REQUIRED }
                .mapTo(mutableSetOf()) { it.first }
            val next = includedKeys + requiredKeys
            if (next == includedKeys) break
            includedKeys = next.toMutableSet()
        }

        val finalFlags = pluginEntries
            .filter { (key, _) -> key in includedKeys }
            .flatMap { (_, plugin) -> plugin.conditionFlags.entries }
            .associate { it.key to it.value }
        return pluginEntries.mapNotNull { (key, plugin) ->
            val effectiveType = plugin.effectiveType(finalFlags)
            if (effectiveType == FomodPluginType.REQUIRED || (key in selectedPluginKeys && effectiveType != FomodPluginType.NOT_USABLE)) {
                plugin
            } else {
                null
            }
        }
    }

    private fun selectedFiles(
        installer: FomodInstaller,
        selectedPlugins: List<FomodPlugin>,
    ): List<FomodFileMapping> {
        val flags = linkedMapOf<String, String>()
        selectedPlugins.forEach { plugin ->
            plugin.conditionFlags.forEach { (name, value) -> flags[name] = value }
        }
        val conditionalFiles = installer.conditionalFileInstalls
            .filter { it.dependencies.matches(flags) }
            .flatMap { it.files }
        return installer.requiredFiles + selectedPlugins.flatMap { it.files } + conditionalFiles
    }

    private fun generateFromFiles(
        installId: String,
        basePath: String,
        selectedFiles: List<FomodFileMapping>,
        targetRoot: String,
        targetRelativePath: String,
        mode: String,
    ): FomodRecipeGenerationResult {
        val recipes = mutableListOf<ModPlacementRecipe>()
        val unsupported = mutableListOf<FomodFileMapping>()
        selectedFiles
            .sortedWith(compareBy<FomodFileMapping> { it.priority }.thenBy { it.source })
            .forEach { mapping ->
                val destination = mapping.destination.trim('/')
                if (mapping.directory || destination.isBlank()) {
                    recipes += ModPlacementRecipe(
                        installId = installId,
                        sourceSubpath = joinPath(basePath, mapping.source),
                        targetRoot = targetRoot,
                        targetRelativePath = joinPath(targetRelativePath, destination),
                        mode = mode,
                        includeSourceDirectory = false,
                    )
                    return@forEach
                }

                val sourceName = mapping.source.substringAfterLast('/')
                val destinationName = destination.substringAfterLast('/')
                if (!sourceName.equals(destinationName, ignoreCase = true)) {
                    unsupported += mapping
                    return@forEach
                }
                recipes += ModPlacementRecipe(
                    installId = installId,
                    sourceSubpath = joinPath(basePath, mapping.source),
                    targetRoot = targetRoot,
                    targetRelativePath = joinPath(targetRelativePath, destination.substringBeforeLast('/', missingDelimiterValue = "")),
                    mode = mode,
                    includeSourceDirectory = false,
                )
            }

        return FomodRecipeGenerationResult(
            recipes = recipes.distinctBy { Triple(it.sourceSubpath, it.targetRoot, it.targetRelativePath) },
            unsupportedMappings = unsupported,
        )
    }

    private fun joinPath(left: String, right: String): String =
        listOf(left, right)
            .map { it.trim().trim('/', '\\') }
            .filter { it.isNotBlank() }
            .joinToString("/")
}

private fun unsupportedFeatureWarnings(
    root: Element,
    conditionalFileInstalls: List<FomodConditionalFileInstall>,
    steps: List<FomodStep>,
): List<String> =
    buildList {
        val hasConditionalFileInstalls = root.getElementsByTagName("conditionalFileInstalls").length > 0
        val unsupportedConditionalRules = conditionalFileInstalls.sumOf { it.dependencies.unsupportedCount() }
        val unsupportedTypeRules = steps
            .flatMap { it.groups }
            .flatMap { it.plugins }
            .flatMap { it.typePatterns }
            .sumOf { it.dependencies.unsupportedCount() }
        if (hasConditionalFileInstalls && conditionalFileInstalls.isEmpty()) {
            add("Conditional FOMOD file installs need manual placement")
        }
        if (unsupportedConditionalRules > 0) {
            add("Some conditional FOMOD rules need manual placement")
        }
        if (unsupportedTypeRules > 0) {
            add("Some FOMOD option availability rules need manual review")
        }
        if (root.getElementsByTagName("moduleDependencies").length > 0) {
            add("FOMOD module dependency rules need manual placement")
        }
        if (root.getElementsByTagName("fileDependency").length > 0) {
            add("Some FOMOD file dependency rules need manual placement")
        }
    }.distinct()

private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

private fun Element.firstChildElement(tagName: String): Element? =
    childElements(tagName).firstOrNull()

private fun Element.childElements(tagName: String? = null): List<Element> =
    buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if (tagName == null || element.tagName.equals(tagName, ignoreCase = true)) {
                    add(element)
                }
            }
        }
    }
