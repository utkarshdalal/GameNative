package app.gamenative.utils

import android.text.Html
import app.gamenative.Constants
import java.text.Normalizer

private val REGEX_UNACCENT = "\\p{M}+".toRegex()


/**
 * Extension functions relating to [String] as the receiver type.
 */

fun String.getAvatarURL(): String =
    this.ifEmpty { null }
        ?.takeIf { str -> str.isNotEmpty() && !str.all { it == '0' } }
        ?.let { "${Constants.Persona.AVATAR_BASE_URL}${it.substring(0, 2)}/${it}_full.jpg" }
        ?: Constants.Persona.MISSING_AVATAR_URL

fun String.fromHtml(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()

fun CharSequence.unaccent(): String {
    val temp = Normalizer.normalize(this, Normalizer.Form.NFKD)
    return REGEX_UNACCENT.replace(temp, "")
}

// This doesn't belong here, but i'm tired.
fun Long.getProfileUrl(): String = "${Constants.Persona.PROFILE_URL}$this/"
