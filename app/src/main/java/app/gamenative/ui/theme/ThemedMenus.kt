package app.gamenative.ui.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import app.gamenative.theme.ThemeFill
import app.gamenative.theme.ThemeMenu

/*
 * Menus, dialogs and side panels with the theme's menu and panel look: corner radius, gradient fill, border, glow,
 * backdrop blur and the focused item fill. Without those theme values every function here calls the Material
 * component with the caller's arguments unchanged.
 */

/** The popup properties Material's DropdownMenu uses by default. */
@PublishedApi
internal val DefaultThemedMenuProperties = PopupProperties(focusable = true)

/** [DropdownMenu] with the theme menu shape, fill, border and glow. */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ThemedDropdownMenu(
    expanded: Boolean,
    noinline onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = DefaultThemedMenuProperties,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    noinline content: @Composable ColumnScope.() -> Unit,
) {
    val menu = PluviaTheme.spec.menuStyle
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (menu == null) modifier else modifier.then(themedMenuContent(menu, shape)),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = if (menu == null) shape else PluviaTheme.menuShape(shape),
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = if (menu == null) border else PluviaTheme.menuBorder ?: border,
        content = content,
    )
}

/** [ExposedDropdownMenuBoxScope.ExposedDropdownMenu] with the theme menu shape, fill, border and glow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ExposedDropdownMenuBoxScope.ThemedExposedDropdownMenu(
    expanded: Boolean,
    noinline onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchTextFieldWidth: Boolean = true,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    noinline content: @Composable ColumnScope.() -> Unit,
) {
    val menu = PluviaTheme.spec.menuStyle
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (menu == null) modifier else modifier.then(themedMenuContent(menu, shape)),
        scrollState = scrollState,
        matchTextFieldWidth = matchTextFieldWidth,
        shape = if (menu == null) shape else PluviaTheme.menuShape(shape),
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = if (menu == null) border else PluviaTheme.menuBorder ?: border,
        content = content,
    )
}

/**
 * Fill layers and glow drawn on a menu's content column, inside the menu surface. A popup window ends at the menu
 * edge, so the glow shows only on its inner side.
 */
@PublishedApi
@Composable
@ReadOnlyComposable
internal fun themedMenuContent(menu: ThemeMenu, default: Shape): Modifier {
    val shape = PluviaTheme.menuShape(default)
    return Modifier
        .themeFill(menu.fill, shape, menuFillAlpha(menu))
        .menuGlow(menuGlowColor(menu), PluviaTheme.menuGlow, shape)
}

/** [DropdownMenuItem] whose focused state uses the theme's focused menu item fill and text. */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ThemedDropdownMenuItem(
    noinline text: @Composable () -> Unit,
    noinline onClick: () -> Unit,
    modifier: Modifier = Modifier,
    noinline leadingIcon: @Composable (() -> Unit)? = null,
    noinline trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    val focusFill = PluviaTheme.spec.menuStyle?.focusFill
    if (focusFill == null) {
        DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
        )
    } else {
        FocusFilledMenuItem(Color(focusFill), text, onClick, modifier, leadingIcon, trailingIcon, enabled, colors, contentPadding, interactionSource)
    }
}

@PublishedApi
@Composable
internal fun FocusFilledMenuItem(
    fill: Color,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    enabled: Boolean,
    colors: MenuItemColors,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val on = PluviaTheme.onMenuFocusColor(colors.textColor)
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = MENU_ITEM_INSET)
            .background(if (focused) fill else Color.Transparent, PluviaTheme.shape(MENU_ITEM_RADIUS)),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = if (focused) colors.copy(textColor = on, leadingIconColor = on, trailingIconColor = on) else colors,
        contentPadding = contentPadding,
        interactionSource = source,
    )
}

