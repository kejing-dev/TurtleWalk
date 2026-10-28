package io.embodit.turtlewalk.ui.turtle.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.turtlewalk.R
import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.Lesson
import io.embodit.turtlewalk.domain.model.Lessons
import io.embodit.turtlewalk.domain.model.RunSpeed
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.model.formatValue
import io.embodit.turtlewalk.domain.usecase.CheckResult
import io.embodit.turtlewalk.domain.usecase.Cursor
import io.embodit.turtlewalk.domain.usecase.Interpreter
import io.embodit.turtlewalk.domain.usecase.Program
import io.embodit.turtlewalk.domain.usecase.Side
import io.embodit.turtlewalk.ui.theme.TurtleColors
import io.embodit.turtlewalk.ui.turtle.Phase
import io.embodit.turtlewalk.ui.turtle.TurnMode
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.TurtleUiState
import io.embodit.turtlewalk.ui.turtle.WalkVerdict
import io.embodit.turtlewalk.ui.turtle.debug.probe
import io.embodit.turtlewalk.ui.turtle.scene.PanelSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The code panel (design `panels.codePanel`, 820 x 560 dp at 1.2): the one standing panel. Lessons, placing,
 * then three columns: blocks, program, and the right column whose content follows the phase (draw, result, turn
 * along, walk). The value bar opens at the bottom when a block of the program is selected. Built only from
 * SpatialUI components inside PicoTheme.
 */
@Composable
internal fun CodePanel(state: TurtleUiState, filled: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val (w, h) = PanelSpec.CODE_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        when (state.phase) {
            Phase.STARTING, Phase.BLOCKED -> Starting()
            Phase.LESSONS -> LessonsLayout(state, onEvent)
            Phase.PLACING -> PlacingLayout(state, onEvent)
            else -> CodeLayout(state, onEvent)
        }
    }
}

@Composable
private fun Starting() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(48.dp))
    }
}

@Composable
private fun MoreKey(onEvent: (TurtleEvent) -> Unit) = Key("more", R.drawable.ic_more, rs(R.string.more)) { onEvent(TurtleEvent.MoreClicked) }

@Composable
private fun BackKey(onEvent: (TurtleEvent) -> Unit) = Key("back", R.drawable.ic_back, rs(R.string.back)) { onEvent(TurtleEvent.BackClicked) }

private fun stepText(m: Float): String = String.format(Locale.US, "%.2f", m).trimEnd('0').trimEnd('.')

@Composable
private fun zh(): Boolean = LocalTurtleResources.current.configuration.locales[0].language == "zh"

// ============================================================================================
// lessons
// ============================================================================================

@Composable
private fun ColumnScope.LessonsLayout(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    TitleRow(rs(R.string.lessons_title), trailing = { MoreKey(onEvent) })
    val zh = zh()
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0 until 2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                for (col in 0 until 5) {
                    val lesson = Lessons.all[row * 5 + col]
                    LessonCard(lesson, state, zh, onEvent)
                }
            }
        }
    }
    val line = if (state.field != null) rs(R.string.lessons_field, stepText(state.fieldM), stepText(state.stepM)) else rs(R.string.lessons_no_field)
    Text(text = line, color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.body, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun LessonCard(lesson: Lesson, state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val record = state.settings.record(lesson.n)
    val onClick = { onEvent(TurtleEvent.LessonClicked(lesson.n)) }
    Button(
        onClick = onClick,
        modifier = Modifier.size(148.dp, 176.dp).probe("lesson_${lesson.n}", onClick),
        size = ButtonDefaults.Regular.copy(height = 176.dp, minWidth = 148.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LessonThumb(lesson, Modifier.size(84.dp))
            Text(text = rs(R.string.lesson_n, lesson.n), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 1)
            Text(text = lesson.name(zh), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tick(record.drawnRight)
                Tick(record.turned || record.walked)
            }
        }
    }
}

/** "Drawn" / "turned" as a check icon or an empty ring (design: hollow circle until done). */
@Composable
private fun Tick(done: Boolean) {
    if (done) {
        Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.passable)
    } else {
        val c = PicoTheme.colorScheme.labelTertiary
        Box(modifier = Modifier.size(20.dp).drawBehind { drawCircle(c, radius = size.minDimension / 2.6f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())) })
    }
}

