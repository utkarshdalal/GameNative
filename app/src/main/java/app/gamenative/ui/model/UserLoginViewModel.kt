package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.enums.LoginResult
import app.gamenative.enums.LoginScreen
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.data.UserLoginState
import com.posthog.PostHog
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class UserLoginViewModel : ViewModel() {
    private val _loginState = MutableStateFlow(UserLoginState())
    val loginState: StateFlow<UserLoginState> = _loginState.asStateFlow()

    private val _snackEvents = Channel<String>()
    val snackEvents = _snackEvents.receiveAsFlow()

    private val submitChannel = Channel<String>()

    private val authenticator = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            Timber.tag("UserLoginViewModel").i("Two-Factor, device confirmation")

            _loginState.update { currentState ->
                currentState.copy(
                    loginResult = LoginResult.DeviceConfirm,
                    loginScreen = LoginScreen.TWO_FACTOR,
                    isLoggingIn = false,
                    lastTwoFactorMethod = "steam_guard",
                )
            }

            return CompletableFuture.completedFuture(true)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            Timber.tag("UserLoginViewModel").d("Two-Factor, device code")

            _loginState.update { currentState ->
                currentState.copy(
                    loginResult = LoginResult.DeviceAuth,
                    loginScreen = LoginScreen.TWO_FACTOR,
                    isLoggingIn = false,
                    previousCodeIncorrect = previousCodeWasIncorrect,
                    lastTwoFactorMethod = "authenticator_code",
                )
            }

            return CompletableFuture<String>().apply {
                viewModelScope.launch {
                    val code = submitChannel.receive()
                    complete(code)
                }
            }
        }

        override fun getEmailCode(
            email: String?,
            previousCodeWasIncorrect: Boolean,
        ): CompletableFuture<String> {
            Timber.tag("UserLoginViewModel").d("Two-Factor, asking for email code")

            _loginState.update { currentState ->
                currentState.copy(
                    loginResult = LoginResult.EmailAuth,
                    loginScreen = LoginScreen.TWO_FACTOR,
                    isLoggingIn = false,
                    email = email,
                    previousCodeIncorrect = previousCodeWasIncorrect,
                    lastTwoFactorMethod = "email_code",
                )
            }

            return CompletableFuture<String>().apply {
                viewModelScope.launch {
                    val code = submitChannel.receive()
                    complete(code)
                }
            }
        }
    }

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.i("Received is connected")
        // Only handle auto-login state, connection state is managed by MainViewModel
        if (it.isAutoLoggingIn) {
            _loginState.update { currentState ->
                currentState.copy(isLoggingIn = true, isSteamConnected = true,)
            }
        }
    }

    private val onSteamDisconnected: (SteamEvent.Disconnected) -> Unit = {
        Timber.tag("UserLoginViewModel").i("Received disconnected from Steam")
        _loginState.update { currentState ->
            currentState.copy(isSteamConnected = false)
        }
    }

    private val onRemoteDisconnected: (SteamEvent.RemotelyDisconnected) -> Unit = {
        Timber.tag("UserLoginViewModel").i("Disconnected from steam remotely")
        _loginState.update { it.copy(isSteamConnected = false) }
    }

    private val onLogonStarted: (SteamEvent.LogonStarted) -> Unit = {
        _loginState.update { currentState ->
            currentState.copy(isLoggingIn = true)
        }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = {
        Timber.tag("UserLoginViewModel").i("Received login result: ${it.loginResult}")
        val prevState = _loginState.value
        _loginState.update { currentState ->
            currentState.copy(
                isLoggingIn = false,
                loginResult = it.loginResult,
            )
        }

        // PostHog logging
        val method = when (prevState.loginScreen) {
            LoginScreen.QR -> "qr"
            LoginScreen.TWO_FACTOR -> prevState.lastTwoFactorMethod ?: "unknown_2fa"
            LoginScreen.CREDENTIAL -> "password"
        }

        if (it.loginResult == LoginResult.Success) {
            PostHog.capture(
                event = "login_success",
                properties = mapOf("method" to method),
            )
        } else if (it.loginResult == LoginResult.Failed) {
            PostHog.capture(
                event = "login_failed",
                properties = mapOf(
                    "method" to method,
                    "reason" to (it.message ?: "unknown"),
                ),
            )
            it.message?.let(::showSnack)
        }
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        val currentLoginScreen = _loginState.value.loginScreen
        if (currentLoginScreen == LoginScreen.TWO_FACTOR) {
            _loginState.update { currentState ->
                currentState.copy(loginScreen = LoginScreen.CREDENTIAL)
            }
        } else if (currentLoginScreen == LoginScreen.QR) {
            _loginState.update { currentState ->
                currentState.copy(loginScreen = LoginScreen.CREDENTIAL)
            }
        }
        // From credential screen, back press is handled by the system (exits app)
    }

    private val onQrChallengeReceived: (SteamEvent.QrChallengeReceived) -> Unit = { event ->
        _loginState.update { currentState ->
            currentState.copy(qrCode = event.challengeUrl, isQrFailed = false)
        }
    }

    private val onQrAuthEnded: (SteamEvent.QrAuthEnded) -> Unit = {
        _loginState.update { currentState ->
            currentState.copy(isQrFailed = !it.success, qrCode = null)
        }

        it.message?.let(::showSnack)
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.tag("UserLoginViewModel").i("Received logged out")
        _loginState.update {
            it.copy(
                isSteamConnected = false,
                isLoggingIn = false,
                isQrFailed = false,
                loginResult = LoginResult.Failed,
                loginScreen = LoginScreen.CREDENTIAL,
            )
        }
    }

    init {
        Timber.tag("UserLoginViewModel").d("init")

        PluviaApp.events.on<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.on<SteamEvent.LogonStarted, Unit>(onLogonStarted)
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<SteamEvent.QrChallengeReceived, Unit>(onQrChallengeReceived)
        PluviaApp.events.on<SteamEvent.QrAuthEnded, Unit>(onQrAuthEnded)
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit>(onLoggedOut)

        val isLoggedIn = SteamService.isLoggedIn
        Timber.d("Logged in? $isLoggedIn")

        val isSteamConnected = SteamService.isConnected
        Timber.tag("UserLoginViewModel").d("Logged in? $isLoggedIn")
        if (isLoggedIn) {
            _loginState.update {
                it.copy(isLoggingIn = true, isQrFailed = false, loginResult = LoginResult.Success)
            }
        }
    }

    override fun onCleared() {
        Timber.tag("UserLoginViewModel").d("onCleared")

        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.LogonStarted, Unit>(onLogonStarted)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<SteamEvent.QrChallengeReceived, Unit>(onQrChallengeReceived)
        PluviaApp.events.off<SteamEvent.QrAuthEnded, Unit>(onQrAuthEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)

        SteamService.stopLoginWithQr()
    }

    private fun showSnack(message: String) {
        viewModelScope.launch {
            _snackEvents.send(message)
        }
    }

    fun onCredentialLogin() {
        with(_loginState.value) {
            if (username.isEmpty() && password.isEmpty()) {
                return@with
            }

            viewModelScope.launch {
                SteamService.startLoginWithCredentials(
                    username = username,
                    password = password,
                    rememberSession = rememberSession,
                    authenticator = authenticator,
                )
            }
        }
    }

    fun submit() {
        viewModelScope.launch {
            submitChannel.send(_loginState.value.twoFactorCode)

            _loginState.update { currentState ->
                currentState.copy(isLoggingIn = true)
            }
        }
    }

    fun onShowLoginScreen(loginScreen: LoginScreen) {
        _loginState.update { currentState ->
            currentState.copy(
                loginScreen = loginScreen,
                isQrFailed = false,
                qrCode = null,
            )
        }

        if (loginScreen == LoginScreen.QR) {
            viewModelScope.launch {
                SteamService.startLoginWithQr()
            }
        } else {
            SteamService.stopLoginWithQr()
        }
    }

    fun onQrRetry() {
        _loginState.update { currentState ->
            currentState.copy(isQrFailed = false, qrCode = null)
        }
        viewModelScope.launch {
            SteamService.startLoginWithQr()
        }
    }

    fun setUsername(username: String) {
        _loginState.update { currentState ->
            currentState.copy(username = username)
        }
    }

    fun setPassword(password: String) {
        _loginState.update { currentState ->
            currentState.copy(password = password)
        }
    }

    fun setRememberSession(rememberPass: Boolean) {
        _loginState.update { currentState ->
            currentState.copy(rememberSession = rememberPass)
        }
    }

    fun setTwoFactorCode(twoFactorCode: String) {
        _loginState.update { currentState ->
            currentState.copy(twoFactorCode = twoFactorCode)
        }
    }

    fun retryConnection(context: Context) {
        // Reset error/login state if needed
        _loginState.update { currentState ->
            currentState.copy(
                isLoggingIn = false,
                loginResult = LoginResult.Failed,
                isSteamConnected = false,
                isQrFailed = false,
                qrCode = null
            )
        }
        // Restart the SteamService
        viewModelScope.launch {
            try {
                val intent = android.content.Intent(context, app.gamenative.service.SteamService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Timber.tag("UserLoginViewModel").e(e, "Failed to restart SteamService in retryConnection")
                showSnack("Failed to restart Steam connection: ${e.localizedMessage}")
            }
        }
    }
}
