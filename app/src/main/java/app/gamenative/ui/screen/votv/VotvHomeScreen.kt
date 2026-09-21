package app.gamenative.ui.screen.votv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.model.VotvViewModel
import app.gamenative.ui.util.SnackbarManager

/**
 * The entire UI for the VOTV launcher variant: import Voices of the Void's files, then
 * launch it. Unlike the full GameNative app, there's no library, no platform login, no
 * settings screen here yet — those are added only as they're actually needed.
 */
@Composable
fun VotvHomeScreen(
    mainViewModel: MainViewModel,
    votvViewModel: VotvViewModel,
) {
    val context = LocalContext.current
    val importState by votvViewModel.importState.collectAsState()
    val gameAppId by votvViewModel.gameAppId.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            votvViewModel.importGame(uri)
        }
    }

    LaunchedEffect(importState.error) {
        importState.error?.let { message ->
            SnackbarManager.show(message)
            votvViewModel.consumeError()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.votv_home_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            when {
                importState.isImporting -> {
                    Text(text = stringResource(R.string.votv_importing))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                gameAppId == null -> {
                    Text(
                        text = stringResource(R.string.votv_import_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { importLauncher.launch(null) }) {
                        Text(text = stringResource(R.string.votv_import_button))
                    }
                }

                else -> {
                    Button(onClick = {
                        mainViewModel.setLaunchedAppId(gameAppId!!)
                        mainViewModel.launchApp(context, gameAppId!!)
                    }) {
                        Text(text = stringResource(R.string.votv_launch_button))
                    }
                    TextButton(onClick = { votvViewModel.clearImportedGame() }) {
                        Text(text = stringResource(R.string.votv_reimport_button))
                    }
                }
            }
        }
    }
}
