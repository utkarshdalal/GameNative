package app.gamenative.ui.screen.login

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gamenative.Constants
import app.gamenative.R
import app.gamenative.enums.LoginResult
import app.gamenative.enums.LoginScreen
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.data.UserLoginState
import app.gamenative.ui.model.UserLoginViewModel
import app.gamenative.ui.theme.PluviaTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.gamenative.ui.enums.Orientation
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import java.util.EnumSet
import android.content.Context
import androidx.compose.material3.FilledTonalButton
import app.gamenative.PrefManager

@Composable
fun UserLoginScreen(
    viewModel: UserLoginViewModel = viewModel(),
    onContinueOffline: () -> Unit,
) {
    val snackBarHostState = remember { SnackbarHostState() }
    val userLoginState by viewModel.loginState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.snackEvents.collect { message ->
            snackBarHostState.showSnackbar(message)
        }
    }

    UserLoginScreenContent(
        snackBarHostState = snackBarHostState,
        userLoginState = userLoginState,
        onUsername = viewModel::setUsername,
        onPassword = viewModel::setPassword,
        onShowLoginScreen = viewModel::onShowLoginScreen,
        onRememberSession = viewModel::setRememberSession,
        onCredentialLogin = viewModel::onCredentialLogin,
        onTwoFactorLogin = viewModel::submit,
        onQrRetry = viewModel::onQrRetry,
        onSetTwoFactor = viewModel::setTwoFactorCode,
        onRetryConnection = viewModel::retryConnection,
        onContinueOffline = onContinueOffline,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserLoginScreenContent(
    snackBarHostState: SnackbarHostState,
    userLoginState: UserLoginState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onShowLoginScreen: (LoginScreen) -> Unit,
    onRememberSession: (Boolean) -> Unit,
    onCredentialLogin: () -> Unit,
    onTwoFactorLogin: () -> Unit,
    onQrRetry: () -> Unit,
    onSetTwoFactor: (String) -> Unit,
    onRetryConnection: (Context) -> Unit,
    onContinueOffline: () -> Unit,
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logo
                Text(
                    text = stringResource(R.string.login_app_name),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        brush = Brush.horizontalGradient(
                            colors = listOf(primaryColor, tertiaryColor)
                        )
                    )
                )

                // Privacy Policy Button
                val uriHandler = LocalUriHandler.current
                TextButton(
                    onClick = { uriHandler.openUri(Constants.Misc.PRIVACY_LINK) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.login_privacy_policy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Main Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // SnackBar
                SnackbarHost(
                    hostState = snackBarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                if (
                    userLoginState.isLoggingIn.not() &&
                    userLoginState.loginResult != LoginResult.Success
                ) {
                    // Login Card
                    Card(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .width(400.dp)
                            .heightIn(min = 450.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                        ),
                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        // Top gradient border
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(primaryColor, tertiaryColor, primaryColor)
                                    )
                                )
                        )

                        // Make the content scrollable
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Title
                            Text(
                                text = stringResource(R.string.login_welcome_back),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Subtitle
                            Text(
                                text = stringResource(R.string.login_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )

                            // Tab selection between Credentials and QR Code
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
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary,
                                indicator = { tabPositions ->
                                    if (selectedTabIndex < tabPositions.size) {
                                        TabRowDefaults.Indicator(
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
                                        onShowLoginScreen(LoginScreen.CREDENTIAL)
                                    },
                                    text = {
                                        Text(
                                            "Credentials",
                                            color = if (selectedTabIndex == 0)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                                Tab(
                                    selected = selectedTabIndex == 1,
                                    onClick = {
                                        selectedTabIndex = 1
                                        onShowLoginScreen(LoginScreen.QR)
                                    },
                                    text = {
                                        Text(
                                            "QR Code",
                                            color = if (selectedTabIndex == 1)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Content based on selected tab
                            Crossfade(
                                targetState = userLoginState.loginScreen,
                                modifier = Modifier.fillMaxWidth()
                            ) { screen ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 350.dp)
                                ) {
                                    when (screen) {
                                        LoginScreen.CREDENTIAL -> {
                                            ModernUsernamePassword(
                                                isSteamConnected = userLoginState.isSteamConnected,
                                                username = userLoginState.username,
                                                onUsername = onUsername,
                                                password = userLoginState.password,
                                                onPassword = onPassword,
                                                rememberSession = userLoginState.rememberSession,
                                                onRememberSession = onRememberSession,
                                                onLoginBtnClick = onCredentialLogin,
                                                onRetryConnection = onRetryConnection,
                                                onContinueOffline = onContinueOffline,
                                                context = context,
                                            )
                                        }

                                        LoginScreen.TWO_FACTOR -> {
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
                                        }

                                        LoginScreen.QR -> {
                                            ModernQRCode(
                                                isQrFailed = userLoginState.isQrFailed,
                                                qrCode = userLoginState.qrCode,
                                                onQrRetry = onQrRetry
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (!userLoginState.isSteamConnected) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LoadingScreen()
                    }
                }
            }
            
            // Skip Steam login option
            if (
                userLoginState.isLoggingIn.not() &&
                userLoginState.loginResult != LoginResult.Success
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = onContinueOffline,
                        modifier = Modifier.padding(top = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.login_skip_steam),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernUsernamePassword(
    isSteamConnected: Boolean,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    rememberSession: Boolean,
    onRememberSession: (Boolean) -> Unit,
    onLoginBtnClick: () -> Unit,
    onRetryConnection: (Context) -> Unit,
    onContinueOffline: () -> Unit,
    context: Context,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val passwordFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Username field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            if (!isSteamConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .border(
                                BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(24.dp) // Padding inside the border
                    ) {
                        Text(stringResource(R.string.no_connection_to_steam), color = Color.White)
                        Box(contentAlignment = Alignment.Center) {
                            OutlinedButton (
                                onClick = { onRetryConnection(context) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    // transparent container keeps the outline style
                                    containerColor = Color.Transparent,
                                    // secondary-style text instead of primary
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            {
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
                modifier = Modifier.padding(bottom = 8.dp)
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
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(usernameFocusRequester),
                placeholder = {
                    Text(
                        "Enter your Steam username",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocusRequester.requestFocus() }
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

        // Password field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.login_password),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
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
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(passwordFocusRequester),
                placeholder = {
                    Text(
                        "Enter your password",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        onLoginBtnClick()
                    }
                ),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                trailingIcon = {
                    val image = if (passwordVisible) {
                        Icons.Filled.Visibility
                    } else {
                        Icons.Filled.VisibilityOff
                    }

                    val description = if (passwordVisible) "Hide password" else "Show password"

                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = image,
                            contentDescription = description,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        // Remember session checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = rememberSession,
                onCheckedChange = onRememberSession,
            )
            Text(
                text = stringResource(R.string.login_remember_session),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Login button
        Button(
            onClick = {
                keyboardController?.hide()
                onLoginBtnClick()
            },
            enabled = isSteamConnected && username.isNotEmpty() && password.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        ) {
            Text(
                text = stringResource(R.string.login_sign_in),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

    }
}

@Composable
private fun ModernQRCode(
    isQrFailed: Boolean,
    qrCode: String?,
    onQrRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 350.dp)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isQrFailed) {
            Text(
                text = stringResource(R.string.login_qr_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedButton(
                onClick = onQrRetry,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.padding(top = 16.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    text = stringResource(R.string.login_retry_qr),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        } else if (qrCode.isNullOrEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(32.dp)
                    .size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            // QR Code with fancy border
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(220.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.colorScheme.primary
                            )
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        QrCodeImage(
                            modifier = Modifier.fillMaxSize(0.95f),
                            content = qrCode,
                            size = 200.dp
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
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

internal class UserLoginPreview : PreviewParameterProvider<UserLoginState> {
    override val values = sequenceOf(
        UserLoginState(isSteamConnected = true),
        UserLoginState(isSteamConnected = true, loginScreen = LoginScreen.QR, qrCode = "Hello World!"),
        UserLoginState(isSteamConnected = true, loginScreen = LoginScreen.QR, isQrFailed = true),
        UserLoginState(isSteamConnected = false),
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_UserLoginScreen(
    @PreviewParameter(UserLoginPreview::class) state: UserLoginState,
) {
    val snackBarHostState = remember { SnackbarHostState() }

    PluviaTheme {
        Surface {
            UserLoginScreenContent(
                snackBarHostState = snackBarHostState,
                userLoginState = state,
                onUsername = { },
                onPassword = { },
                onRememberSession = { },
                onCredentialLogin = { },
                onTwoFactorLogin = { },
                onQrRetry = { },
                onSetTwoFactor = { },
                onShowLoginScreen = { },
                onRetryConnection = { context -> },
                onContinueOffline = { }
            )
        }
    }
}
