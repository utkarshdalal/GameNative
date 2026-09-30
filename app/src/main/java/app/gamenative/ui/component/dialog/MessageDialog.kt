package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun MessageDialog(
    visible: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    onConfirmClick: (() -> Unit)? = null,
    onDismissClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
    confirmBtnText: String = "Confirm",
    dismissBtnText: String = "Dismiss",
    actionBtnText: String? = null,
    icon: ImageVector? = null,
    title: String? = null,
    message: String? = null,
    useHtmlInMsg: Boolean = false,
) {
    when {
        visible -> {
            AlertDialog(
                icon = icon?.let { { Icon(imageVector = icon, contentDescription = null) } },
                title = title?.let { { Text(it) } },
                text = message?.let {
                    {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (useHtmlInMsg) {
                                Text(
                                    text = AnnotatedString.fromHtml(
                                        htmlString = it,
                                        linkStyles = TextLinkStyles(
                                            style = SpanStyle(
                                                textDecoration = TextDecoration.Underline,
                                                fontStyle = FontStyle.Italic,
                                                color = Color.Blue,
                                            ),
                                        ),
                                    ),
                                )
                            } else {
                                Text(it)
                            }
                        }
                    }
                },
                onDismissRequest = { onDismissRequest?.invoke() },
                dismissButton = onDismissClick?.let {
                    {
                        TextButton(onClick = it) {
                            Text(dismissBtnText)
                        }
                    }
                },
                confirmButton = {
                    Row {
                        // Action button (displayed first if available)
                        if (actionBtnText != null && onActionClick != null) {
                            TextButton(onClick = onActionClick) {
                                Text(actionBtnText)
                            }
                        }

                        // Confirm button
                        onConfirmClick?.let {
                            TextButton(onClick = it) {
                                Text(confirmBtnText)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_MessageDialog() {
    PluviaTheme {
        MessageDialog(
            visible = true,
            icon = Icons.Default.Gamepad,
            title = "Title",
            message = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed " +
                "do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                "Ut enim ad minim veniam, quis nostrud exercitation ullamco " +
                "laboris nisi ut aliquip ex ea commodo consequat. Duis aute " +
                "irure dolor in reprehenderit in voluptate velit esse cillum " +
                "dolore eu fugiat nulla pariatur. Excepteur sint occaecat " +
                "cupidatat non proident, sunt in culpa qui officia deserunt " +
                "mollit anim id est laborum.",
            onDismissRequest = {},
            onDismissClick = {},
            onConfirmClick = {},
            onActionClick = {},
            actionBtnText = "Action",
        )
    }
}
