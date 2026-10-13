package app.gamenative.inputcontrols

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.online.OnlineStoreSignals
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.util.Locale
import org.json.JSONObject
import timber.log.Timber

/**
 * Picks and applies an on-screen control layout (KBM / FPS / RTS / Virtual Gamepad).
 * Used at install (container create) and as a launch-time fallback when no buttons exist.
 */
object AutoControlSetup {
    const val EXTRA_APPLIED = "autoControlSetupApplied"
    const val EXTRA_FORCE_SHOW = "autoControlSetupForceShow"
    const val EXTRA_CHOICE = "controlLayoutChoice"

    /**
     * If enabled and the container has no on-screen button layout, apply the saved
     * (or default) layout choice as a per-game working copy.
     */
    fun ensureOnScreenControls(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        steamAppId: Int? = null,
    ): ControlsProfile? {
        PrefManager.init(context)
        if (!PrefManager.autoControlSetup) {
            return currentProfile(container, manager)
        }

        val current = currentProfile(container, manager)
        if (current != null && hasOnScreenElements(context, current)) {
            return current
        }

        val choice = readChoice(context, container)
        return applyLayout(
            context = context,
            container = container,
            manager = manager,
            choice = choice,
            steamAppId = steamAppId,
            force = false,
        )
    }

    /**
     * Apply [choice] now (install-time or user switch). [force] replaces an existing on-screen layout.
     */
    fun applyLayout(
        context: Context,
        container: Container,
        manager: InputControlsManager? = null,
        choice: ControlLayoutChoice,
        steamAppId: Int? = null,
        force: Boolean = true,
    ): ControlsProfile? {
        PrefManager.init(context)
        val mgr = manager ?: PluviaApp.inputControlsManager ?: InputControlsManager(context)
        val current = currentProfile(container, mgr)
        if (!force && current != null && hasOnScreenElements(context, current)) {
            return current
        }

        ControlLayoutStore.save(context, container.id, choice)
        container.putExtra(EXTRA_CHOICE, choice.storageKey)

        val resolvedAppId = steamAppId ?: runCatching {
            container.id.removePrefix("STEAM_").toIntOrNull()
        }.getOrNull()

        val steamApp = resolvedAppId?.let { runCatching { SteamService.getAppInfoOf(it) }.getOrNull() }
        val tags = buildList {
            resolvedAppId?.let { id -> OnlineStoreSignals.cachedTags(context, id)?.let(::addAll) }
            steamApp?.name?.takeIf { it.isNotBlank() }?.let(::add)
            container.name.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        val discovered = resolvedAppId?.let { resolveSteamKeys(it) }

        val style = resolveStyle(choice, tags, steamApp?.controllerSupport, discovered)
        val applied = when (style) {
            GameKeyboardLayoutFactory.Style.GAMEPAD ->
                applyBuiltInGamepad(context, container, mgr, current, tags)
            else -> applyGeneratedLayout(
                context = context,
                container = container,
                manager = mgr,
                current = current,
                style = style,
                gameName = steamApp?.name ?: container.name,
                discovered = discovered,
            )
        }
        container.putExtra(EXTRA_CHOICE, choice.storageKey)
        container.saveData()
        return applied
    }

    /** Apply a pending/saved choice after the container is created during install. */
    fun applyPendingIfAny(context: Context, container: Container, steamAppId: Int? = null) {
        PrefManager.init(context)
        if (!PrefManager.autoControlSetup) return
        val choice = ControlLayoutStore.get(context, container.id)
            ?: ControlLayoutChoice.fromStorageKey(container.getExtra(EXTRA_CHOICE, ""))
            ?: PrefManager.defaultControlLayout
        // Skip if already applied with the same choice and buttons exist.
        val current = currentProfile(container, PluviaApp.inputControlsManager ?: InputControlsManager(context))
        val savedChoice = ControlLayoutChoice.fromStorageKey(container.getExtra(EXTRA_CHOICE, ""))
        if (current != null &&
            hasOnScreenElements(context, current) &&
            savedChoice == choice &&
            container.getExtra(EXTRA_APPLIED, "0") == "1"
        ) {
            return
        }
        applyLayout(
            context = context,
            container = container,
            choice = choice,
            steamAppId = steamAppId,
            force = true,
        )
    }

    fun readChoice(context: Context, container: Container): ControlLayoutChoice {
        PrefManager.init(context)
        return ControlLayoutChoice.fromStorageKey(container.getExtra(EXTRA_CHOICE, ""))
            ?: ControlLayoutStore.get(context, container.id)
            ?: PrefManager.defaultControlLayout
    }

    fun shouldForceShowControls(container: Container): Boolean {
        return container.getExtra(EXTRA_FORCE_SHOW, "0") == "1"
    }

    fun clearForceShowFlag(container: Container) {
        if (container.getExtra(EXTRA_FORCE_SHOW, "0") != "1") return
        container.putExtra(EXTRA_FORCE_SHOW, "0")
        container.saveData()
    }

    fun hasOnScreenElements(context: Context, profile: ControlsProfile): Boolean {
        if (profile.isElementsLoaded && profile.elements.isNotEmpty()) return true
        val file = ControlsProfile.getProfileFile(context, profile.id)
        if (!file.isFile) return false
        return runCatching {
            val json = JSONObject(FileUtils.readString(file))
            json.optJSONArray("elements")?.length()?.let { it > 0 } == true
        }.getOrDefault(false)
    }

    private fun resolveStyle(
        choice: ControlLayoutChoice,
        tags: Collection<String>,
        controllerSupport: app.gamenative.enums.ControllerSupport?,
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
    ): GameKeyboardLayoutFactory.Style {
        choice.toStyleOrNull()?.let { return it }

        var style = GameKeyboardLayoutFactory.resolveStyle(tags, controllerSupport)
        if (style == GameKeyboardLayoutFactory.Style.GAMEPAD && discovered?.hasKeyboardKeys == true) {
            style = if (tags.any { it.contains("fps", ignoreCase = true) || it.contains("shooter", ignoreCase = true) }) {
                GameKeyboardLayoutFactory.Style.FPS
            } else {
                GameKeyboardLayoutFactory.Style.KEYBOARD_MOUSE
            }
            Timber.i("AutoControlSetup: Steam Input keys found — using %s instead of Virtual Gamepad", style)
        }
        return style
    }

    private fun resolveSteamKeys(steamAppId: Int): SteamInputKeyBindingExtractor.DiscoveredKeys? {
        val vdf = runCatching { SteamService.resolveSteamControllerVdfText(steamAppId) }.getOrNull()
            ?: return null
        return SteamInputKeyBindingExtractor.extractFromVdf(vdf).also { keys ->
            if (keys != null) {
                Timber.d(
                    "AutoControlSetup: Steam keys move=%s actions=%s mouseLook=%s",
                    keys.move,
                    keys.actions.map { it.binding },
                    keys.hasMouseLook,
                )
            }
        }
    }

    private fun applyGeneratedLayout(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        current: ControlsProfile?,
        style: GameKeyboardLayoutFactory.Style,
        gameName: String?,
        discovered: SteamInputKeyBindingExtractor.DiscoveredKeys?,
    ): ControlsProfile? {
        return runCatching {
            val generated = GameKeyboardLayoutFactory.buildWorkingProfileJson(
                profileId = 0,
                containerId = container.id,
                style = style,
                gameName = gameName,
                discovered = discovered,
            )
            val applied = ControlProfileService.applyGeneratedOnScreenProfile(
                context = context,
                container = container,
                manager = manager,
                generated = generated,
            )
            markApplied(container)
            Timber.i(
                "AutoControlSetup: applied generated %s '%s' (id=%s) to container %s",
                style,
                applied.name,
                applied.id,
                container.id,
            )
            applied
        }.onFailure {
            Timber.w(it, "AutoControlSetup: generated %s layout failed; falling back to Virtual Gamepad", style)
        }.getOrElse {
            applyBuiltInGamepad(context, container, manager, current, emptyList())
        }
    }

    private fun applyBuiltInGamepad(
        context: Context,
        container: Container,
        manager: InputControlsManager,
        current: ControlsProfile?,
        tags: Collection<String>,
    ): ControlsProfile? {
        val source = pickBuiltInLayout(manager, context, tags)
            ?: run {
                Timber.w("AutoControlSetup: no built-in on-screen profile found")
                return current
            }

        return runCatching {
            val applied = ControlProfileService.applyProfile(
                context = context,
                container = container,
                manager = manager,
                source = source,
                selectedSections = setOf(ControlProfileSection.ON_SCREEN),
            )
            markApplied(container)
            Timber.i(
                "AutoControlSetup: applied built-in '%s' (id=%s) to container %s",
                source.name,
                applied.id,
                container.id,
            )
            applied
        }.onFailure {
            Timber.w(it, "AutoControlSetup: failed to apply '%s'", source.name)
        }.getOrDefault(current)
    }

    private fun markApplied(container: Container) {
        container.putExtra(EXTRA_APPLIED, "1")
        container.putExtra(EXTRA_FORCE_SHOW, "1")
        container.saveData()
    }

    private fun currentProfile(container: Container, manager: InputControlsManager): ControlsProfile? {
        val profileId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
        return if (profileId != 0) {
            manager.getProfile(profileId)
        } else {
            manager.getProfile(0) ?: manager.getProfiles(false).firstOrNull()
        }
    }

    private fun pickBuiltInLayout(
        manager: InputControlsManager,
        context: Context,
        tags: Collection<String>,
    ): ControlsProfile? {
        val listed = manager.getProfiles(false)
        val withButtons = listed.filter { hasOnScreenElements(context, it) }
        if (withButtons.isEmpty()) return null

        val preferredNames = preferredNameOrder(tags)
        for (needle in preferredNames) {
            withButtons.firstOrNull { it.name.lowercase(Locale.ROOT).contains(needle) }?.let { return it }
        }
        return withButtons.firstOrNull {
            val n = it.name.lowercase(Locale.ROOT)
            n.contains("virtual") || n.contains("hybrid") || n.contains("gamepad")
        } ?: withButtons.first()
    }

    private fun preferredNameOrder(tags: Collection<String>): List<String> {
        val joined = tags.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            joined.contains("fps") || joined.contains("shooter") ->
                listOf("fps", "hybrid", "virtual gamepad", "virtual", "gamepad")
            joined.contains("rts") || joined.contains("strategy") ->
                listOf("rts", "template", "virtual gamepad", "virtual", "gamepad")
            else -> listOf("virtual gamepad", "hybrid", "virtual", "gamepad", "template", "fps")
        }
    }
}