/** The lesson's target as a yellow line drawing (design `spatialUiMap.lessonThumb`). */
@Composable
internal fun LessonThumb(lesson: Lesson, modifier: Modifier) {
    val exec = Interpreter.run(lesson.solution, lesson.start)
    val pts = ArrayList<Pair<Float, Float>>()
    for (f in exec.forwards) {
        if (pts.isEmpty()) pts += f.from.x to f.from.y
        pts += f.to.x to f.to.y
    }
    Box(
        modifier =
            modifier.drawBehind {
                if (pts.isEmpty()) return@drawBehind
                val minX = pts.minOf { it.first }
                val maxX = pts.maxOf { it.first }
                val minY = pts.minOf { it.second }
                val maxY = pts.maxOf { it.second }
                val span = maxOf(maxX - minX, maxY - minY, 0.5f)
                val k = (size.minDimension - 12.dp.toPx()) / span
                val cx = size.width / 2f - (minX + maxX) / 2f * k
                val cy = size.height / 2f + (minY + maxY) / 2f * k
                fun o(p: Pair<Float, Float>) = Offset(cx + p.first * k, cy - p.second * k)
                for (i in 0 until pts.size - 1) drawLine(TurtleColors.InkYellow, o(pts[i]), o(pts[i + 1]), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(TurtleColors.TurtleShell, radius = 5.dp.toPx(), center = o(pts.first()))
                if (lesson.star) drawCircle(TurtleColors.Interaction, radius = 5.dp.toPx(), center = o(pts.last()))
            }
    )
}

// ============================================================================================
// placing
// ============================================================================================

@Composable
private fun ColumnScope.PlacingLayout(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    TitleRow(rs(R.string.placing_title), leading = { BackKey(onEvent) })
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = rs(R.string.placing_body), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.body, maxLines = 4)
            FieldPicture(Modifier.size(200.dp, 150.dp))
        }
        Column(modifier = Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val sizes = TurtleRules.FIELD_SIZES
                Segments(
                    items = sizes.map { "size_${stepText(it)}" to "${stepText(it)} m" },
                    selected = sizes.indexOfFirst { abs(it - state.fieldM) < 0.01f },
                    onSelect = { onEvent(TurtleEvent.FieldSizeSelected(sizes[it])) },
                    itemWidth = 76.dp,
                )
                Text(text = rs(R.string.step_is, stepText(state.stepM)), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painter = painterResource(R.drawable.ic_stick), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Text(text = rs(R.string.placing_hint), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.label, maxLines = 2)
            }
            val check = state.placeCheck
            val warning =
                when {
                    check.wallCrosses -> rs(R.string.wall_crosses)
                    check.frontTooCloseCm != null -> rs(R.string.front_too_close, check.frontTooCloseCm)
                    check.cramped != null -> rs(R.string.cramped, sideWord(check.cramped.side), sideWord(opposite(check.cramped.side)), check.cramped.moveCm)
                    else -> null
                }
            if (warning != null) {
                Chip(warning, R.drawable.ic_warning, TurtleColors.WarnChipBg, TurtleColors.WarnChipText, Modifier.fillMaxWidth())
                val smaller = TurtleRules.FIELD_SIZES.firstOrNull { it < state.fieldM - 0.01f }
                if (smaller != null) WordButton("use_smaller", rs(R.string.use_smaller, stepText(smaller)), { onEvent(TurtleEvent.FieldSizeSelected(smaller)) })
            }
            if (state.estFloor) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = rs(R.string.est_floor_line), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 1, modifier = Modifier.weight(1f))
                    WordButton("adjust_height", rs(R.string.adjust_height), { onEvent(TurtleEvent.MoreClicked) }, minWidth = 96.dp)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                WordButton("rotate45", rs(R.string.rotate45), { onEvent(TurtleEvent.Rotate45Clicked) }, iconRes = R.drawable.ic_rotate45)
                WordButton("place_here", rs(R.string.place_here), { onEvent(TurtleEvent.PlaceHereClicked) }, primary = true, enabled = state.canPlace, large = true, minWidth = 180.dp, iconRes = R.drawable.ic_check)
            }
        }
    }
}

