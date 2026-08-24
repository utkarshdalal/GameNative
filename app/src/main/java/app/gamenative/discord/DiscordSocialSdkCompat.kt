package app.gamenative.discord

import android.app.Activity
import java.lang.ref.WeakReference
import timber.log.Timber

/**
 * Reflective shim for `com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity(Activity)`,
 * the one Java entry point the Discord Social SDK AAR exposes. Reached by reflection so that
 * app/src/main/java compiles identically whether or not the AAR is present.
 *
 * The Activity is registered at startup but not handed to the SDK there: setEngineActivity starts
 * the SDK's own networking, which aborts the process if it fails, so [DiscordRichPresence] calls
 * it once the user has actually enabled the feature.
 */
internal object DiscordSocialSdkCompat {

    private const val TAG = "DiscordRPC"
    private const val INIT_CLASS = "com.discord.socialsdk.DiscordSocialSdkInit"

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var attached = false

    /** Called from Activity.onCreate. Does not touch the SDK. */
    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Hands the registered Activity to the SDK. Returns false when unavailable. */
    fun attachEngineActivity(): Boolean {
        if (attached) return true
        if (!DiscordNative.isAvailable) return false
        val activity = activityRef?.get() ?: run {
            Timber.tag(TAG).w("No activity registered for the Discord Social SDK")
            return false
        }
        return try {
            Class.forName(INIT_CLASS)
                .getMethod("setEngineActivity", Activity::class.java)
                .invoke(null, activity)
            attached = true
            Timber.tag(TAG).i("Attached engine activity to Discord Social SDK")
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).w("Could not attach engine activity: %s", t.javaClass.simpleName)
            false
        }
    }
}
