package app.gamenative.ui.data

import app.gamenative.enums.LoginResult
import app.gamenative.enums.LoginScreen

data class UserLoginState(
    val username: String = "",
    val password: String = "",
    val rememberSession: Boolean = true,
    val twoFactorCode: String = "",

    val isSteamConnected: Boolean = false,
    val isLoggingIn: Boolean = false,

    val loginResult: LoginResult = LoginResult.Failed,
    val loginScreen: LoginScreen = LoginScreen.CREDENTIAL,

    val previousCodeIncorrect: Boolean = false,

    val email: String? = null,

    val qrCode: String? = null,
    val isQrFailed: Boolean = false,
    val lastTwoFactorMethod: String? = null,
)