/** The small picture of placing: you, the field ahead of you with its front chevron, the code panel beyond. */
@Composable
private fun FieldPicture(modifier: Modifier) {
    val line = PicoTheme.colorScheme.labelPrimary
    val panel = PicoTheme.colorScheme.labelSecondary
    Box(
        modifier =
            modifier.drawBehind {
                val u = size.height / 10f
                val cx = size.width / 2f
                // the field
                val top = 1.6f * u
                val side = 5.4f * u
                val left = cx - side / 2f
                val dash = 0.5f * u
                var t = 0f
                while (t < side) {
                    val e = minOf(side, t + dash)
                    drawLine(line, Offset(left + t, top), Offset(left + e, top), strokeWidth = 2.dp.toPx())
                    drawLine(line, Offset(left + t, top + side), Offset(left + e, top + side), strokeWidth = 2.dp.toPx())
                    drawLine(line, Offset(left, top + t), Offset(left, top + e), strokeWidth = 2.dp.toPx())
                    drawLine(line, Offset(left + side, top + t), Offset(left + side, top + e), strokeWidth = 2.dp.toPx())
                    t += dash * 1.8f
                }
                // the code panel beyond the front edge, you below the back edge
                drawLine(panel, Offset(cx - 2.2f * u, 0.6f * u), Offset(cx + 2.2f * u, 0.6f * u), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(TurtleColors.Interaction, radius = 0.45f * u, center = Offset(cx, top + side + 1.2f * u))
                drawCircle(TurtleColors.TurtleShell, radius = 0.4f * u, center = Offset(cx, top + side * 0.7f))
            }
    )
}

@Composable
private fun sideWord(side: Side): String =
    when (side) {
        Side.LEFT -> rs(R.string.side_left)
        Side.RIGHT -> rs(R.string.side_right)
        Side.FRONT -> rs(R.string.side_front)
        Side.BACK -> rs(R.string.side_back)
    }

private fun opposite(side: Side): Side =
    when (side) {
        Side.LEFT -> Side.RIGHT
        Side.RIGHT -> Side.LEFT
        Side.FRONT -> Side.BACK
        Side.BACK -> Side.FRONT
    }

// ============================================================================================
// coding, running, results, turning, walking: three columns
// ============================================================================================

@Composable
private fun ColumnScope.CodeLayout(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    val lesson = state.lesson
    val zh = zh()
    val backable = state.phase in setOf(Phase.CODING, Phase.DRAWN, Phase.TURN_DONE, Phase.WALK_READY, Phase.WALK_DONE)
    val goal = goalText(lesson.n)
    val leading: (@Composable () -> Unit)? = if (backable) { { BackKey(onEvent) } } else null
    TitleRow(rs(R.string.lesson_title, lesson.n, lesson.name(zh)), sub = goal, leading = leading, trailing = { MoreKey(onEvent) })
    val editing = state.phase == Phase.CODING || state.phase == Phase.DRAWN
    val showValueBar = editing && state.selected != null
    val wide = state.phase == Phase.CODING
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Palette(state, wide, onEvent, Modifier.width(if (wide) 180.dp else 72.dp).fillMaxHeight())
        ProgramColumn(state, onEvent, Modifier.width(380.dp).fillMaxHeight())
        RightColumn(state, zh, onEvent, Modifier.weight(1f).fillMaxHeight())
    }
    if (showValueBar) ValueBar(state, onEvent)
}

@Composable
private fun goalText(n: Int): String =
    when (n) {
        1 -> rs(R.string.goal_1)
        2 -> rs(R.string.goal_2)
        3 -> rs(R.string.goal_3)
        4 -> rs(R.string.goal_4)
        5 -> rs(R.string.goal_5)
        6 -> rs(R.string.goal_6)
        7 -> rs(R.string.goal_7)
        8 -> rs(R.string.goal_8)
        9 -> rs(R.string.goal_9)
        else -> rs(R.string.goal_10)
    }

/** Block colours: the colour each block draws on the floor (design `palette.rule`). */
internal fun blockColors(kind: BlockKind): Pair<Color, Color> =
    when (kind) {
        BlockKind.FORWARD -> TurtleColors.InkYellow to TurtleColors.InkYellowText
        BlockKind.RIGHT -> TurtleColors.TurnRight to TurtleColors.TurnRightText
        BlockKind.LEFT -> TurtleColors.TurnLeft to TurtleColors.TurnLeftText
        BlockKind.REPEAT -> TurtleColors.RepeatViolet to TurtleColors.RepeatText
    }

internal fun blockIcon(kind: BlockKind): Int =
    when (kind) {
        BlockKind.FORWARD -> R.drawable.ic_forward
        BlockKind.RIGHT -> R.drawable.ic_turn_right
        BlockKind.LEFT -> R.drawable.ic_turn_left
        BlockKind.REPEAT -> R.drawable.ic_repeat
    }

@Composable
internal fun blockName(kind: BlockKind): String =
    when (kind) {
        BlockKind.FORWARD -> rs(R.string.block_forward)
        BlockKind.RIGHT -> rs(R.string.block_right)
        BlockKind.LEFT -> rs(R.string.block_left)
        BlockKind.REPEAT -> rs(R.string.block_repeat)
    }

@Composable
internal fun valueText(kind: BlockKind, value: Float): String =
    when (kind) {
        BlockKind.FORWARD -> rs(R.string.unit_steps, formatValue(value))
        BlockKind.LEFT, BlockKind.RIGHT -> formatValue(value) + "°"
        BlockKind.REPEAT -> rs(R.string.unit_times, formatValue(value))
    }

