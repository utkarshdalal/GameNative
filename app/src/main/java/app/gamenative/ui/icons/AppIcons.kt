package app.gamenative.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Custom icons exposed similarly to Material icons.
 * Steam icon will be tinted by Icon() like Material icons.
 */
val Icons.Filled.Steam: ImageVector
    get() {
        if (_steam != null) return _steam!!
        _steam = ImageVector.Builder(
            name = "Steam",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 256.0f,
            viewportHeight = 259.0f,
        ).apply {
            addPath(
                pathData = addPathNodes("M127.779 0C60.42 0 5.24 52.412 0 119.014l68.724 28.674a35.812 35.812 0 0 1 20.426-6.366c.682 0 1.356.019 2.02.056l30.566-44.71v-.626c0-26.903 21.69-48.796 48.353-48.796 26.662 0 48.352 21.893 48.352 48.796 0 26.902-21.69 48.804-48.352 48.804-.37 0-.73-.009-1.098-.018l-43.593 31.377c.028.582.046 1.163.046 1.735 0 20.204-16.283 36.636-36.294 36.636-17.566 0-32.263-12.658-35.584-29.412L4.41 164.654c15.223 54.313 64.673 94.132 123.369 94.132 70.818 0 128.221-57.938 128.221-129.393C256 57.93 198.597 0 127.779 0zM80.352 196.332l-15.749-6.568c2.787 5.867 7.621 10.775 14.033 13.47 13.857 5.83 29.836-.803 35.612-14.799a27.555 27.555 0 0 0 .046-21.035c-2.768-6.79-7.999-12.086-14.706-14.909-6.67-2.795-13.811-2.694-20.085-.304l16.275 6.79c10.222 4.3 15.056 16.145 10.794 26.46-4.253 10.314-15.998 15.195-26.22 10.895zm121.957-100.29c0-17.925-14.457-32.52-32.217-32.52-17.769 0-32.226 14.595-32.226 32.52 0 17.926 14.457 32.512 32.226 32.512 17.76 0 32.217-14.586 32.217-32.512zm-56.37-.055c0-13.488 10.84-24.42 24.2-24.42 13.368 0 24.208 10.932 24.208 24.42 0 13.488-10.84 24.421-24.209 24.421-13.359 0-24.2-10.933-24.2-24.42z"),
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
        return _steam!!
    }

private var _steam: ImageVector? = null

val Icons.Filled.Amazon: ImageVector
    get() {
        if (_amazon != null) return _amazon!!
        _amazon = ImageVector.Builder(
            name = "Amazon",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f,
        ).apply {
            addPath(
                pathData = addPathNodes("M8.333 4.143a3.143 3.143 0 0 1 6.286 0c0 0.146-0.024 0.371-0.053 0.65-0.086 0.817-0.22 2.09 0.053 3.151 0.367 1.425 2.258 4.986 3.904 6.14 0.394 0.277 0.856 0.516 1.33 0.76 1.51 0.779 3.147 1.624 3.147 3.966A4.19 4.19 0 0 1 18.81 23c-1.004 0-1.8-0.587-2.645-1.21-0.483-0.355-0.982-0.723-1.546-1-1.549-0.762-8.033-0.688-9.114-0.344-0.47 0.15-0.84 0.355-1.173 0.54-0.432 0.239-0.799 0.443-1.237 0.443A2.095 2.095 0 0 1 1 19.333c0-1.085 1.176-2.029 1.947-2.648l0.148-0.12c0.758-0.614 3.51-4.771 4.916-8.375 0.405-1.04 0.374-2.033 0.341-3.11a28.36 28.36 0 0 1-0.019-0.937ZM7.201 16.767c0.782 1.237 7.17 1.44 8.382 0 1.023-1.215-1.943-7.104-4.038-7.255C9.449 9.36 6.418 15.53 7.2 16.767Z"),
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd,
            )
        }.build()
        return _amazon!!
    }

private var _amazon: ImageVector? = null

val Icons.Filled.CustomGame: ImageVector
    get() = Icons.Filled.FolderOpen

// Material Symbols head_mounted_device; its viewBox starts at y = -960, hence the translation.
val Icons.Filled.VrHeadset: ImageVector
    get() {
        if (_vrHeadset != null) return _vrHeadset!!
        _vrHeadset = ImageVector.Builder(
            name = "VrHeadset",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 960.0f,
            viewportHeight = 960.0f,
        ).apply {
            addGroup(translationY = 960.0f)
            addPath(
                pathData = addPathNodes("M300-240q-66 0-113-47t-47-113v-163q0-51 32-89.5t82-47.5q57-11 113-15.5t113-4.5q57 0 113.5 4.5T706-700q50 10 82 48t32 89v163q0 66-47 113t-113 47h-40q-13 0-26-1.5t-25-6.5l-64-22q-12-5-25-5t-25 5l-64 22q-12 5-25 6.5t-26 1.5h-40Zm0-80h40q7 0 13.5-1t12.5-3q29-9 56.5-19t57.5-10q30 0 58 9.5t56 19.5q6 2 12.5 3t13.5 1h40q33 0 56.5-23.5T740-400v-163q0-22-14-38t-35-21q-52-11-104.5-14.5T480-640q-54 0-106 4t-105 14q-21 4-35 20.5T220-563v163q0 33 23.5 56.5T300-320ZM70-400q-13 0-21.5-8.5T40-430v-100q0-13 8.5-21.5T70-560q13 0 21.5 8.5T100-530v100q0 13-8.5 21.5T70-400Zm820 0q-13 0-21.5-8.5T860-430v-100q0-13 8.5-21.5T890-560q13 0 21.5 8.5T920-530v100q0 13-8.5 21.5T890-400Zm-590 80q-33 0-56.5-23.5T220-400v-163q0-22 14-38.5t35-20.5q53-10 105-14t106-4q54 0 106.5 3.5T691-622q21 5 35 21t14 38v163q0 33-23.5 56.5T660-320h-40q-7 0-13.5-1t-12.5-3q-28-10-56-19.5t-58-9.5q-30 0-57.5 10T366-324q-6 2-12.5 3t-13.5 1h-40Z"),
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.NonZero,
            )
            clearGroup()
        }.build()
        return _vrHeadset!!
    }

private var _vrHeadset: ImageVector? = null
