package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import timber.log.Timber

object SteamControllerVdfUtils {
    private val keymapDigital = mapOf(
        "button_a" to "A",
        "button_b" to "B",
        "button_x" to "X",
        "button_y" to "Y",
        "dpad_north" to "DUP",
        "dpad_south" to "DDOWN",
        "dpad_east" to "DRIGHT",
        "dpad_west" to "DLEFT",
        "button_escape" to "START",
        "button_menu" to "BACK",
        "left_bumper" to "LBUMPER",
        "right_bumper" to "RBUMPER",
        "button_back_left" to "A",
        "button_back_right" to "X",
        "button_back_left_upper" to "B",
        "button_back_right_upper" to "Y",
    )

    fun generateControllerConfig(controllerVdfText: String, outputDir: Path) {
        val root = VdfParser(controllerVdfText).parse()
        val controllerMappings = root.getObject("controller_mappings") ?: return

        val groupsById = LinkedHashMap<String, VdfObject>()
        controllerMappings.getObjects("group").forEach { group ->
            group.getString("id")?.let { groupsById[it] = group }
        }

        val actionList = mutableListOf<String>()
        controllerMappings.getObjects("actions").forEach { actions ->
            actionList.addAll(actions.keys())
        }

        val presets = controllerMappings.getObjects("preset")
        val presetsByName = presets.mapNotNull { preset ->
            preset.getString("name")?.let { name -> name to preset }
        }.toMap()
        val allBindings = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()

        for (preset in presets) {
            val name = preset.getString("name") ?: continue
            if (!actionList.contains(name) && name.lowercase() != "default") continue

            val bindings = buildPresetBindings(name, preset, groupsById)
            allBindings[name] = bindings
        }

        controllerMappings.getObject("action_layers")?.keys()?.forEach { layerName ->
            val preset = presetsByName[layerName]
            if (preset == null) {
                Timber.tag("SteamControllerVdf").d("Missing preset for action layer $layerName")
                return@forEach
            }
            val bindings = buildPresetBindings(layerName, preset, groupsById)
            allBindings[layerName] = bindings
        }

        if (allBindings.isEmpty()) return

        Files.createDirectories(outputDir)
        for ((presetName, bindings) in allBindings) {
            val outputFile = outputDir.resolve("$presetName.txt")
            val content = buildString {
                for ((actionName, actionBindings) in bindings) {
                    append(actionName)
                    append("=")
                    appendLine(actionBindings.joinToString(","))
                }
            }
            outputFile.toFile().writeText(content, Charsets.UTF_8)
        }
    }

