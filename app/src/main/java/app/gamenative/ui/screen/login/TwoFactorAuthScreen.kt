package app.gamenative.ui.screen.login

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.enums.LoginResult
import app.gamenative.ui.data.UserLoginState
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun TwoFactorAuthScreenContent(
    userLoginState: UserLoginState,
    message: String,
    onSetTwoFactor: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 350.dp)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (userLoginState.loginResult == LoginResult.DeviceConfirm) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (userLoginState.loginResult == LoginResult.EmailAuth ||
            userLoginState.loginResult == LoginResult.DeviceAuth
        ) {
            TwoFactorTextField(
                twoFactorText = userLoginState.twoFactorCode,
                onTwoFactorTextChange = onSetTwoFactor,
            )
            Spacer(modifier = Modifier.height(24.dp))
            ElevatedButton(
                enabled = userLoginState.twoFactorCode.length == 5,
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                content = {
                    Text(
                        text = stringResource(R.string.two_factor_login),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    )
                },
            )
        }
    }
}

// Someday: Redo this with the possibly of fancy OTP boxes with proper autofilling.
@Composable
private fun TwoFactorTextField(
    twoFactorText: String,
    onTwoFactorTextChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(true) {
        focusRequester.requestFocus()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.two_factor_verification_code),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp)
                ),
            value = twoFactorText,
            onValueChange = { value ->
                val filtered = value.filter { it.isLetterOrDigit() }.take(5)
                onTwoFactorTextChange(filtered)
            },
            singleLine = true,
            placeholder = {
                Text(
                    stringResource(R.string.two_factor_enter_code),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

internal class TwoFactorPreview : PreviewParameterProvider<UserLoginState> {
    override val values = sequenceOf(
        UserLoginState(loginResult = LoginResult.DeviceConfirm),
        UserLoginState(loginResult = LoginResult.DeviceAuth),
        UserLoginState(loginResult = LoginResult.EmailAuth),
    )
}

// Odin2 Mini
@Preview(device = "spec:width=1920px,height=1080px,dpi=440", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_TwoFactorAuthScreen(
    @PreviewParameter(TwoFactorPreview::class) state: UserLoginState,
) {
    var currentState by remember { mutableStateOf(state) }
    PluviaTheme {
        Surface {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                TwoFactorAuthScreenContent(
                    userLoginState = currentState,
                    message = when (state.loginResult) {
                        LoginResult.DeviceAuth -> stringResource(R.string.steam_2fa_device)
                        LoginResult.DeviceConfirm -> stringResource(R.string.steam_2fa_confirmation)
                        LoginResult.EmailAuth -> stringResource(
                            R.string.steam_2fa_email,
                            "pluvia@email.com",
                        )

                        else -> "???"
                    },
                    onSetTwoFactor = { value ->
                        currentState = currentState.copy(twoFactorCode = value)
                    },
                    onLogin = {
                        currentState = currentState.copy(twoFactorCode = "")
                    },
                )
            }
        }
    }
}
