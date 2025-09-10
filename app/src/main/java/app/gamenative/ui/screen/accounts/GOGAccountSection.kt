package app.gamenative.ui.screen.accounts

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.service.GOG.GOGService
import app.gamenative.ui.model.AccountManagementViewModel
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun GOGAccountSection(
    viewModel: AccountManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for GOG
    var isGOGLoggedIn by remember { mutableStateOf(false) }
    var gogUsername by remember { mutableStateOf("") }
    var gogAuthInProgress by remember { mutableStateOf(false) }
    var gogError by remember { mutableStateOf<String?>(null) }

    // Check for existing GOG credentials on startup
    LaunchedEffect(Unit) {
        if (GOGService.hasStoredCredentials(context)) {
            // Use GOGDL to validate credentials (this handles token refresh automatically)
            val validationResult = GOGService.validateCredentials(context)

            if (validationResult.isSuccess && validationResult.getOrThrow()) {
                // Credentials are valid, get user info
                val credentialsResult = GOGService.getStoredCredentials(context)
                if (credentialsResult.isSuccess) {
                    val credentials = credentialsResult.getOrThrow()
                    isGOGLoggedIn = true
                    gogUsername = credentials.username
                    gogError = null
                } else {
                    gogError = "Failed to get user info: ${credentialsResult.exceptionOrNull()?.message}"
                    isGOGLoggedIn = false
                    gogUsername = ""
                }
            } else {
                val errorMsg = if (validationResult.isFailure) {
                    "Validation failed: ${validationResult.exceptionOrNull()?.message}"
                } else {
                    "Session expired or invalid credentials"
                }
                gogError = errorMsg
                isGOGLoggedIn = false
                gogUsername = ""
            }
        }
    }

    // OAuth launcher for GOG authentication
    val gogOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val authCode = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
                if (authCode != null) {
                    // Got authorization code, now authenticate with GOGDL
                    scope.launch {
                        gogAuthInProgress = true
                        gogError = null

                        try {
                            val authConfigPath = "${context.filesDir}/gog_auth.json"
                            val authResult = GOGService.authenticateWithCode(authConfigPath, authCode)

                            if (authResult.isSuccess) {
                                val credentials = authResult.getOrThrow()
                                isGOGLoggedIn = true
                                gogUsername = credentials.username
                                gogError = null

                                // Automatically start GOG library sync after successful login
                                Timber.i("GOG login successful, starting automatic library sync...")
                                viewModel.syncGOGLibraryAsync(context, clearExisting = true) { result ->
                                    if (result.isSuccess) {
                                        Timber.i("GOG library sync started successfully after login")
                                    } else {
                                        Timber.w("Failed to start GOG library sync after login: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            } else {
                                gogError = authResult.exceptionOrNull()?.message ?: "Authentication failed"
                            }
                        } catch (e: Exception) {
                            gogError = e.message ?: "Authentication failed"
                        } finally {
                            gogAuthInProgress = false
                        }
                    }
                } else {
                    gogError = "No authorization code received"
                    gogAuthInProgress = false
                }
            }
            android.app.Activity.RESULT_CANCELED -> {
                val error = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                gogError = error ?: "Authentication cancelled"
                gogAuthInProgress = false
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // GOG Account Section
        AccountSection(
            title = "GOG",
            description = "Access your GOG library and DRM-free games",
            icon = "https://www.gog.com/favicon.ico",
            isLoggedIn = isGOGLoggedIn,
            username = if (isGOGLoggedIn) gogUsername else null,
            isLoading = gogAuthInProgress,
            error = gogError,
            onLogin = {
                // Launch GOG OAuth activity
                gogAuthInProgress = true
                gogError = null
                val intent = Intent(context, GOGOAuthActivity::class.java)
                gogOAuthLauncher.launch(intent)
            },
            onLogout = {
                scope.launch {
                    try {
                        // Clear stored credentials using the service method
                        GOGService.clearStoredCredentials(context)

                        isGOGLoggedIn = false
                        gogUsername = ""
                        gogError = null
                    } catch (e: Exception) {
                        gogError = "Logout error: ${e.message}"
                    }
                }
            },
        )
    }
}
