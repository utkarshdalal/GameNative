package app.gamenative.html5.host

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Html5PageProfilesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun eachContainerGetsAProfileOfItsOwn() {
        assertEquals("html5-steam-4705510", Html5PageProfiles.nameFor("STEAM_4705510"))
        assertNotEquals(Html5PageProfiles.nameFor("STEAM_4705510"), Html5PageProfiles.nameFor("GOG_4705510"))
    }

    @Test
    fun theDeleteQueueKeepsEachNameOnce() {
        val queue = File(tempFolder.root, "html5/profile-pending-delete")
        ProfileQueue.add(queue, "html5-a")
        ProfileQueue.add(queue, "html5-a")
        ProfileQueue.add(queue, "html5-b")
        assertEquals(setOf("html5-a", "html5-b"), ProfileQueue.read(queue))
        ProfileQueue.remove(queue, "html5-a")
        ProfileQueue.remove(queue, "html5-b")
        assertEquals(emptySet<String>(), ProfileQueue.read(queue))
        assertFalse(queue.exists())
    }
}
