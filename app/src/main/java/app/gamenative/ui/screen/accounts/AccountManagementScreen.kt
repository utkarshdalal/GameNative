package app.gamenative.ui.screen.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.gamenative.ui.component.topbar.BackButton
import com.alorma.compose.settings.ui.SettingsGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val snackBarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackBarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Manage Accounts") },
                navigationIcon = {
                    BackButton(onClick = { navController.popBackStack() })
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .padding(paddingValues)
                .displayCutoutPadding()
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            AccountsGroup(navController = navController)
        }
    }
}

@Composable
private fun AccountsGroup(
    navController: NavController
) {
    SettingsGroup(title = { Text(text = "Accounts") }) {
        SteamAccountSection(navController = navController)
        // Other account sections (GOG, Epic Games, etc.)
    }
}

@Composable
private fun AccountInfoCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Platform Information",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = "• You can use GameNative without logging into any accounts\n" +
                      "• Steam login enables downloading and playing Steam games",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Keep the existing AccountSection for backward compatibility
@Composable
fun AccountSection(
    title: String,
    description: String,
    icon: String,
    isLoggedIn: Boolean,
    username: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(16.dp)
    ) {
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
        
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CoilImage(
                    imageModel = { icon },
                    imageOptions = ImageOptions(
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        alignment = androidx.compose.ui.Alignment.Center
                    ),
                    modifier = Modifier.size(32.dp),
                    failure = {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = if (isLoggedIn) 
                                MaterialTheme.colorScheme.onPrimaryContainer 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isLoggedIn) 
                            MaterialTheme.colorScheme.onPrimaryContainer 
                        else 
                            MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = if (isLoggedIn && username != null) 
                            "Logged in as $username" 
                        else 
                            description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isLoggedIn) 
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status indicator
                Icon(
                    imageVector = if (isLoggedIn) Icons.Default.CheckCircle else Icons.Default.Circle,
                    contentDescription = if (isLoggedIn) "Connected" else "Not connected",
                    tint = if (isLoggedIn) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            // Error message
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // Action button
            if (isLoggedIn) {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Logout, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out")
                }
            } else {
                Button(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLoading) "Signing In..." else "Sign In")
                }
            }
        }
    }
}
