package app.gamenative.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.gamenative.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

// Define Bricolage Grotesque font family
val BricolageGrotesque = FontFamily(
    Font(R.font.bricolage_grotesque_light, FontWeight.Light),
    Font(R.font.bricolage_grotesque_regular, FontWeight.Normal),
    Font(R.font.bricolage_grotesque_medium, FontWeight.Medium),
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)

// Custom typography using Bricolage Grotesque
val PluviaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

fun Typography.withShadow(shadow: Shadow): Typography = copy(
    displayLarge = displayLarge.copy(shadow = shadow),
    displayMedium = displayMedium.copy(shadow = shadow),
    displaySmall = displaySmall.copy(shadow = shadow),
    headlineLarge = headlineLarge.copy(shadow = shadow),
    headlineMedium = headlineMedium.copy(shadow = shadow),
    headlineSmall = headlineSmall.copy(shadow = shadow),
    titleLarge = titleLarge.copy(shadow = shadow),
    titleMedium = titleMedium.copy(shadow = shadow),
    titleSmall = titleSmall.copy(shadow = shadow),
    bodyLarge = bodyLarge.copy(shadow = shadow),
    bodyMedium = bodyMedium.copy(shadow = shadow),
    bodySmall = bodySmall.copy(shadow = shadow),
    labelLarge = labelLarge.copy(shadow = shadow),
    labelMedium = labelMedium.copy(shadow = shadow),
    labelSmall = labelSmall.copy(shadow = shadow),
)

/** [this] with its size and line height multiplied by [factor]. */
fun TextStyle.scaled(factor: Float): TextStyle {
    if (factor == 1f) return this
    return copy(
        fontSize = if (fontSize.isSp) (fontSize.value * factor).sp else fontSize,
        lineHeight = if (lineHeight.isSp) (lineHeight.value * factor).sp else lineHeight,
    )
}

/** Every style scaled by [scale] and given [letterSpacingEm] of extra letter spacing (on top of its own). */
fun Typography.themed(scale: Float, letterSpacingEm: Float): Typography {
    fun TextStyle.t(): TextStyle {
        val s = scaled(scale)
        if (letterSpacingEm == 0f || !s.fontSize.isSp) return s
        val own = if (s.letterSpacing.isSp) s.letterSpacing.value else 0f
        return s.copy(letterSpacing = (own + s.fontSize.value * letterSpacingEm).sp)
    }
    return copy(
        displayLarge = displayLarge.t(),
        displayMedium = displayMedium.t(),
        displaySmall = displaySmall.t(),
        headlineLarge = headlineLarge.t(),
        headlineMedium = headlineMedium.t(),
        headlineSmall = headlineSmall.t(),
        titleLarge = titleLarge.t(),
        titleMedium = titleMedium.t(),
        titleSmall = titleSmall.t(),
        bodyLarge = bodyLarge.t(),
        bodyMedium = bodyMedium.t(),
        bodySmall = bodySmall.t(),
        labelLarge = labelLarge.t(),
        labelMedium = labelMedium.t(),
        labelSmall = labelSmall.t(),
    )
}

private object ThemeFontCache {
    private var key: String? = null
    private var family: FontFamily? = null

    @Synchronized
    fun get(key: String): FontFamily? = if (this.key == key) family else null

    /** Last family loaded for [path], without touching the disk. */
    @Synchronized
    fun peek(path: String): FontFamily? = if (key?.startsWith("$path:") == true) family else null

    @Synchronized
    fun put(key: String, family: FontFamily?) {
        this.key = key
        this.family = family
    }

    fun cacheKey(file: File): String = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
}

/**
 * Compose loads file fonts lazily at text layout and throws on a bad file, so the file is parsed
 * here first and only a font Android can read is handed to Compose.
 */
private fun loadThemeFont(path: String): FontFamily? {
    val file = File(path)
    if (!file.isFile) return null
    val key = ThemeFontCache.cacheKey(file)
    ThemeFontCache.get(key)?.let { return it }
    val family = try {
        val typeface = Typeface.createFromFile(file)
        if (typeface == null || typeface == Typeface.DEFAULT) null else FontFamily(Font(file))
    } catch (e: Exception) {
        Timber.w(e, "Theme font unreadable: %s", path)
        null
    }
    ThemeFontCache.put(key, family)
    return family
}

/** [PluviaTypography] in the theme font when [fontFile] is a readable font, else the bundled family. */
@Composable
fun rememberThemeTypography(fontFile: String?): Typography =
    if (fontFile == null) PluviaTypography else rememberFileTypography(fontFile)

@Composable
private fun rememberFileTypography(fontFile: String): Typography {
    val family by produceState(ThemeFontCache.peek(fontFile), fontFile) {
        value = withContext(Dispatchers.IO) { loadThemeFont(fontFile) }
    }
    return remember(family) { family?.let { PluviaTypography.withFontFamily(it) } ?: PluviaTypography }
}
