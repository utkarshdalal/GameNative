package app.gamenative.service.ubisoft

/**
 * A product's configuration, the YAML document the ownership service sends with each owned game:
 * display name, artwork, how the launcher starts the game, and whether another store runs it.
 */
class UbisoftProductConfig(
    val name: String?,
    val thumbImage: String?,
    val logoImage: String?,
    val backgroundImage: String?,
    val iconImage: String?,
    /** The online executables, else the offline ones; the first is the game. */
    val executables: List<Executable>,
    /** Set when another store starts the game (Steam, for instance); the launcher does not install it. */
    val thirdPartyPlatform: String?,
    val spaceId: String?,
    val appId: String?,
) {
    /** A file location as the launcher writes it: relative to the install folder, or read from a registry value. */
    class Location(val relative: String?, val register: String?, val append: String?)

    class Executable(
        val path: Location,
        val workingDirectory: Location?,
        val arguments: String,
        val argumentsRegister: String?,
        val shortcutName: String?,
    )

    companion object {
        fun parse(yaml: String): UbisoftProductConfig? {
            val doc = UbisoftYaml.parse(yaml) as? Map<*, *> ?: return null
            val root = doc["root"] as? Map<*, *> ?: return null
            val strings = ((doc["localizations"] as? Map<*, *>)?.get("default") as? Map<*, *>).orEmpty()
            fun text(value: Any?): String? {
                val raw = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                return (strings[raw] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: raw
            }
            val startGame = root["start_game"] as? Map<*, *>
            val executables = listOf("online", "offline").firstNotNullOfOrNull { mode ->
                ((startGame?.get(mode) as? Map<*, *>)?.get("executables") as? List<*>)
                    ?.mapNotNull { executable(it as? Map<*, *>) }
                    ?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            val thirdParty = (root["third_party_platform"] as? Map<*, *>)?.get("name") as? String
            return UbisoftProductConfig(
                name = text(root["name"]) ?: text(root["display_name"])
                    ?: text((root["installer"] as? Map<*, *>)?.get("game_identifier")),
                thumbImage = text(root["thumb_image"]),
                logoImage = text(root["logo_image"]),
                backgroundImage = text(root["background_image"]),
                iconImage = text(root["icon_image"]),
                executables = executables,
                thirdPartyPlatform = thirdParty?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (startGame?.keys?.any { it == "steam" || it == "third_party_steam" } == true) "steam" else null,
                spaceId = text(root["space_id"]),
                appId = text(root["app_id"]),
            )
        }

        private fun location(node: Any?): Location? {
            val map = node as? Map<*, *> ?: return null
            val loc = Location(
                relative = (map["relative"] as? String)?.takeIf { it.isNotBlank() },
                register = (map["register"] as? String)?.takeIf { it.isNotBlank() },
                append = (map["append"] as? String)?.takeIf { it.isNotBlank() },
            )
            return loc.takeIf { it.relative != null || it.register != null }
        }

        private fun executable(map: Map<*, *>?): Executable? {
            map ?: return null
            return Executable(
                path = location(map["path"]) ?: return null,
                workingDirectory = location(map["working_directory"]),
                arguments = (map["arguments"] as? String).orEmpty(),
                argumentsRegister = (map["arguments_register"] as? String)?.takeIf { it.isNotBlank() },
                shortcutName = map["shortcut_name"] as? String,
            )
        }
    }
}

/**
 * The block YAML subset product configurations use: nested mappings, sequences, plain and quoted
 * scalars, empty flow collections. Anything else is read as text and never fails the document.
 */
internal object UbisoftYaml {
    private class Line(val indent: Int, val text: String)

    fun parse(yaml: String): Any? {
        val lines = yaml.replace("\t", "  ").split('\n').mapNotNull { raw ->
            val line = raw.trimEnd('\r', ' ')
            val text = line.trimStart(' ')
            if (text.isEmpty() || text.startsWith("#") || text == "---") null else Line(line.length - text.length, text)
        }
        if (lines.isEmpty()) return null
        return Parser(lines).block(lines[0].indent)
    }

    private class Parser(val lines: List<Line>) {
        var pos = 0
        /** A sequence item's inline mapping, read as a line at the column of its first key. */
        var pending: Line? = null

        fun peek(): Line? = pending ?: lines.getOrNull(pos)

        fun next(): Line = pending?.also { pending = null } ?: lines[pos++]

        fun block(indent: Int): Any? {
            val line = peek() ?: return null
            return if (isItem(line.text)) sequence(line.indent) else mapping(indent)
        }

        fun mapping(indent: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                val line = peek() ?: break
                if (line.indent != indent || isItem(line.text)) break
                next()
                val colon = keyColon(line.text)
                if (colon < 0) continue
                val key = unquote(line.text.substring(0, colon).trim())
                val rest = line.text.substring(colon + 1).trim()
                out[key] = when {
                    rest.isEmpty() -> {
                        val child = peek()
                        when {
                            child == null -> null
                            child.indent > indent -> block(child.indent)
                            child.indent == indent && isItem(child.text) -> sequence(indent)
                            else -> null
                        }
                    }
                    rest.startsWith("|") || rest.startsWith(">") -> literal(indent)
                    else -> scalar(rest)
                }
            }
            return out
        }

        fun sequence(indent: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (true) {
                val line = peek() ?: break
                if (line.indent != indent || !isItem(line.text)) break
                next()
                val item = line.text.substring(1).trimStart(' ')
                val column = line.indent + (line.text.length - item.length)
                out += when {
                    item.isEmpty() -> peek()?.takeIf { it.indent > indent }?.let { block(it.indent) }
                    isItem(item) || keyColon(item) >= 0 -> {
                        pending = Line(column, item)
                        block(column)
                    }
                    else -> scalar(item)
                }
            }
            return out
        }

        fun literal(indent: Int): String {
            val parts = ArrayList<String>()
            while (true) {
                val line = peek() ?: break
                if (line.indent <= indent) break
                parts += next().text
            }
            return parts.joinToString("\n")
        }
    }

    private fun isItem(text: String) = text == "-" || text.startsWith("- ")

    /** The colon ending a key: outside quotes, followed by a space or the end of the line. */
    private fun keyColon(text: String): Int {
        var quote = 0.toChar()
        for (i in text.indices) {
            val c = text[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
                continue
            }
            if (i == 0 && (c == '"' || c == '\'')) {
                quote = c
                continue
            }
            if (c == ':' && (i + 1 == text.length || text[i + 1] == ' ')) return i
        }
        return -1
    }

    private fun scalar(raw: String): Any? = when (raw) {
        "{}" -> emptyMap<String, Any?>()
        "[]" -> emptyList<Any?>()
        "~", "null" -> null
        else -> unquote(if (raw.startsWith("\"") || raw.startsWith("'")) raw else raw.substringBefore(" #").trimEnd())
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
            value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        value.length >= 2 && value.startsWith("'") && value.endsWith("'") ->
            value.substring(1, value.length - 1).replace("''", "'")
        else -> value
    }
}

/** How the launcher starts an installed build, resolved from its configuration and the build's registry values. */
class UbisoftGameStart(
    /** Relative to the install folder, forward slashes. */
    val executable: String,
    /** Relative to the install folder; null for the executable's own folder. */
    val workingDirectory: String?,
    val arguments: String,
) {
    /** One registry value the launcher writes at install time; [hive] is HKLM or HKCU. */
    class RegistryWrite(val hive: String, val key: String, val name: String, val text: String?, val number: Long?)

    companion object {
        private const val INSTALL_DIR = "\$INSTALL_DIR"
        /**
         * The one name the launcher fills in launch arguments: the product's application id, from
         * its configuration or else from the ownership service.
         */
        private val APP_ID = Regex("""\{AppId\}""", RegexOption.IGNORE_CASE)
        private val LAUNCHER_INSTALL = Regex("""\\Ubisoft\\Launcher\\Installs\\\d+\\InstallDir$""", RegexOption.IGNORE_CASE)

        fun resolve(
            config: UbisoftProductConfig,
            registry: List<UbisoftManifest.RegistryValue>,
            language: String?,
            ownedAppId: String? = null,
        ): UbisoftGameStart? {
            val exe = config.executables.firstOrNull() ?: return null
            val path = locate(exe.path, registry, language)?.takeIf { it.isNotEmpty() } ?: return null
            val argsFromRegistry = exe.argumentsRegister?.let { value(registry, it, language) }.orEmpty()
            val arguments = listOf(argsFromRegistry, exe.arguments).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            val appId = config.appId ?: ownedAppId?.takeIf { it.isNotBlank() }
            if (appId == null && APP_ID.containsMatchIn(arguments)) {
                throw UbisoftUnsupportedException("the launch arguments need the game's application id, and Ubisoft sent none. Refresh the library and try again")
            }
            return UbisoftGameStart(
                executable = path,
                workingDirectory = exe.workingDirectory?.let { locate(it, registry, language) },
                arguments = if (appId == null) arguments else arguments.replace(APP_ID) { appId },
            )
        }

        /** A relative path inside the install folder, or null when it cannot be told. */
        private fun locate(loc: UbisoftProductConfig.Location, registry: List<UbisoftManifest.RegistryValue>, language: String?): String? {
            val base = when {
                loc.relative != null -> loc.relative
                loc.register != null && LAUNCHER_INSTALL.containsMatchIn(loc.register) -> ""
                loc.register != null -> {
                    val raw = value(registry, loc.register, language) ?: return null
                    if (raw.startsWith(INSTALL_DIR)) raw.removePrefix(INSTALL_DIR) else if (looksAbsolute(raw)) return null else raw
                }
                else -> return null
            }
            val joined = (base + loc.append.orEmpty()).replace('\\', '/').trim('/')
            return joined.takeIf { it.split('/').none { part -> part == ".." } && !looksAbsolute(joined) }
        }

        private fun looksAbsolute(path: String) = path.length >= 2 && path[1] == ':'

        private fun value(registry: List<UbisoftManifest.RegistryValue>, key: String, language: String?): String? {
            val entry = registry.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return null
            return pick(entry.strings, language) ?: pick(entry.numbers, language)?.toString()
        }

        private fun <T> pick(values: Map<String, T>, language: String?): T? =
            language?.let { lang -> values.entries.firstOrNull { it.key.equals(lang, true) }?.value } ?: values[""] ?: values.values.firstOrNull()

        /** The build's registry values for [language], with the install folder filled in as [gameRoot] (`A:\`). */
        fun registryWrites(registry: List<UbisoftManifest.RegistryValue>, language: String?, gameRoot: String): List<RegistryWrite> {
            val root = gameRoot.trimEnd('\\', '/')
            return registry.mapNotNull { entry ->
                val hive = when {
                    entry.key.startsWith("HKEY_LOCAL_MACHINE\\", true) -> "HKLM"
                    entry.key.startsWith("HKEY_CURRENT_USER\\", true) -> "HKCU"
                    else -> return@mapNotNull null
                }
                val path = entry.key.substringAfter('\\')
                val key = path.substringBeforeLast('\\', "")
                val name = path.substringAfterLast('\\')
                if (key.isEmpty()) return@mapNotNull null
                val text = pick(entry.strings, language)
                val number = if (text == null) pick(entry.numbers, language) else null
                if (text == null && number == null) return@mapNotNull null
                RegistryWrite(hive, key, name, text?.let { fillRoot(it, root) }, number)
            }
        }

        private fun fillRoot(value: String, root: String): String {
            if (!value.startsWith(INSTALL_DIR)) return value
            val rest = value.removePrefix(INSTALL_DIR)
            return if (rest.isEmpty()) "$root\\" else root + rest
        }
    }
}
