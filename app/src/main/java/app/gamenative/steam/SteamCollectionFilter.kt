package app.gamenative.steam

import app.gamenative.data.SteamCollection

object SteamCollectionFilter {
    /** True = keep the app. Fail-open: not-loaded or effectively-empty selection shows everything. */
    fun passes(appId: Int, selectedIds: Set<String>, collections: List<SteamCollection>?): Boolean {
        val allowed = allowedAppIds(selectedIds, collections) ?: return true
        return appId in allowed
    }

    /**
     * The union of app ids across the selected collections, or null to keep everything (fail-open:
     * collections not loaded, no selection, or a selection that matches no known collection).
     * Compute this once per filter pass and test membership per app, rather than rebuilding the
     * selected subset for every app (which is O(apps x collections) with a per-app allocation).
     */
    fun allowedAppIds(selectedIds: Set<String>, collections: List<SteamCollection>?): Set<Int>? {
        if (collections == null) return null
        if (selectedIds.isEmpty()) return null
        val selected = collections.filter { it.id in selectedIds }
        if (selected.isEmpty()) return null
        return buildSet { selected.forEach { addAll(it.appIds) } }
    }

    data class Reconciliation(val cleaned: Set<String>, val removedAny: Boolean)

    /** Drop selected ids no longer present. No-op while collections are not loaded (null). */
    fun reconcile(selectedIds: Set<String>, collections: List<SteamCollection>?): Reconciliation {
        if (collections == null) return Reconciliation(selectedIds, removedAny = false)
        val present = collections.mapTo(HashSet()) { it.id }
        val cleaned = selectedIds.filterTo(LinkedHashSet()) { it in present }
        return Reconciliation(cleaned, removedAny = cleaned.size != selectedIds.size)
    }
}
