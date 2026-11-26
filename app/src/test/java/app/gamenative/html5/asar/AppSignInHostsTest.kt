package app.gamenative.html5.asar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSignInHostsTest {

    @Test
    fun theStoresTheAppSignsInToAreCovered() {
        listOf(
            "store.steampowered.com",
            "login.steampowered.com",
            "steamcommunity.com",
            "signin.rockstargames.com",
            "www.amazon.com",
            "auth.gog.com",
            "www.epicgames.com",
            "accounts.ea.com",
            "connect.ubisoft.com",
            "public-ubiservices.ubi.com",
            "STORE.SteamPowered.com.",
        ).forEach { assertTrue(it, AppSignInHosts.covers(it)) }
    }

    @Test
    fun otherSitesAreNot() {
        listOf("example.test", "totaljerkface.com", "notsteampowered.com", "steampowered.com.evil.test", "amazon.co")
            .forEach { assertFalse(it, AppSignInHosts.covers(it)) }
    }
}
