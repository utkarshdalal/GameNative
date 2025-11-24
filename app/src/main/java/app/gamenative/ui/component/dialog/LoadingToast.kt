package app.gamenative.ui.component.dialog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Toast-style loading indicator that appears at the bottom of the screen.
 * 
 * @param visible Whether the toast is visible
 * @param message The loading message to display
 * @param doneMessage Optional message to show when done (triggers checkmark and fade out)
 * @param onDismiss Called when the toast should be dismissed (after showing done message)
 */
@Composable
fun LoadingToast(
    visible: Boolean,
    message: String,
    doneMessage: String? = null,
    onDismiss: () -> Unit = {},
) {
    var showDone by remember { mutableStateOf(false) }
    var shouldFadeOut by remember { mutableStateOf(false) }
    
    // When doneMessage is set, show checkmark and then fade out
    LaunchedEffect(doneMessage) {
        if (doneMessage != null) {
            showDone = true
            // Show done message for duration similar to Toast.LENGTH_SHORT (~2 seconds)
            delay(2000)
            shouldFadeOut = true
            delay(300) // Wait for fade out animation
            onDismiss()
        }
    }
    
    // Reset state when visibility changes
    LaunchedEffect(visible) {
        if (!visible) {
            showDone = false
            shouldFadeOut = false
        }
    }
    
    // Keep toast visible during fade out animation
    val toastVisible = visible || shouldFadeOut
    
    if (toastVisible) {
        // Custom toast overlay - no dialog, just a Box with high z-index
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1000f) // High z-index to appear on top
        ) {
            AnimatedVisibility(
                visible = visible && !shouldFadeOut,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 50.dp), // Match toast positioning (50dp from bottom)
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Toast-style card at the bottom
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF323232).copy(alpha = 0.95f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Animated transition between spinner and checkmark
                            AnimatedContent(
                                targetState = showDone && doneMessage != null,
                                transitionSpec = {
                                    (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith
                                    (fadeOut() + scaleOut(targetScale = 0.8f))
                                },
                                label = "iconTransition"
                            ) { isDone ->
                                if (isDone) {
                                    // Show checkmark when done
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = Color.White
                                    )
                                } else {
                                    // Show spinner while loading
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                }
                            }
                            
                            // Animated transition between messages
                            Crossfade(
                                targetState = if (showDone && doneMessage != null) doneMessage else message,
                                label = "messageTransition"
                            ) { currentMessage ->
                                Text(
                                    text = currentMessage ?: message,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

