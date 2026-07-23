package app.gamenative.utils

import android.text.Html
import app.gamenative.Constants
import java.text.Normalizer

private val REGEX_UNACCENT = "\\p{M}+".toRegex()
private val REGEX_FILENAME_UNSAFE = Regex("[^a-zA-Z0-9_-]")
private val REGEX_NON_ALPHANUMERIC = Regex("[^a-zA-Z0-9]")

/**
 * Normalizes a game title for comparison across different stores.
 * Removes accents, converts to lowercase, strips non-alphanumeric characters, and trims.
 * For example, "The Witcher® 3" and "the-witcher-3" will both normalize to "thewitcher3".
 *
 * @return The normalized string.
 */
fun CharSequence.normalizeForComparison(): String {
    return this.unaccent()
        .lowercase()
        .replace(REGEX_NON_ALPHANUMERIC, "")
        .trim()
}

/**
 * Extension functions relating to [String] as the receiver type.
 */

/**
 * Gets the avatar URL for a Steam persona.
 *
 * @return The avatar URL or a default missing avatar URL.
 */
fun String.getAvatarURL(): String =
    this.ifEmpty { null }
        ?.takeIf { str -> str.isNotEmpty() && !str.all { it == '0' } }
        ?.let { "${Constants.Persona.AVATAR_BASE_URL}${it.substring(0, 2)}/${it}_full.jpg" }
        ?: Constants.Persona.MISSING_AVATAR_URL

/**
 * Converts a string containing HTML entities to a plain string.
 *
 * @return The decoded string.
 */
fun String.fromHtml(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()

/**
 * Removes accents and diacritics from a string using NFKD normalization.
 *
 * @return The unaccented string.
 */
fun CharSequence.unaccent(): String {
    val temp = Normalizer.normalize(this, Normalizer.Form.NFKD)
    return REGEX_UNACCENT.replace(temp, "")
}

/**
 * Replaces any character that isn't ASCII alphanumeric, underscore, or hyphen with an
 * underscore. Intended for turning identifiers (app names, namespaces, catalog ids)
 * into safe filename components.
 */
fun String.sanitizeForFilename(): String = REGEX_FILENAME_UNSAFE.replace(this, "_")
