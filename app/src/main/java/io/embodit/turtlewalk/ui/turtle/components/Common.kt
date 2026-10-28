package io.embodit.turtlewalk.ui.turtle.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.turtlewalk.ui.theme.TurtleColors
import io.embodit.turtlewalk.ui.turtle.debug.probe

/** Panel padding and the corner radius of the glass (design: Material.Regular root, 32 dp corners). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp

/** Glass root of a panel: clipped shape, Material.Regular, padding. */
@Composable
internal fun Glass(width: Dp, height: Dp, corner: Dp = 32.dp, material: Material = Material.Regular, padding: Dp = PanelPadding, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** Type roles of the design (`typography.roles`); the minimum on the code panel is 18 sp (read at 2.75 m at scale 1.2). */
internal object TurtleType {
    /** "Turned 120°", "You turned 270°": 72 / 76 at 700, tabular figures. */
    val bigNumber: TextStyle
        @Composable get() = PicoTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum, lnum")

    /** Result comparison, verdict: 28 / 34 at 600. */
    val headline: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Title bar: 22 / 28 at 600. */
    val titleLarge: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)

    /** Block text "Forward 1": 22 / 26 at 600. */
    val block: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Lesson names, distances, deviations: 20 / 24 at 600, tabular figures. */
    val titleMedium: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Explanations, insight, guidance: 18 / 26 at 500. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)

    /** "Lesson 3", "Segment 3 of 4", hint lines, button words: 18 / 22 at 600. */
    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Degree tags on the floor: 28 / 32 at 600, tabular. */
    val tag: TextStyle
        @Composable get() = PicoTheme.typography.headlineLarge.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")
}

/** A 56 x 48 icon key (more, back, close, delete, up, down, clear). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = content),
        size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button, 48 dp (56 dp for [large]) high. [primary] = interaction fill with ink text (the main action of a
 * layout); otherwise the tertiary fill with glass text. An icon goes before the word (design B19: icons carry words).
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    iconRes: Int? = null,
    large: Boolean = false,
) {
    val container = if (primary) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary
    val content = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (primary) TurtleColors.Ink else PicoTheme.colorScheme.labelPrimary
    val h = if (large) 56.dp else 48.dp
    Button(
        onClick = onClick,
        modifier = modifier.height(h).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = h, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TurtleType.label)
    }
}

/** A segmented control (field size, speed, values, language): SpatialUI SegmentControl. */
@Composable
internal fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: Dp = 80.dp, leadingIcon: Int? = null, iconDescription: String = "", modifier: Modifier = Modifier, enabled: Boolean = true) {
    SegmentControl(modifier = modifier.width(itemWidth * items.size).height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { if (enabled) onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(KeyHeight).probe(name, onClick),
                textStyle = TurtleType.label,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = iconDescription, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, sub: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(text = sub, color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (trailing != null) trailing()
    }
}

/**
 * A dark solid chip with light text (design `palette.brandTokens.warnChip / infoChip`, taste D13: warnings never as
 * orange text on glass): icon + one or two lines.
 */
@Composable
internal fun Chip(text: String, iconRes: Int?, background: Color, foreground: Color, modifier: Modifier = Modifier, style: TextStyle = TurtleType.label, maxLines: Int = 2) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).backgroundSolid(background).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (iconRes != null) Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp), tint = foreground)
        Text(text = text, color = foreground, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** An opaque fill drawn behind the content. */
internal fun Modifier.backgroundSolid(color: Color): Modifier = this.drawBehind { drawRect(color) }
