package app.gamenative.ui.screen.xr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImmersiveSessionOwnershipTest {
    @Before
    fun setUp() {
        ImmersiveSessionOwnership.resetForTest()
    }

    @After
    fun tearDown() {
        ImmersiveSessionOwnership.resetForTest()
    }

    @Test
    fun launcherCannotTearDownEnvironmentWhileImmersiveLaunchIsPending() {
        ImmersiveSessionOwnership.beginLaunch("STEAM_123")

        assertFalse(ImmersiveSessionOwnership.shouldLauncherHandleDestruction(false))
        assertEquals(ImmersiveSessionOwnership.Phase.LAUNCHING, ImmersiveSessionOwnership.snapshot().phase)
    }

    @Test
    fun launcherCannotTearDownEnvironmentWhileImmersiveActivityIsActive() {
        ImmersiveSessionOwnership.beginLaunch("STEAM_123")
        ImmersiveSessionOwnership.markActivityActive("STEAM_123")

        assertFalse(ImmersiveSessionOwnership.shouldLauncherHandleDestruction(false))
        assertEquals(ImmersiveSessionOwnership.Phase.ACTIVE, ImmersiveSessionOwnership.snapshot().phase)
    }

    @Test
    fun launcherRegainsTeardownOwnershipAfterImmersiveSessionFinishes() {
        ImmersiveSessionOwnership.beginLaunch("STEAM_123")
        ImmersiveSessionOwnership.markActivityActive("STEAM_123")
        ImmersiveSessionOwnership.release("STEAM_123")

        assertTrue(ImmersiveSessionOwnership.shouldLauncherHandleDestruction(false))
        assertEquals(ImmersiveSessionOwnership.Phase.IDLE, ImmersiveSessionOwnership.snapshot().phase)
    }

    @Test
    fun mismatchedActivityCannotReleaseAnotherGamesHandoff() {
        ImmersiveSessionOwnership.beginLaunch("STEAM_123")

        ImmersiveSessionOwnership.release("STEAM_456")

        assertTrue(ImmersiveSessionOwnership.isOwnedByImmersive())
        assertEquals("STEAM_123", ImmersiveSessionOwnership.snapshot().appId)
    }

    @Test
    fun configurationRecreationNeverGivesLauncherTeardownOwnership() {
        assertFalse(ImmersiveSessionOwnership.shouldLauncherHandleDestruction(true))
    }
}
