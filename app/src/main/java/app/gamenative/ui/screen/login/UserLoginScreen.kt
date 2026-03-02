package app.gamenative.ui.screen.login

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import app.gamenative.Constants
import app.gamenative.ui.screen.auth.AmazonOAuthActivity
import app.gamenative.ui.screen.auth.EpicOAuthActivity
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import app.gamenative.utils.PlatformOAuthHandlers
import app.gamenative.R
import app.gamenative.enums.LoginResult
import app.gamenative.enums.LoginScreen
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.data.UserLoginState
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.model.UserLoginViewModel
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun UserLoginScreen(
    connectionState: ConnectionState,
    viewModel: UserLoginViewModel = viewModel(),
    onRetryConnection: () -> Unit,
    onContinueOffline: () -> Unit,
    onPlatformSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
    val snackBarHostState = remember { SnackbarHostState() }
    val userLoginState by viewModel.loginState.collectAsState()

    val gogOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleGogAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.gog_login_success_title),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    onPlatformSignedIn()
                },
                onDialogClose = { },
            )
        }
    }

    val epicOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleEpicAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.epic_login_success_title),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    onPlatformSignedIn()
                },
                onDialogClose = { },
            )
        }
    }

    val amazonOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleAmazonAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                onSuccess = {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.amazon_login_success_title),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    onPlatformSignedIn()
                },
                onDialogClose = { },
            )
        }
    }

    LaunchedEffect(userLoginState.loginScreen, userLoginState.isLoggingIn) {
        if (
            userLoginState.loginScreen != LoginScreen.TWO_FACTOR &&
            userLoginState.loginScreen != LoginScreen.QR &&
            userLoginState.isLoggingIn.not()
        ) {
            viewModel.onShowLoginScreen(LoginScreen.QR)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.snackEvents.collect { message ->
            snackBarHostState.showSnackbar(message)
        }
    }

    UserLoginScreenContent(
        snackBarHostState = snackBarHostState,
        connectionState = connectionState,
        userLoginState = userLoginState,
        onUsername = viewModel::setUsername,
        onPassword = viewModel::setPassword,
        onShowLoginScreen = viewModel::onShowLoginScreen,
        onRememberSession = viewModel::setRememberSession,
        onCredentialLogin = viewModel::onCredentialLogin,
        onTwoFactorLogin = viewModel::submit,
        onQrRetry = viewModel::onQrRetry,
        onSetTwoFactor = viewModel::setTwoFactorCode,
        onRetryConnection = onRetryConnection,
        onContinueOffline = onContinueOffline,
        onLaunchGog = { gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java)) },
        onLaunchEpic = { epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java)) },
        onLaunchAmazon = { amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UserLoginScreenContent(
    snackBarHostState: SnackbarHostState,
    connectionState: ConnectionState,
    userLoginState: UserLoginState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onShowLoginScreen: (LoginScreen) -> Unit,
    onRememberSession: (Boolean) -> Unit,
    onCredentialLogin: () -> Unit,
    onTwoFactorLogin: () -> Unit,
    onQrRetry: () -> Unit,
    onSetTwoFactor: (String) -> Unit,
    onRetryConnection: () -> Unit,
    onContinueOffline: () -> Unit,
    onLaunchGog: () -> Unit,
    onLaunchEpic: () -> Unit,
    onLaunchAmazon: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Logo
                Text(
                    text = stringResource(R.string.login_app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        brush = Brush.horizontalGradient(
                            colors = listOf(primaryColor, tertiaryColor),
                        ),
                    ),
                )

                // Privacy Policy Button
                val uriHandler = LocalUriHandler.current
                TextButton(
                    onClick = { uriHandler.openUri(Constants.Misc.PRIVACY_LINK) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.login_privacy_policy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Main Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // SnackBar
                SnackbarHost(
                    hostState = snackBarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                if (
                    userLoginState.isLoggingIn.not() &&
                    userLoginState.loginResult != LoginResult.Success
                ) {
                    // Login Card
                    Card(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        ),
                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        // Top gradient border
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(primaryColor, tertiaryColor, primaryColor),
                                    ),
                                ),
                        )

                        // Make the content scrollable
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (userLoginState.loginScreen == LoginScreen.TWO_FACTOR) {
                                TwoFactorAuthScreenContent(
                                    userLoginState = userLoginState,
                                    message = when {
                                        userLoginState.previousCodeIncorrect ->
                                            stringResource(R.string.steam_2fa_incorrect)

                                        userLoginState.loginResult == LoginResult.DeviceAuth ->
                                            stringResource(R.string.steam_2fa_device)

                                        userLoginState.loginResult == LoginResult.DeviceConfirm ->
                                            stringResource(R.string.steam_2fa_confirmation)

                                        userLoginState.loginResult == LoginResult.EmailAuth ->
                                            stringResource(
                                                R.string.steam_2fa_email,
                                                userLoginState.email ?: "...",
                                            )

                                        else -> ""
                                    },
                                    onSetTwoFactor = onSetTwoFactor,
                                    onLogin = onTwoFactorLogin,
                                )
                            } else {
                                if (isLandscape) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        QRCodeLogin(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                            isQrFailed = userLoginState.isQrFailed,
                                            qrCode = userLoginState.qrCode,
                                            onQrRetry = onQrRetry,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                        ) {
                                            CredentialsForm(
                                                connectionState = connectionState,
                                                username = userLoginState.username,
                                                onUsername = onUsername,
                                                password = userLoginState.password,
                                                onPassword = onPassword,
                                                rememberSession = userLoginState.rememberSession,
                                                onRememberSession = onRememberSession,
                                                onLoginBtnClick = onCredentialLogin,
                                                onRetryConnection = onRetryConnection,
                                                onContinueOffline = onContinueOffline,
                                            )
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        QRCodeLogin(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            isQrFailed = userLoginState.isQrFailed,
                                            qrCode = userLoginState.qrCode,
                                            onQrRetry = onQrRetry,
                                        )
                                        CredentialsForm(
                                            connectionState = connectionState,
                                            username = userLoginState.username,
                                            onUsername = onUsername,
                                            password = userLoginState.password,
                                            onPassword = onPassword,
                                            rememberSession = userLoginState.rememberSession,
                                            onRememberSession = onRememberSession,
                                            onLoginBtnClick = onCredentialLogin,
                                            onRetryConnection = onRetryConnection,
                                            onContinueOffline = onContinueOffline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // User is logging in - show appropriate loading state
                    LoadingScreen()
                }
            }

            // Or sign in with: Epic · GOG · Amazon · Skip login
            if (
                userLoginState.isLoggingIn.not() &&
                userLoginState.loginResult != LoginResult.Success
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.login_or_sign_in_with),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(onClick = onLaunchEpic) {
                            Text(
                                text = "Epic",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = onLaunchGog) {
                            Text(
                                text = "GOG",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = onLaunchAmazon) {
                            Text(
                                text = "Amazon",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = onContinueOffline) {
                            Text(
                                text = stringResource(R.string.login_skip_login),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsForm(
    connectionState: ConnectionState,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    rememberSession: Boolean,
    onRememberSession: (Boolean) -> Unit,
    onLoginBtnClick: () -> Unit,
    onRetryConnection: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    val isConnecting = connectionState == ConnectionState.CONNECTING
    val isSteamConnected = connectionState == ConnectionState.CONNECTED
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val passwordFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Username field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            // Show connecting state or disconnected error
            if (isConnecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .border(
                                BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(24.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.connecting_to_steam),
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (!isSteamConnected) {
                // Show "No connection to Steam" error with retry button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .border(
                                BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .padding(24.dp),
                    ) {
                        Text(stringResource(R.string.no_connection_to_steam), color = Color.White)
                        Box(contentAlignment = Alignment.Center) {
                            OutlinedButton(
                                onClick = onRetryConnection,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) {
                                Text(stringResource(R.string.retry_steam_connection))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Box(contentAlignment = Alignment.Center) {
                            Button(onClick = { onContinueOffline() }) {
                                Text(stringResource(R.string.continue_offline))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(R.string.login_username),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsername,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .focusRequester(usernameFocusRequester),
                placeholder = {
                    Text(
                        stringResource(R.string.login_username_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocusRequester.requestFocus() },
                ),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }

        // Password field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.login_password),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .focusRequester(passwordFocusRequester),
                placeholder = {
                    Text(
                        stringResource(R.string.login_password_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        onLoginBtnClick()
                    },
                ),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                trailingIcon = {
                    val image = if (passwordVisible) {
                        Icons.Filled.Visibility
                    } else {
                        Icons.Filled.VisibilityOff
                    }

                    val description = if (passwordVisible) {
                        stringResource(R.string.login_password_hide)
                    } else {
                        stringResource(R.string.login_password_show)
                    }

                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = image,
                            contentDescription = description,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        // Login button
        Button(
            onClick = {
                keyboardController?.hide()
                onLoginBtnClick()
            },
            enabled = isSteamConnected && username.isNotEmpty() && password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top=12.dp)
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                text = stringResource(R.string.login_sign_in),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun QRCodeLogin(
    modifier: Modifier = Modifier,
    isQrFailed: Boolean,
    qrCode: String?,
    onQrRetry: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isQrFailed) {
            Text(
                text = stringResource(R.string.login_qr_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            OutlinedButton(
                onClick = onQrRetry,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.login_retry_qr),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else if (qrCode.isNullOrEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(160.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.login_qr_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            // QR Code with fancy border
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(160.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.colorScheme.primary,
                            ),
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCodeImage(
                            modifier = Modifier.fillMaxSize(0.95f),
                            content = qrCode,
                            size = 200.dp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.login_qr_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Preview data class combining connection state and login state
 */
private data class LoginPreviewData(
    val connectionState: ConnectionState,
    val loginState: UserLoginState = UserLoginState(),
)

private class UserLoginPreview : PreviewParameterProvider<LoginPreviewData> {
    override val values = sequenceOf(
        LoginPreviewData(ConnectionState.CONNECTED),
        LoginPreviewData(ConnectionState.CONNECTED, UserLoginState(loginScreen = LoginScreen.QR, qrCode = "Hello World!")),
        LoginPreviewData(ConnectionState.CONNECTED, UserLoginState(loginScreen = LoginScreen.QR, isQrFailed = true)),
        LoginPreviewData(ConnectionState.CONNECTING),
        LoginPreviewData(ConnectionState.DISCONNECTED),
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_UserLoginScreen(
    @PreviewParameter(UserLoginPreview::class) previewData: LoginPreviewData,
) {
    val snackBarHostState = remember { SnackbarHostState() }

    PluviaTheme {
        Surface {
            UserLoginScreenContent(
                snackBarHostState = snackBarHostState,
                connectionState = previewData.connectionState,
                userLoginState = previewData.loginState,
                onUsername = { },
                onPassword = { },
                onRememberSession = { },
                onCredentialLogin = { },
                onTwoFactorLogin = { },
                onQrRetry = { },
                onSetTwoFactor = { },
                onShowLoginScreen = { },
                onRetryConnection = { },
                onContinueOffline = { },
                onLaunchGog = { },
                onLaunchEpic = { },
                onLaunchAmazon = { },
            )
        }
    }
}

@Preview(
    name = "UserLoginScreen - Landscape",
    widthDp = 960,
    heightDp = 540,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun Preview_UserLoginScreen_Landscape(
    @PreviewParameter(UserLoginPreview::class) previewData: LoginPreviewData,
) {
    val snackBarHostState = remember { SnackbarHostState() }

    PluviaTheme {
        Surface {
            UserLoginScreenContent(
                snackBarHostState = snackBarHostState,
                connectionState = previewData.connectionState,
                userLoginState = previewData.loginState,
                onUsername = { },
                onPassword = { },
                onRememberSession = { },
                onCredentialLogin = { },
                onTwoFactorLogin = { },
                onQrRetry = { },
                onSetTwoFactor = { },
                onShowLoginScreen = { },
                onRetryConnection = { },
                onContinueOffline = { },
                onLaunchGog = { },
                onLaunchEpic = { },
                onLaunchAmazon = { },
            )
        }
    }
}