@Composable
private fun Palette(state: TurtleUiState, wide: Boolean, onEvent: (TurtleEvent) -> Unit, modifier: Modifier) {
    val editing = state.phase == Phase.CODING || state.phase == Phase.DRAWN
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (wide) Text(text = rs(R.string.palette_title), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 1)
        for (kind in state.lesson.blocks) {
            val (bg, fg) = blockColors(kind)
            val enabled = editing && state.refusal(kind) == null
            val onClick = { onEvent(TurtleEvent.PaletteClicked(kind)) }
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(64.dp).alpha(if (enabled) 1f else 0.45f).probe("palette_${kind.tag}", onClick),
                size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = if (wide) 170.dp else 64.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = editing,
                colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
            ) {
                Icon(painter = painterResource(blockIcon(kind)), contentDescription = blockName(kind), modifier = Modifier.size(28.dp), tint = fg)
                if (wide) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = blockName(kind), color = fg, style = TurtleType.block, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProgramColumn(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit, modifier: Modifier) {
    val editing = state.phase == Phase.CODING || state.phase == Phase.DRAWN
    val highlight =
        when (state.phase) {
            Phase.RUNNING, Phase.TURN_ALONG -> state.run?.blockId
            Phase.WALKING, Phase.TURN_HOME -> state.walk?.blockId
            else -> null
        }
    val showGhost = state.hintLevel >= 2 && state.program.isEmpty() && editing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.program_title), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 1, modifier = Modifier.weight(1f))
            Text(text = "${state.blockCount}", color = PicoTheme.colorScheme.labelTertiary, style = TurtleType.label, maxLines = 1)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showGhost) {
                Box(modifier = Modifier.alpha(0.4f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { GhostBlocks(state.lesson.solution, 0) }
                }
            } else if (state.program.isEmpty()) {
                Slot(state, Cursor(null, 0), editing, onEvent)
                Text(text = rs(R.string.program_empty), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.body, maxLines = 2, modifier = Modifier.padding(8.dp))
            } else {
                BlockList(state, state.program, null, 0, editing, highlight, onEvent)
            }
        }
    }
}

@Composable
private fun GhostBlocks(blocks: List<Block>, depth: Int) {
    for (b in blocks) {
        ProgramBlockFace(b, depth, selected = false, running = false, onClick = null)
        if (b.kind == BlockKind.REPEAT) GhostBlocks(b.body, depth + 1)
    }
}

/** The blocks of one level with their insertion slots; a repeat shows its body indented and an "into repeat" row. */
@Composable
private fun BlockList(state: TurtleUiState, blocks: List<Block>, parent: Int?, depth: Int, editing: Boolean, highlight: Int?, onEvent: (TurtleEvent) -> Unit) {
    Slot(state, Cursor(parent, 0), editing, onEvent, depth)
    blocks.forEachIndexed { i, b ->
        val onClick = { onEvent(TurtleEvent.BlockClicked(b.id)) }
        ProgramBlockFace(b, depth, selected = state.selected == b.id, running = highlight == b.id, onClick = if (editing) onClick else null)
        if (b.kind == BlockKind.REPEAT) {
            if (b.body.isNotEmpty()) BlockList(state, b.body, b.id, depth + 1, editing, highlight, onEvent)
            IntoRepeat(state, b, depth + 1, editing, onEvent)
        }
        if (i < blocks.size - 1 || parent == null) Slot(state, Cursor(parent, i + 1), editing, onEvent, depth)
    }
}

/** An insertion slot: a thin line that shows on hover; the cursor is a white 4 dp line. */
@Composable
private fun Slot(state: TurtleUiState, cursor: Cursor, editing: Boolean, onEvent: (TurtleEvent) -> Unit, depth: Int = 0) {
    val here = state.cursor == cursor && editing
    val onClick = { onEvent(TurtleEvent.SlotClicked(cursor)) }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(start = (depth * 24).dp).height(28.dp).probe("slot_${cursor.parent ?: 0}_${cursor.index}", onClick),
        size = ButtonDefaults.Regular.copy(height = 28.dp, minWidth = 120.dp),
        shape = RoundedCornerShape(8.dp),
        enabled = editing,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(if (here) 4.dp else 1.dp).clip(RoundedCornerShape(2.dp)).backgroundSolid(if (here) TurtleColors.Highlight else PicoTheme.colorScheme.dividerLine))
    }
}

