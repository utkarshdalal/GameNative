package app.gamenative.ui.screen.support

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.api.SupportApi
import app.gamenative.service.SupportReplyWatchService

private fun canRequestNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (context.applicationInfo.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) return false
    return try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
    } catch (e: Exception) {
        false
    }
}

@Composable
internal fun rememberNotifyOffer(conversation: SupportApi.Conversation?): (() -> Unit)? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requestable = remember { canRequestNotifications(context) }
    var enabled by remember { mutableStateOf(SupportReplyWatchService.canNotify(context)) }
    var declined by rememberSaveable { mutableStateOf(false) }
    val current by rememberUpdatedState(conversation)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = SupportReplyWatchService.canNotify(context)
        val target = current
        if (enabled && target != null && target.awaitingReply) {
            SupportReportSubmitter.watchForReply(context, target, target.id, target.game)
        } else if (!granted) {
            declined = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = SupportReplyWatchService.canNotify(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (conversation == null || !conversation.awaitingReply || enabled || declined || !requestable) return null
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
