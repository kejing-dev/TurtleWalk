package io.embodit.turtlewalk.ui.turtle.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.turtlewalk.R
import io.embodit.turtlewalk.domain.model.BlockReason
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.Snack
import io.embodit.turtlewalk.ui.turtle.SnackKind
import io.embodit.turtlewalk.ui.theme.TurtleColors
import io.embodit.turtlewalk.ui.turtle.debug.probe
import io.embodit.turtlewalk.ui.turtle.scene.PanelSpec
import kotlin.math.abs

private const val FADE_IN_MS = 150

/**
 * "Spatial features unavailable" (design `panels.blockedDialog`): a self-built attachment panel
 * (480 x 240 dp at 1.125, 0.9 m ahead) with icon, title, one sentence, exit and retry. In a Stage the
 * SpatialUI `AlertDialog` composes nothing (Spatial Ruler finding).
 */
@Composable
internal fun BlockedDialog(reason: BlockReason?, onEvent: (TurtleEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (reason == null) return@Box
        val fade = remember(reason) { Animatable(0f) }
        LaunchedEffect(reason) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier =
                Modifier
                    .width(400.dp)
                    .heightIn(max = h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(32.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.error)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = rs(R.string.blocked_title), color = ink, style = TurtleType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(text = rs(R.string.blocked_body), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(TurtleEvent.ExitClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(TurtleEvent.RetryClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
            }
        }
    }
}

@Composable
private fun snackText(snack: Snack): String =
    when (snack.kind) {
        SnackKind.SCANNING -> rs(R.string.snack_scanning)
        SnackKind.EST_FLOOR -> rs(R.string.snack_est_floor)
        SnackKind.LOST -> rs(R.string.snack_lost)
        SnackKind.NOT_ON_START -> rs(R.string.snack_not_on_start)
        SnackKind.OFF_PATH -> rs(R.string.snack_off_path)
        SnackKind.SNAPPED -> rs(R.string.snack_snapped)
        SnackKind.STEP_SIZE -> rs(R.string.step_is, String.format(java.util.Locale.US, "%.2f", snack.value).trimEnd('0').trimEnd('.'))
        SnackKind.CLEARED -> rs(R.string.snack_cleared)
        SnackKind.TOO_LONG -> rs(R.string.snack_too_long, snack.number)
        SnackKind.LIMIT_BLOCKS -> rs(R.string.snack_limit_blocks)
        SnackKind.LIMIT_NESTING -> rs(R.string.snack_limit_nesting)
    }

/** Warnings speak on the dark warm chip, everything else on the dark info chip (design `panels.snackbar`, taste D13). */
private fun SnackKind.warns(): Boolean = this == SnackKind.LOST || this == SnackKind.NOT_ON_START || this == SnackKind.OFF_PATH || this == SnackKind.TOO_LONG || this == SnackKind.LIMIT_BLOCKS || this == SnackKind.LIMIT_NESTING

/**
 * The snackbar (design `panels.snackbar`): an attachment panel 0.04 m above the code panel's top edge, at most
 * 600 dp wide; icon or spinner + one line (+ "undo" after clearing), dark solid ground with light text. The capsule
 * takes the width of its message inside the fixed-size panel ([applied]: the scene squeezes the panel to the
 * capsule's width and the capsule is drawn stretched back).
 */
@Composable
internal fun SnackPanel(snack: Snack?, applied: Float = 1f, onWidth: (Float) -> Unit = {}, onEvent: (TurtleEvent) -> Unit) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { w.dp.toPx() }
        val warn = snack.kind.warns()
        val bg = if (warn) TurtleColors.WarnChipBg else TurtleColors.InfoChipBg
        val ink = if (warn) TurtleColors.WarnChipText else TurtleColors.InfoChipText
        val fade = remember(snack.kind) { Animatable(0f) }
        LaunchedEffect(snack.kind) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = w.dp)
                    .defaultMinSize(minHeight = 60.dp)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(30.dp))
                    .backgroundSolid(bg)
                    .probe("snackbar")
                    .padding(start = 18.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snack.kind == SnackKind.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(22.dp))
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (snack.kind) {
                                SnackKind.LOST -> R.drawable.ic_unavailable
                                SnackKind.EST_FLOOR -> R.drawable.ic_floor
                                SnackKind.NOT_ON_START -> R.drawable.ic_walk
                                SnackKind.STEP_SIZE -> R.drawable.ic_stick
                                SnackKind.CLEARED -> R.drawable.ic_clear
                                SnackKind.SNAPPED -> R.drawable.ic_check
                                else -> R.drawable.ic_warning
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ink,
                )
            }
            Text(text = snackText(snack), color = ink, style = TurtleType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snack.kind == SnackKind.CLEARED) {
                WordButton("undo", rs(R.string.action_undo), { onEvent(TurtleEvent.UndoClicked) }, primary = true, minWidth = 96.dp, iconRes = R.drawable.ic_undo)
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

/**
 * First-run coachmarks (design `panels.coach`, 400 x 136 dp at the code panel's lower left): (1) how to drag the
 * field, (2) pinch a block, (3) turn along. Each shows once (stored).
 */
@Composable
internal fun CoachPanel(which: Int?, onEvent: (TurtleEvent) -> Unit) {
    val (w, h) = PanelSpec.COACH_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (which == null) return@Box
        val fade = remember(which) { Animatable(0f) }
        LaunchedEffect(which) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("coach")
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(when (which) { 1 -> R.drawable.ic_stick; 2 -> R.drawable.ic_forward; else -> R.drawable.ic_turn }),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = PicoTheme.colorScheme.interaction,
            )
            Text(
                text = rs(when (which) { 1 -> R.string.coach_1; 2 -> R.string.coach_2; else -> R.string.coach_3 }),
                color = PicoTheme.colorScheme.labelPrimary,
                style = TurtleType.body,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WordButton("coach_got_it", rs(R.string.action_got_it), { onEvent(TurtleEvent.CoachDismissed) }, primary = true, minWidth = 88.dp, iconRes = R.drawable.ic_close)
        }
    }
}

/**
 * A degree tag on the floor (design `entities3d.turnWedge`): 120 x 56 dp, an opaque dark chip with the turtle's
 * degrees in yellow, 28 sp tabular figures; the scene turns it to your head every frame.
 */
@Composable
internal fun TagPanel(text: String?) {
    val (w, h) = PanelSpec.TAG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (text == null) return@Box
        Box(modifier = Modifier.clip(RoundedCornerShape(14.dp)).backgroundSolid(TurtleColors.InfoChipBg).padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(text = text, color = TurtleColors.TagTurtle, style = TurtleType.tag, maxLines = 1, softWrap = false)
        }
    }
}
