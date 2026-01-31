package app.gamenative.utils

import android.content.Context
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.GPUHelper
import com.winlator.core.StringUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object ManifestComponentHelper {
    data class InstalledContentLists(
        val dxvk: List<String>,
        val vkd3d: List<String>,
        val box64: List<String>,
        val wowBox64: List<String>,
        val fexcore: List<String>,
        val wine: List<String>,
        val proton: List<String>,
    )

    data class InstalledContentListsAndDrivers(
        val installed: InstalledContentLists,
        val installedDrivers: List<String>,
    )

    data class ComponentAvailability(
        val manifest: ManifestData,
        val installed: InstalledContentLists,
        val installedDrivers: List<String>,
    )

    data class VersionOption(
        val label: String,
        val id: String,
        val isManifest: Boolean,
        val isInstalled: Boolean,
    )

    data class VersionOptionList(
        val labels: List<String>,
        val ids: List<String>,
        val muted: List<Boolean>,
    )

    fun filterManifestByVariant(entries: List<ManifestEntry>, variant: String?): List<ManifestEntry> {
        return entries.filter { entry -> entry.variant?.lowercase(Locale.ENGLISH) == variant?.lowercase(Locale.ENGLISH) }
    }

    suspend fun loadInstalledContentLists(
        context: Context,
    ): InstalledContentListsAndDrivers = withContext(Dispatchers.IO) {
        val installedDrivers = AdrenotoolsManager(context).enumarateInstalledDrivers()

        val installedContent = try {
            val mgr = ContentsManager(context)
            mgr.syncContents()

            fun profilesToDisplay(
                list: List<ContentProfile>?,
            ): List<String> {
                if (list == null) return emptyList()
                return list.filter { profile -> profile.remoteUrl == null }.map { profile ->
                    val entry = ContentsManager.getEntryName(profile)
                    val firstDash = entry.indexOf('-')
                    if (firstDash >= 0 && firstDash + 1 < entry.length) entry.substring(firstDash + 1) else entry
                }
            }

            InstalledContentLists(
                dxvk = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK),
                ),
                vkd3d = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D),
                ),
                box64 = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_BOX64),
                ),
                wowBox64 = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64),
                ),
                fexcore = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE),
                ),
                wine = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE),
                ),
                proton = profilesToDisplay(
                    mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON),
                ),
            )
        } catch (_: Exception) {
            InstalledContentLists(
                dxvk = emptyList(),
                vkd3d = emptyList(),
                box64 = emptyList(),
                wowBox64 = emptyList(),
                fexcore = emptyList(),
                wine = emptyList(),
                proton = emptyList(),
            )
        }

        InstalledContentListsAndDrivers(
            installed = installedContent,
            installedDrivers = installedDrivers,
        )
    }

    suspend fun loadComponentAvailability(context: Context): ComponentAvailability = withContext(Dispatchers.IO) {
        val installed = loadInstalledContentLists(context)
        val manifest = ManifestRepository.loadManifest(context)
        ComponentAvailability(
            manifest = manifest,
            installed = installed.installed,
            installedDrivers = installed.installedDrivers,
        )
    }

    fun buildAvailableVersions(base: List<String>, installed: List<String>, manifest: List<ManifestEntry>, ): List<String> {
        return (base + installed + manifest.map { it.id }).distinct()
    }

    fun buildVersionOptionList(base: List<String>, installed: List<String>, manifest: List<ManifestEntry>, ): VersionOptionList {
        val options = LinkedHashMap<String, VersionOption>()

        (base + installed).forEach { label ->
            options[label] = VersionOption(label, label, false, true)
        }

        val availableIds = options.keys.toSet()
        manifest.forEach { entry ->
            if (!options.containsKey(entry.id)) {
                val isInstalled = availableIds.contains(entry.id)
                options[entry.id] = VersionOption(entry.id, entry.id, isManifest = true, isInstalled = isInstalled)
            }
        }

        val values = options.values.toList()
        return VersionOptionList(
            labels = values.map { it.label },
            ids = values.map { it.id },
            muted = values.map { it.isManifest && !it.isInstalled },
        )
    }

    data class DxvkContext(
        val isVortekLike: Boolean,
        val labels: List<String>,
        val ids: List<String>,
        val muted: List<Boolean>,
    )

    /**
     * Builds a [DxvkContext] describing the effective DXVK options for the current
     * container / driver / wrapper configuration.
     *
     * This centralizes the logic for:
     * - Detecting \"Vortek-like\" drivers
     * - Applying Vulkan-version constraints on older devices
     * - Selecting between constrained, bionic, and base DXVK lists
     */
    fun buildDxvkContext(
        containerVariant: String,
        graphicsDrivers: List<String>,
        graphicsDriverIndex: Int,
        dxWrappers: List<String>,
        dxWrapperIndex: Int,
        inspectionMode: Boolean,
        isBionicVariant: Boolean,
        dxvkVersionsBase: List<String>,
        dxvkOptions: VersionOptionList,
    ): DxvkContext {
        val driverType = StringUtils.parseIdentifier(
            graphicsDrivers.getOrNull(graphicsDriverIndex).orEmpty(),
        )
        val isVortekLike = containerVariant.equals("glibc", ignoreCase = true) &&
            driverType in listOf("vortek", "adreno", "sd-8-elite")

        val isVKD3D = StringUtils.parseIdentifier(
            dxWrappers.getOrNull(dxWrapperIndex).orEmpty(),
        ) == "vkd3d"
        val constrainedLabels = listOf("1.10.3", "1.10.9-sarek", "1.9.2", "async-1.10.3")
        val constrainedIds = constrainedLabels.map { StringUtils.parseIdentifier(it) }
        val useConstrained =
            !inspectionMode && isVortekLike &&
                GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(1, 3, 0)

        val labels =
            if (useConstrained) constrainedLabels
            else if (isBionicVariant) dxvkOptions.labels
            else dxvkVersionsBase
        val ids =
            if (useConstrained) constrainedIds
            else if (isBionicVariant) dxvkOptions.ids
            else dxvkVersionsBase.map { StringUtils.parseIdentifier(it) }
        val muted =
            if (useConstrained) List(labels.size) { false }
            else if (isBionicVariant) dxvkOptions.muted
            else emptyList()

        return if (isVKD3D) {
            DxvkContext(isVortekLike, emptyList(), emptyList(), emptyList())
        } else {
            DxvkContext(isVortekLike, labels, ids, muted)
        }
    }

    fun versionExists(version: String, available: List<String>): Boolean {
        if (version.isEmpty()) return false
        val trimmed = version.trim()
        return available.any { it.equals(trimmed, ignoreCase = true) || StringUtils.parseIdentifier(it).equals(trimmed) }
    }

    fun findManifestEntryForVersion(
        version: String,
        entries: List<ManifestEntry>,
    ): ManifestEntry? {
        val normalized = version.trim()
        if (normalized.isEmpty()) return null
        return entries.firstOrNull { entry ->
            normalized.equals(entry.id, ignoreCase=true)
        }
    }
}
