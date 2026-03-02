package app.gamenative.theme.runtime

import app.gamenative.theme.model.Breakpoint

/**
 * Centralized variable resolution for the theme engine.
 * 
 * Resolves variable references (e.g., @{vars.cornerRadius}) to their actual values,
 * taking into account breakpoints for orientation-aware theming.
 * 
 * This class is used at both:
 * - Map-time: When converting ThemeTree to ThemeDefinition
 * - Render-time: When resolving any remaining bindings
 */
object VariableResolver {
    
    private const val VAR_PREFIX = "@{vars."
    private const val BINDING_PREFIX = "@{"
    private const val BINDING_SUFFIX = "}"
    
    /**
     * Resolve all matching breakpoints and merge their variables with base variables.
     * Later breakpoints override earlier ones (CSS cascade behavior).
     * 
     * @param baseVariables Default variable values from theme
     * @param breakpoints List of breakpoints that may override variables
     * @param isPortrait True if current orientation is portrait
     * @param screenWidthDp Current screen width in dp
     * @return Map of resolved variable names to values
     */
    fun resolveWithBreakpoints(
        baseVariables: Map<String, String>,
        breakpoints: List<Breakpoint>,
        isPortrait: Boolean,
        screenWidthDp: Int
    ): Map<String, String> {
        return buildMap {
            // Start with base variables
            putAll(baseVariables)
            
            // Apply matching breakpoints in order (cascade)
            breakpoints
                .filter { it.matches(isPortrait, screenWidthDp) }
                .forEach { breakpoint ->
                    putAll(breakpoint.variables)
                }
        }
    }
    
    /**
     * Check if a string is a variable binding (e.g., @{vars.cornerRadius}).
     */
    fun isVariableBinding(value: String): Boolean {
        return value.startsWith(VAR_PREFIX) && value.endsWith(BINDING_SUFFIX)
    }
    
    /**
     * Check if a string is any kind of binding (e.g., @{vars.x} or @{game.title}).
     */
    fun isBinding(value: String): Boolean {
        return value.startsWith(BINDING_PREFIX) && value.endsWith(BINDING_SUFFIX)
    }
    
    /**
     * Extract the binding path from a binding string.
     * E.g., "@{vars.cornerRadius}" -> "vars.cornerRadius"
     */
    fun getBindingPath(value: String): String {
        return value.removePrefix(BINDING_PREFIX).removeSuffix(BINDING_SUFFIX)
    }
    
    /**
     * Extract the variable name from a variable binding.
     * E.g., "@{vars.cornerRadius}" -> "cornerRadius"
     */
    fun getVariableName(value: String): String? {
        if (!isVariableBinding(value)) return null
        val path = getBindingPath(value)
        return path.removePrefix("vars.")
    }
    
    // Regex to find all @{vars.xxx} patterns
    private val VAR_PATTERN = Regex("""@\{vars\.([^}]+)\}""")
    
    /**
     * Resolve a single value that may be a variable reference.
     * 
     * @param value The value to resolve (may be literal or @{vars.name} reference)
     * @param variables Map of resolved variables
     * @return The resolved value, or null if variable not found
     */
    fun resolveValue(value: String?, variables: Map<String, String>): String? {
        if (value == null) return null
        
        if (isVariableBinding(value)) {
            val varName = getVariableName(value) ?: return null
            return variables[varName]
        }
        
        return value
    }
    
    /**
     * Resolve ALL variable references within a string.
     * 
     * Unlike [resolveValue] which only works when the entire string is a single variable binding,
     * this function finds and replaces all @{vars.xxx} patterns within the string.
     * 
     * Example: "@{vars.radius} @{vars.radius} 0 0" -> "20 20 0 0"
     * 
     * @param value The string potentially containing variable references
     * @param variables Map of resolved variables
     * @return The string with all variable references replaced, or null if input is null
     */
    fun resolveAllVariables(value: String?, variables: Map<String, String>): String? {
        if (value == null) return null
        
        // If entire string is a single variable binding, use simple resolution
        if (isVariableBinding(value)) {
            return resolveValue(value, variables)
        }
        
        // Find and replace all @{vars.xxx} patterns
        return VAR_PATTERN.replace(value) { match ->
            val varName = match.groupValues[1]
            variables[varName] ?: match.value // Keep original if not found
        }
    }
    
    /**
     * Resolve a value with a default fallback.
     */
    fun resolveValue(value: String?, variables: Map<String, String>, default: String): String {
        return resolveValue(value, variables) ?: default
    }
    
    /**
     * Resolve a float value that may be a variable reference.
     */
    fun resolveFloat(value: String?, variables: Map<String, String>, default: Float): Float {
        val resolved = resolveValue(value, variables) ?: return default
        return resolved.toFloatOrNull() ?: default
    }
    
    /**
     * Resolve an optional float value.
     */
    fun resolveFloatOrNull(value: String?, variables: Map<String, String>): Float? {
        val resolved = resolveValue(value, variables) ?: return null
        return resolved.toFloatOrNull()
    }
    
    /**
     * Resolve an int value that may be a variable reference.
     */
    fun resolveInt(value: String?, variables: Map<String, String>, default: Int): Int {
        val resolved = resolveValue(value, variables) ?: return default
        return resolved.toIntOrNull() ?: default
    }
    
    /**
     * Resolve an optional int value.
     */
    fun resolveIntOrNull(value: String?, variables: Map<String, String>): Int? {
        val resolved = resolveValue(value, variables) ?: return null
        return resolved.toIntOrNull()
    }
    
    /**
     * Resolve a color value that may be a variable reference.
     * Supports hex colors (#RRGGBB, #AARRGGBB, 0xRRGGBB, 0xAARRGGBB).
     */
    fun resolveColor(value: String?, variables: Map<String, String>, default: Int): Int {
        val resolved = resolveValue(value, variables) ?: return default
        return parseColor(resolved) ?: default
    }
    
    /**
     * Resolve an optional color value.
     */
    fun resolveColorOrNull(value: String?, variables: Map<String, String>): Int? {
        val resolved = resolveValue(value, variables) ?: return null
        return parseColor(resolved)
    }
    
    /**
     * Parse a color string to Int.
     * Supports #RRGGBB, #AARRGGBB, 0xRRGGBB, 0xAARRGGBB formats.
     */
    private fun parseColor(value: String): Int? {
        var v = value.trim()
        val isHex = v.startsWith("#") || v.lowercase().startsWith("0x")
        if (!isHex) return v.toLongOrNull()?.toInt()
        
        v = v.removePrefix("#").removePrefix("0x").removePrefix("0X")
        val parsed = v.toLongOrNull(16) ?: return null
        return if (v.length <= 6) {
            // RRGGBB -> assume opaque
            (0xFF000000 or parsed).toInt()
        } else {
            parsed.toInt() // AARRGGBB
        }
    }
}