@Composable
private fun IntoRepeat(state: TurtleUiState, repeat: Block, depth: Int, editing: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val cursor = Cursor(repeat.id, repeat.body.size)
    val here = state.cursor == cursor && editing
    val onClick = { onEvent(TurtleEvent.SlotClicked(cursor)) }
    Row(modifier = Modifier.fillMaxWidth().padding(start = ((depth - 1) * 24).dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(8.dp).height(56.dp).backgroundSolid(TurtleColors.RepeatViolet))
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).height(56.dp).probe("into_${repeat.id}", onClick),
            size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 120.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = editing,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Text(text = rs(R.string.into_repeat), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.label, maxLines = 1, modifier = Modifier.weight(1f))
            if (here) Box(modifier = Modifier.width(40.dp).height(4.dp).backgroundSolid(TurtleColors.Highlight))
        }
    }
}

/** One block of the program: its colour, icon, word and value; selected / running = white 4 dp outline, lighter. */
@Composable
private fun ProgramBlockFace(b: Block, depth: Int, selected: Boolean, running: Boolean, onClick: (() -> Unit)?) {
    val (bg, fg) = blockColors(b.kind)
    val outlined = selected || running
    Box(modifier = Modifier.fillMaxWidth().padding(start = (depth * 24).dp)) {
        Box(
            modifier =
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).let { m ->
                    if (outlined) m.backgroundSolid(TurtleColors.Highlight).padding(4.dp) else m
                }
        ) {
            Button(
                onClick = onClick ?: {},
                modifier = Modifier.fillMaxSize().let { if (onClick != null) it.probe("block_${b.id}", onClick) else it },
                size = ButtonDefaults.Regular.copy(height = if (outlined) 52.dp else 60.dp, minWidth = 120.dp),
                shape = RoundedCornerShape(if (outlined) 12.dp else 16.dp),
                // stays enabled while read-only (running, turning): a disabled SpatialUI button greys the block colour
                colors = ButtonDefaults.buttonColors(containerColor = if (outlined) lighten(bg) else bg, contentColor = fg),
            ) {
                Icon(painter = painterResource(blockIcon(b.kind)), contentDescription = null, modifier = Modifier.size(26.dp), tint = fg)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = blockName(b.kind), color = fg, style = TurtleType.block, maxLines = 1)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = valueText(b.kind, b.value), color = fg, style = TurtleType.block, maxLines = 1)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun lighten(c: Color): Color = Color(c.red + (1f - c.red) * 0.25f, c.green + (1f - c.green) * 0.25f, c.blue + (1f - c.blue) * 0.25f, c.alpha)

/** The value bar (design `panels.valueBar`): the selected block's name, its values, up / down / delete. */
@Composable
private fun ValueBar(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    val b = state.selected?.let { Program.find(state.program, it) } ?: return
    val (bg, _) = blockColors(b.kind)
    Row(
        modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(20.dp)).backgroundSolid(TurtleColors.InfoChipBg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painter = painterResource(blockIcon(b.kind)), contentDescription = null, modifier = Modifier.size(28.dp), tint = bg)
        Text(text = blockName(b.kind), color = TurtleColors.InfoChipText, style = TurtleType.titleMedium, maxLines = 1, modifier = Modifier.width(96.dp))
        val values = b.kind.values
        Segments(
            items = values.map { "value_${formatValue(it)}" to (formatValue(it) + if (b.isTurn) "°" else "") },
            selected = values.indexOfFirst { abs(it - b.value) < 1e-3f },
            onSelect = { onEvent(TurtleEvent.ValueSelected(values[it])) },
            itemWidth = if (values.size > 6) 52.dp else 66.dp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Key("move_up", R.drawable.ic_up, rs(R.string.move_up)) { onEvent(TurtleEvent.MoveSelected(true)) }
        Key("move_down", R.drawable.ic_down, rs(R.string.move_down)) { onEvent(TurtleEvent.MoveSelected(false)) }
        Key("delete", R.drawable.ic_delete, rs(R.string.delete)) { onEvent(TurtleEvent.DeleteSelected) }
    }
}

// ============================================================================================
// right column
// ============================================================================================

@Composable
private fun RightColumn(state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.phase) {
            Phase.CODING -> CodingRight(state, zh, onEvent)
            Phase.RUNNING -> RunningRight(state, onEvent)
            Phase.DRAWN -> DrawnRight(state, zh, onEvent)
            Phase.TURN_ALONG -> TurnAlongRight(state, onEvent)
            Phase.TURN_DONE -> TurnDoneRight(state, zh, onEvent)
            Phase.WALK_READY -> WalkReadyRight(state, onEvent)
            Phase.WALKING, Phase.TURN_HOME -> WalkingRight(state, onEvent)
            Phase.WALK_DONE -> WalkDoneRight(state, zh, onEvent)
            else -> Unit
        }
    }
}

@Composable
private fun Big(text: String) = Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.bigNumber, maxLines = 1, softWrap = false)