    private fun addInputBindings(
        group: VdfObject,
        bindings: MutableMap<String, MutableList<String>>,
        forceBinding: String? = null,
        keymap: Map<String, String> = keymapDigital,
    ) {
        val inputs = group.getObject("inputs") ?: return
        for ((inputName, inputValue) in inputs.objectEntries()) {
            for (activator in inputValue.objectValues()) {
                for (fullPress in activator.objectValues()) {
                    for (bindingGroup in fullPress.objectValues()) {
                        for ((bindingKey, bindingValue) in bindingGroup.stringEntries()) {
                            if (!bindingKey.equals("binding", ignoreCase = true)) continue
                            val tokens = bindingValue.split(Regex("\\s+"))
                            if (tokens.isEmpty()) continue

                            val actionName = when (tokens[0].lowercase()) {
                                "game_action" -> tokens.getOrNull(2)?.trimEnd(',')
                                "xinput_button" -> tokens.getOrNull(1)?.trimEnd(',')
                                else -> null
                            }

                            if (actionName.isNullOrEmpty()) continue

                            val binding = forceBinding ?: keymap[inputName.lowercase()]
                            if (binding.isNullOrEmpty()) {
                                Timber.tag("SteamControllerVdf").d("Missing keymap for $inputName")
                                continue
                            }

                            val list = bindings.getOrPut(actionName) { mutableListOf() }
                            if (!list.contains(binding)) {
                                list.add(binding)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addActionBinding(
        bindings: MutableMap<String, MutableList<String>>,
        actionName: String,
        binding: String,
        bindingSuffix: String,
    ) {
        val list = bindings.getOrPut(actionName) { mutableListOf() }
        val bindingWithSuffix = "$binding=$bindingSuffix"
        if (!list.contains(binding) && !list.contains(bindingWithSuffix)) {
            if (list.isEmpty()) {
                list.add(bindingWithSuffix)
            } else {
                list.add(0, binding)
            }
        }
    }

    private fun buildPresetBindings(
        presetName: String,
        preset: VdfObject,
        groupsById: Map<String, VdfObject>,
    ): LinkedHashMap<String, MutableList<String>> {
        val groupBindings = preset.getObject("group_source_bindings") ?: return LinkedHashMap()
        val bindings = LinkedHashMap<String, MutableList<String>>()

        for ((groupId, groupBinding) in groupBindings.stringEntries()) {
            val tokens = groupBinding.split(Regex("\\s+"))
            if (tokens.size < 2 || tokens[1].lowercase() != "active") continue

            val group = groupsById[groupId] ?: continue
            val groupMode = group.getString("mode")?.lowercase().orEmpty()
            val bindingType = tokens[0].lowercase()

            if (bindingType in listOf("switch", "button_diamond", "dpad")) {
                addInputBindings(group, bindings)
            }

            if (bindingType in listOf("left_trigger", "right_trigger")) {
                if (groupMode == "trigger") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = if (bindingType == "left_trigger") "LTRIGGER" else "RTRIGGER"
                        addActionBinding(bindings, actionName, binding, bindingSuffix = "trigger")
                    }
                    val forceBinding = if (bindingType == "left_trigger") "DLTRIGGER" else "DRTRIGGER"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                } else {
                    Timber.tag("SteamControllerVdf").d("Unhandled trigger mode: $groupMode")
                }
            }

            if (bindingType in listOf("joystick", "right_joystick", "dpad")) {
                if (groupMode == "joystick_move") {
                    val actionName = group.getObject("gameactions")?.getString(presetName)
                    if (!actionName.isNullOrEmpty()) {
                        val binding = when (bindingType) {
                            "joystick" -> "LJOY"
                            "right_joystick" -> "RJOY"
                            "dpad" -> "DPAD"
                            else -> ""
                        }
                        if (binding.isNotEmpty()) {
                            addActionBinding(bindings, actionName, binding, bindingSuffix = "joystick_move")
                        }
                    }
                    val forceBinding = if (bindingType == "joystick") "LSTICK" else "RSTICK"
                    addInputBindings(group, bindings, forceBinding = forceBinding)
                } else if (groupMode == "dpad") {
                    if (bindingType == "joystick") {
                        val bindingMap = mapOf(
                            "dpad_north" to "DLJOYUP",
                            "dpad_south" to "DLJOYDOWN",
                            "dpad_west" to "DLJOYLEFT",
                            "dpad_east" to "DLJOYRIGHT",
                            "click" to "LSTICK",
                        )
                        addInputBindings(group, bindings, keymap = bindingMap)
                    } else if (bindingType == "right_joystick") {
                        val bindingMap = mapOf(
                            "dpad_north" to "DRJOYUP",
                            "dpad_south" to "DRJOYDOWN",
                            "dpad_west" to "DRJOYLEFT",
                            "dpad_east" to "DRJOYRIGHT",
                            "click" to "RSTICK",
                        )
                        addInputBindings(group, bindings, keymap = bindingMap)
                    }
                }
            }
        }

        return bindings
    }
}

private sealed interface VdfValue

private data class VdfEntry(val key: String, val value: VdfValue)

private data class VdfString(val value: String) : VdfValue

private class VdfObject : VdfValue {
    private val entries = mutableListOf<VdfEntry>()

    fun add(key: String, value: VdfValue) {
        entries.add(VdfEntry(key, value))
    }

    fun getObject(key: String): VdfObject? = getObjects(key).firstOrNull()

    fun getObjects(key: String): List<VdfObject> = entries.mapNotNull {
        if (it.key == key && it.value is VdfObject) it.value else null
    }

    fun getString(key: String): String? = getStrings(key).firstOrNull()

    fun getStrings(key: String): List<String> = entries.mapNotNull {
        if (it.key == key && it.value is VdfString) it.value.value else null
    }

    fun objectEntries(): List<Pair<String, VdfObject>> = entries.mapNotNull {
        if (it.value is VdfObject) it.key to it.value else null
    }

    fun objectValues(): List<VdfObject> = entries.mapNotNull { it.value as? VdfObject }

    fun stringEntries(): List<Pair<String, String>> = entries.mapNotNull {
        if (it.value is VdfString) it.key to it.value.value else null
    }

    fun keys(): List<String> = entries.map { it.key }
}

private class VdfParser(text: String) {
    private val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
    private var index = 0

    fun parse(): VdfObject = parseObject()

    private fun parseObject(): VdfObject {
        val obj = VdfObject()
        while (true) {
            val token = nextToken() ?: break
            if (token == "}") break
            val key = token
            val valueToken = nextToken() ?: break
            if (valueToken == "{") {
                obj.add(key, parseObject())
            } else if (valueToken == "}") {
                break
            } else {
                obj.add(key, VdfString(valueToken))
            }
        }
        return obj
    }

    private fun nextToken(): String? {
        skipWhitespaceAndComments()
        if (index >= source.length) return null
        return when (val ch = source[index]) {
            '{', '}' -> {
                index++
                ch.toString()
            }
            '"' -> parseQuoted()
            else -> parseUnquoted()
        }
    }

    private fun parseQuoted(): String {
        index++ // skip opening quote
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '"') break
            if (ch == '\\' && index < source.length) {
                val escaped = source[index++]
                sb.append(unescapeChar(escaped))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseUnquoted(): String {
        val start = index
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace() || ch == '{' || ch == '}') break
            index++
        }
        return unescape(source.substring(start, index))
    }

    private fun skipWhitespaceAndComments() {
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace()) {
                index++
                continue
            }
            if (ch == '/' && index + 1 < source.length && source[index + 1] == '/') {
                index += 2
                while (index < source.length && source[index] != '\n') index++
                continue
            }
            break
        }
    }

    private fun unescapeChar(ch: Char): Char = when (ch) {
        'n' -> '\n'
        't' -> '\t'
        'v' -> '\u000B'
        'b' -> '\b'
        'r' -> '\r'
        'f' -> '\u000C'
        'a' -> '\u0007'
        '\\' -> '\\'
        '?' -> '?'
        '"' -> '"'
        '\'' -> '\''
        else -> ch
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                sb.append(unescapeChar(next))
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
