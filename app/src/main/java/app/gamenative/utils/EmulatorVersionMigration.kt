package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import timber.log.Timber

/**
 * The Modern flavor bundles only the default Box64 / WoWBox64 builds and no FEX build; older
 * versions ship with the Legacy flavors only. A container pinned to a version that is neither
 * bundled, installed nor installable from the manifest would fail to launch, so move it to the
 * current default before the launch installs whatever the container references.
 */
object EmulatorVersionMigration {
    private const val GLIBC_DEFAULT_BOX64 = "0.3.6"

    suspend fun apply(context: Context, container: Container) {
        try {
            val installed = ManifestComponentHelper.loadInstalledContentLists(context).installed
            val manifest = runCatching { ManifestRepository.loadManifest(context) }.getOrNull()
            fun inManifest(type: String, version: String) =
                ManifestComponentHelper.versionExists(version, manifest?.items?.get(type).orEmpty().map { it.id })
            fun bundled(path: String) = runCatching { context.assets.open(path).close() }.isSuccess

            var changed = false
            val bionic = container.containerVariant.equals(Container.BIONIC, ignoreCase = true)
            val arm64ec = container.wineVersion.contains("arm64ec", ignoreCase = true)

            val box64 = container.box64Version.orEmpty()
            val box64Available = when {
                box64.isEmpty() -> true
                !bionic -> bundled("box86_64/box64-$box64.tzst") ||
                    ManifestComponentHelper.versionExists(box64, installed.box64)
                // arm64ec Wine runs 32-bit code through WoWBox64, which follows the Box64 version.
                arm64ec -> bundled("wowbox64/wowbox64-$box64.tzst") ||
                    ManifestComponentHelper.versionExists(box64, installed.wowBox64) ||
                    inManifest(ManifestContentTypes.WOWBOX64, box64)
                else -> bundled("box86_64/box64-$box64-bionic.tzst") ||
                    ManifestComponentHelper.versionExists(box64, installed.box64) ||
                    inManifest(ManifestContentTypes.BOX64, box64)
            }
            if (!box64Available) {
                val fallback = if (bionic) DefaultVersion.BOX64 else GLIBC_DEFAULT_BOX64
                Timber.i("Box64 $box64 is no longer available, moving container ${container.id} to $fallback")
                container.box64Version = fallback
                changed = true
            }

            val fex = container.fexCoreVersion.orEmpty()
            if (bionic && fex.isNotEmpty() &&
                !bundled("fexcore/fexcore-$fex.tzst") &&
                !ManifestComponentHelper.versionExists(fex, installed.fexcore) &&
                !inManifest(ManifestContentTypes.FEXCORE, fex)
            ) {
                Timber.i("FEXCore $fex is no longer available, moving container ${container.id} to ${DefaultVersion.FEXCORE}")
                container.fexCoreVersion = DefaultVersion.FEXCORE
                changed = true
            }

            if (changed) container.saveData()
        } catch (e: Exception) {
            Timber.w(e, "Emulator version migration failed for container ${container.id}")
        }
    }
}
