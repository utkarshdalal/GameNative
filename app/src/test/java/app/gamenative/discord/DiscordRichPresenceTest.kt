package app.gamenative.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
