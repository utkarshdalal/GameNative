package app.gamenative.service.ubisoft

enum class UbisoftCloudPreference { NONE, LOCAL, REMOTE }

/**
 * One local slot. [uploadHash] is the hash this client stamps when it uploads these bytes;
 * [wantsUpload] is the game's own `.upload` marker for the slot.
 */
data class UbisoftLocalSlot(
    val contentKey: String,
    val size: Long,
    val stampedHash: String?,
    val uploadHash: String,
    val wantsUpload: Boolean = false,
)

data class UbisoftRemoteSlot(val hash: String, val size: Long)

data class UbisoftSyncedSlot(val contentKey: String, val remoteHash: String, val size: Long)

enum class UbisoftCloudAction { NONE, DOWNLOAD, UPLOAD, CONFLICT }

/**
 * [keptRemoved]: conflicts on slots the cloud no longer lists, resolved for the cloud; the local file stays and is not uploaded.
 * [forcedLocal]: conflicts resolved for the local copy (also in [upload]).
 */
data class UbisoftCloudPullPlan(
    val download: Set<Int>,
    val upload: Set<Int>,
    val conflicts: Set<Int>,
    val keptRemoved: Set<Int> = emptySet(),
    val forcedLocal: Set<Int> = emptySet(),
) {
    val conflict: Boolean get() = conflicts.isNotEmpty()
}

/** Pure per-slot planner for Ubisoft cloud saves: local content, the cloud record's slot hash, last-synced state. */
object UbisoftCloudSyncPlanner {
    /**
     * [deleted] = the game removed the slot locally (a `.delete` file and no save); a save missing without
     * one is restored from the cloud. [recordExists] = the account has a cloud record for the game, so a
     * synced slot it no longer lists was removed elsewhere. A stamp only names the cloud version a file is
     * based on: the game copies it forward when it rewrites the file.
     */
    fun decide(
        local: UbisoftLocalSlot?,
        remote: UbisoftRemoteSlot?,
        synced: UbisoftSyncedSlot?,
        deleted: Boolean = false,
        recordExists: Boolean = true,
    ): UbisoftCloudAction {
        if (local == null && remote == null) return UbisoftCloudAction.NONE
        if (local == null) return if (deleted) UbisoftCloudAction.NONE else UbisoftCloudAction.DOWNLOAD
        if (remote == null) {
            if (synced == null || !recordExists) return UbisoftCloudAction.UPLOAD
            return if (synced.contentKey == local.contentKey) UbisoftCloudAction.NONE else UbisoftCloudAction.CONFLICT
        }
        if (synced != null && synced.remoteHash.equals(remote.hash, ignoreCase = true) && synced.contentKey == local.contentKey) {
            return UbisoftCloudAction.NONE
        }
        if (synced == null && local.stampedHash != null && local.stampedHash.equals(remote.hash, ignoreCase = true)) {
            return if (local.wantsUpload) UbisoftCloudAction.UPLOAD else UbisoftCloudAction.NONE
        }
        // The record already names these bytes but the upload behind it never completed.
        if (local.uploadHash.equals(remote.hash, ignoreCase = true)) return UbisoftCloudAction.UPLOAD
        if (synced == null) return UbisoftCloudAction.CONFLICT
        val localChanged = synced.contentKey != local.contentKey
        val remoteChanged = !synced.remoteHash.equals(remote.hash, ignoreCase = true)
        return when {
            localChanged && remoteChanged -> UbisoftCloudAction.CONFLICT
            remoteChanged -> UbisoftCloudAction.DOWNLOAD
            localChanged -> UbisoftCloudAction.UPLOAD
            else -> UbisoftCloudAction.NONE
        }
    }

    fun planPull(
        local: Map<Int, UbisoftLocalSlot>,
        remote: Map<Int, UbisoftRemoteSlot>,
        synced: Map<Int, UbisoftSyncedSlot>,
        preference: UbisoftCloudPreference,
        deleted: Set<Int> = emptySet(),
        recordExists: Boolean = true,
    ): UbisoftCloudPullPlan {
        val download = LinkedHashSet<Int>()
        val upload = LinkedHashSet<Int>()
        val conflicts = LinkedHashSet<Int>()
        val keptRemoved = LinkedHashSet<Int>()
        val forcedLocal = LinkedHashSet<Int>()
        for (slot in (local.keys + remote.keys).toSortedSet()) {
            when (decide(local[slot], remote[slot], synced[slot], slot in deleted, recordExists)) {
                UbisoftCloudAction.NONE -> Unit
                UbisoftCloudAction.DOWNLOAD -> download += slot
                UbisoftCloudAction.UPLOAD -> upload += slot
                UbisoftCloudAction.CONFLICT -> when (preference) {
                    UbisoftCloudPreference.NONE -> conflicts += slot
                    UbisoftCloudPreference.REMOTE -> if (slot in remote) download += slot else keptRemoved += slot
                    UbisoftCloudPreference.LOCAL -> {
                        upload += slot
                        forcedLocal += slot
                    }
                }
            }
        }
        return UbisoftCloudPullPlan(download, upload, conflicts, keptRemoved, forcedLocal)
    }

    fun planPush(local: Map<Int, UbisoftLocalSlot>, synced: Map<Int, UbisoftSyncedSlot>): Set<Int> =
        local.keys.toSortedSet().filterTo(LinkedHashSet()) { slot ->
            val known = synced[slot]
            known == null || known.contentKey != local.getValue(slot).contentKey
        }
}
