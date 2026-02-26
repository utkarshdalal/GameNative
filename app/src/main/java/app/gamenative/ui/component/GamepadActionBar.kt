package app.gamenative.ui.component

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.icons.InputIcons
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.shouldShowGamepadUI

// Icons from https://kenney.nl/assets/input-prompts (CC0 License)
enum class GamepadButton(@field:DrawableRes val iconRes: Int) {
    A(InputIcons.Xbox.buttonColorA),
    B(InputIcons.Xbox.buttonColorB),
    X(InputIcons.Xbox.buttonColorX),
    Y(InputIcons.Xbox.buttonColorY),
    LB(InputIcons.Xbox.lb),
    RB(InputIcons.Xbox.rb),
    LT(InputIcons.Xbox.lt),
    RT(InputIcons.Xbox.rt),
    START(InputIcons.Xbox.start),
    SELECT(InputIcons.Xbox.select),
    DPAD(InputIcons.Xbox.dpad),
    DPAD_UP(InputIcons.Xbox.dpadUp),
    DPAD_DOWN(InputIcons.Xbox.dpadDown),
    DPAD_LEFT(InputIcons.Xbox.dpadLeft),
    DPAD_RIGHT(InputIcons.Xbox.dpadRight),
}

data class GamepadAction(
    val button: GamepadButton,
    @get:StringRes val labelResId: Int,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun GamepadButtonHint(
    action: GamepadAction,
    modifier: Modifier = Modifier,
) {
    val clickableModifier = if (action.onClick != null) {
        modifier.clickable(onClick = action.onClick)
    } else {
        modifier
    }

    val label = stringResource(action.labelResId)

    Row(
        modifier = clickableModifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(action.button.iconRes),
            contentDescription = label,
            modifier = Modifier.size(28.dp),
        )

        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun GamepadActionBar(
    actions: List<GamepadAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val showGamepadUI = shouldShowGamepadUI()

    AnimatedVisibility(
        visible = visible && actions.isNotEmpty() && showGamepadUI,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        ) + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        ),
                    ),
                ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        GamepadButtonHint(action = action)
                    }
                }
            }
        }
    }
}

object LibraryActions {
    val select = GamepadAction(GamepadButton.A, R.string.action_select)
    val back = GamepadAction(GamepadButton.B, R.string.back)
    val options = GamepadAction(GamepadButton.START, R.string.options)
    val search = GamepadAction(GamepadButton.Y, R.string.search)
    val addGame = GamepadAction(GamepadButton.X, R.string.action_add_game)
    val refresh = GamepadAction(GamepadButton.RB, R.string.action_refresh)
    val play = GamepadAction(GamepadButton.A, R.string.run_app)
    val details = GamepadAction(GamepadButton.X, R.string.action_details)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_GamepadActionBar() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                GamepadActionBar(
                    actions = listOf(
                        LibraryActions.select,
                        LibraryActions.options,
                        LibraryActions.search,
                        LibraryActions.addGame,
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
