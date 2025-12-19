package app.gamenative.theme.io

import app.gamenative.theme.model.*
import app.gamenative.theme.runtime.VariableResolver
import java.io.File

/**
 * Maps a merged ThemeTree (XmlNode-based) produced by ThemeLoader into the runtime ThemeDefinition model
 * used by the rendering engine. This does not perform validation — call ThemeValidator first.
 */
object ThemeXmlMapper {

    /**
     * Convert the merged [ThemeTree] into a [ThemeDefinition].
     * 
     * @param tree The loaded theme tree
     * @param resolvedVariables Optional pre-resolved variables (with breakpoints applied).
     *                          If null, uses base variables from tree without breakpoint overrides.
     */
    fun map(tree: ThemeTree, resolvedVariables: Map<String, String>? = null): ThemeDefinition {
        val root = tree.themeXml
        
        // Use resolved variables if provided, otherwise use base variables
        val effectiveVariables = resolvedVariables ?: tree.variables
        
        // Create a tree view with the effective variables for parsing
        val effectiveTree = if (resolvedVariables != null) {
            tree.copy(variables = effectiveVariables)
        } else {
            tree
        }
        
        // Variables: best-effort mapping from loader map -> Variable entries (typed as STRING by default)
        val variables = effectiveVariables.map { (k, v) ->
            Variable(id = k, type = ValueType.STRING, defaultValue = v)
        }

        // Parse <elements> section (new format) for pre-defined cards and fixed containers
        val elementsNode = root.children.firstOrNull { it.name.equals("elements", ignoreCase = true) }
        val elementCards = elementsNode?.let { parseCardsFromContainer(it, effectiveTree) } ?: emptyList()
        val elementFixedContainers = elementsNode?.let { parseFixedContainersFromContainer(it, effectiveTree) } ?: emptyList()
        
        // Build lookup maps for element references
        val fixedContainerLookup = elementFixedContainers.associateBy { it.id }
        
        // Parse cards from both <elements> and legacy <cards> section
        val legacyCards = parseCards(root, effectiveTree)
        
        // Parse layout and extract layout elements and inline cards in declaration order
        val layoutResult = parseLayoutWithFixedContainers(root, effectiveTree, fixedContainerLookup)
        val inlineCards = layoutResult.inlineCards
        
        // Combine all cards: elements + legacy + inline (inline cards take precedence for same ID)
        val cards = (elementCards + legacyCards + inlineCards).distinctBy { it.id }
        
        // Determine which layout elements to use:
        // - If layout contains fixed/element/content tags, use those (new format)
        // - Otherwise fall back to root-level <fixed> tags (backwards compat) + just the content node
        val layoutElements = if (layoutResult.layoutElements.any { it is LayoutElement.Fixed }) {
            layoutResult.layoutElements
        } else {
            // Legacy mode: parse root-level fixed containers and combine with content node
            val legacyFixed = parseFixedContainers(root, effectiveTree)
            val contentElements = layoutResult.layoutElements.filterIsInstance<LayoutElement.Content>()
            // Put fixed containers at the end (legacy behavior: UI elements on top)
            contentElements + legacyFixed.mapIndexed { index, container ->
                LayoutElement.Fixed(
                    container = container,
                    zIndex = null,
                    declarationOrder = contentElements.size + index,
                )
            }
        }
        
        val manifest = buildManifest(tree)

        return ThemeDefinition(
            manifest = manifest,
            variables = variables,
            breakpoints = tree.breakpoints,
            cards = cards,
            layoutElements = layoutElements,
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
            engineVersion = ThemeEngine.ENGINE_VERSION,
            minAppVersion = "0.0.0",
            maxAppVersion = null,
        )
    }
    // endregion

    // region Cards
    private fun parseCards(root: XmlNode, tree: ThemeTree): List<Card> {
        // Support both new <cards>/<card> and legacy <templates>/<template> for backward compatibility
        val cardsRoot = root.children.firstOrNull { it.name.equals("cards", ignoreCase = true) }
            ?: root.children.firstOrNull { it.name.equals("templates", ignoreCase = true) }
            ?: return emptyList()
        
        return parseCardsFromContainer(cardsRoot, tree)
    }
    
