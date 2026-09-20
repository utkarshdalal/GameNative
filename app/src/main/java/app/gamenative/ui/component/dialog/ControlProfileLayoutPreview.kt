package app.gamenative.ui.component.dialog

import android.graphics.Color.TRANSPARENT
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView
import org.json.JSONObject

@Composable
internal fun ControlLayoutPreview(json: JSONObject, screenSize: String) {
    val dimensions = screenSize.lowercase().split("x").mapNotNull { it.trim().toFloatOrNull() }
    val ratio = if (dimensions.size == 2 && dimensions[1] > 0f) {
        (dimensions[0] / dimensions[1]).coerceIn(0.6f, 2.5f)
    }
    else {
        16f / 9f
    }
    val jsonKey = json.toString()
    val frameColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(max = 320.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF11161D),
        border = BorderStroke(1.dp, frameColor),
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF18202A), Color(0xFF0E1319)),
                    ),
                )
                val minorGuide = Color.White.copy(alpha = 0.035f)
                val centerGuide = Color.White.copy(alpha = 0.075f)
                listOf(0.25f, 0.75f).forEach { fraction ->
                    drawLine(
                        minorGuide,
                        Offset(size.width * fraction, 0f),
                        Offset(size.width * fraction, size.height),
                    )
                    drawLine(
                        minorGuide,
                        Offset(0f, size.height * fraction),
                        Offset(size.width, size.height * fraction),
                    )
                }
                drawLine(
                    centerGuide,
                    Offset(size.width * 0.5f, 0f),
                    Offset(size.width * 0.5f, size.height),
                )
                drawLine(
                    centerGuide,
                    Offset(0f, size.height * 0.5f),
                    Offset(size.width, size.height * 0.5f),
                )
            }

            key(jsonKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        InputControlsView(context).apply previewView@ {
                            isEnabled = false
                            isClickable = false
                            isFocusable = false
                            setBackgroundColor(TRANSPARENT)
                            setOverlayOpacity(InputControlsView.DEFAULT_OVERLAY_OPACITY)
                            setProfile(
                                ControlsProfile(context, PREVIEW_PROFILE_ID).apply {
                                    name = json.optString("name")
                                    isListed = false
                                    loadElementsFromJson(this@previewView, json)
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

private const val PREVIEW_PROFILE_ID = -1
