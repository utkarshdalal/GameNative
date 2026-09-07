package app.gamenative.ui.screen.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.service.ea.EaAuthManager
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.screen.auth.EaOAuthActivity
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.launch
import timber.log.Timber

/** EA account used by Real-Steam launches of EA titles (link2ea games). */
@Composable
fun EaAccountSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(EaAuthManager.displayName(context)) }
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(EaOAuthActivity.EXTRA_AUTH_CODE)
        if (result.resultCode != Activity.RESULT_OK || code.isNullOrEmpty()) {
            val err = result.data?.getStringExtra(EaOAuthActivity.EXTRA_ERROR)
            if (err != null) SnackbarManager.show(context.getString(R.string.ea_login_failed, err))
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            EaAuthManager.authenticateWithCode(context, code)
                .onSuccess {
                    displayName = it.displayName
                    SnackbarManager.show(context.getString(R.string.ea_login_success, it.displayName))
                }
                .onFailure {
                    Timber.e(it, "EA login failed")
                    SnackbarManager.show(context.getString(R.string.ea_login_failed, it.message ?: ""))
                }
            busy = false
        }
    }

    SettingsGroup(
        modifier = Modifier.background(Color.Transparent),
        title = { Text(text = stringResource(R.string.settings_ea_account)) },
    ) {
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(if (displayName == null) R.string.ea_login else R.string.ea_logout)) },
            subtitle = {
                Text(
                    text = when {
                        busy -> stringResource(R.string.ea_login_in_progress)
                        displayName != null -> stringResource(R.string.ea_signed_in_as, displayName!!)
                        else -> stringResource(R.string.ea_login_subtitle)
                    },
                )
            },
            onClick = {
                if (busy) return@SettingsMenuLink
                if (displayName == null) {
                    launcher.launch(Intent(context, EaOAuthActivity::class.java))
                } else {
                    EaAuthManager.logout(context)
                    displayName = null
                }
            },
        )
    }
}
