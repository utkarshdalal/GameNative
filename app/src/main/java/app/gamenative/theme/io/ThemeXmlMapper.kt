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
            val id = req(n, "id")
            val width = reqFloat(n, "width")
            val height = reqFloat(n, "height")
            val layers = n.children.mapNotNull { parseLayer(it) }
            Card(
                id = id,
                canvas = DimSize(Dimension.Px(width), Dimension.Px(height)),
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

    private fun parseLayer(n: XmlNode): Layer? = when (n.name.lowercase()) {
        "image" -> Layer.ImageLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            source = MediaSource.Image(
                src = stringBinding(n.attributes["src"]) ?: StringOrBinding.Literal(""),
                fallback = stringBinding(n.attributes["fallback"]) ,
            ),
            cornerRadius = n.attributes["cornerRadius"],
            tintColor = intBinding(n.attributes["tint"]),
        )
        "video" -> Layer.VideoLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
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
        "overlay" -> Layer.OverlayLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0x88000000.toInt()),
            cornerRadius = n.attributes["cornerRadius"],
        )
        "shadow" -> Layer.ShadowLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            radius = floatBinding(n.attributes["radius"]) ?: FloatOrBinding.Literal(8f),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0x80000000.toInt()),
            offset = DimOffset(px(n, "dx"), px(n, "dy")),
        )
        "border" -> Layer.BorderLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            strokeWidth = floatBinding(n.attributes["strokeWidth"]) ?: FloatOrBinding.Literal(2f),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            cornerRadius = n.attributes["cornerRadius"],
        )
        "text" -> Layer.TextLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            text = stringBinding(n.attributes["text"]) ?: StringOrBinding.Literal(""),
            color = intBinding(n.attributes["color"]) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            textSize = floatBinding(n.attributes["textSize"]) ?: FloatOrBinding.Literal(18f),
            maxLines = n.attributes["maxLines"]?.toIntOrNull(),
            textAlign = n.attributes["textAlign"] ?: "left",
        )
        "backdrop" -> Layer.BackdropLayer(
            id = n.attributes["id"],
            position = DimOffset(px(n, "x"), px(n, "y")),
            size = size(n),
            opacity = floatBinding(n.attributes["opacity"]),
            blurRadius = floatBinding(n.attributes["blurRadius"]),
            tintColor = intBinding(n.attributes["tint"]),
        )
        else -> null
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
        val w = reqFloat(node, "width")
        val h = reqFloat(node, "height")
        val children = node.children.filter { it.name.equals("child", ignoreCase = true) }.map { ch ->
            // Support both new "card" and legacy "template" attribute
            val cardId = ch.attributes["card"] ?: ch.attributes["template"] 
                ?: error("Missing required attribute 'card' on <child>")
            CanvasChild(
                cardId = cardId,
                position = DimOffset(px(ch, "x"), px(ch, "y")),
                size = size(ch),
            )
        }
        return LayoutNode.Canvas(size = DimSize(Dimension.Px(w), Dimension.Px(h)), children = children)
    }

    private fun parseGrid(node: XmlNode, tree: ThemeTree): LayoutNode.Grid {
        val cols = reqInt(node, "columns")
        val rows = node.attributes["rows"]?.toIntOrNull()
        val cellW = reqFloat(node, "cellWidth")
        val cellH = reqFloat(node, "cellHeight")
        val hSpacing = resolveFloat(node, "hSpacing", default = 0f, tree)
        val vSpacing = resolveFloat(node, "vSpacing", default = 0f, tree)
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.MOVING
            else -> SelectionMode.MOVING
        }
        // Support both new "itemCard" and legacy "itemTemplate" attribute
        val itemCard = node.attributes["itemCard"] ?: node.attributes["itemTemplate"]
            ?: error("Missing required attribute 'itemCard' on <grid>")
        
        // Content padding
        val paddingTop = resolveFloat(node, "contentPaddingTop", default = 0f, tree)
        val paddingBottom = resolveFloat(node, "contentPaddingBottom", default = 0f, tree)
        val paddingStart = resolveFloat(node, "contentPaddingStart", default = 0f, tree)
        val paddingEnd = resolveFloat(node, "contentPaddingEnd", default = 0f, tree)
        
        return LayoutNode.Grid(
            columns = cols,
            rows = rows,
            cellSize = DimSize(Dimension.Px(cellW), Dimension.Px(cellH)),
            hSpacing = hSpacing,
            vSpacing = vSpacing,
            selectionMode = sel,
            itemCard = itemCard,
            contentPaddingTop = paddingTop,
            contentPaddingBottom = paddingBottom,
            contentPaddingStart = paddingStart,
            contentPaddingEnd = paddingEnd,
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
        val itemW = reqFloat(node, "itemWidth")
        val itemH = reqFloat(node, "itemHeight")
        val spacing = resolveFloat(node, "itemSpacing", default = 0f, tree)
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.STATIONARY
            else -> SelectionMode.STATIONARY
        }
        val pageSize = node.attributes["pageSize"]?.toIntOrNull()
        // Support both new "itemCard" and legacy "itemTemplate" attribute
        val itemCard = node.attributes["itemCard"] ?: node.attributes["itemTemplate"]
            ?: error("Missing required attribute 'itemCard' on <carousel>")
        return LayoutNode.Carousel(
            direction = dir,
            itemSize = DimSize(Dimension.Px(itemW), Dimension.Px(itemH)),
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
        val w = n.attributes["width"]?.toFloatOrNull()
        val h = n.attributes["height"]?.toFloatOrNull()
        return if (w != null && h != null) DimSize(Dimension.Px(w), Dimension.Px(h)) else null
    }

    private fun px(n: XmlNode, key: String): Dimension =
        Dimension.Px(n.attributes[key]?.toFloatOrNull() ?: 0f)

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
        return if (isBinding(s)) IntOrBinding.Ref(Binding(bindingPath(s))) else IntOrBinding.Literal(parseColor(s))
    }

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
    // endregion
}