@Composable
private fun Label(text: String) = Text(text = text, color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 2)

@Composable
private fun Body(text: String, maxLines: Int = 4) = Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.body, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

private fun degText(d: Float): String = "${d.roundToInt()}°"

@Composable
private fun ColumnScope.CodingRight(state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val lesson = state.lesson
    if (state.hintLevel == 0) {
        Label(rs(R.string.turn_sum))
        Big("—")
    } else {
        Chip(lesson.hint1(zh), R.drawable.ic_hint, TurtleColors.InfoChipBg, TurtleColors.InfoChipText, Modifier.fillMaxWidth(), maxLines = 4)
        if (state.hintLevel >= 2 && state.program.isNotEmpty()) AnswerCard(lesson)
    }
    Spacer(modifier = Modifier.weight(1f))
    WordButton("run", rs(R.string.action_run), { onEvent(TurtleEvent.RunClicked) }, Modifier.fillMaxWidth(), primary = true, enabled = state.program.isNotEmpty(), iconRes = R.drawable.ic_play, large = true)
    if (state.drawnRight) WordButton("turn_along", rs(R.string.action_turn_along), { onEvent(TurtleEvent.TurnAlongClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_turn)
    else WordButton("guess", rs(R.string.action_guess), { onEvent(TurtleEvent.GuessTurnClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_turn)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WordButton("hint", rs(R.string.action_hint), { onEvent(TurtleEvent.HintClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_hint, minWidth = 96.dp)
        Key("clear", R.drawable.ic_clear, rs(R.string.action_clear), enabled = state.program.isNotEmpty()) { onEvent(TurtleEvent.ClearClicked) }
    }
}

/** The answer of hint level 2 when the program is not empty: a small read-only card (design R2-11). */
@Composable
private fun AnswerCard(lesson: Lesson) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).backgroundSolid(TurtleColors.InfoChipBg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = rs(R.string.hint_answer), color = TurtleColors.InfoChipText, style = TurtleType.label, maxLines = 1)
        Text(text = answerText(lesson.solution), color = TurtleColors.InfoChipText, style = TurtleType.label, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun answerText(blocks: List<Block>): String =
    blocks.map { b ->
        if (b.kind == BlockKind.REPEAT) "${blockName(b.kind)} ${valueText(b.kind, b.value)} { ${answerText(b.body)} }"
        else "${blockName(b.kind)} ${valueText(b.kind, b.value)}"
    }.joinToString(" · ")

@Composable
private fun ColumnScope.RunningRight(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    Label(rs(R.string.turned))
    Big(degText(state.run?.turned ?: 0f))
    Segments(
        items = listOf("speed_slow" to rs(R.string.speed_slow), "speed_fast" to rs(R.string.speed_fast)),
        selected = if (state.runSpeed == RunSpeed.SLOW) 0 else 1,
        onSelect = { onEvent(TurtleEvent.SpeedSelected(if (it == 0) RunSpeed.SLOW else RunSpeed.FAST)) },
        itemWidth = 96.dp,
    )
    Spacer(modifier = Modifier.weight(1f))
    WordButton("finish", rs(R.string.action_finish), { onEvent(TurtleEvent.FinishRunClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_skip, large = true)
    WordButton("stop", rs(R.string.action_stop), { onEvent(TurtleEvent.StopClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_stop)
}

@Composable
private fun verdictLine(r: CheckResult): String =
    when (r.verdict) {
        Verdict.MATCH -> rs(R.string.verdict_match)
        Verdict.OPEN -> if (r.outOfField) rs(R.string.verdict_different) else rs(R.string.verdict_open, String.format(Locale.US, "%.1f", r.gapSteps))
        Verdict.SPIN -> rs(R.string.verdict_spin, abs(r.spinDeg).roundToInt())
        Verdict.MIRROR -> rs(R.string.verdict_mirror, if (r.mirrorToRight) rs(R.string.block_right) else rs(R.string.block_left))
        Verdict.TOO_MANY -> rs(R.string.verdict_too_many, r.blockCount)
        Verdict.DIFFERENT -> if (r.outOfField) rs(R.string.verdict_out) else rs(R.string.verdict_different)
    }

@Composable
private fun ColumnScope.DrawnRight(state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val r = state.result ?: return
    val lesson = state.lesson
    val right = r.verdict == Verdict.MATCH && !r.outOfField
    Text(text = rs(R.string.turtle_turned, r.turnTotal.roundToInt()), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.headline, maxLines = 2)
    r.laps?.let { Text(text = rq(R.plurals.laps, it), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1) }
    val (bg, fg, icon) =
        when {
            right -> Triple(PicoTheme.colorScheme.passable, TurtleColors.PassableText, R.drawable.ic_check)
            r.verdict == Verdict.DIFFERENT -> Triple(TurtleColors.Error, TurtleColors.ErrorText, R.drawable.ic_warning)
            else -> Triple(TurtleColors.WarnChipBg, TurtleColors.WarnChipText, R.drawable.ic_warning)
        }
    Chip(verdictLine(r), icon, bg, fg, Modifier.fillMaxWidth(), maxLines = 3)
    if (r.outOfField && r.verdict != Verdict.DIFFERENT) Label(rs(R.string.out_note))
    if (r.sixtyHint) Chip(rs(R.string.sixty_hint), R.drawable.ic_hint, TurtleColors.InfoChipBg, TurtleColors.InfoChipText, Modifier.fillMaxWidth(), maxLines = 3)
    if (right) Body(lesson.insight(zh))
    if (state.hintLevel >= 1 && !right) Chip(lesson.hint1(zh), R.drawable.ic_hint, TurtleColors.InfoChipBg, TurtleColors.InfoChipText, Modifier.fillMaxWidth(), maxLines = 3)
    if (state.hintLevel >= 2 && !right) AnswerCard(lesson)
    Spacer(modifier = Modifier.weight(1f))
    if (right) {
        WordButton("turn_along", rs(R.string.action_turn_along), { onEvent(TurtleEvent.TurnAlongClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_turn, large = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WordButton("walk", rs(R.string.action_walk), { onEvent(TurtleEvent.WalkClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_walk, minWidth = 120.dp)
            WordButton("next", if (lesson.n >= Lessons.all.size) rs(R.string.action_lessons) else rs(R.string.action_next), { onEvent(TurtleEvent.NextLessonClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_chevron_right, minWidth = 120.dp)
        }
        Text(text = if (lesson.walkOptional) rs(R.string.lesson9_walk) else rs(R.string.walk_needs), color = PicoTheme.colorScheme.labelSecondary, style = TurtleType.label, maxLines = 1)
    } else {
        WordButton("again", rs(R.string.action_again), { onEvent(TurtleEvent.RunClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_play, large = true)
        WordButton("hint", rs(R.string.action_hint), { onEvent(TurtleEvent.HintClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_hint)
    }
}

@Composable
private fun ColumnScope.TurnAlongRight(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    val t = state.turn ?: return
    Body(if (t.mode == TurnMode.GUESS) rs(R.string.guess_body) else rs(R.string.turn_body), maxLines = 2)
    Label(rs(R.string.you_turned))
    Big(degText(t.youDeg))
    if (t.total > 0) Text(text = rs(R.string.corner_of, (t.corner + 1).coerceAtMost(t.total), t.total), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.label, maxLines = 1)
    if (state.lesson.walkOptional) Label(rs(R.string.lesson9_watch))
    Spacer(modifier = Modifier.weight(1f))
    WordButton("turned", rs(R.string.action_turned), { onEvent(TurtleEvent.TurnedClicked) }, Modifier.fillMaxWidth(), primary = true, enabled = t.waiting, iconRes = R.drawable.ic_check, large = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.lesson.walkOptional) WordButton("skip_end", rs(R.string.action_skip_end), { onEvent(TurtleEvent.SkipToEndClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_skip, minWidth = 120.dp)
        WordButton("stop", rs(R.string.action_stop), { onEvent(TurtleEvent.StopClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_stop, minWidth = 96.dp)
    }
}

@Composable
private fun ColumnScope.TurnDoneRight(state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val t = state.turn ?: return
    val lesson = state.lesson
    if (t.mode == TurnMode.GUESS) {
        Text(text = rs(R.string.guess_yours, t.yours.joinToString(" · ") { degText(abs(it)) }), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 3)
        Text(text = rs(R.string.guess_turtle, t.turtles.joinToString(" · ") { degText(abs(it)) }), color = TurtleColors.InkYellow, style = TurtleType.titleMedium, maxLines = 3)
        Spacer(modifier = Modifier.weight(1f))
        WordButton("back_to_blocks", rs(R.string.action_back_to_blocks), { onEvent(TurtleEvent.BackToBlocksClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_back, large = true)
        return
    }
    if (t.total == 0) {
        Text(text = rs(R.string.no_corners), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.headline, maxLines = 2)
    } else {
        Text(text = rs(R.string.turn_compare, t.turtles.sum().roundToInt(), t.yours.sum().roundToInt()), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.headline, maxLines = 2)
        val closed = state.result?.laps != null
        if (closed) Chip(rs(R.string.full_turn), R.drawable.ic_check, PicoTheme.colorScheme.passable, TurtleColors.PassableText, Modifier.fillMaxWidth())
    }
    Body(lesson.insight(zh))
    Spacer(modifier = Modifier.weight(1f))
    WordButton("next", if (lesson.n >= Lessons.all.size) rs(R.string.action_lessons) else rs(R.string.action_next), { onEvent(TurtleEvent.NextLessonClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_chevron_right, large = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (t.total > 0) WordButton("turn_again", rs(R.string.action_turn_again), { onEvent(TurtleEvent.TurnAlongClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_turn, minWidth = 120.dp)
        WordButton("walk", rs(R.string.action_walk), { onEvent(TurtleEvent.WalkClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_walk, minWidth = 120.dp)
    }
}

@Composable
private fun ColumnScope.WalkReadyRight(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    val w = state.walk ?: return
    Body(rs(R.string.walk_ready_body), maxLines = 2)
    val ok = w.toStartM <= 0.25f * state.stepM && w.facing
    if (ok) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.passable)
            Text(text = rs(R.string.standing_ok), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1)
        }
    } else {
        Text(text = rs(R.string.to_start, String.format(Locale.US, "%.1f", w.toStartM)), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1)
    }
    if (state.lesson.walkOptional) Label(rs(R.string.lesson9_walk))
    Spacer(modifier = Modifier.weight(1f))
    WordButton("start_walk", rs(R.string.action_start_walk), { onEvent(TurtleEvent.StartWalkClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_walk, large = true)
    WordButton("no_walk", rs(R.string.action_no_walk), { onEvent(TurtleEvent.StopClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_close)
}

@Composable
private fun ColumnScope.WalkingRight(state: TurtleUiState, onEvent: (TurtleEvent) -> Unit) {
    val w = state.walk ?: return
    if (state.phase == Phase.TURN_HOME) Body(rs(R.string.home_body), maxLines = 2)
    Label(rs(R.string.you_turned))
    Big(degText(w.youDeg))
    if (state.phase == Phase.WALKING) Text(text = rs(R.string.segment_of, (w.segment + 1).coerceAtMost(w.segments), w.segments), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.label, maxLines = 1)
    if (state.offPath) Chip(rs(R.string.snack_off_path), R.drawable.ic_warning, TurtleColors.WarnChipBg, TurtleColors.WarnChipText, Modifier.fillMaxWidth())
    Spacer(modifier = Modifier.weight(1f))
    WordButton("stop", rs(R.string.action_stop), { onEvent(TurtleEvent.StopClicked) }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_stop, large = true)
}

@Composable
private fun ColumnScope.WalkDoneRight(state: TurtleUiState, zh: Boolean, onEvent: (TurtleEvent) -> Unit) {
    val w = state.walk ?: return
    val lesson = state.lesson
    Text(text = rs(R.string.turn_compare, w.turtleDeg.roundToInt(), w.youDeg.roundToInt()), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.headline, maxLines = 2)
    Text(text = rs(R.string.mean_dev, w.meanDevCm), color = PicoTheme.colorScheme.labelPrimary, style = TurtleType.titleMedium, maxLines = 1)
    val (bg, fg, text) =
        when (w.verdict) {
            WalkVerdict.GREAT -> Triple(PicoTheme.colorScheme.passable, TurtleColors.PassableText, rs(R.string.walk_great))
            WalkVerdict.OK -> Triple(TurtleColors.WarnChipBg, TurtleColors.WarnChipText, rs(R.string.walk_ok))
            else -> Triple(TurtleColors.Error, TurtleColors.ErrorText, rs(R.string.walk_again))
        }
    Chip(text, if (w.verdict == WalkVerdict.GREAT) R.drawable.ic_check else R.drawable.ic_warning, bg, fg, Modifier.fillMaxWidth())
    Body(lesson.insight(zh), maxLines = 3)
    Spacer(modifier = Modifier.weight(1f))
    WordButton("next", if (lesson.n >= Lessons.all.size) rs(R.string.action_lessons) else rs(R.string.action_next), { onEvent(TurtleEvent.NextLessonClicked) }, Modifier.fillMaxWidth(), primary = true, iconRes = R.drawable.ic_chevron_right, large = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WordButton("walk_again", rs(R.string.action_walk_again), { onEvent(TurtleEvent.WalkClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_walk, minWidth = 120.dp)
        WordButton("back_to_blocks", rs(R.string.action_back_to_blocks), { onEvent(TurtleEvent.BackToBlocksClicked) }, Modifier.weight(1f), iconRes = R.drawable.ic_back, minWidth = 120.dp)
    }
}

@Suppress("unused")
private val keepDp: Dp = 0.dp
