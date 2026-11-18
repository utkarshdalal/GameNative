package app.gamenative.launch

import android.app.Activity
import androidx.compose.runtime.Composable

object LaunchReadiness {

    interface Check {
        val pending: Boolean

        suspend fun refresh()

        suspend fun resolve(activity: Activity): Boolean

        @Composable
        fun Prompt(activity: Activity, onResolved: () -> Unit)
    }

    @Volatile
    var check: Check? = null

    val pending: Boolean get() = check?.pending ?: false

    suspend fun refresh() {
        check?.refresh()
    }

    suspend fun resolve(activity: Activity): Boolean = check?.resolve(activity) ?: true

    @Composable
    fun Prompt(activity: Activity, onResolved: () -> Unit) {
        val c = check
        if (c != null) c.Prompt(activity, onResolved) else onResolved()
    }
}
