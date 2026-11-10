package io.github.aqua0801.droidlink.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import io.github.aqua0801.droidlink.desktop.ThemeMode

private val Green = Color(0xFF3DDC84)
private val DeepGreen = Color(0xFF006D3B)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DroidLinkDesktopTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = Green, secondary = Green)
    } else {
        lightColorScheme(primary = DeepGreen, secondary = DeepGreen)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Drawn in code so the app needs no image resources: two linked rings on a green disc. */
object AppIcon : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawCircle(Green)
        val r = size.minDimension * 0.17f
        val stroke = Stroke(width = size.minDimension * 0.07f)
        drawCircle(Color.White, radius = r, center = Offset(size.width * 0.40f, size.height * 0.5f), style = stroke)
        drawCircle(Color.White, radius = r, center = Offset(size.width * 0.60f, size.height * 0.5f), style = stroke)
    }
}
