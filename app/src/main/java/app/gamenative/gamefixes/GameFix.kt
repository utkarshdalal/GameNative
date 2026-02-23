package app.gamenative.gamefixes

import android.content.Context

interface GameFix {
    fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
    ): Boolean
}
