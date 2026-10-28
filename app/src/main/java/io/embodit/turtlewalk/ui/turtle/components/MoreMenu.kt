package io.embodit.turtlewalk.ui.turtle.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.Material
import io.embodit.turtlewalk.R
import io.embodit.turtlewalk.domain.model.LanguagePref
import io.embodit.turtlewalk.ui.turtle.Phase
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.TurtleUiState
import io.embodit.turtlewalk.ui.turtle.scene.PanelSpec

/**
 * The More menu (design `panels.moreMenu`, a pre-built attachment panel under the More key): language segment
 * (acts in place), "bring the field to me" (taste D9), the floor height keys (only on the estimated floor,
 * taste D7), lessons, place the field again, exit.
 */
@Composable
internal fun MoreMenu(state: TurtleUiState, filled: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp) {
        if (!filled) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Segments(
                // "follow the system" in words (taste B19: icon-only buttons are not understood)
                items = listOf("lang_system" to rs(R.string.lang_system_short), "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)),
                selected = state.language.ordinal,
                onSelect = { onEvent(TurtleEvent.LanguageSelected(LanguagePref.entries[it])) },
                itemWidth = 84.dp,
                leadingIcon = R.drawable.ic_globe,
                iconDescription = rs(R.string.lang_system),
            )
        }
        WordButton("menu_bring", rs(R.string.menu_bring), { onEvent(TurtleEvent.BringFieldClicked) }, modifier = Modifier.fillMaxWidth(), enabled = state.field != null && state.phase != Phase.WALKING, iconRes = R.drawable.ic_floor)
        if (state.estFloor) {
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = rs(R.string.menu_floor) + figureCm(state.floorNudge),
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = TurtleType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Key("floor_down", R.drawable.ic_down, rs(R.string.floor_down)) { onEvent(TurtleEvent.FloorNudged(-1)) }
                Key("floor_up", R.drawable.ic_up, rs(R.string.floor_up)) { onEvent(TurtleEvent.FloorNudged(1)) }
            }
        }
        WordButton("menu_lessons", rs(R.string.menu_lessons), { onEvent(TurtleEvent.LessonListClicked) }, modifier = Modifier.fillMaxWidth(), enabled = state.phase != Phase.LESSONS, iconRes = R.drawable.ic_back)
        val canReplace = state.phase in setOf(Phase.LESSONS, Phase.CODING, Phase.DRAWN, Phase.WALK_DONE, Phase.TURN_DONE)
        WordButton("menu_replace", rs(R.string.menu_replace), { onEvent(TurtleEvent.ReplaceFieldClicked) }, modifier = Modifier.fillMaxWidth(), enabled = canReplace, iconRes = R.drawable.ic_rotate45)
        WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(TurtleEvent.ExitClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_exit)
    }
}

/** " +4 cm" / " -2 cm" after the floor-height label; nothing at zero. */
private fun figureCm(m: Float): String {
    val cm = Math.round(m * 100f)
    return if (cm == 0) "" else if (cm > 0) "  +$cm cm" else "  $cm cm"
}
