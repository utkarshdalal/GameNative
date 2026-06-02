package app.gamenative.html5.profile

import android.content.Context
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import timber.log.Timber

// resolves the active EngineProfile for an html5 launch:
// 1. pack default from assets/html5/packs/<pack>.json
// 2. optional title overrides from assets/html5/packs/<pack>-patches.json[byAppId][appId]
// missing pack file = null. missing patches file = pack defaults only.
object ProfileRegistry {
    private val json = Json { ignoreUnknownKeys = true }

    private const val PACK_PATH_TEMPLATE = "html5/packs/%s.json"
    private const val PATCHES_PATH_TEMPLATE = "html5/packs/%s-patches.json"

    fun loadPackDefaults(context: Context, packId: String): EngineProfile? {
        if (packId.isBlank()) return null
        val shortId = packId.removePrefix("pack:")
        val body = readAsset(context, PACK_PATH_TEMPLATE.format(shortId)) ?: return null
        return decode("pack:$shortId", body, EngineProfile.serializer())
    }

    fun resolveProfile(context: Context, appId: String?, engineId: String): EngineProfile? {
        val pack = loadPackDefaults(context, engineId) ?: return null
        if (appId.isNullOrBlank()) return pack
        val overrides = loadPatchOverrides(context, engineId, appId) ?: return pack
        return applyOverrides(pack, overrides)
    }

    // visible for tests
    internal fun applyOverrides(pack: EngineProfile, overrides: PatchOverrides): EngineProfile = pack.copy(
        patches = pack.patches + overrides.patches,
        shims = pack.shims + overrides.shims,
        gamepadKeySynthesisMap = overrides.gamepadKeySynthesisMap ?: pack.gamepadKeySynthesisMap,
        overlay = overrides.overlay ?: pack.overlay,
        saves = overrides.saves ?: pack.saves,
        input = overrides.input ?: pack.input,
        workerShim = overrides.workerShim ?: pack.workerShim,
        desktopUaSpoof = overrides.desktopUaSpoof ?: pack.desktopUaSpoof,
        fsBridgeOnly = overrides.fsBridgeOnly ?: pack.fsBridgeOnly,
    )

    private fun loadPatchOverrides(context: Context, packId: String, appId: String): PatchOverrides? {
        val shortId = packId.removePrefix("pack:")
        val body = readAsset(context, PATCHES_PATH_TEMPLATE.format(shortId)) ?: return null
        val registry = decode("patches:$shortId", body, PatchRegistry.serializer()) ?: return null
        return registry.byAppId[appId]
    }

    private fun <T> decode(reason: String, body: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString(serializer, body)
        } catch (e: Exception) {
            Timber.tag("ProfileRegistry").e(e, "decode failed for %s", reason)
            null
        }
    }

    private fun readAsset(context: Context, path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Exception) {
            Timber.tag("ProfileRegistry").e(e, "read failed for %s", path)
            null
        }
    }
}
