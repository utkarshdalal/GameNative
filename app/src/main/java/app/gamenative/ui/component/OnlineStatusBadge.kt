package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.online.OnlineStatus

@Composable
fun OnlineStatusBadge(
    status: OnlineStatus,
    modifier: Modifier = Modifier,
) {
    val background = when (status) {
        OnlineStatus.ONLINE_OK -> Color(0xFF1B5E20)
        OnlineStatus.ONLINE_FLAKY -> Color(0xFFEF6C00)
        OnlineStatus.ONLINE_BLOCKED -> Color(0xFFB71C1C)
        OnlineStatus.UNTESTED_ONLINE -> Color(0xFF1565C0)
        OnlineStatus.OFFLINE_ONLY -> Color(0xFF455A64)
    }
    Text(
        text = status.badgeLabel,
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
    )
}
