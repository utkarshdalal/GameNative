package app.gamenative.theme.io

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Locale

/**
 * Resolves `@string/key` references from theme-specific string files and app resources.
 * 
 * Priority order:
 * 1. Theme strings for current device locale (e.g., `strings/da.xml`)
 * 2. Theme strings for fallback (e.g., `strings/default.xml` or `strings/en.xml`)
 * 3. App string resources (`R.string.*`)
 * 4. Return the key name if nothing found
 */
class ThemeStringResolver(
    private val context: Context,
    private val assetManager: AssetManager,
) {
    companion object {
        private const val TAG = "ThemeStringResolver"
        private const val STRING_PREFIX = "@string/"
    }

    // Cache: themePath -> (locale -> strings map)
    private val themeStringCache = mutableMapOf<String, Map<String, Map<String, String>>>()

    /**
     * Check if a string value is a string resource reference.
     */
    fun isStringReference(value: String): Boolean = value.startsWith(STRING_PREFIX)

    /**
     * Resolve a string value. If it starts with @string/, look up the resource.
     * Otherwise return the value as-is.
     */
    fun resolve(value: String, themePath: String?): String {
        if (!isStringReference(value)) return value
        
        val key = value.removePrefix(STRING_PREFIX)
        return resolveKey(key, themePath)
    }

    /**
     * Resolve a string key from theme strings or app resources.
     */
    fun resolveKey(key: String, themePath: String?): String {
        // 1. Try theme strings first
        if (themePath != null) {
            val themeString = getThemeString(key, themePath)
            if (themeString != null) return themeString
        }

        // 2. Try app string resources
        val appString = getAppString(key)
        if (appString != null) return appString

        // 3. Return key as fallback
        Log.w(TAG, "String not found: $key")
        return key
    }

    /**
     * Get a string from theme's strings folder.
     */
    private fun getThemeString(key: String, themePath: String): String? {
        val strings = loadThemeStrings(themePath)
        val locale = Locale.getDefault().language // e.g., "da", "en", "de"

        // Try current locale first
        strings[locale]?.get(key)?.let { return it }

        // Try default.xml or en.xml as fallback
        strings["default"]?.get(key)?.let { return it }
        strings["en"]?.get(key)?.let { return it }

        return null
    }

    /**
     * Get a string from app resources by name.
     */
    private fun getAppString(key: String): String? {
        return try {
            val resId = context.resources.getIdentifier(key, "string", context.packageName)
            if (resId != 0) context.getString(resId) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app string: $key", e)
            null
        }
    }

    /**
     * Load all string files from a theme's strings/ folder.
     * Returns a map of locale code -> (key -> value) map.
     */
    private fun loadThemeStrings(themePath: String): Map<String, Map<String, String>> {
        // Return cached if available
        themeStringCache[themePath]?.let { return it }

        val result = mutableMapOf<String, Map<String, String>>()
        val stringsPath = "$themePath/strings"

        try {
            val files = assetManager.list(stringsPath) ?: emptyArray()
            for (file in files) {
                if (file.endsWith(".xml")) {
                    val locale = file.removeSuffix(".xml") // "da", "en", "default"
                    val strings = parseStringFile("$stringsPath/$file")
                    if (strings.isNotEmpty()) {
                        result[locale] = strings
                    }
                }
            }
        } catch (e: Exception) {
            // No strings folder or error reading - that's fine
            Log.d(TAG, "No strings folder for theme: $themePath")
        }

        themeStringCache[themePath] = result
        return result
    }

    /**
     * Parse a string XML file.
     * Format: <strings><string name="key">Value</string></strings>
     */
    private fun parseStringFile(path: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        try {
            val inputStream: InputStream = assetManager.open(path)
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var currentName: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "string") {
                            currentName = parser.getAttributeValue(null, "name")
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentName != null) {
                            result[currentName] = parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "string") {
                            currentName = null
                        }
                    }
                }
                eventType = parser.next()
            }
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing string file: $path", e)
        }

        return result
    }

    /**
     * Clear the cache for a specific theme or all themes.
     */
    fun clearCache(themePath: String? = null) {
        if (themePath != null) {
            themeStringCache.remove(themePath)
        } else {
            themeStringCache.clear()
        }
    }
}

