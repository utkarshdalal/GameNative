package app.gamenative.theme.io

import app.gamenative.theme.model.*
import java.io.File

/**
 * Maps a merged ThemeTree (XmlNode-based) produced by ThemeLoader into the runtime ThemeDefinition model
 * used by the rendering engine. This does not perform validation — call ThemeValidator first.
 */
object ThemeXmlMapper {

    /** Convert the merged [ThemeTree] into a [ThemeDefinition]. */
    fun map(tree: ThemeTree): ThemeDefinition {
        val root = tree.themeXml
        // Variables: best-effort mapping from loader map -> Variable entries (typed as STRING by default)
        val variables = tree.variables.map { (k, v) ->
            Variable(id = k, type = ValueType.STRING, defaultValue = v)
        }

        val cards = parseCards(root)
        val fixedContainers = parseFixedContainers(root)
        val layout = parseLayout(root, tree)
        val manifest = buildManifest(tree)

        return ThemeDefinition(
            manifest = manifest,
            variables = variables,
            cards = cards,
            fixedContainers = fixedContainers,
            layout = layout,
        )
    }

    // region Manifest
    private fun buildManifest(tree: ThemeTree): Manifest {
        // Try to infer id and version from manifest.xml filename and contents are already validated elsewhere.
        val themeDir = File(tree.rootDir)
        val id = themeDir.name.ifBlank { tree.manifestEntry?.themePath ?: "unknown" }
        // We don't have parsed manifest fields here; use safe defaults (engine is authoritative)
        return Manifest(
            id = id,
            version = "1.0.0",
            engineVersion = ThemeEngine.ENGINE_MAJOR,
            minAppVersion = "0.0.0",
            maxAppVersion = null,
        )
    }
    // endregion

    // region Cards
    private fun parseCards(root: XmlNode): List<Card> {
        // Support both new <cards>/<card> and legacy <templates>/<template> for backward compatibility
        val cardsRoot = root.children.firstOrNull { it.name.equals("cards", ignoreCase = true) }
            ?: root.children.firstOrNull { it.name.equals("templates", ignoreCase = true) }
            ?: return emptyList()
        
        val cardTagName = if (cardsRoot.name.equals("cards", ignoreCase = true)) "card" else "template"
        return cardsRoot.children.filter { it.name.equals(cardTagName, ignoreCase = true) }.map { n ->
            val id = n.attributes["id"] ?: "card_${System.nanoTime()}"
            // Default to 100% width/height if not specified
            val width = n.attributes["width"]?.let { parseDimensionWidth(it) } ?: Dimension.RelW(1f)
            val height = n.attributes["height"]?.let { parseDimensionHeight(it) } ?: Dimension.RelH(1f)
            val layers = n.children.mapNotNull { child ->
                parseLayer(child)
            }
            Card(
                id = id,
                canvas = DimSize(width, height),
                layers = layers,
                // states/transitions future work — kept empty for now
            )
        }
    }

    // region Fixed Containers
    private fun parseFixedContainers(root: XmlNode): List<FixedContainer> {
        return root.children.filter { it.name.equals("fixed", ignoreCase = true) }.map { containerNode ->
            val id = containerNode.attributes["id"] ?: "default"
            val elements = containerNode.children.mapNotNull { parseFixedElement(it) }
            val backgroundColor = containerNode.attributes["backgroundColor"]?.let { parseColor(it) }
            val height = containerNode.attributes["height"]?.toFloatOrNull()
            FixedContainer(id = id, elements = elements, backgroundColor = backgroundColor, height = height)
        }
    }