    /** Parse card definitions from a container node (either <cards> or <elements>). */
    private fun parseCardsFromContainer(container: XmlNode, tree: ThemeTree): List<Card> {
        // Support both "card" and "template" tag names for backwards compatibility
        return container.children.filter { 
            it.name.equals("card", ignoreCase = true) || it.name.equals("template", ignoreCase = true)
        }.map { n ->
            val id = n.attributes["id"] ?: "card_${System.nanoTime()}"
            // Default to 100% width/height if not specified
            val width = resolveDimensionWidth(n, "width", tree) ?: Dimension.RelW(1f)
            val height = resolveDimensionHeight(n, "height", tree) ?: Dimension.RelH(1f)
            val layers = n.children.mapIndexedNotNull { index, child ->
                parseLayer(child, tree, index)
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
    
    /** Parse root-level <fixed> containers (backwards compatibility). */
    private fun parseFixedContainers(root: XmlNode, tree: ThemeTree): List<FixedContainer> {
        return root.children.filter { it.name.equals("fixed", ignoreCase = true) }.map { containerNode ->
            parseFixedContainerNode(containerNode, tree)
        }
    }
    
    /** Parse <fixed> containers from a container node (e.g., <elements>). */
    private fun parseFixedContainersFromContainer(container: XmlNode, tree: ThemeTree): List<FixedContainer> {
        return container.children.filter { it.name.equals("fixed", ignoreCase = true) }.map { containerNode ->
            parseFixedContainerNode(containerNode, tree)
        }
    }
    
    /** Parse a single <fixed> container node into a FixedContainer. */
    private fun parseFixedContainerNode(containerNode: XmlNode, tree: ThemeTree): FixedContainer {
        val id = containerNode.attributes["id"] ?: "default"
        val elements = containerNode.children.mapIndexedNotNull { index, child -> 
            parseFixedElement(child, tree, index) 
        }
        val backgroundColor = resolveColorAttr(containerNode, "backgroundColor", tree)
        val height = resolveFloatOrNull(containerNode, "height", tree)
        val visibility = Visibility.fromString(containerNode.attributes["visibility"])
        val padding = resolveStringAttr(containerNode, "padding", tree)
        val cornerRadius = resolveFloat(containerNode, "cornerRadius", 0f, tree)
        return FixedContainer(
            id = id, 
            elements = elements, 
            backgroundColor = backgroundColor, 
            height = height,
            visibility = visibility,
            padding = padding,
            cornerRadius = cornerRadius,
        )
    }

    /**
     * Helper class holding common base properties shared by all fixed element types.
     */
    private data class FixedElementBase(
        val position: DimOffset,
        val anchor: Anchor,
        val visibility: Visibility,
        val zIndex: Float,
        val declarationOrder: Int,
        val highlightColor: Int?,
        val highlightOpacity: Float,
        val highlightBorderWidth: Float,
        val highlightTransitionSpeed: Int,
        val navigationId: String?,
        val navigateUp: String?,
        val navigateDown: String?,
        val navigateLeft: String?,
        val navigateRight: String?,
    )
    
    /** Extract common base properties from an XML node for fixed elements. */
    private fun parseFixedElementBase(n: XmlNode, tree: ThemeTree, declarationOrder: Int) = FixedElementBase(
        position = DimOffset(pxResolved(n, "x", tree), pxResolved(n, "y", tree)),
        anchor = Anchor.fromString(n.attributes["anchor"]),
        visibility = Visibility.fromString(n.attributes["visibility"]),
        zIndex = resolveFloat(n, "zIndex", default = 0f, tree),
        declarationOrder = declarationOrder,
        highlightColor = resolveColorAttr(n, "highlightColor", tree),
        highlightOpacity = resolveFloat(n, "highlightOpacity", 0.8f, tree),
        highlightBorderWidth = resolveFloat(n, "highlightBorderWidth", 2f, tree),
        highlightTransitionSpeed = resolveInt(n, "highlightTransitionSpeed", tree) ?: 200,
        navigationId = n.attributes["navigationId"],
        navigateUp = n.attributes["navigateUp"],
        navigateDown = n.attributes["navigateDown"],
        navigateLeft = n.attributes["navigateLeft"],
        navigateRight = n.attributes["navigateRight"],
    )

    private fun parseFixedElement(n: XmlNode, tree: ThemeTree, declarationOrder: Int): FixedElement? {
        val base = parseFixedElementBase(n, tree, declarationOrder)
        
        return when (n.name.lowercase()) {
            "header" -> FixedElement.Header(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                textColor = resolveColorAttr(n, "textColor", tree) ?: 0xFFFFFFFF.toInt(),
                showAppName = n.attributes["showAppName"]?.toBooleanStrictOrNull() 
                    ?: n.children.any { it.name.equals("appName", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
                showThemeName = n.attributes["showThemeName"]?.toBooleanStrictOrNull()
                    ?: n.children.any { it.name.equals("themeName", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
                showGameCount = n.attributes["showGameCount"]?.toBooleanStrictOrNull()
                    ?: n.children.any { it.name.equals("gameCount", true) && it.attributes["visible"]?.toBooleanStrictOrNull() != false }
                    ?: true,
                size = sizeResolved(n, tree),
                backgroundColor = resolveColorAttr(n, "backgroundColor", tree),
                cornerRadius = resolveFloat(n, "cornerRadius", 0f, tree),
                padding = resolveFloat(n, "padding", 8f, tree),
                textSize = resolveFloat(n, "textSize", 14f, tree),
                fontWeight = n.attributes["fontWeight"] ?: "bold",
            )
            "searchbar" -> FixedElement.SearchBar(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(400f), Dimension.Px(48f)),
                backgroundColor = resolveColorAttr(n, "backgroundColor", tree),
                textColor = resolveColorAttr(n, "textColor", tree),
                borderRadius = resolveFloat(n, "borderRadius", 8f, tree),
                collapsible = n.attributes["collapsible"]?.toBooleanStrictOrNull() ?: false,
            )
            "profilebutton" -> FixedElement.ProfileButton(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = resolveFloat(n, "size", 48f, tree),
                iconSize = resolveFloat(n, "iconSize", 24f, tree),
                padding = resolveFloat(n, "padding", 8f, tree),
                backgroundColor = resolveColorAttr(n, "backgroundColor", tree),
                cornerRadius = resolveFloat(n, "cornerRadius", 12f, tree),
            )
            "filterbutton" -> FixedElement.FilterButton(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                expanded = n.attributes["expanded"]?.toBooleanStrictOrNull() ?: true,
                size = resolveFloat(n, "size", 56f, tree),
                iconSize = resolveFloat(n, "iconSize", 24f, tree),
                backgroundColor = resolveColorAttr(n, "backgroundColor", tree),
                iconColor = resolveColorAttr(n, "iconColor", tree),
                cornerRadius = resolveFloat(n, "cornerRadius", 16f, tree),
            )
            "addbutton" -> FixedElement.AddButton(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = resolveFloat(n, "size", 56f, tree),
                iconSize = resolveFloat(n, "iconSize", 24f, tree),
                backgroundColor = resolveColorAttr(n, "backgroundColor", tree),
                iconColor = resolveColorAttr(n, "iconColor", tree),
                cornerRadius = resolveFloat(n, "cornerRadius", 16f, tree),
            )
            "image" -> FixedElement.Image(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(100f), Dimension.Px(100f)),
                src = resolveStringAttr(n, "src", tree) ?: "",
                scaleType = n.attributes["scaleType"] ?: "cover",
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "video" -> FixedElement.Video(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(200f), Dimension.Px(150f)),
                src = resolveStringAttr(n, "src", tree) ?: "",
                poster = resolveStringAttr(n, "poster", tree),
                autoplay = n.attributes["autoplay"]?.toBooleanStrictOrNull() ?: false,
                loop = n.attributes["loop"]?.toBooleanStrictOrNull() ?: true,
                muted = n.attributes["muted"]?.toBooleanStrictOrNull() ?: true,
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "rect" -> FixedElement.Rect(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(100f), Dimension.Px(100f)),
                color = resolveColorAttr(n, "color", tree) ?: 0x00000000,
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                borderWidth = resolveFloat(n, "borderWidth", 0f, tree),
                borderColor = resolveColorAttr(n, "borderColor", tree) ?: 0x00000000,
                gradientStart = resolveColorAttr(n, "gradientStart", tree),
                gradientEnd = resolveColorAttr(n, "gradientEnd", tree),
                gradientAngle = resolveFloat(n, "gradientAngle", 0f, tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "text" -> FixedElement.Text(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree),
                text = resolveStringAttr(n, "text", tree) ?: "",
                color = resolveColorAttr(n, "color", tree) ?: 0xFFFFFFFF.toInt(),
                textSize = resolveFloat(n, "textSize", 14f, tree),
                maxLines = resolveInt(n, "maxLines", tree),
                textAlign = n.attributes["textAlign"] ?: "left",
                fontWeight = n.attributes["fontWeight"] ?: "normal",
                fontStyle = n.attributes["fontStyle"] ?: "normal",
                overflow = n.attributes["overflow"] ?: "ellipsis",
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "shadow" -> FixedElement.Shadow(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(100f), Dimension.Px(100f)),
                radius = resolveFloat(n, "radius", 8f, tree),
                color = resolveColorAttr(n, "color", tree) ?: 0x66000000,
                offsetX = resolveFloat(n, "offsetX", 0f, tree),
                offsetY = resolveFloat(n, "offsetY", 4f, tree),
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "border" -> FixedElement.Border(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(100f), Dimension.Px(100f)),
                strokeWidth = resolveFloat(n, "strokeWidth", 1f, tree),
                color = resolveColorAttr(n, "color", tree) ?: 0xFFFFFFFF.toInt(),
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            "backdrop" -> FixedElement.Backdrop(
                position = base.position,
                anchor = base.anchor,
                visibility = base.visibility,
                zIndex = base.zIndex,
                declarationOrder = base.declarationOrder,
                highlightColor = base.highlightColor,
                highlightOpacity = base.highlightOpacity,
                highlightBorderWidth = base.highlightBorderWidth,
                highlightTransitionSpeed = base.highlightTransitionSpeed,
                navigationId = base.navigationId,
                navigateUp = base.navigateUp,
                navigateDown = base.navigateDown,
                navigateLeft = base.navigateLeft,
                navigateRight = base.navigateRight,
                size = sizeResolved(n, tree) ?: DimSize(Dimension.Px(100f), Dimension.Px(100f)),
                blurRadius = resolveFloat(n, "blurRadius", 16f, tree),
                tintColor = resolveColorAttr(n, "tintColor", tree),
                cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
                opacity = resolveFloat(n, "opacity", 1f, tree),
            )
            else -> null
        }
    }

    // endregion

    /**
     * Helper class holding common base properties shared by all layer types.
     */
    private data class LayerBase(
        val id: String?,
        val position: DimOffset,
        val size: DimSize?,
        val opacity: FloatOrBinding?,
        val anchor: Anchor,
        val visibility: Visibility,
        val zIndex: Float,
        val declarationOrder: Int,
        val focusOnly: Boolean,
        val focusTransitionSpeed: Int,
        val visibleWhen: String?,
    )
    
    /** Extract common base properties from an XML node. */
    private fun parseLayerBase(n: XmlNode, tree: ThemeTree, declarationOrder: Int) = LayerBase(
        id = n.attributes["id"],
        position = DimOffset(pxResolved(n, "x", tree), pxResolved(n, "y", tree)),
        size = sizeResolved(n, tree),
        opacity = floatBindingResolved(n.attributes["opacity"], tree),
        anchor = Anchor.fromString(n.attributes["anchor"]),
        visibility = Visibility.fromString(n.attributes["visibility"]),
        zIndex = resolveFloat(n, "zIndex", default = 0f, tree),
        declarationOrder = declarationOrder,
        focusOnly = n.attributes["focusOnly"]?.toBooleanStrictOrNull() ?: false,
        focusTransitionSpeed = resolveInt(n, "focusTransitionSpeed", tree) ?: 150,
        visibleWhen = n.attributes["visibleWhen"],
    )

    private fun parseLayer(n: XmlNode, tree: ThemeTree, declarationOrder: Int): Layer? {
        val base = parseLayerBase(n, tree, declarationOrder)
        
        return when (n.name.lowercase()) {
        "image" -> Layer.ImageLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            source = MediaSource.Image(
                src = stringBinding(n.attributes["src"]) ?: StringOrBinding.Literal(""),
                fallback = stringBinding(n.attributes["fallback"]),
            ),
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
            tintColor = intBindingResolved(n.attributes["tint"], tree),
            scaleType = n.attributes["scaleType"] ?: "cover",
        )
        "video" -> Layer.VideoLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
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
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
        )
        // Support both "rect" (new) and "overlay" (legacy) for rectangle shapes
        "rect", "overlay" -> Layer.RectLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            color = intBindingResolved(n.attributes["color"], tree) ?: IntOrBinding.Literal(0x88000000.toInt()),
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
            borderWidth = floatBindingResolved(n.attributes["borderWidth"], tree),
            borderColor = intBindingResolved(n.attributes["borderColor"], tree),
            gradientStart = intBindingResolved(n.attributes["gradientStart"], tree),
            gradientEnd = intBindingResolved(n.attributes["gradientEnd"], tree),
            gradientAngle = floatBindingResolved(n.attributes["gradientAngle"], tree),
        )
        "shadow" -> Layer.ShadowLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            radius = floatBindingResolved(n.attributes["radius"], tree) ?: FloatOrBinding.Literal(8f),
            color = intBindingResolved(n.attributes["color"], tree) ?: IntOrBinding.Literal(0x80000000.toInt()),
            offset = DimOffset(pxResolved(n, "dx", tree), pxResolved(n, "dy", tree)),
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
        )
        "border" -> Layer.BorderLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            strokeWidth = floatBindingResolved(n.attributes["strokeWidth"], tree) ?: FloatOrBinding.Literal(2f),
            color = intBindingResolved(n.attributes["color"], tree) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
        )
        "text" -> Layer.TextLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            text = stringBinding(n.attributes["text"]) ?: StringOrBinding.Literal(""),
            color = intBindingResolved(n.attributes["color"], tree) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            textSize = floatBindingResolved(n.attributes["textSize"], tree) ?: FloatOrBinding.Literal(18f),
            maxLines = resolveInt(n, "maxLines", tree),
            textAlign = n.attributes["textAlign"] ?: "left",
            fontWeight = n.attributes["fontWeight"] ?: "normal",
            fontStyle = n.attributes["fontStyle"] ?: "normal",
            lineHeight = floatBindingResolved(n.attributes["lineHeight"], tree),
            letterSpacing = floatBindingResolved(n.attributes["letterSpacing"], tree),
            textDecoration = n.attributes["textDecoration"] ?: "none",
            overflow = n.attributes["overflow"] ?: "ellipsis",
        )
        "backdrop" -> Layer.BackdropLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            visibility = base.visibility,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            blurRadius = floatBindingResolved(n.attributes["blurRadius"], tree),
            tintColor = intBindingResolved(n.attributes["tint"], tree),
        )
        "button" -> Layer.ButtonLayer(
            id = base.id,
            position = base.position,
            size = base.size,
            opacity = base.opacity,
            anchor = base.anchor,
            zIndex = base.zIndex,
            declarationOrder = base.declarationOrder,
            focusOnly = base.focusOnly,
            focusTransitionSpeed = base.focusTransitionSpeed,
            visibleWhen = base.visibleWhen,
            visibility = base.visibility,
            text = stringBinding(n.attributes["text"]) ?: StringOrBinding.Literal(""),
            backgroundColor = intBindingResolved(n.attributes["backgroundColor"], tree) ?: IntOrBinding.Literal(0xFFE91E63.toInt()),
            textColor = intBindingResolved(n.attributes["textColor"], tree) ?: IntOrBinding.Literal(0xFFFFFFFF.toInt()),
            textSize = floatBindingResolved(n.attributes["textSize"], tree) ?: FloatOrBinding.Literal(14f),
            cornerRadius = resolveStringAttr(n, "cornerRadius", tree),
            borderWidth = floatBindingResolved(n.attributes["borderWidth"], tree),
            borderColor = intBindingResolved(n.attributes["borderColor"], tree),
            fontWeight = n.attributes["fontWeight"] ?: "normal",
            padding = n.attributes["padding"],
        )
        else -> null
        }
    }
    // endregion

