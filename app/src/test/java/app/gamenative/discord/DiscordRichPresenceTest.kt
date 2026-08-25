package app.gamenative.discord

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract that makes the integration safe to ship disabled: with no Discord Social SDK
 * present, no entry point may throw and none may claim to be publishing.
 */
class DiscordRichPresenceTest {

    @Test
    fun nativeBridge_isAbsentWithoutTheDiscordSdk() {
        assertFalse(
            "libdiscordbridge.so must not be loadable in a plain JVM test",
            DiscordNative.isAvailable,
        )
    }

    @Test
    fun lifecycleCalls_areNoOpsWhenDiscordIsUnavailable() {
        DiscordRichPresence.onGameStarted("STEAM_271590", System.currentTimeMillis())
        DiscordRichPresence.onGameStopped()
        DiscordRichPresence.awaitIdle()

        assertNotEquals(DiscordAvailability.READY, DiscordRichPresence.availability)
    }

    @Test
    fun settingToggle_isSafeWithoutDiscord() {
        DiscordRichPresence.onEnabledChanged(true)
        DiscordRichPresence.onEnabledChanged(false)
        DiscordRichPresence.awaitIdle()

        assertEquals(DiscordAvailability.DISABLED, DiscordRichPresence.availability)
    }

    @Test
    fun suspendAndResume_areNoOpsWhenDiscordIsUnavailable() {
        DiscordRichPresence.onGameStarted("STEAM_271590", System.currentTimeMillis())
        DiscordRichPresence.onGameSuspended()
        DiscordRichPresence.onGameResumed()
        DiscordRichPresence.onGameStopped()
        DiscordRichPresence.awaitIdle()

        assertNotEquals(DiscordAvailability.READY, DiscordRichPresence.availability)
    }

    /** Resuming without a preceding suspend must not republish or throw. */
    @Test
    fun resume_withoutSuspend_isANoOp() {
        DiscordRichPresence.onGameResumed()
        DiscordRichPresence.awaitIdle()

        assertNotEquals(DiscordAvailability.READY, DiscordRichPresence.availability)
    }

    /**
     * onAppTaskRemoved blocks its caller (the main thread, from Service.onTaskRemoved), so the
     * thing worth guarding is that it returns promptly and does not throw with no SDK present.
     */
    @Test
    fun taskRemoved_returnsPromptlyWithoutDiscord() {
        DiscordRichPresence.onGameStarted("STEAM_271590", System.currentTimeMillis())

        val elapsed = measureTimeMillis { DiscordRichPresence.onAppTaskRemoved() }

        assertTrue("onAppTaskRemoved must not block the main thread: took ${elapsed}ms", elapsed < 2_000)
        assertNotEquals(DiscordAvailability.READY, DiscordRichPresence.availability)
    }
}
