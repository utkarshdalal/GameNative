package app.gamenative.service.ea

enum class EaCloudPreference { NONE, LOCAL, REMOTE }

data class EaCloudPullPlan(
    val download: Set<String>,
    val removeLocal: Set<String>,
    val conflict: Boolean,
    val uploadPending: Boolean,
    val forcedNames: Set<String>,
)

data class EaCloudPushPlan(
    val upload: Set<String>,
    val manifest: Map<String, String>,
    val skip: Boolean,
    val reason: String,
)

/** Pure three-way (local / cloud / last-synced base) planner for EA cloud saves. */
object EaCloudSyncPlanner {
    const val REASON_PUSH = "push"
    const val REASON_NO_CHANGES = "no_changes"
    const val REASON_CLOUD_CHANGED = "cloud_changed_during_session"
    const val REASON_LOCAL_EMPTY = "local_empty_guard"
    const val REASON_MASS_DELETE = "mass_delete_guard"

    private const val MASS_DELETE_MIN_ENTRIES = 3

    fun planPull(
        local: Map<String, String>,
        cloud: Map<String, String>,
        base: Map<String, String>?,
        preference: EaCloudPreference,
    ): EaCloudPullPlan {
        val known = base.orEmpty()
        val download = LinkedHashSet<String>()
        val removeLocal = LinkedHashSet<String>()
        val conflicts = LinkedHashSet<String>()
        var uploadPending = false

        for (name in local.keys + cloud.keys + known.keys) {
            val l = local[name]
            val c = cloud[name]
            val b = known[name]
            when {
                l == c -> Unit
                l == b -> if (c != null) download += name else removeLocal += name
                c == b -> uploadPending = true
                else -> conflicts += name
            }
        }

        if (conflicts.isEmpty()) {
            return EaCloudPullPlan(download, removeLocal, conflict = false, uploadPending = uploadPending, forcedNames = emptySet())
        }

        val effective = if (preference == EaCloudPreference.LOCAL && local.isEmpty()) EaCloudPreference.NONE else preference
        return when (effective) {
            EaCloudPreference.NONE -> EaCloudPullPlan(
                download = emptySet(),
                removeLocal = emptySet(),
                conflict = true,
                uploadPending = false,
                forcedNames = emptySet(),
            )
            EaCloudPreference.REMOTE -> {
                for (name in conflicts) if (name in cloud) download += name else removeLocal += name
                EaCloudPullPlan(download, removeLocal, conflict = false, uploadPending = uploadPending, forcedNames = emptySet())
            }
            EaCloudPreference.LOCAL -> EaCloudPullPlan(download, removeLocal, conflict = false, uploadPending = true, forcedNames = conflicts)
        }
    }

    fun planPush(
        local: Map<String, String>,
        cloud: Map<String, String>,
        base: Map<String, String>?,
        cloudAtPull: Map<String, String>,
        forcedNames: Set<String>,
    ): EaCloudPushPlan {
        if (cloud != cloudAtPull) return skip(cloud, REASON_CLOUD_CHANGED)
        if (local.isEmpty() && cloud.isNotEmpty()) return skip(cloud, REASON_LOCAL_EMPTY)

        val manifest = LinkedHashMap(cloud)
        val upload = LinkedHashSet<String>()
        var dropped = 0
        for ((name, key) in local) {
            if (cloud[name] != key) {
                manifest[name] = key
                upload += name
            }
        }
        for ((name, key) in cloud) {
            if (name !in local && (name in forcedNames || (base != null && base[name] == key))) {
                manifest.remove(name)
                dropped++
            }
        }
        if (dropped >= MASS_DELETE_MIN_ENTRIES && dropped * 2 > cloud.size) return skip(cloud, REASON_MASS_DELETE)

        if (manifest == cloud) return skip(cloud, REASON_NO_CHANGES)
        return EaCloudPushPlan(upload = upload, manifest = manifest, skip = false, reason = REASON_PUSH)
    }

    private fun skip(cloud: Map<String, String>, reason: String) =
        EaCloudPushPlan(upload = emptySet(), manifest = cloud.toMap(), skip = true, reason = reason)
}