/** [AlertDialog] with the theme menu shape, fill, border, glow and backdrop blur. */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ThemedAlertDialog(
    noinline onDismissRequest: () -> Unit,
    noinline confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    noinline dismissButton: @Composable (() -> Unit)? = null,
    noinline icon: @Composable (() -> Unit)? = null,
    noinline title: @Composable (() -> Unit)? = null,
    noinline text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val menu = PluviaTheme.spec.menuStyle
    if (menu == null) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties,
        )
    } else {
        MenuStyledAlertDialog(
            menu, onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text, shape, containerColor,
            iconContentColor, titleContentColor, textContentColor, tonalElevation, properties,
        )
    }
}

@PublishedApi
@Composable
internal fun MenuStyledAlertDialog(
    menu: ThemeMenu,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: @Composable (() -> Unit)?,
    icon: @Composable (() -> Unit)?,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    shape: Shape,
    containerColor: Color,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color,
    tonalElevation: Dp,
    properties: DialogProperties,
) {
    val themedShape = PluviaTheme.menuShape(shape)
    val fill = menu.fill?.takeIf { !it.isEmpty }
    // The dialog surface paints over its modifier, so with a fill the container colour moves to the modifier too.
    val decoration = Modifier
        .then(if (fill != null) Modifier.background(containerColor, themedShape) else Modifier)
        .themeFill(fill, themedShape, menuFillAlpha(menu))
        .then(PluviaTheme.menuBorder?.let { Modifier.border(it, themedShape) } ?: Modifier)
        .menuGlow(menuGlowColor(menu), PluviaTheme.menuGlow, themedShape)
    val blur = PluviaTheme.menuBlur
    val confirm: @Composable () -> Unit = if (blur == null) {
        confirmButton
    } else {
        remember(blur, confirmButton) {
            @Composable {
                DialogBackdropBlur(blur)
                confirmButton()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirm,
        modifier = modifier.then(decoration),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = themedShape,
        containerColor = if (fill != null) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * [Surface] for the panel a custom [androidx.compose.ui.window.Dialog] draws itself, with the theme menu color,
 * shape, fill, border, glow and backdrop blur. Without a theme menu style it is the plain [Surface].
 */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun ThemedDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    noinline content: @Composable () -> Unit,
) {
    val menu = PluviaTheme.spec.menuStyle
    if (menu == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
    } else {
        MenuStyledDialogSurface(menu, modifier, shape, color, contentColor, tonalElevation, shadowElevation, border, content)
    }
}

@PublishedApi
@Composable
internal fun MenuStyledDialogSurface(
    menu: ThemeMenu,
    modifier: Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color,
    tonalElevation: Dp,
    shadowElevation: Dp,
    border: BorderStroke?,
    content: @Composable () -> Unit,
) {
    val themedShape = PluviaTheme.menuShape(shape)
    val base = PluviaTheme.menuColor(color)
    val container = menu.opacity?.let { base.scaleAlpha(it.coerceIn(0f, 1f)) } ?: base
    val fill = menu.fill?.takeIf { !it.isEmpty }
    // The surface paints over its modifier, so with a fill the container color moves to the modifier too.
    val decoration = Modifier
        .then(if (fill != null) Modifier.background(container, themedShape) else Modifier)
        .themeFill(fill, themedShape, menuFillAlpha(menu))
        .menuGlow(menuGlowColor(menu), PluviaTheme.menuGlow, themedShape)
    val blur = PluviaTheme.menuBlur
    val body: @Composable () -> Unit = if (blur == null) {
        content
    } else {
        remember(blur, content) {
            @Composable {
                DialogBackdropBlur(blur)
                content()
            }
        }
    }
    Surface(
        modifier = modifier.then(decoration),
        shape = themedShape,
        color = if (fill != null) Color.Transparent else container,
        contentColor = PluviaTheme.onMenuColor(contentColor),
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = PluviaTheme.menuBorder ?: border,
        content = body,
    )
}

/**
 * Blurs the screen behind the dialog window this is composed in (API 31+, and only while the system allows
 * cross-window blur). Android can blur behind a whole window but not behind part of it, so this stands in for a
 * CSS backdrop blur behind the dialog panel.
 */
@Composable
fun DialogBackdropBlur(radius: Dp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val px = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(window, px) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = px }
        onDispose { }
    }
}

@PublishedApi
internal fun menuFillAlpha(menu: ThemeMenu): Float = menu.opacity?.coerceIn(0f, 1f) ?: 1f

@PublishedApi
@Composable
@ReadOnlyComposable
internal fun menuGlowColor(menu: ThemeMenu): Color = menu.borderColor?.let { Color(it) } ?: PluviaTheme.colors.focus

/**
 * A soft glow of [radius] in [color] along the edge of [shape], drawn over the content. Without a radius it
 * returns this modifier unchanged.
 */
@PublishedApi
internal fun Modifier.menuGlow(color: Color, radius: Dp?, shape: Shape): Modifier {
    if (radius == null) return this
    return drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val r = radius.toPx()
        val strokes = (1..GLOW_STEPS).map { Stroke(width = 2f * r * it / GLOW_STEPS) }
        onDrawWithContent {
            drawContent()
            for (s in strokes) drawPath(path, color, alpha = GLOW_STEP_ALPHA, style = s)
        }
    }
}

