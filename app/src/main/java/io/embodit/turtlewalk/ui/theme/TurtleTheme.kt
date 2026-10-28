package io.embodit.turtlewalk.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme (design `palette.implementation`): all sixteen public roles are written out once.
 * interaction / passable / alert / error carry the design values; the other twelve are forwarded from
 * the system (Vibrant) scheme on purpose. Text on glass uses `labelPrimary`.
 */
@Composable
fun TurtleTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimary = system.labelPrimary,
                labelPrimaryLight = system.labelPrimaryLight,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                dividerLine = system.dividerLine,
                interaction = TurtleColors.Interaction,
                passable = TurtleColors.Passable,
                alert = TurtleColors.Alert,
                error = TurtleColors.Error,
            )
        }
    val family = rememberTurtleFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
