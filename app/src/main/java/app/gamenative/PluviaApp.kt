package app.gamenative

import android.os.StrictMode
import androidx.navigation.NavController
import app.gamenative.events.EventDispatcher
import app.gamenative.utils.IntentLaunchManager
import com.google.android.play.core.splitcompat.SplitCompatApplication
import com.posthog.PersonProfiles
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xenvironment.XEnvironment
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

// Add PostHog imports
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

// Supabase imports
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.network.supabaseApi
import io.ktor.client.plugins.HttpTimeout

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : SplitCompatApplication() {

    override fun onCreate() {
        super.onCreate()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Init our datastore preferences.
        PrefManager.init(this)

        // Clear any stale temporary config overrides from previous app sessions
        try {
            IntentLaunchManager.clearAllTemporaryOverrides()
            Timber.d("[PluviaApp]: Cleared temporary config overrides from previous session")
        } catch (e: Exception) {
            Timber.e(e, "[PluviaApp]: Failed to clear temporary config overrides")
        }

        // Initialize PostHog Analytics
        val postHogConfig = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = BuildConfig.POSTHOG_HOST,
        ).apply {
            /* turn every event into an identified one */
            personProfiles = PersonProfiles.ALWAYS
        }
        PostHogAndroid.setup(this, postHogConfig)

        // Initialize Supabase client
        try {
            initSupabase()
            Timber.d("Supabase client initialized with URL: ${BuildConfig.SUPABASE_URL}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Supabase client: ${e.message}")
            e.printStackTrace()
        }
    }

    companion object {
        internal val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null

        // Supabase client for game feedback
        lateinit var supabase: SupabaseClient
            private set

        // Initialize Supabase client
        @OptIn(SupabaseInternal::class)
        fun initSupabase() {
            Timber.d("Initializing Supabase client with URL: ${BuildConfig.SUPABASE_URL}")
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) {
                Timber.e("Invalid Supabase URL or key - URL: ${BuildConfig.SUPABASE_URL}, key empty: ${BuildConfig.SUPABASE_KEY.isBlank()}")
                throw IllegalStateException("Supabase URL or key is empty")
            }

            supabase = createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_KEY
            ) {
                Timber.d("Configuring Supabase client")
                httpConfig {
                    Timber.d("Setting up HTTP timeouts")
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30_000   // overall call
                        connectTimeoutMillis = 15_000   // TCP handshake / TLS
                        socketTimeoutMillis  = 30_000   // idle socket
                    }
                }
                install(Postgrest)
                Timber.d("Postgrest plugin installed")
            }
        }
    }
}
