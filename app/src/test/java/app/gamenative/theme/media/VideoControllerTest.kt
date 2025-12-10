package app.gamenative.theme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoControllerTest {

    private class Recorder : VideoController.Listener {
        val events = mutableListOf<Pair<String, VideoController.Action>>()
        override fun onAction(key: String, action: VideoController.Action) {
            events += key to action
        }
        fun takeAll(): List<Pair<String, VideoController.Action>> = events.toList().also { events.clear() }
    }

    @Test
    fun default_cap_is_1_and_autoplay_requires_focus_or_selection() {
        val rec = Recorder()
        val vc = VideoController()
        vc.setListener(rec)

        // Three visible slots, only one focused: only that one should PLAY; others preload
        vc.updateSlot(VideoController.SlotState(key = "a", visible = true, focused = true, selected = false))
        vc.updateSlot(VideoController.SlotState(key = "b", visible = true, focused = false, selected = false))
        vc.updateSlot(VideoController.SlotState(key = "c", visible = true, focused = false, selected = false))

        val actions = rec.takeAll()
        // Expect: a -> PLAY; b -> PRELOAD_METADATA; c -> PRELOAD_METADATA (order not guaranteed for preload)
        assertTrue(actions.contains("a" to VideoController.Action.PLAY))
        val preloads = actions.filter { it.second == VideoController.Action.PRELOAD_METADATA }.map { it.first }.toSet()
        assertEquals(setOf("b","c"), preloads)
    }

    @Test
    fun strong_device_allows_two_concurrent_videos() {
        val rec = Recorder()
        val vc = VideoController()
        vc.setListener(rec)
        // Strong device policy
        val strong = MediaPolicy.Config(maxConcurrentVideos = 2)
        vc.setPolicy(strong)

        vc.updateSlot(VideoController.SlotState(key = "a", visible = true, focused = true, selected = false))
        vc.updateSlot(VideoController.SlotState(key = "b", visible = true, focused = true, selected = false))
        vc.updateSlot(VideoController.SlotState(key = "c", visible = true, focused = false, selected = false))

        val actions = rec.takeAll()
        // Two focused should PLAY; third should PRELOAD
        val plays = actions.filter { it.second == VideoController.Action.PLAY }.map { it.first }.toSet()
        assertEquals(setOf("a","b"), plays)
        val preloads = actions.filter { it.second == VideoController.Action.PRELOAD_METADATA }.map { it.first }.toSet()
        assertEquals(setOf("c"), preloads)
    }

    @Test
    fun offscreen_items_pause_immediately() {
        val rec = Recorder()
        val vc = VideoController()
        vc.setListener(rec)

        // Start with visible & focused -> should play
        vc.updateSlot(VideoController.SlotState(key = "x", visible = true, focused = true, selected = false))
        rec.takeAll()
        // Now mark as not visible -> should PAUSE
        vc.updateSlot(VideoController.SlotState(key = "x", visible = false, focused = true, selected = false))
        val actions = rec.takeAll()
        assertTrue(actions.contains("x" to VideoController.Action.PAUSE))
    }

    @Test
    fun selected_has_higher_priority_than_focused() {
        val rec = Recorder()
        val vc = VideoController()
        vc.setListener(rec)

        // cap=1 default. Both visible. Selected should win over focused.
        vc.updateSlot(VideoController.SlotState(key = "sel", visible = true, focused = false, selected = true))
        vc.updateSlot(VideoController.SlotState(key = "foc", visible = true, focused = true, selected = false))
        val actions = rec.takeAll()
        assertTrue(actions.contains("sel" to VideoController.Action.PLAY))
        // focused should be asked to preload metadata
        assertTrue(actions.contains("foc" to VideoController.Action.PRELOAD_METADATA))
    }
}