    private fun parseFixedElement(n: XmlNode): FixedElement? {
        val position = DimOffset(px(n, "x"), px(n, "y"))
        val anchor = parseAnchor(n.attributes["anchor"])
        
        return when (n.name.lowercase()) {
            "header" -> FixedElement.Header(
                position = position,
                anchor = anchor,
                textColor = n.attributes["textColor"]?.let { parseColor(it) } ?: 0xFFFFFFFF.toInt(),
                showAppName = n.attributes["showAppName"]?.toBooleanStrictOrNull() 
                    ?: n.children.any { it.name.equals("appName", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
                showThemeName = n.attributes["showThemeName"]?.toBooleanStrictOrNull()
                    ?: n.children.any { it.name.equals("themeName", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
                showGameCount = n.attributes["showGameCount"]?.toBooleanStrictOrNull()
                    ?: n.children.any { it.name.equals("gameCount", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
            )
            "searchbar" -> FixedElement.SearchBar(
                position = position,
                anchor = anchor,
                size = size(n) ?: DimSize(Dimension.Px(400f), Dimension.Px(48f)),
                backgroundColor = n.attributes["backgroundColor"]?.let { parseColor(it) },
                borderRadius = n.attributes["borderRadius"]?.toFloatOrNull() ?: 8f,
            )
            "profilebutton" -> FixedElement.ProfileButton(
                position = position,
                anchor = anchor,
                size = n.attributes["size"]?.toFloatOrNull() ?: 40f,
            )
            "filterbutton" -> FixedElement.FilterButton(
                position = position,
                anchor = anchor,
                expanded = n.attributes["expanded"]?.toBooleanStrictOrNull() ?: true,
            )
            "addbutton" -> FixedElement.AddButton(
                position = position,
                anchor = anchor,
            )
            else -> null
        }
    }

    private fun parseAnchor(value: String?): Anchor {
        return when (value?.lowercase()?.replace("_", "")) {
            "topleft" -> Anchor.TOP_LEFT
            "topcenter" -> Anchor.TOP_CENTER
            "topright" -> Anchor.TOP_RIGHT
            "centerleft" -> Anchor.CENTER_LEFT
            "center" -> Anchor.CENTER
            "centerright" -> Anchor.CENTER_RIGHT
            "bottomleft" -> Anchor.BOTTOM_LEFT
            "bottomcenter" -> Anchor.BOTTOM_CENTER
            "bottomright" -> Anchor.BOTTOM_RIGHT
            else -> Anchor.TOP_LEFT
        }
    }
    // endregion

    private fun parseLayerAnchor(s: String?): LayerAnchor {
        return when (s?.lowercase()?.replace("_", "")) {
            "topleft" -> LayerAnchor.TOP_LEFT
            "topcenter" -> LayerAnchor.TOP_CENTER
            "topright" -> LayerAnchor.TOP_RIGHT
            "centerleft" -> LayerAnchor.CENTER_LEFT
            "center" -> LayerAnchor.CENTER
            "centerright" -> LayerAnchor.CENTER_RIGHT
            "bottomleft" -> LayerAnchor.BOTTOM_LEFT
            "bottomcenter" -> LayerAnchor.BOTTOM_CENTER
            "bottomright" -> LayerAnchor.BOTTOM_RIGHT
            else -> LayerAnchor.TOP_LEFT
        }
    }

    private fun parseLayer(n: XmlNode): Layer? {
        val layerSize = size(n)
        return when (n.name.lowercase()) {
        "image" -> Layer.ImageLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            source = MediaSource.Image(
                src = stringBinding(n.attributes["src"]) ?: StringOrBinding.Literal(""),
                fallback = stringBinding(n.attributes["fallback"]) ,
            ),
            cornerRadius = n.attributes["cornerRadius"],
            tintColor = intBinding(n.attributes["tint"]),
            scaleType = n.attributes["scaleType"] ?: "cover",
        )
        "video" -> Layer.VideoLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            source = MediaSource.Video(
                src = stringBinding(n.attributes["src"]) ?: StringOrBinding.Literal(""),
                poster = stringBinding(n.attributes["poster"]),
                autoplay = n.attributes["autoplay"]?.toBooleanStrictOrNull() ?: false,
                loop = n.attributes["loop"]?.toBooleanStrictOrNull() ?: true,
                muted = n.attributes["muted"]?.toBooleanStrictOrNull() ?: true,
                preload = when (n.attributes["preload"]?.lowercase()) {
                    "none" -> VideoPreloadPolicy.NONE
                    "auto" -> VideoPreloadPolicy.AUTO
                    else -> VideoPreloadPolicy.METADATA
                },
                fallbackImage = stringBinding(n.attributes["fallbackImage"]),
            ),
            cornerRadius = floatBinding(n.attributes["cornerRadius"]),
        )
        // Support both "rect" (new) and "overlay" (legacy) for rectangle shapes
        "rect", "overlay" -> Layer.RectLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0x88000000.toInt()),
            cornerRadius = n.attributes["cornerRadius"],
            borderWidth = floatBinding(n.attributes["borderWidth"]),
            borderColor = intBinding(n.attributes["borderColor"]),
        )
        "shadow" -> Layer.ShadowLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            radius = floatBinding(n.attributes["radius"]) ?: FloatOrBinding.Literal(8f),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0x80000000.toInt()),
            offset = DimOffset(px(n, "dx"), px(n, "dy")),
        )
        "border" -> Layer.BorderLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            strokeWidth = floatBinding(n.attributes["strokeWidth"]) ?: FloatOrBinding.Literal(2f),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            cornerRadius = n.attributes["cornerRadius"],
        )
        "text" -> Layer.TextLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            text = stringBinding(n.attributes["text"]) ?: StringOrBinding.Literal(""),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            textSize = floatBinding(n.attributes["textSize"]) ?: FloatOrBinding.Literal(18f),
            maxLines = n.attributes["maxLines"]?.toIntOrNull(),
            textAlign = n.attributes["textAlign"] ?: "left",
            fontWeight = n.attributes["fontWeight"] ?: "normal",
            fontStyle = n.attributes["fontStyle"] ?: "normal",
        )
        "backdrop" -> Layer.BackdropLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            blurRadius = floatBinding(n.attributes["blurRadius"]),
            tintColor = intBinding(n.attributes["tint"]),
        )
        "button" -> Layer.ButtonLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = layerSize,
            opacity = floatBinding(n.attributes["opacity"]),
            anchor = parseLayerAnchor(n.attributes["anchor"]),
            text = stringBinding(n.attributes["text"]) ?: StringOrBinding.Literal(""),
            backgroundColor = intBinding(n.attributes["backgroundColor"]) ?: IntOrBinding.Literal(0xFFE91E63.toInt()),
            textColor = intBinding(n.attributes["textColor"]) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            textSize = floatBinding(n.attributes["textSize"]) ?: FloatOrBinding.Literal(14f),
            cornerRadius = n.attributes["cornerRadius"],
        )
        else -> null
        }
    }
    // endregion

    // region Layout
    private fun parseLayout(root: XmlNode, tree: ThemeTree): LayoutNode {
        val layoutRoot = root.children.firstOrNull { it.name.equals("layout", ignoreCase = true) }
            ?: error("<layout> element not found in theme.xml")
        // Only one root layout child is supported (canvas/grid/carousel)
        val child = layoutRoot.children.firstOrNull()
            ?: error("<layout> must contain a root layout node (canvas/grid/carousel)")
        return when (child.name.lowercase()) {
            "canvas" -> parseCanvas(child)
            "grid" -> parseGrid(child, tree)
            "carousel" -> parseCarousel(child, tree)
            else -> error("Unknown layout node: ${child.name}")
        }
    }

    private fun parseCanvas(node: XmlNode): LayoutNode.Canvas {
        // Default to 100% width/height if not specified
        val w = node.attributes["width"]?.let { parseDimensionWidth(it) } ?: Dimension.RelW(1f)
        val h = node.attributes["height"]?.let { parseDimensionHeight(it) } ?: Dimension.RelH(1f)
        val children = node.children.filter { it.name.equals("child", ignoreCase = true) }.map { ch ->
            // Support both new "card" and legacy "template" attribute
            val cardId = ch.attributes["card"] ?: ch.attributes["template"] ?: "default"
            CanvasChild(
                cardId = cardId,
                position = DimOffset(px(ch, "x"), px(ch, "y")),
                size = size(ch),
            )
        }
        return LayoutNode.Canvas(size = DimSize(w, h), children = children)
    }

    private fun parseGrid(node: XmlNode, tree: ThemeTree): LayoutNode.Grid {
        // columns is optional - null means adaptive based on cellWidth
        val cols = node.attributes["columns"]?.toIntOrNull()
        val rows = node.attributes["rows"]?.toIntOrNull()
        // cellWidth defaults to 100% (single column) if not specified
        val cellW = node.attributes["cellWidth"]?.let { parseDimensionWidth(it) } ?: Dimension.RelW(1f)
        // cellHeight is optional - if not specified, aspectRatio or card height will be used
        val cellH = node.attributes["cellHeight"]?.let { parseDimensionHeight(it) }
        // aspectRatio for automatic height calculation (width/height, e.g. 2.14 for hero, 0.67 for capsule)
        val aspectRatio = node.attributes["aspectRatio"]?.toFloatOrNull()
        
        // cellSpacing sets both hSpacing and vSpacing; individual values override it
        val cellSpacing = resolveFloat(node, "cellSpacing", default = 0f, tree)
        val hSpacing = resolveFloat(node, "hSpacing", default = cellSpacing, tree)
        val vSpacing = resolveFloat(node, "vSpacing", default = cellSpacing, tree)
        
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.MOVING
            else -> SelectionMode.MOVING
        }
        // Support both new "itemCard" and legacy "itemTemplate" attribute; default to "default" if not specified
        val itemCard = node.attributes["itemCard"] ?: node.attributes["itemTemplate"] ?: "default"
        
        // Content padding - supports CSS-like shorthand: 1, 2, 3, or 4 values
        val (paddingTop, paddingEnd, paddingBottom, paddingStart) = parsePadding(node.attributes["padding"], tree)
        
        // Parse optional separator
        val separator = node.children.firstOrNull { it.name.equals("separator", ignoreCase = true) }?.let { sepNode ->
            // Separator height defaults to 1px if not specified
            val sepHeight = sepNode.attributes["height"]?.let { parseDimensionHeight(it) } ?: Dimension.Px(1f)
            val sepLayers = sepNode.children.mapNotNull { parseLayer(it) }
            // Parse margin - supports CSS-like shorthand
            val (marginTop, marginEnd, marginBottom, marginStart) = parsePadding(sepNode.attributes["margin"], tree)
            GridSeparator(
                height = sepHeight, 
                layers = sepLayers,
                marginTop = marginTop,
                marginBottom = marginBottom,
                marginStart = marginStart,
                marginEnd = marginEnd,
            )
        }
        
        return LayoutNode.Grid(
            columns = cols,
            rows = rows,
            cellWidth = cellW,
            cellHeight = cellH,
            aspectRatio = aspectRatio,
            hSpacing = hSpacing,
            vSpacing = vSpacing,
            selectionMode = sel,
            itemCard = itemCard,
            contentPaddingTop = paddingTop,
            contentPaddingBottom = paddingBottom,
            contentPaddingStart = paddingStart,
            contentPaddingEnd = paddingEnd,
            separator = separator,
        )
    }

    private fun parseCarousel(node: XmlNode, tree: ThemeTree): LayoutNode.Carousel {
        val dir = when (node.attributes["direction"]?.lowercase()) {
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            else -> Direction.RIGHT
        }
        // Default item size to 200x200 if not specified
        val itemW = node.attributes["itemWidth"]?.let { parseDimensionWidth(it) } ?: Dimension.Px(200f)
        val itemH = node.attributes["itemHeight"]?.let { parseDimensionHeight(it) } ?: Dimension.Px(200f)
        val spacing = resolveFloat(node, "itemSpacing", default = 0f, tree)
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.STATIONARY
            else -> SelectionMode.STATIONARY
        }
        val pageSize = node.attributes["pageSize"]?.toIntOrNull()
        // Support both new "itemCard" and legacy "itemTemplate" attribute; default to "default" if not specified
        val itemCard = node.attributes["itemCard"] ?: node.attributes["itemTemplate"] ?: "default"
        return LayoutNode.Carousel(
            direction = dir,
            itemSize = DimSize(itemW, itemH),
            itemSpacing = spacing,
            selectionMode = sel,
            itemCard = itemCard,
            pageSize = pageSize,
        )
    }
    // endregion

    // region Helpers
    private fun req(node: XmlNode, key: String): String =
        node.attributes[key] ?: error("Missing required attribute '$key' on <${node.name}>")

    private fun reqFloat(node: XmlNode, key: String): Float =
        node.attributes[key]?.toFloatOrNull()
            ?: error("Attribute '$key' on <${node.name}> must be a number")

    private fun reqInt(node: XmlNode, key: String): Int =
        node.attributes[key]?.toIntOrNull()
            ?: error("Attribute '$key' on <${node.name}> must be an integer")

    private fun size(n: XmlNode): DimSize? {
        val wAttr = n.attributes["width"]
        val hAttr = n.attributes["height"]
        if (wAttr == null || hAttr == null) return null
        val w = parseDimensionWidth(wAttr) ?: return null
        val h = parseDimensionHeight(hAttr) ?: return null
        return DimSize(w, h)
    }

    private fun px(n: XmlNode, key: String): Dimension {
        val attr = n.attributes[key] ?: return Dimension.Px(0f)
        // For x position, use width-relative; for y position, use height-relative
        return if (key == "y" || key == "dy") {
            parseDimensionHeight(attr) ?: Dimension.Px(0f)
        } else {
            parseDimensionWidth(attr) ?: Dimension.Px(0f)
        }
    }

    /**
     * Parse a dimension value that can be either pixels or percentage.
     * Percentages are relative to parent width.
     * - "100" → Dimension.Px(100f)
     * - "50%" → Dimension.RelW(0.5f)
     */
    private fun parseDimensionWidth(value: String): Dimension? {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("%") -> {
                val percent = trimmed.dropLast(1).toFloatOrNull() ?: return null
                Dimension.RelW(percent / 100f)
            }
            else -> {
                val px = trimmed.toFloatOrNull() ?: return null
                Dimension.Px(px)
            }
        }
    }

    /**
     * Parse a dimension value that can be either pixels or percentage.
     * Percentages are relative to parent height.
     * - "100" → Dimension.Px(100f)
     * - "50%" → Dimension.RelH(0.5f)
     */
    private fun parseDimensionHeight(value: String): Dimension? {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("%") -> {
                val percent = trimmed.dropLast(1).toFloatOrNull() ?: return null
                Dimension.RelH(percent / 100f)
            }
            else -> {
                val px = trimmed.toFloatOrNull() ?: return null
                Dimension.Px(px)
            }
        }
    }

    /**
     * Parse a required dimension for width (returns Px or RelW).
     */
    private fun reqDimensionWidth(node: XmlNode, key: String): Dimension {
        val attr = node.attributes[key] ?: error("Missing required attribute '$key' on <${node.name}>")
        return parseDimensionWidth(attr) ?: error("Invalid dimension value '$attr' for '$key' on <${node.name}>")
    }

    /**
     * Parse a required dimension for height (returns Px or RelH).
     */
    private fun reqDimensionHeight(node: XmlNode, key: String): Dimension {
        val attr = node.attributes[key] ?: error("Missing required attribute '$key' on <${node.name}>")
        return parseDimensionHeight(attr) ?: error("Invalid dimension value '$attr' for '$key' on <${node.name}>")
    }

    private fun stringBinding(raw: String?): StringOrBinding? {
        val s = raw ?: return null
        return if (isBinding(s)) StringOrBinding.Ref(Binding(bindingPath(s))) else StringOrBinding.Literal(s)
    }

    private fun floatBinding(raw: String?): FloatOrBinding? {
        val s = raw ?: return null
        return if (isBinding(s)) FloatOrBinding.Ref(Binding(bindingPath(s))) else FloatOrBinding.Literal(s.toFloatOrNull() ?: 0f)
    }

    private fun intBinding(raw: String?): IntOrBinding? {
        val s = raw ?: return null
        return when {
            isBinding(s) -> IntOrBinding.Ref(Binding(bindingPath(s)))
            isColorRef(s) -> IntOrBinding.Ref(Binding(s)) // Keep @color/primary as binding path
            else -> IntOrBinding.Literal(parseColor(s))
        }
    }

    /** Check for @color/ system color reference */
    private fun isColorRef(s: String): Boolean = s.startsWith("@color/")

    private fun parseColor(s: String): Int {
        // Supports #AARRGGBB or 0xAARRGGBB; also #RRGGBB (assume opaque)
        var v = s.trim()
        val isHex = v.startsWith("#") || v.lowercase().startsWith("0x")
        if (!isHex) return v.toLongOrNull()?.toInt() ?: 0 // fallback for arbitrary ints
        v = v.removePrefix("#").removePrefix("0x").removePrefix("0X")
        val value = v.toLong(16)
        return if (v.length <= 6) {
            // RRGGBB -> assume opaque
            (0xFF000000 or value).toInt()
        } else value.toInt() // AARRGGBB
    }

    private fun isBinding(s: String): Boolean = s.startsWith("@{") && s.endsWith("}")
    private fun bindingPath(s: String): String = s.removePrefix("@{").removeSuffix("}")

    private fun resolveFloat(node: XmlNode, key: String, default: Float, tree: ThemeTree): Float {
        val s = node.attributes[key] ?: return default
        if (!isBinding(s)) return s.toFloatOrNull() ?: default
        // Resolve @{vars.name} from tree.variables if possible
        val path = bindingPath(s)
        // Expect pattern vars.foo
        val varName = path.substringAfter("vars.", missingDelimiterValue = path)
        val value = tree.variables[varName] ?: return default
        return value.toFloatOrNull() ?: default
    }

    /**
     * Parse CSS-like padding shorthand.
     * - 1 value: all sides get the same value
     * - 2 values: top/bottom, start/end (vertical, horizontal)
     * - 3 values: top, start/end, bottom
     * - 4 values: top, end, bottom, start (clockwise from top)
     * @return Quadruple of (top, end, bottom, start)
     */
    private data class PaddingValues(val top: Float, val end: Float, val bottom: Float, val start: Float)
    
    private fun parsePadding(value: String?, tree: ThemeTree): PaddingValues {
        if (value.isNullOrBlank()) return PaddingValues(0f, 0f, 0f, 0f)
        
        val parts = value.trim().split("\\s+".toRegex()).map { part ->
            if (isBinding(part)) {
                val path = bindingPath(part)
                val varName = path.substringAfter("vars.", missingDelimiterValue = path)
                tree.variables[varName]?.toFloatOrNull() ?: 0f
            } else {
                part.toFloatOrNull() ?: 0f
            }
        }
        
        return when (parts.size) {
            0 -> PaddingValues(0f, 0f, 0f, 0f)
            1 -> PaddingValues(parts[0], parts[0], parts[0], parts[0])
            2 -> PaddingValues(
                top = parts[0],
                end = parts[1],
                bottom = parts[0],
                start = parts[1]
            )
            3 -> PaddingValues(
                top = parts[0],
                end = parts[1],
                bottom = parts[2],
                start = parts[1]
            )
            else -> PaddingValues(
                top = parts[0],
                end = parts[1],
                bottom = parts[2],
                start = parts[3]
            )
        }
    }
    // endregion
}