private const val GLOW_STEPS = 6
private const val GLOW_STEP_ALPHA = 0.12f

@PublishedApi
internal val MENU_ITEM_INSET = 8.dp

@PublishedApi
internal val MENU_ITEM_RADIUS = 8.dp

/** Side panel colour: the base colour of the theme panel fill, else [default]; both at the theme panel opacity. */
@Composable
@ReadOnlyComposable
fun themedPanelColor(default: Color): Color {
    val base = PluviaTheme.spec.panelFill?.color ?: return PluviaTheme.panel(default)
    return PluviaTheme.panel(Color(base))
}

/** Draws the gradient layers of the theme panel fill (e.g. radial halos) behind the content; unchanged without them. */
@Composable
@ReadOnlyComposable
fun Modifier.themePanelLayers(): Modifier {
    val spec = PluviaTheme.spec
    val fill = spec.panelFill?.takeIf { it.layers.isNotEmpty() } ?: return this
    return themeFill(ThemeFill(layers = fill.layers), alpha = spec.panelOpacity?.coerceIn(0f, 1f) ?: 1f)
}

/** How a focused row in a side panel is drawn when the theme gives focus a fill or an underline. */
@Immutable
data class ThemedRowFocus(
    val fill: ThemeFill?,
    val onFill: Color?,
    val underline: Brush?,
    val underlineHeight: Dp,
    /** Ring around the focused row, when the theme sets a ring width. */
    val ring: BorderStroke?,
)

/** The theme's focused-row look for side panels, or null to keep the app's own. */
@Composable
@ReadOnlyComposable
fun themedRowFocus(): ThemedRowFocus? {
    val f = PluviaTheme.spec.focusStyle ?: return null
    if (f.fill == null && f.underline == null) return null
    val style = PluviaTheme.focusStyle
    val ringDp = f.ringDp?.takeIf { it > 0f }
    return ThemedRowFocus(
        fill = style.fill,
        onFill = style.onFill,
        underline = style.underline,
        underlineHeight = style.underlineHeight,
        ring = ringDp?.let { BorderStroke(it.dp, style.ringColor) },
    )
}

/** Text and icons on a row while [focused]: the theme's color on its focus fill, else [default]. */
fun ThemedRowFocus?.contentColor(focused: Boolean, default: Color): Color =
    if (focused && this != null && fill?.isEmpty == false) onFill ?: default else default

/** Draws [focus] inside [shape] while [focused]: the fill, the underline along the bottom edge and the ring. */
fun Modifier.themedFocusRow(focus: ThemedRowFocus, focused: Boolean, shape: Shape): Modifier {
    if (!focused) return this
    var m = themeFill(focus.fill, shape)
    focus.underline?.let { brush ->
        val height = focus.underlineHeight
        m = m.drawBehind {
            val h = height.toPx()
            drawRect(brush, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
        }
    }
    focus.ring?.let { m = m.border(it, shape) }
    return m
}
