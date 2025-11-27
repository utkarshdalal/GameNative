package app.gamenative.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun ConnectingServersScreen(
    onContinueOffline: () -> Unit
) {
    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Connecting to remote servers...",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            CircularProgressIndicator(
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 24.dp),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(modifier = Modifier.padding(vertical = 16.dp), onClick = onContinueOffline) {
                Text(stringResource(R.string.continue_offline))
            }
        }
    }
}

@Preview
@Composable
private fun Preview_ConnectSteamScreen() {
    PluviaTheme {
        ConnectingServersScreen(
            onContinueOffline = {}
        )
    }
}
