package app.gamenative.mods

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusOAuthAccessTokenTest {
    @Test
    fun officialNestedUserClaims_parseAccountAndPremiumRole() {
        val account = nexusAccountFromAccessToken(
            nexusTestAccessToken(
                userId = 51_448_566L,
                username = "Dragon Modder",
                membershipRoles = listOf("member", "premium"),
            ),
        )

        assertEquals("51448566", account?.id)
        assertEquals("Dragon Modder", account?.name)
        assertEquals(listOf("member", "premium"), account?.membershipRoles)
        assertTrue(account?.isPremium == true)
    }

    @Test
    fun lifetimeRole_isPremiumButSupporterAloneIsNot() {
        val lifetime = nexusAccountFromAccessToken(
            nexusTestAccessToken(membershipRoles = listOf("member", "lifetime")),
        )
        val supporter = nexusAccountFromAccessToken(
            nexusTestAccessToken(membershipRoles = listOf("member", "supporter")),
        )

        assertTrue(lifetime?.isPremium == true)
        assertNotNull(supporter)
        assertFalse(requireNotNull(supporter).isPremium)
    }

    @Test
    fun omittedMembershipRoles_parsesAsFreeAccount() {
        val token = nexusTestJwt(
            JSONObject().put(
                "user",
                JSONObject()
                    .put("id", 42)
                    .put("username", "Free Modder"),
            ),
        )

        val account = nexusAccountFromAccessToken(token)

        assertNotNull(account)
        assertEquals(emptyList<String>(), account?.membershipRoles)
        assertFalse(requireNotNull(account).isPremium)
    }

    @Test
    fun malformedOrNonCanonicalJwt_returnsNullWithoutThrowing() {
        val invalidUtf8Payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(0xC3.toByte()))
        val missingClaims = nexusTestJwt(JSONObject().put("user", JSONObject()))
        val nonStringRole = nexusTestJwt(
            JSONObject().put(
                "user",
                JSONObject()
                    .put("id", 42)
                    .put("username", "Modder")
                    .put("membership_roles", JSONArray().put(123)),
            ),
        )
        val values = listOf(
            "",
            "not-a-jwt",
            "a.b.c.d",
            "header.%%%.signature",
            "header.$invalidUtf8Payload.signature",
            missingClaims,
            nonStringRole,
        )

        values.forEach { value -> assertNull(value, nexusAccountFromAccessToken(value)) }
    }

    @Test
    fun oversizedJwtPayload_isRejectedBeforeDecode() {
        val oversizedPayload = "A".repeat(384 * 1024 + 1)

        assertNull(nexusAccountFromAccessToken("e30.$oversizedPayload.signature"))
    }
}
