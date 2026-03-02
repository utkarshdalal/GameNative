package app.gamenative.utils

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService

object PlatformAuthUtils {
    fun isSignedInToAnyPlatform(context: Context): Boolean =
        SteamService.isLoggedIn ||
        GOGService.hasStoredCredentials(context) ||
        EpicService.hasStoredCredentials(context) ||
        AmazonService.hasStoredCredentials(context)
}