    // region Layout
    
    /**
     * Result of parsing the layout section, containing ordered layout elements
     * and any inline card definitions found inside grid/carousel elements.
     */
    private data class LayoutParseResult(
        val layoutElements: List<LayoutElement>,
        val inlineCards: List<Card>,
    )
    
    /**
     * Result of parsing a grid or carousel node, containing the layout node and
     * any inline card definition found inside it.
     */
    private data class LayoutNodeWithCard(
        val node: LayoutNode,
        val inlineCard: Card?,
    )
    
    /**
     * Parse layout and extract elements in declaration order.
     * This supports the new format where <fixed> and <element ref="..."> can appear inside <layout>,
     * and <card> can be defined inline inside <grid> or <carousel>.
     * 
     * Elements are returned in declaration order with optional zIndex for explicit z-ordering.
     * 
     * @return LayoutParseResult containing ordered layout elements and inline cards
     */
    private fun parseLayoutWithFixedContainers(
        root: XmlNode, 
        tree: ThemeTree,
        fixedContainerLookup: Map<String, FixedContainer>
    ): LayoutParseResult {
        val layoutRoot = root.children.firstOrNull { it.name.equals("layout", ignoreCase = true) }
            ?: error("<layout> element not found in theme.xml")
        
        val layoutElements = mutableListOf<LayoutElement>()
        val inlineCards = mutableListOf<Card>()
        var declarationIndex = 0
        var hasContentNode = false
        
        // Process children in declaration order
        for (child in layoutRoot.children) {
            val zIndex = child.attributes["zIndex"]?.toIntOrNull()
            
            when (child.name.lowercase()) {
                // Inline <fixed> container definition
                "fixed" -> {
                    val container = parseFixedContainerNode(child, tree)
                    layoutElements.add(LayoutElement.Fixed(
                        container = container,
                        zIndex = zIndex,
                        declarationOrder = declarationIndex++,
                    ))
                }
                // Reference to pre-defined element: <element ref="id" />
                "element" -> {
                    val ref = child.attributes["ref"]
                    if (ref != null) {
                        val referencedContainer = fixedContainerLookup[ref]
                        if (referencedContainer != null) {
                            layoutElements.add(LayoutElement.Fixed(
                                container = referencedContainer,
                                zIndex = zIndex,
                                declarationOrder = declarationIndex++,
                            ))
                        } else {
                            // Element reference not found - could log warning here
                        }
                    }
                }
                // Layout nodes (only one expected)
                "canvas" -> {
                    if (!hasContentNode) {
                        hasContentNode = true
                        layoutElements.add(LayoutElement.Content(
                            node = parseCanvas(child, tree),
                            zIndex = zIndex,
                            declarationOrder = declarationIndex++,
                        ))
                    }
                }
                "grid" -> {
                    if (!hasContentNode) {
                        hasContentNode = true
                        val result = parseGridWithInlineCard(child, tree)
                        layoutElements.add(LayoutElement.Content(
                            node = result.node,
                            zIndex = zIndex,
                            declarationOrder = declarationIndex++,
                        ))
                        result.inlineCard?.let { inlineCards.add(it) }
                    }
                }
                "carousel" -> {
                    if (!hasContentNode) {
                        hasContentNode = true
                        val result = parseCarouselWithInlineCard(child, tree)
                        layoutElements.add(LayoutElement.Content(
                            node = result.node,
                            zIndex = zIndex,
                            declarationOrder = declarationIndex++,
                        ))
                        result.inlineCard?.let { inlineCards.add(it) }
                    }
                }
                // Ignore other nodes (like include which is handled by IncludeResolver)
            }
        }
        
        // Ensure at least one content node was found
        if (!hasContentNode) {
            error("<layout> must contain a layout node (canvas/grid/carousel)")
        }
        
        return LayoutParseResult(layoutElements, inlineCards)
    }
    
