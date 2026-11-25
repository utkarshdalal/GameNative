package app.gamenative.service.xbox

import java.io.File
import org.json.JSONObject

/** The device registration made once per install: Windows Live device name, password and its license block. */
data class XboxDevice(
    val puid: String,
    val hwid: String,
    val deviceId: String,
    val spLicense: String,
    val username: String,
    val password: String,
)

/** Signed-in account: Windows Live PUID and sign-in name. */
data class XboxUser(val puid: String, val username: String)

/** A token issued by login.live.com RST2. Legacy tokens carry the proof key used to sign later exchanges. */
sealed class StsToken {
    data class Legacy(
        val keyName: String?,
        val token: String,
        val binarySecret: String?,
        val tpmKey: String?,
        val created: String,
        val expires: String,
    ) : StsToken()

    data class Compact(val value: String) : StsToken()
}

/**
 * Persistent Xbox credentials in one file under the app's private storage, encrypted with [codec].
 * Tokens are keyed by the RST2 address or key name they were issued for, as the Windows token broker keys them.
 */
class XboxTokenStore(private val file: File, private val codec: Codec) {
    interface Codec {
        fun encrypt(bytes: ByteArray): ByteArray
        fun decrypt(bytes: ByteArray): ByteArray
    }

    private var cache: JSONObject? = null

    @Synchronized
    private fun root(): JSONObject {
        cache?.let { return it }
        val loaded = runCatching { JSONObject(String(codec.decrypt(file.readBytes()), Charsets.UTF_8)) }.getOrNull()
        return (loaded ?: JSONObject()).also { cache = it }
    }

    @Synchronized
    private fun write(mutate: JSONObject.() -> Unit) {
        val json = root().apply(mutate)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(codec.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
        check(tmp.renameTo(file)) { "Cannot write Xbox credentials" }
    }

    fun device(): XboxDevice? = root().optJSONObject(DEVICE)?.let {
        XboxDevice(
            puid = it.optString("puid"),
            hwid = it.optString("hwid"),
            deviceId = it.optString("deviceId"),
            spLicense = it.getString("spLicense"),
            username = it.getString("username"),
            password = it.getString("password"),
        )
    }

    fun saveDevice(device: XboxDevice) = write {
        put(DEVICE, JSONObject().apply {
            put("puid", device.puid)
            put("hwid", device.hwid)
            put("deviceId", device.deviceId)
            put("spLicense", device.spLicense)
            put("username", device.username)
            put("password", device.password)
        })
        remove(DEVICE_TOKENS)
    }

    fun deviceToken(address: String): StsToken? = token(DEVICE_TOKENS, address)

    fun saveDeviceToken(address: String, token: StsToken) = putToken(DEVICE_TOKENS, address, token)

    fun deviceStsToken(): StsToken.Legacy? = deviceToken(PASSPORT_STS) as? StsToken.Legacy

    fun userToken(address: String): StsToken? = token(USER_TOKENS, address)

    fun saveUserToken(address: String, token: StsToken) = putToken(USER_TOKENS, address, token)

    fun userStsToken(): StsToken.Legacy? = userToken(PASSPORT_STS) as? StsToken.Legacy

    fun user(): XboxUser? = root().optJSONObject(USER)?.let { XboxUser(it.getString("puid"), it.getString("username")) }

    fun saveUser(user: XboxUser) = write { put(USER, JSONObject().put("puid", user.puid).put("username", user.username)) }

    /** Sign-out keeps the device registration, as Windows does. */
    fun clearUser() = write {
        remove(USER)
        remove(USER_TOKENS)
    }

    private fun token(section: String, address: String): StsToken? =
        root().optJSONObject(section)?.optJSONObject(address)?.let(::decode)

    private fun putToken(section: String, address: String, token: StsToken) = write {
        val tokens = optJSONObject(section) ?: JSONObject().also { put(section, it) }
        tokens.put(address, encode(token))
    }

    private fun encode(token: StsToken): JSONObject = when (token) {
        is StsToken.Compact -> JSONObject().put("compact", token.value)
        is StsToken.Legacy -> JSONObject().apply {
            token.keyName?.let { put("keyName", it) }
            put("token", token.token)
            token.binarySecret?.let { put("binarySecret", it) }
            token.tpmKey?.let { put("tpmKey", it) }
            put("created", token.created)
            put("expires", token.expires)
        }
    }

    private fun decode(json: JSONObject): StsToken =
        if (json.has("compact")) {
            StsToken.Compact(json.getString("compact"))
        } else {
            StsToken.Legacy(
                keyName = json.optString("keyName").takeIf { it.isNotEmpty() },
                token = json.getString("token"),
                binarySecret = json.optString("binarySecret").takeIf { it.isNotEmpty() },
                tpmKey = json.optString("tpmKey").takeIf { it.isNotEmpty() },
                created = json.getString("created"),
                expires = json.getString("expires"),
            )
        }

    companion object {
        const val PASSPORT_STS = "http://Passport.NET/STS"
        private const val DEVICE = "device"
        private const val DEVICE_TOKENS = "deviceTokens"
        private const val USER_TOKENS = "userTokens"
        private const val USER = "user"
    }
}
