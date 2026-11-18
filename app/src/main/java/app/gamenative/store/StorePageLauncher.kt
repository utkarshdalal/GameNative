package app.gamenative.store

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import timber.log.Timber

object StorePageLauncher {
    fun launch(
        context: Context,
        target: StorePageTarget,
        startActivity: (Intent) -> Unit = context::startActivity,
    ): StorePageLaunchResult {
        if (target is StorePageTarget.NativeWithWebFallback) {
            for (candidate in target.nativeCandidates) {
                val intent = Intent(Intent.ACTION_VIEW, candidate.uri.toUri())
                    .setPackage(candidate.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                try {
                    startActivity(intent)
                    Timber.i("Opened %s store page with native app", target.source)
                    return StorePageLaunchResult.NativeLaunched
                } catch (error: ActivityNotFoundException) {
                    Timber.d(error, "Native %s store handler unavailable", target.source)
                } catch (error: SecurityException) {
                    Timber.w(error, "Native %s store handler rejected launch", target.source)
                }
            }
        }

        val webIntent = Intent(Intent.ACTION_VIEW, target.canonicalWebUrl.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(webIntent)
            Timber.i("Opened %s store page in browser", target.source)
            StorePageLaunchResult.WebLaunched
        } catch (error: ActivityNotFoundException) {
            Timber.e(error, "No browser available for %s store page", target.source)
            StorePageLaunchResult.Failed(target.canonicalWebUrl)
        } catch (error: SecurityException) {
            Timber.e(error, "Browser rejected %s store page launch", target.source)
            StorePageLaunchResult.Failed(target.canonicalWebUrl)
        }
    }
}