    /** Legacy layout parser (backwards compatibility). */
    private fun parseLayout(root: XmlNode, tree: ThemeTree): LayoutNode {
        val layoutRoot = root.children.firstOrNull { it.name.equals("layout", ignoreCase = true) }
            ?: error("<layout> element not found in theme.xml")
        // Only one root layout child is supported (canvas/grid/carousel)
        val child = layoutRoot.children.firstOrNull { 
            it.name.lowercase() in listOf("canvas", "grid", "carousel")
        } ?: error("<layout> must contain a root layout node (canvas/grid/carousel)")
        return when (child.name.lowercase()) {
            "canvas" -> parseCanvas(child, tree)
            "grid" -> parseGrid(child, tree)
            "carousel" -> parseCarousel(child, tree)
            else -> error("Unknown layout node: ${child.name}")
        }
    }

    private fun parseCanvas(node: XmlNode, tree: ThemeTree): LayoutNode.Canvas {
        // Default to 100% width/height if not specified
        val w = resolveDimensionWidth(node, "width", tree) ?: Dimension.RelW(1f)
        val h = resolveDimensionHeight(node, "height", tree) ?: Dimension.RelH(1f)
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
        val cols = resolveInt(node, "columns", tree)
        val rows = resolveInt(node, "rows", tree)
        // cellWidth defaults to 100% (single column) if not specified
        val cellW = resolveDimensionWidth(node, "cellWidth", tree) ?: Dimension.RelW(1f)
        // cellHeight is optional - if not specified, aspectRatio or card height will be used
        val cellH = resolveDimensionHeight(node, "cellHeight", tree)
        // aspectRatio for automatic height calculation (width/height, e.g. 2.14 for hero, 0.67 for capsule)
        val aspectRatio = resolveFloatOrNull(node, "aspectRatio", tree)
        
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
            val sepHeight = resolveDimensionHeight(sepNode, "height", tree) ?: Dimension.Px(1f)
            val sepLayers = sepNode.children.mapIndexedNotNull { index, child -> parseLayer(child, tree, index) }
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

        // Navigation attributes
        val navigationId = node.attributes["navigationId"]
        val navigateUp = node.attributes["navigateUp"]
        val navigateDown = node.attributes["navigateDown"]
        val navigateLeft = node.attributes["navigateLeft"]
        val navigateRight = node.attributes["navigateRight"]
        
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
            verticalAlign = VerticalAlign.fromString(node.attributes["verticalAlign"]),
            navigationId = navigationId,
            navigateUp = navigateUp,
            navigateDown = navigateDown,
            navigateLeft = navigateLeft,
            navigateRight = navigateRight,
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
        val itemW = resolveDimensionWidth(node, "itemWidth", tree) ?: Dimension.Px(200f)
        val itemH = resolveDimensionHeight(node, "itemHeight", tree) ?: Dimension.Px(200f)
        val spacing = resolveFloat(node, "itemSpacing", default = 0f, tree)
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.STATIONARY
            else -> SelectionMode.STATIONARY
        }
        val pageSize = resolveInt(node, "pageSize", tree)
        // Support both new "itemCard" and legacy "itemTemplate" attribute; default to "default" if not specified
        val itemCard = node.attributes["itemCard"] ?: node.attributes["itemTemplate"] ?: "default"
        
