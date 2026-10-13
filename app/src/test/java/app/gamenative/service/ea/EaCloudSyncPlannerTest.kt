package app.gamenative.service.ea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EaCloudSyncPlannerTest {

    private val states = listOf<String?>(null, "a", "b", "c")

    private val singleNameTable = mapOf(
        "- -" to "NNNN",
        "- a" to "DUXX",
        "- b" to "DXUX",
        "- c" to "DXXU",
        "a -" to "URXX",
        "a a" to "NNNN",
        "a b" to "XDUX",
        "a c" to "XDXU",
        "b -" to "UXRX",
        "b a" to "XUDX",
        "b b" to "NNNN",
        "b c" to "XXDU",
        "c -" to "UXXR",
        "c a" to "XUXD",
        "c b" to "XXUD",
        "c c" to "NNNN",
    )

    private fun one(key: String?): Map<String, String> = if (key == null) emptyMap() else mapOf("f" to key)

    private fun sym(key: String?) = key ?: "-"

    private fun pull(
        local: Map<String, String>,
        cloud: Map<String, String>,
        base: Map<String, String>?,
        preference: EaCloudPreference = EaCloudPreference.NONE,
    ) = EaCloudSyncPlanner.planPull(local, cloud, base, preference)

    private fun push(
        local: Map<String, String>,
        cloud: Map<String, String>,
        base: Map<String, String>?,
        cloudAtPull: Map<String, String> = cloud,
        forcedNames: Set<String> = emptySet(),
    ) = EaCloudSyncPlanner.planPush(local, cloud, base, cloudAtPull, forcedNames)

    private fun pullPlan(
        download: Set<String> = emptySet(),
        removeLocal: Set<String> = emptySet(),
        conflict: Boolean = false,
        uploadPending: Boolean = false,
        forcedNames: Set<String> = emptySet(),
    ) = EaCloudPullPlan(download, removeLocal, conflict, uploadPending, forcedNames)

    private fun applyPull(local: Map<String, String>, cloud: Map<String, String>, plan: EaCloudPullPlan): Map<String, String> {
        val out = LinkedHashMap(local)
        plan.removeLocal.forEach { out.remove(it) }
        plan.download.forEach { out[it] = cloud.getValue(it) }
        return out
    }

    @Test
    fun `single name table covers every local cloud base combination`() {
        var checked = 0
        for (l in states) for (c in states) for (b in states) {
            val row = singleNameTable.getValue("${sym(l)} ${sym(c)}")
            val expected = when (row[states.indexOf(b)]) {
                'N' -> pullPlan()
                'D' -> pullPlan(download = setOf("f"))
                'R' -> pullPlan(removeLocal = setOf("f"))
                'U' -> pullPlan(uploadPending = true)
                'X' -> pullPlan(conflict = true)
                else -> error("bad table")
            }
            val label = "L=${sym(l)} C=${sym(c)} B=${sym(b)}"
            assertEquals(label, expected, pull(one(l), one(c), one(b)))
            checked++
        }
        assertEquals(64, checked)
    }

    @Test
    fun `single name table is unaffected by unrelated synced files`() {
        val other = mapOf("other" to "z")
        for (l in states) for (c in states) for (b in states) {
            val label = "L=${sym(l)} C=${sym(c)} B=${sym(b)}"
            assertEquals(label, pull(one(l), one(c), one(b)), pull(one(l) + other, one(c) + other, one(b) + other))
        }
    }

    @Test
    fun `stale preference is ignored for every non conflicting single name case`() {
        for (l in states) for (c in states) for (b in states) {
            val none = pull(one(l), one(c), one(b))
            if (none.conflict) continue
            val label = "L=${sym(l)} C=${sym(c)} B=${sym(b)}"
            assertEquals(label, none, pull(one(l), one(c), one(b), EaCloudPreference.LOCAL))
            assertEquals(label, none, pull(one(l), one(c), one(b), EaCloudPreference.REMOTE))
        }
    }

    @Test
    fun `first sync with empty cloud and empty local does nothing`() {
        assertEquals(pullPlan(), pull(emptyMap(), emptyMap(), null))
    }

    @Test
    fun `first sync with empty cloud marks local for upload`() {
        assertEquals(pullPlan(uploadPending = true), pull(mapOf("s1" to "a", "s2" to "b"), emptyMap(), null))
    }

    @Test
    fun `first sync with empty local downloads everything`() {
        assertEquals(
            pullPlan(download = setOf("s1", "s2")),
            pull(emptyMap(), mapOf("s1" to "a", "s2" to "b"), null),
        )
    }

    @Test
    fun `first sync with identical content does nothing`() {
        val files = mapOf("s1" to "a", "s2" to "b")
        assertEquals(pullPlan(), pull(files, files, null))
    }

    @Test
    fun `first sync with disjoint names merges both sides`() {
        assertEquals(
            pullPlan(download = setOf("cloud1"), uploadPending = true),
            pull(mapOf("local1" to "a", "same" to "s"), mapOf("cloud1" to "b", "same" to "s"), null),
        )
    }

    @Test
    fun `first sync with a differing shared name is a conflict`() {
        val plan = pull(
            mapOf("shared" to "a", "local1" to "x"),
            mapOf("shared" to "b", "cloud1" to "y"),
            null,
        )
        assertEquals(pullPlan(conflict = true), plan)
    }

    @Test
    fun `first sync behaves like an empty base`() {
        for (l in states) for (c in states) {
            assertEquals("L=${sym(l)} C=${sym(c)}", pull(one(l), one(c), emptyMap()), pull(one(l), one(c), null))
        }
    }

    @Test
    fun `conflict with no preference returns an empty plan even when other files are actionable`() {
        val local = mapOf("clash" to "l", "gone" to "g", "mine" to "m")
        val cloud = mapOf("clash" to "c", "fresh" to "f")
        val base = mapOf("clash" to "o", "gone" to "g")
        assertEquals(pullPlan(conflict = true), pull(local, cloud, base, EaCloudPreference.NONE))
    }

    @Test
    fun `remote preference resolves conflict names and keeps local only new files`() {
        val local = mapOf("clash" to "l", "same" to "s", "mine" to "m", "gone" to "g", "edited" to "e")
        val cloud = mapOf("clash" to "c", "same" to "s", "fresh" to "f")
        val base = mapOf("clash" to "o", "same" to "s", "gone" to "g", "edited" to "o")
        val plan = pull(local, cloud, base, EaCloudPreference.REMOTE)
        assertEquals(pullPlan(download = setOf("clash", "fresh"), removeLocal = setOf("gone", "edited"), uploadPending = true), plan)
        assertEquals(cloud + ("mine" to "m"), applyPull(local, cloud, plan))
    }

    @Test
    fun `local preference keeps conflict names and still applies cloud only changes`() {
        val local = mapOf("clash" to "l", "gone" to "g", "changed" to "1")
        val cloud = mapOf("clash" to "c", "fresh" to "f", "changed" to "2")
        val base = mapOf("clash" to "o", "gone" to "g", "changed" to "1")
        assertEquals(
            pullPlan(download = setOf("fresh", "changed"), removeLocal = setOf("gone"), uploadPending = true, forcedNames = setOf("clash")),
            pull(local, cloud, base, EaCloudPreference.LOCAL),
        )
    }

    @Test
    fun `first sync conflict honours each preference`() {
        val local = mapOf("shared" to "a", "local1" to "x")
        val cloud = mapOf("shared" to "b", "cloud1" to "y")
        assertEquals(
            pullPlan(download = setOf("shared", "cloud1"), uploadPending = true),
            pull(local, cloud, null, EaCloudPreference.REMOTE),
        )
        assertEquals(
            pullPlan(download = setOf("cloud1"), uploadPending = true, forcedNames = setOf("shared")),
            pull(local, cloud, null, EaCloudPreference.LOCAL),
        )
    }

    @Test
    fun `local preference on a local edit against cloud delete keeps the file`() {
        assertEquals(
            pullPlan(uploadPending = true, forcedNames = setOf("f")),
            pull(mapOf("f" to "b", "g" to "s"), mapOf("g" to "s"), mapOf("f" to "a", "g" to "s"), EaCloudPreference.LOCAL),
        )
    }

    @Test
    fun `local delete against cloud edit is a conflict`() {
        assertTrue(pull(emptyMap(), mapOf("f" to "b"), mapOf("f" to "a")).conflict)
    }

    @Test
    fun `local edit against cloud delete is a conflict`() {
        assertTrue(pull(mapOf("f" to "b"), emptyMap(), mapOf("f" to "a")).conflict)
    }

    @Test
    fun `both sides converging on the same new content is not a conflict`() {
        assertEquals(pullPlan(), pull(mapOf("f" to "b"), mapOf("f" to "b"), mapOf("f" to "a")))
    }

    @Test
    fun `both sides deleting the same file is not a conflict`() {
        assertEquals(pullPlan(), pull(emptyMap(), emptyMap(), mapOf("f" to "a")))
    }

    @Test
    fun `mixed multi file pull without conflict`() {
        val base = mapOf("same" to "s", "cloudEdit" to "1", "cloudDel" to "d", "localEdit" to "1", "localDel" to "x")
        val local = mapOf("same" to "s", "cloudEdit" to "1", "cloudDel" to "d", "localEdit" to "2", "localNew" to "n")
        val cloud = mapOf("same" to "s", "cloudEdit" to "2", "localEdit" to "1", "localDel" to "x", "cloudNew" to "n")
        val plan = pull(local, cloud, base, EaCloudPreference.REMOTE)
        assertEquals(
            pullPlan(download = setOf("cloudEdit", "cloudNew"), removeLocal = setOf("cloudDel"), uploadPending = true),
            plan,
        )
    }

    @Test
    fun `cloud only changes do not set upload pending`() {
        val base = mapOf("a" to "1", "b" to "1")
        val plan = pull(base, mapOf("a" to "2"), base)
        assertEquals(pullPlan(download = setOf("a"), removeLocal = setOf("b")), plan)
    }

    @Test
    fun `push skips when cloud changed during the session`() {
        val plan = push(
            local = mapOf("f" to "new"),
            cloud = mapOf("f" to "other"),
            base = mapOf("f" to "old"),
            cloudAtPull = mapOf("f" to "old"),
        )
        assertEquals(EaCloudPushPlan(emptySet(), mapOf("f" to "other"), true, "cloud_changed_during_session"), plan)
    }

    @Test
    fun `push skips when cloud changed during the session even when forced`() {
        val plan = push(
            local = mapOf("f" to "new"),
            cloud = mapOf("f" to "other"),
            base = null,
            cloudAtPull = mapOf("f" to "old"),
            forcedNames = setOf("f"),
        )
        assertTrue(plan.skip)
        assertEquals("cloud_changed_during_session", plan.reason)
        assertTrue(plan.upload.isEmpty())
    }

    @Test
    fun `push skips when cloud gained a file during the session`() {
        val plan = push(
            local = mapOf("f" to "a"),
            cloud = mapOf("f" to "a", "g" to "b"),
            base = mapOf("f" to "a"),
            cloudAtPull = mapOf("f" to "a"),
        )
        assertEquals("cloud_changed_during_session", plan.reason)
    }

    @Test
    fun `push skips when local is empty and cloud is not`() {
        val cloud = mapOf("f" to "a")
        val plan = push(local = emptyMap(), cloud = cloud, base = cloud)
        assertEquals(EaCloudPushPlan(emptySet(), cloud, true, "local_empty_guard"), plan)
    }

    @Test
    fun `local empty guard applies on first sync too`() {
        val plan = push(local = emptyMap(), cloud = mapOf("f" to "a"), base = null)
        assertEquals("local_empty_guard", plan.reason)
        assertTrue(plan.skip)
    }

    @Test
    fun `forced push with empty local is blocked by the local empty guard`() {
        val cloud = mapOf("f" to "a")
        val plan = push(local = emptyMap(), cloud = cloud, base = null, forcedNames = setOf("f"))
        assertEquals(EaCloudPushPlan(emptySet(), cloud, true, "local_empty_guard"), plan)
    }

    @Test
    fun `local preference with empty local stays a conflict`() {
        val plan = pull(emptyMap(), mapOf("f" to "b"), mapOf("f" to "a"), EaCloudPreference.LOCAL)
        assertEquals(pullPlan(conflict = true), plan)
    }

    @Test
    fun `remote preference with empty local still resolves the conflict`() {
        val plan = pull(emptyMap(), mapOf("f" to "b"), mapOf("f" to "a"), EaCloudPreference.REMOTE)
        assertEquals(pullPlan(download = setOf("f")), plan)
    }

    @Test
    fun `push skips when nothing changed`() {
        val files = mapOf("f" to "a", "g" to "b")
        assertEquals(EaCloudPushPlan(emptySet(), files, true, "no_changes"), push(files, files, files))
    }

    @Test
    fun `push skips when both sides are empty`() {
        assertEquals(
            EaCloudPushPlan(emptySet(), emptyMap(), true, "no_changes"),
            push(emptyMap(), emptyMap(), null),
        )
    }

    @Test
    fun `forced push skips when local already equals cloud`() {
        val files = mapOf("f" to "a")
        assertEquals(
            EaCloudPushPlan(emptySet(), files, true, "no_changes"),
            push(files, files, null, forcedNames = setOf("f")),
        )
    }

    @Test
    fun `push with only kept cloud entries is no changes`() {
        val plan = push(
            local = mapOf("f" to "a"),
            cloud = mapOf("f" to "a", "never" to "n"),
            base = mapOf("f" to "a"),
        )
        assertEquals(EaCloudPushPlan(emptySet(), mapOf("f" to "a", "never" to "n"), true, "no_changes"), plan)
    }

    @Test
    fun `push uploads changed and new local files`() {
        val plan = push(
            local = mapOf("same" to "s", "edit" to "2", "new" to "n"),
            cloud = mapOf("same" to "s", "edit" to "1"),
            base = mapOf("same" to "s", "edit" to "1"),
        )
        assertEquals(
            EaCloudPushPlan(
                setOf("edit", "new"),
                mapOf("same" to "s", "edit" to "2", "new" to "n"),
                false,
                "push",
            ),
            plan,
        )
    }

    @Test
    fun `first push to an empty cloud uploads everything`() {
        val local = mapOf("f" to "a", "g" to "b")
        assertEquals(EaCloudPushPlan(setOf("f", "g"), local, false, "push"), push(local, emptyMap(), null))
    }

    @Test
    fun `push drops a file that base proves was deleted locally`() {
        val plan = push(
            local = mapOf("keep" to "k"),
            cloud = mapOf("keep" to "k", "deleted" to "d"),
            base = mapOf("keep" to "k", "deleted" to "d"),
        )
        assertEquals(EaCloudPushPlan(emptySet(), mapOf("keep" to "k"), false, "push"), plan)
    }

    @Test
    fun `push keeps cloud entries absent from base`() {
        val plan = push(
            local = mapOf("mine" to "2"),
            cloud = mapOf("mine" to "1", "never" to "n"),
            base = mapOf("mine" to "1"),
        )
        assertEquals(EaCloudPushPlan(setOf("mine"), mapOf("mine" to "2", "never" to "n"), false, "push"), plan)
    }

    @Test
    fun `push keeps cloud entries whose base key differs from cloud`() {
        val plan = push(
            local = mapOf("mine" to "2"),
            cloud = mapOf("mine" to "1", "moved" to "new"),
            base = mapOf("mine" to "1", "moved" to "old"),
        )
        assertEquals(mapOf("mine" to "2", "moved" to "new"), plan.manifest)
        assertFalse(plan.skip)
    }

    @Test
    fun `push keeps every missing cloud entry when base is null`() {
        val plan = push(
            local = mapOf("mine" to "m"),
            cloud = mapOf("c1" to "1", "c2" to "2", "c3" to "3"),
            base = null,
        )
        assertEquals(
            EaCloudPushPlan(
                setOf("mine"),
                mapOf("c1" to "1", "c2" to "2", "c3" to "3", "mine" to "m"),
                false,
                "push",
            ),
            plan,
        )
    }

    @Test
    fun `mass delete guard skips when most of the cloud would be dropped`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")
        val plan = push(local = mapOf("a" to "1"), cloud = cloud, base = cloud)
        assertEquals(EaCloudPushPlan(emptySet(), cloud, true, "mass_delete_guard"), plan)
    }

    @Test
    fun `mass delete guard skips when all cloud entries are replaced by a new name`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3")
        val plan = push(local = mapOf("fresh" to "f"), cloud = cloud, base = cloud)
        assertEquals("mass_delete_guard", plan.reason)
        assertTrue(plan.skip)
        assertTrue(plan.upload.isEmpty())
    }

    @Test
    fun `mass delete guard allows dropping two entries`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3")
        val plan = push(local = mapOf("a" to "1"), cloud = cloud, base = cloud)
        assertEquals(EaCloudPushPlan(emptySet(), mapOf("a" to "1"), false, "push"), plan)
    }

    @Test
    fun `mass delete guard allows dropping exactly half`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4", "e" to "5", "f" to "6")
        val local = mapOf("a" to "1", "b" to "2", "c" to "3")
        val plan = push(local = local, cloud = cloud, base = cloud)
        assertEquals(EaCloudPushPlan(emptySet(), local, false, "push"), plan)
    }

    @Test
    fun `mass delete guard does not count kept cloud entries`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")
        val plan = push(local = mapOf("a" to "9"), cloud = cloud, base = null)
        assertEquals(EaCloudPushPlan(setOf("a"), cloud + ("a" to "9"), false, "push"), plan)
    }

    @Test
    fun `forced name absent locally is dropped from the cloud`() {
        val plan = push(
            local = mapOf("a" to "1"),
            cloud = mapOf("a" to "1", "b" to "2"),
            base = null,
            forcedNames = setOf("b"),
        )
        assertEquals(EaCloudPushPlan(emptySet(), mapOf("a" to "1"), false, "push"), plan)
    }

    @Test
    fun `forced push keeps cloud only names that were not in conflict`() {
        val plan = push(
            local = mapOf("same" to "s", "edit" to "2"),
            cloud = mapOf("same" to "s", "edit" to "1", "other" to "o"),
            base = mapOf("same" to "s", "edit" to "0"),
            forcedNames = setOf("edit"),
        )
        assertEquals(EaCloudPushPlan(setOf("edit"), mapOf("same" to "s", "edit" to "2", "other" to "o"), false, "push"), plan)
    }

    @Test
    fun `mass delete guard counts forced drops`() {
        val cloud = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")
        val plan = push(local = mapOf("a" to "1"), cloud = cloud, base = null, forcedNames = setOf("b", "c", "d"))
        assertEquals(EaCloudPushPlan(emptySet(), cloud, true, "mass_delete_guard"), plan)
    }

    @Test
    fun `pull then push converges for every two file combination`() {
        val names = listOf("x", "y")
        fun snapshot(keys: List<String?>): Map<String, String> =
            names.zip(keys).mapNotNull { (n, k) -> k?.let { n to it } }.toMap()

        val pairs = states.flatMap { p -> states.map { q -> listOf(p, q) } }
        val bases: List<Map<String, String>?> = listOf<Map<String, String>?>(null) + pairs.map { snapshot(it) }
        var applied = 0

        for (lk in pairs) for (ck in pairs) for (base in bases) {
            val local = snapshot(lk)
            val cloud = snapshot(ck)
            val label = "local=$local cloud=$cloud base=$base"
            val pullPlan = pull(local, cloud, base)

            if (pullPlan.conflict) {
                assertTrue(label, pullPlan.download.isEmpty() && pullPlan.removeLocal.isEmpty())
                assertFalse(label, pullPlan.uploadPending)
                assertTrue(label, pullPlan.forcedNames.isEmpty())
                continue
            }
            assertTrue(label, pullPlan.forcedNames.isEmpty())
            assertTrue(label, cloud.keys.containsAll(pullPlan.download))
            assertTrue(label, local.keys.containsAll(pullPlan.removeLocal))
            assertTrue(label, pullPlan.removeLocal.none { it in cloud })

            val afterPull = applyPull(local, cloud, pullPlan)
            val pushPlan = push(afterPull, cloud, base, cloud, pullPlan.forcedNames)

            if (pushPlan.reason == "local_empty_guard") {
                assertTrue(label, afterPull.isEmpty() && cloud.isNotEmpty())
                continue
            }
            if (!pullPlan.uploadPending) {
                assertEquals(label, "no_changes", pushPlan.reason)
                assertEquals(label, cloud, afterPull)
            }
            if (pushPlan.skip) {
                assertEquals(label, "no_changes", pushPlan.reason)
                assertEquals(label, cloud, pushPlan.manifest)
            }
            for (name in pushPlan.upload) {
                assertEquals(label, afterPull[name], pushPlan.manifest[name])
            }
            for (name in afterPull.keys + pushPlan.manifest.keys) {
                val l = afterPull[name]
                val m = pushPlan.manifest[name]
                val keptCloudOnly = l == null && m != null && m == cloud[name]
                assertTrue("$label name=$name local=$l manifest=$m", l == m || keptCloudOnly)
                if (l != null && l != cloud[name]) assertTrue(label, name in pushPlan.upload)
            }
            applied++
        }
        assertTrue(applied > 0)
    }

    @Test
    fun `pull then push leaves no kept cloud only entries when base is known`() {
        val names = listOf("x", "y")
        fun snapshot(keys: List<String?>): Map<String, String> =
            names.zip(keys).mapNotNull { (n, k) -> k?.let { n to it } }.toMap()

        val pairs = states.flatMap { p -> states.map { q -> listOf(p, q) } }
        for (lk in pairs) for (ck in pairs) for (bk in pairs) {
            val local = snapshot(lk)
            val cloud = snapshot(ck)
            val base = snapshot(bk)
            val pullPlan = pull(local, cloud, base)
            if (pullPlan.conflict) continue
            val afterPull = applyPull(local, cloud, pullPlan)
            val pushPlan = push(afterPull, cloud, base)
            if (pushPlan.reason == "local_empty_guard") continue
            assertEquals("local=$local cloud=$cloud base=$base", afterPull, pushPlan.manifest)
        }
    }
}
