// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import app.gamenative.service.xbox.StsToken
import app.gamenative.service.xbox.XboxDevice
import app.gamenative.service.xbox.XboxTokenStore
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Windows Live device registration: one device account per install, signed in with its license RSA key. */
object LiveDeviceAuth {
    private val lock = Mutex()
    private const val EXPIRY_MARGIN_SECONDS = 60L

    /**
     * Registers the device when the store has none, then signs it in again when the device STS token is
     * missing or expired. Returns the valid device STS token.
     */
    suspend fun ensureDevice(store: XboxTokenStore, cfg: LiveConfig, sts: LiveSts = LiveSts(cfg)): StsToken.Legacy =
        lock.withLock {
            val device = store.device() ?: provision(store, sts)
            val token = store.deviceStsToken()
            if (token != null && !isExpired(token)) token else reauthenticate(store, sts, device)
        }

    fun isExpired(token: StsToken.Legacy, now: Instant = Instant.now()): Boolean =
        runCatching { parseLiveTime(token.expires) }.getOrNull()?.let { it.minusSeconds(EXPIRY_MARGIN_SECONDS) <= now } ?: true

    private suspend fun provision(store: XboxTokenStore, sts: LiveSts): XboxDevice {
        val username = "02" + LiveCrypto.randomAlphanumeric(14)
        val password = LiveCrypto.randomAlphanumeric(20)
        val components = SyntheticHardware.components(SyntheticHardware.seedFor(username, password))
        val result = sts.registerDevice(username, password, components)
        val device = XboxDevice(
            puid = result.puid,
            hwid = result.hwDeviceId,
            deviceId = result.deviceId,
            spLicense = result.spLicenseBlock,
            username = username,
            password = password,
        )
        store.saveDevice(device)
        return device
    }

    private suspend fun reauthenticate(store: XboxTokenStore, sts: LiveSts, device: XboxDevice): StsToken.Legacy {
        val license = SpLicense.parseBase64(device.spLicense)
        val signState = license.clepSignState ?: throw LiveException("Device license has no ClepSignState")
        val key = LiveBlobs.rsaPrivateKey(LiveBlobs.clepSignRsaBlob(signState))
        val response = sts.authenticateDevice(device.username, key)
        val body = response.body
        val tokenResponse = (body as? RstBody.Responses)?.takeIf { !it.collection }?.tokens?.singleOrNull()
            ?: throw LiveException("Device sign-in did not return a token: $body")
        val keyName = tokenResponse.encryptedData?.keyName ?: throw LiveException("Device token has no key name")
        val token = tokenResponse.toStsToken() as? StsToken.Legacy ?: throw LiveException("Device token is not a legacy token")
        store.saveDeviceToken(keyName, token)
        return token
    }
}