        // Center-focus carousel attributes
        val centerFocus = node.attributes["centerFocus"]?.toBooleanStrictOrNull() ?: false
        // Support both "focusedScale" (new) and "highlightScale" (legacy) attribute names
        val focusedScale = resolveFloatOrNull(node, "focusedScale", tree)
            ?: resolveFloat(node, "highlightScale", default = 1.0f, tree)
        val verticalAlign = VerticalAlign.fromString(node.attributes["verticalAlign"])
        val verticalOffset = resolveDimensionWidth(node, "verticalOffset", tree) ?: Dimension.Px(0f)
        
        // Orientation and alignment (for vertical carousels)
        val orientation = CarouselOrientation.fromString(node.attributes["orientation"])
        val horizontalAlign = HorizontalAlign.fromString(node.attributes["horizontalAlign"])
        val horizontalOffset = resolveDimensionWidth(node, "horizontalOffset", tree) ?: Dimension.Px(0f)
        
        // Focused item offset and spacing
        val focusedOffsetX = resolveFloat(node, "focusedOffsetX", default = 0f, tree)
        val focusedOffsetY = resolveFloat(node, "focusedOffsetY", default = 0f, tree)
        val focusedSpacing = resolveFloat(node, "focusedSpacing", default = 0f, tree)
        
