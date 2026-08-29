package app.gamenative.mods

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal fun nexusTestAccessToken(
    userId: Long = 42L,
    username: String = "Modder",
    membershipRoles: List<String> = listOf("member"),
): String = nexusTestJwt(
    JSONObject()
        .put("iss", "https://users.nexusmods.com")
        .put("sub", userId.toString())
        .put(
            "user",
            JSONObject()
                .put("id", userId)
                .put("username", username)
                .put("membership_roles", JSONArray(membershipRoles)),
        ),
)

internal fun nexusTestJwt(payload: JSONObject): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(value: ByteArray): String = encoder.encodeToString(value)
    return listOf(
        encode("""{"alg":"RS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8)),
        encode(payload.toString().toByteArray(Charsets.UTF_8)),
        encode(ByteArray(32) { index -> (index + 1).toByte() }),
    ).joinToString(".")
}
