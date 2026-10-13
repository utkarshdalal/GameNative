package app.gamenative.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.online.InstallProfile
import app.gamenative.online.OnlineGameInfo
import app.gamenative.online.OnlineStatus

@Composable
fun OnlineInstallProfileSection(
    onlineInfo: OnlineGameInfo,
    selectedProfile: InstallProfile,
    onProfileSelected: (InstallProfile) -> Unit,
    modifier: Modifier = Modifier,
    titleRes: Int = R.string.online_install_detected_title,
    requireInstallChoice: Boolean = true,
) {
    if (requireInstallChoice && !onlineInfo.showInstallProfileChoice) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.online_install_status_label,
                onlineInfo.status.badgeLabel,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (onlineInfo.status == OnlineStatus.ONLINE_FLAKY || onlineInfo.status == OnlineStatus.UNTESTED_ONLINE) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.online_install_experimental_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        ProfileOptionCard(
            selected = selectedProfile == InstallProfile.OFFLINE,
            title = stringResource(R.string.online_install_offline_title),
            subtitle = stringResource(R.string.online_install_offline_subtitle),
            onClick = { onProfileSelected(InstallProfile.OFFLINE) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProfileOptionCard(
            selected = selectedProfile == InstallProfile.ONLINE,
            title = stringResource(R.string.online_install_online_title),
            subtitle = stringResource(R.string.online_install_online_subtitle),
            onClick = { onProfileSelected(InstallProfile.ONLINE) },
        )
    }
}

@Composable
private fun ProfileOptionCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