        // Background image attributes
        val focusedBackground = node.attributes["focusedBackground"]?.let { stringBinding(it) }
        val backgroundOpacity = resolveFloat(node, "backgroundOpacity", default = 0.3f, tree)
        val backgroundTransitionSpeed = resolveInt(node, "backgroundTransitionSpeed", tree) ?: 400
        
        return LayoutNode.Carousel(
            direction = dir,
            orientation = orientation,
            itemSize = DimSize(itemW, itemH),
            itemSpacing = spacing,
            selectionMode = sel,
            itemCard = itemCard,
            pageSize = pageSize,
            centerFocus = centerFocus,
            focusedScale = focusedScale,
            verticalAlign = verticalAlign,
            verticalOffset = verticalOffset,
            horizontalAlign = horizontalAlign,
            horizontalOffset = horizontalOffset,
            focusedOffsetX = focusedOffsetX,
            focusedOffsetY = focusedOffsetY,
            focusedSpacing = focusedSpacing,
            focusedBackground = focusedBackground,
            backgroundOpacity = backgroundOpacity,
            backgroundTransitionSpeed = backgroundTransitionSpeed,
        )
    }
    
    /**
     * Parse a grid node with support for inline card definitions.
     * If a <card> child is found inside the grid, it will be parsed and used as the item card.
     * Otherwise, the itemCard attribute is used to reference a pre-defined card.
     */
    private fun parseGridWithInlineCard(node: XmlNode, tree: ThemeTree): LayoutNodeWithCard {
        // Check for inline <card> definition
        val inlineCardNode = node.children.firstOrNull { it.name.equals("card", ignoreCase = true) }
        
        val inlineCard = inlineCardNode?.let { cardNode ->
            // Generate ID: use explicit id attribute, or auto-generate from grid context
            val cardId = cardNode.attributes["id"] ?: "inline_grid_card_${System.nanoTime()}"
            val width = resolveDimensionWidth(cardNode, "width", tree) ?: Dimension.RelW(1f)
            val height = resolveDimensionHeight(cardNode, "height", tree) ?: Dimension.RelH(1f)
            val layers = cardNode.children.mapIndexedNotNull { index, child -> parseLayer(child, tree, index) }
            Card(id = cardId, canvas = DimSize(width, height), layers = layers)
        }
        
        // Parse the grid, using inline card's ID if present, otherwise use the attribute
        val effectiveItemCard = inlineCard?.id 
            ?: node.attributes["itemCard"] 
            ?: node.attributes["itemTemplate"] 
            ?: "default"
        
        // Parse grid with the effective item card ID
        val grid = parseGridInternal(node, tree, effectiveItemCard)
        
        return LayoutNodeWithCard(grid, inlineCard)
    }
    
    /** Internal grid parser that takes the item card ID as a parameter. */
    private fun parseGridInternal(node: XmlNode, tree: ThemeTree, itemCardId: String): LayoutNode.Grid {
        val cols = resolveInt(node, "columns", tree)
        val rows = resolveInt(node, "rows", tree)
        val cellW = resolveDimensionWidth(node, "cellWidth", tree) ?: Dimension.RelW(1f)
        val cellH = resolveDimensionHeight(node, "cellHeight", tree)
        val aspectRatio = resolveFloatOrNull(node, "aspectRatio", tree)
        
        val cellSpacing = resolveFloat(node, "cellSpacing", default = 0f, tree)
        val hSpacing = resolveFloat(node, "hSpacing", default = cellSpacing, tree)
        val vSpacing = resolveFloat(node, "vSpacing", default = cellSpacing, tree)
        
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.MOVING
            else -> SelectionMode.MOVING
        }
        
        val (paddingTop, paddingEnd, paddingBottom, paddingStart) = parsePadding(node.attributes["padding"], tree)
        
        // Parse optional separator (skip <card> children)
        val separator = node.children.firstOrNull { it.name.equals("separator", ignoreCase = true) }?.let { sepNode ->
            val sepHeight = resolveDimensionHeight(sepNode, "height", tree) ?: Dimension.Px(1f)
            val sepLayers = sepNode.children.mapIndexedNotNull { index, child -> parseLayer(child, tree, index) }
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

        // Navigation attributes
        val navigationId = node.attributes["navigationId"]
        val navigateUp = node.attributes["navigateUp"]
        val navigateDown = node.attributes["navigateDown"]
        val navigateLeft = node.attributes["navigateLeft"]
        val navigateRight = node.attributes["navigateRight"]

        return LayoutNode.Grid(
            columns = cols,
            rows = rows,
            cellWidth = cellW,
            cellHeight = cellH,
            aspectRatio = aspectRatio,
            hSpacing = hSpacing,
            vSpacing = vSpacing,
            selectionMode = sel,
            itemCard = itemCardId,
            contentPaddingTop = paddingTop,
            contentPaddingBottom = paddingBottom,
            contentPaddingStart = paddingStart,
            contentPaddingEnd = paddingEnd,
            separator = separator,
            verticalAlign = VerticalAlign.fromString(node.attributes["verticalAlign"]),
            navigationId = navigationId,
            navigateUp = navigateUp,
            navigateDown = navigateDown,
            navigateLeft = navigateLeft,
            navigateRight = navigateRight,
        )
    }

    /**
     * Parse a carousel node with support for inline card definitions.
     * If a <card> child is found inside the carousel, it will be parsed and used as the item card.
     * Otherwise, the itemCard attribute is used to reference a pre-defined card.
     */
    private fun parseCarouselWithInlineCard(node: XmlNode, tree: ThemeTree): LayoutNodeWithCard {
        // Check for inline <card> definition
        val inlineCardNode = node.children.firstOrNull { it.name.equals("card", ignoreCase = true) }
        
        val inlineCard = inlineCardNode?.let { cardNode ->
            // Generate ID: use explicit id attribute, or auto-generate from carousel context
            val cardId = cardNode.attributes["id"] ?: "inline_carousel_card_${System.nanoTime()}"
            val width = resolveDimensionWidth(cardNode, "width", tree) ?: Dimension.RelW(1f)
            val height = resolveDimensionHeight(cardNode, "height", tree) ?: Dimension.RelH(1f)
            val layers = cardNode.children.mapIndexedNotNull { index, child -> parseLayer(child, tree, index) }
            Card(id = cardId, canvas = DimSize(width, height), layers = layers)
        }
        
        // Parse the carousel, using inline card's ID if present, otherwise use the attribute
        val effectiveItemCard = inlineCard?.id 
            ?: node.attributes["itemCard"] 
            ?: node.attributes["itemTemplate"] 
            ?: "default"
        
        // Parse carousel with the effective item card ID
        val carousel = parseCarouselInternal(node, tree, effectiveItemCard)
        
        return LayoutNodeWithCard(carousel, inlineCard)
    }
    
    /** Internal carousel parser that takes the item card ID as a parameter. */
    private fun parseCarouselInternal(node: XmlNode, tree: ThemeTree, itemCardId: String): LayoutNode.Carousel {
        val dir = when (node.attributes["direction"]?.lowercase()) {
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            else -> Direction.RIGHT
        }
        val orientation = CarouselOrientation.fromString(node.attributes["orientation"])
        val itemW = resolveDimensionWidth(node, "itemWidth", tree) ?: Dimension.Px(200f)
        val itemH = resolveDimensionHeight(node, "itemHeight", tree) ?: Dimension.Px(200f)
        val spacing = resolveFloat(node, "itemSpacing", default = 0f, tree)
        val sel = when (node.attributes["selectionMode"]?.lowercase()) {
            "stationary" -> SelectionMode.STATIONARY
            "moving" -> SelectionMode.MOVING
            null -> SelectionMode.STATIONARY
            else -> SelectionMode.STATIONARY
        }
        val pageSize = resolveInt(node, "pageSize", tree)
        
        val centerFocus = node.attributes["centerFocus"]?.toBooleanStrictOrNull() ?: false
        // Support both "focusedScale" (new) and "highlightScale" (legacy) attribute names
        val focusedScale = resolveFloatOrNull(node, "focusedScale", tree)
            ?: resolveFloat(node, "highlightScale", default = 1.0f, tree)
        val verticalAlign = VerticalAlign.fromString(node.attributes["verticalAlign"])
        val verticalOffset = resolveDimensionWidth(node, "verticalOffset", tree) ?: Dimension.Px(0f)
        val horizontalAlign = HorizontalAlign.fromString(node.attributes["horizontalAlign"])
        val horizontalOffset = resolveDimensionWidth(node, "horizontalOffset", tree) ?: Dimension.Px(0f)
        
        val focusedOffsetX = resolveFloat(node, "focusedOffsetX", default = 0f, tree)
        val focusedOffsetY = resolveFloat(node, "focusedOffsetY", default = 0f, tree)
        val focusedSpacing = resolveFloat(node, "focusedSpacing", default = 0f, tree)
        
        val focusedBackground = node.attributes["focusedBackground"]?.let { stringBinding(it) }
        val backgroundOpacity = resolveFloat(node, "backgroundOpacity", default = 0.3f, tree)
        val backgroundTransitionSpeed = resolveInt(node, "backgroundTransitionSpeed", tree) ?: 400

        // Navigation attributes
        val navigationId = node.attributes["navigationId"]
        val navigateUp = node.attributes["navigateUp"]
        val navigateDown = node.attributes["navigateDown"]
        val navigateLeft = node.attributes["navigateLeft"]
        val navigateRight = node.attributes["navigateRight"]

        return LayoutNode.Carousel(
            direction = dir,
            orientation = orientation,
            itemSize = DimSize(itemW, itemH),
            itemSpacing = spacing,
            selectionMode = sel,
            itemCard = itemCardId,
            pageSize = pageSize,
            centerFocus = centerFocus,
            focusedScale = focusedScale,
            verticalAlign = verticalAlign,
            verticalOffset = verticalOffset,
            horizontalAlign = horizontalAlign,
            horizontalOffset = horizontalOffset,
            focusedOffsetX = focusedOffsetX,
            focusedOffsetY = focusedOffsetY,
            focusedSpacing = focusedSpacing,
            focusedBackground = focusedBackground,
            backgroundOpacity = backgroundOpacity,
            backgroundTransitionSpeed = backgroundTransitionSpeed,
            navigationId = navigationId,
            navigateUp = navigateUp,
            navigateDown = navigateDown,
            navigateLeft = navigateLeft,
            navigateRight = navigateRight,
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

    // ----- Centralized Variable Resolution (delegating to VariableResolver) -----
    
    private fun isBinding(s: String): Boolean = VariableResolver.isBinding(s)
    private fun bindingPath(s: String): String = VariableResolver.getBindingPath(s)
    
    /** 
     * Resolve a raw string value using the variable resolver.
     * Handles both single variable bindings and embedded variables in strings.
     */
    private fun resolveRawValue(value: String?, tree: ThemeTree): String? {
        return VariableResolver.resolveAllVariables(value, tree.variables)
    }

    private fun resolveFloat(node: XmlNode, key: String, default: Float, tree: ThemeTree): Float {
        return VariableResolver.resolveFloat(node.attributes[key], tree.variables, default)
    }

    /** Resolve an optional float that may be a variable reference. Returns null if not present or invalid. */
    private fun resolveFloatOrNull(node: XmlNode, key: String, tree: ThemeTree): Float? {
        return VariableResolver.resolveFloatOrNull(node.attributes[key], tree.variables)
    }

    /** Resolve an optional int that may be a variable reference. Returns null if not present or invalid. */
    private fun resolveInt(node: XmlNode, key: String, tree: ThemeTree): Int? {
        return VariableResolver.resolveIntOrNull(node.attributes[key], tree.variables)
    }

    /** Resolve a string attribute, expanding variable references. */
    private fun resolveString(node: XmlNode, key: String, tree: ThemeTree): String? {
        return VariableResolver.resolveValue(node.attributes[key], tree.variables)
    }

    /** Resolve a dimension (width-relative) that may be a variable reference. */
    private fun resolveDimensionWidth(node: XmlNode, key: String, tree: ThemeTree): Dimension? {
        val resolved = resolveRawValue(node.attributes[key], tree) ?: return null
        return parseDimensionWidth(resolved)
    }

    /** Resolve a dimension (height-relative) that may be a variable reference. */
    private fun resolveDimensionHeight(node: XmlNode, key: String, tree: ThemeTree): Dimension? {
        val resolved = resolveRawValue(node.attributes[key], tree) ?: return null
        return parseDimensionHeight(resolved)
    }

    /** Resolve a position dimension (px) with variable support. */
    private fun pxResolved(n: XmlNode, key: String, tree: ThemeTree): Dimension {
        val resolved = resolveRawValue(n.attributes[key], tree) ?: return Dimension.Px(0f)
        // For x position, use width-relative; for y position, use height-relative
        return if (key == "y" || key == "dy") {
            parseDimensionHeight(resolved) ?: Dimension.Px(0f)
        } else {
            parseDimensionWidth(resolved) ?: Dimension.Px(0f)
        }
    }

    /** Resolve size with variable support. Returns null only if BOTH width and height are missing. */
    private fun sizeResolved(n: XmlNode, tree: ThemeTree): DimSize? {
        val wResolved = resolveRawValue(n.attributes["width"], tree)
        val hResolved = resolveRawValue(n.attributes["height"], tree)
        
        // If both are missing, return null
        if (wResolved == null && hResolved == null) return null

        // Parse dimensions, using null for unspecified values (will use Dimension.Unspecified)
        val w = wResolved?.let { parseDimensionWidth(it) }
        val h = hResolved?.let { parseDimensionHeight(it) }
        
        // Return size with available dimensions (Dimension.Unspecified for missing ones)
        return DimSize(
            w ?: Dimension.Unspecified,
            h ?: Dimension.Unspecified
        )
    }

    /** Create a FloatOrBinding, resolving variable references to literal values. */
    private fun floatBindingResolved(raw: String?, tree: ThemeTree): FloatOrBinding? {
        val s = raw ?: return null
        return when {
            VariableResolver.isVariableBinding(s) -> {
                // Variable binding - resolve to literal
                val resolved = VariableResolver.resolveFloatOrNull(s, tree.variables)
                FloatOrBinding.Literal(resolved ?: 0f)
            }
            isBinding(s) -> {
                // Other binding (e.g., @{game.x}) - keep as reference
                FloatOrBinding.Ref(Binding(bindingPath(s)))
            }
            else -> FloatOrBinding.Literal(s.toFloatOrNull() ?: 0f)
        }
    }

    /** Create an IntOrBinding, resolving variable references to literal values. */
    private fun intBindingResolved(raw: String?, tree: ThemeTree): IntOrBinding? {
        val s = raw ?: return null
        return when {
            VariableResolver.isVariableBinding(s) -> {
                // Variable binding - resolve to literal color
                val resolved = VariableResolver.resolveColorOrNull(s, tree.variables)
                    ?: return null
                IntOrBinding.Literal(resolved)
            }
            isBinding(s) -> {
                // Other binding (e.g., @{game.compatibility.color}) - keep as reference
                IntOrBinding.Ref(Binding(bindingPath(s)))
            }
            isColorRef(s) -> IntOrBinding.Ref(Binding(s)) // Keep @color/primary as binding path
            else -> IntOrBinding.Literal(parseColor(s))
        }
    }

    /** 
     * Resolve a string attribute with variable support. Returns the resolved literal string.
     * Handles both single variable bindings and multi-value strings with embedded variables.
     * Example: "@{vars.radius} @{vars.radius} 0 0" -> "20 20 0 0"
     */
    private fun resolveStringAttr(node: XmlNode, key: String, tree: ThemeTree): String? {
        return VariableResolver.resolveAllVariables(node.attributes[key], tree.variables)
    }

    /** Resolve a color attribute with variable support. Returns the parsed color int. */
    private fun resolveColorAttr(node: XmlNode, key: String, tree: ThemeTree): Int? {
        return VariableResolver.resolveColorOrNull(node.attributes[key], tree.variables)
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
            VariableResolver.resolveFloat(part, tree.variables, 0f)
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
