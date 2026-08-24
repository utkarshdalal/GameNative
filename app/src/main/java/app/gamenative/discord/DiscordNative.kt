package app.gamenative.discord

import androidx.annotation.Keep
import timber.log.Timber

/**
 * JNI wrapper around the Discord Social SDK bridge in app/src/main/cpp/discordrpc.
 *
 * The bridge is only built when the Discord Social SDK AAR is present in app/libs, so without it
 * there is no libdiscordbridge.so to load and [isAvailable] stays false.
 *
 * Use [DiscordRichPresence] rather than talking to this directly.
 */
@Keep
internal object DiscordNative {

    private const val TAG = "DiscordRPC"

    /** Matches kOpUpdatePresence in discord_bridge.cpp. */
    private const val OP_UPDATE_PRESENCE = 1

    val isAvailable: Boolean

    @Volatile
    var presenceResultListener: ((Boolean, String) -> Unit)? = null

    init {
        var loaded = false
        try {
            System.loadLibrary("discordbridge")
            loaded = true
        } catch (t: Throwable) {
            Timber.tag(TAG).i("Discord bridge not present in this build (%s)", t.javaClass.simpleName)
        }
        isAvailable = loaded
    }

    external fun nativeInitialize(applicationId: Long): Boolean

    external fun nativeUpdatePresence(
        name: String,
        details: String?,
        state: String?,
        startTimestampMs: Long,
        largeImage: String?,
        largeText: String?,
        smallImage: String?,
        smallText: String?,
    )

    external fun nativeClearPresence()

    external fun nativeShutdown()

    /** Invoked from the SDK's callback thread. Looked up by name in JNI_OnLoad. */
    @Keep
    @JvmStatic
    fun onNativeResult(op: Int, success: Boolean, message: String?) {
        if (op != OP_UPDATE_PRESENCE) return
        try {
            presenceResultListener?.invoke(success, message.orEmpty())
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "presence result listener threw")
        }
    }
}
