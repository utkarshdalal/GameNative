package app.gamenative.ui.component.dialog

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.enums.LoginResult
import app.gamenative.enums.LoginScreen
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.screen.login.QrCodeImage
import app.gamenative.ui.data.UserLoginState
import app.gamenative.ui.model.UserLoginViewModel
import app.gamenative.ui.screen.login.TwoFactorAuthScreenContent
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamLoginDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    viewModel: UserLoginViewModel = hiltViewModel()
) {
    if (!visible) return

    val userLoginState by viewModel.loginState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Reset login state when opening
    LaunchedEffect(visible) {
        if (visible && userLoginState.loginResult == LoginResult.Success) {
            // If already logged in, we might want to show logout or just close
            // For now, let's just allow it to open
        }
    }

    // Automatically close dialog on successful login
    LaunchedEffect(userLoginState.loginResult) {
        if (userLoginState.loginResult == LoginResult.Success) {
            onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isLandscape,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.9f else 1f)
                .heightIn(max = configuration.screenHeightDp.dp * 0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Login,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Sign in to Steam",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab selection
                var selectedTabIndex by remember {
                    mutableIntStateOf(
                        when (userLoginState.loginScreen) {
                            LoginScreen.QR -> 1
                            else -> 0
                        }
                    )
                }

                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        if (selectedTabIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                height = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = {
                            selectedTabIndex = 0
                            viewModel.onShowLoginScreen(LoginScreen.CREDENTIAL)
                        },
                        text = { Text("Credentials") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = {
                            selectedTabIndex = 1
                            viewModel.onShowLoginScreen(LoginScreen.QR)
                        },
                        text = { Text("QR Code") }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    if (userLoginState.isLoggingIn && userLoginState.loginResult != LoginResult.Success) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Crossfade(targetState = userLoginState.loginScreen) { screen ->
                            when (screen) {
                                LoginScreen.CREDENTIAL -> {
                                    UsernamePasswordForm(
                                        userLoginState = userLoginState,
                                        viewModel = viewModel,
                                        context = context
                                    )
                                }
                                LoginScreen.TWO_FACTOR -> {
                                    TwoFactorAuthScreenContent(
                                        userLoginState = userLoginState,
                                        message = when {
                                            userLoginState.previousCodeIncorrect -> stringResource(R.string.steam_2fa_incorrect)
                                            userLoginState.loginResult == LoginResult.DeviceAuth -> stringResource(R.string.steam_2fa_device)
                                            userLoginState.loginResult == LoginResult.DeviceConfirm -> stringResource(R.string.steam_2fa_confirmation)
                                            userLoginState.loginResult == LoginResult.EmailAuth -> stringResource(R.string.steam_2fa_email, userLoginState.email ?: "...")
                                            else -> ""
                                        },
                                        onSetTwoFactor = viewModel::setTwoFactorCode,
                                        onLogin = viewModel::submit
                                    )
                                }
                                LoginScreen.QR -> {
                                    QRCodeContent(
                                        userLoginState = userLoginState,
                                        onQrRetry = viewModel::onQrRetry
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        enabled = !userLoginState.isLoggingIn
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun UsernamePasswordForm(
    userLoginState: UserLoginState,
    viewModel: UserLoginViewModel,
    context: Context
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val passwordFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!userLoginState.isSteamConnected) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.no_connection_to_steam),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = { viewModel.retryConnection(context) }) {
                        Text(stringResource(R.string.retry_steam_connection))
                    }
                }
            }
        }

        OutlinedTextField(
            value = userLoginState.username,
            onValueChange = viewModel::setUsername,
            label = { Text(stringResource(R.string.login_username)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
        )

        OutlinedTextField(
            value = userLoginState.password,
            onValueChange = viewModel::setPassword,
            label = { Text(stringResource(R.string.login_password)) },
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester),
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboardController?.hide()
                viewModel.onCredentialLogin()
            })
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = userLoginState.rememberSession,
                onCheckedChange = viewModel::setRememberSession
            )
            Text(
                text = stringResource(R.string.login_remember_session),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = {
                keyboardController?.hide()
                viewModel.onCredentialLogin()
            },
            enabled = userLoginState.isSteamConnected && userLoginState.username.isNotEmpty() && userLoginState.password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(stringResource(R.string.login_sign_in))
        }
    }
}

@Composable
private fun QRCodeContent(
    userLoginState: UserLoginState,
    onQrRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (userLoginState.isQrFailed) {
            Text(
                text = stringResource(R.string.login_qr_failed),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onQrRetry) {
                Text(stringResource(R.string.login_retry_qr))
            }
        } else if (userLoginState.qrCode.isNullOrEmpty()) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                QrCodeImage(
                    modifier = Modifier.fillMaxSize(),
                    content = userLoginState.qrCode!!,
                    size = 184.dp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.login_qr_instructions),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
