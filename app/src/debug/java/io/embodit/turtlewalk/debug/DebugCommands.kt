package io.embodit.turtlewalk.debug

import android.content.Context
import io.embodit.turtlewalk.domain.model.LanguagePref
import io.embodit.turtlewalk.platform.TurtleLog
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.debug.TurtleProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `TurtleWalk`) answers every command.
 *
 *   adb shell "am broadcast -a io.embodit.turtlewalk.DEBUG -p io.embodit.turtlewalk --es cmd 'state drawn:open@L5'"
 *
 * - `state <name>`               straight to a designed state and hold it until the next `state`:
 *                                phase[+overlay...][:mode][@Ln] (design `stateNaming`), e.g. `coding+blockSelected@L4`,
 *                                `drawn:mirror@L3`, `drawn+outOfField@L3`, `turnAlong@L3`, `turnDone:guess@L5`, `placing+estFloor`
 * - `lesson <n>`                 opens lesson n (as tapping its card)
 * - `place [2|1.5|1]`            places the field in front of you now
 * - `program <text>`             sets the program: `F1 R90`, `R4[F1 R90]` (R<n>[...] or X<n>[...] = repeat)
 * - `solve`                      the lesson's reference answer as the program
 * - `run [slow|fast|instant]`    draws the program; `run_to <n>` stops the turtle at the end of segment n
 * - `turn_along perfect|lazy|guess`  turning along with a synthetic head; `turn <deg>` turns your level heading
 * - `walk_perfect|walk_noisy|walk_cut|walk_overshoot|walk_off`  the optional walk with a synthetic walker; `walk_to <steps>`
 * - `stick <x>[,<y>][,<ms>]`     the thumbstick (after the dead zone) for ms (default 1000)
 * - `canvas move <dx>,<dy>` / `canvas rot <deg>` / `canvas scale <m>`
 * - `snap`, `nofloor`, `lost` / `found`, `coach_reset`, `lang system|zh|en`, `menu`, `back`, `exit`
 * - `ui <control>`               presses a panel control by its probe name (lesson_3, palette_F, block_2, slot_0_1,
 *                                value_90, move_up, delete, clear, undo, hint, run, guess, turn_along, finish, stop, next,
 *                                again, walk, turned, start_walk, place_here, rotate45, size_1.5, menu_bring, coach_got_it ...)
 * - `dump`                       the model as one JSON line (`DUMP {...}`), also written to files/dump.json
 * - `pose`, `bounds`, `planes`, `park`, `fps on|off`
 */
internal object DebugCommands {

    fun run(context: Context, raw: String, s: String?) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val text = (s ?: rest.joinToString(" ")).trim()
        val host = TurtleProbe.host
        if (host == null) {
            TurtleLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, text)
        } catch (t: Throwable) {
            TurtleLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: TurtleProbe.Host, name: String, text: String) {
        val scene = host.scene()
        val word = text.lowercase(Locale.US)
        when (name) {
            "state" -> host.dispatch(TurtleEvent.DebugState(text))
            "lesson" -> text.toIntOrNull()?.let { host.dispatch(TurtleEvent.LessonClicked(it)) }
            "place" -> host.dispatch(TurtleEvent.DebugPlace(text.toFloatOrNull()))
            "program" -> host.dispatch(TurtleEvent.DebugProgram(text))
            "solve" -> host.dispatch(TurtleEvent.DebugSolve)
            "run" -> host.dispatch(TurtleEvent.DebugRun(word.ifEmpty { null }))
            "run_to" -> host.dispatch(TurtleEvent.DebugRunTo(text.toIntOrNull() ?: 1))
            "turn_along" -> host.dispatch(TurtleEvent.DebugTurnAlong(word.ifEmpty { "perfect" }))
            "turn" -> TurtleLog.i(scene?.turnBy(text.toFloatOrNull() ?: 0f) ?: NO_SCENE)
            "walk_perfect", "walk_noisy", "walk_cut", "walk_overshoot", "walk_off" -> host.dispatch(TurtleEvent.DebugWalk(name.removePrefix("walk_")))
            "walk_to" -> TurtleLog.i(scene?.walkTo(text.toFloatOrNull() ?: 0f) ?: NO_SCENE)
            "turn_home" -> host.dispatch(TurtleEvent.DebugWalk("perfect"))
            "stick" -> {
                val parts = text.split(',').map { it.trim().toFloatOrNull() }
                val x = parts.getOrNull(0) ?: 0f
                val y = if (parts.size >= 3) parts[1] ?: 0f else 0f
                val ms = (if (parts.size >= 3) parts[2] else parts.getOrNull(1))?.toLong() ?: 1000L
                TurtleLog.i(scene?.stick(x, y, ms) ?: NO_SCENE)
            }
            "canvas" -> {
                val kind = word.substringBefore(' ')
                val nums = word.substringAfter(' ', "").split(',', ' ').mapNotNull { it.trim().toFloatOrNull() }
                TurtleLog.i(scene?.canvas(kind, nums.getOrElse(0) { 0f }, nums.getOrElse(1) { 0f }) ?: NO_SCENE)
            }
            "snap" -> TurtleLog.i(scene?.snap() ?: NO_SCENE)
            "nofloor" -> host.dispatch(TurtleEvent.DebugNoFloor)
            "lost" -> host.dispatch(TurtleEvent.DebugTracking(true))
            "found" -> host.dispatch(TurtleEvent.DebugTracking(false))
            "coach_reset", "coach" -> host.dispatch(TurtleEvent.DebugCoachReset)
            "lang" -> host.dispatch(TurtleEvent.LanguageSelected(LanguagePref.fromTag(word)))
            "menu" -> host.dispatch(TurtleEvent.MoreClicked)
            "back" -> host.dispatch(TurtleEvent.BackClicked)
            "exit" -> host.dispatch(TurtleEvent.ExitClicked)
            "ui" -> TurtleLog.i("UI $text pressed=${host.pressControl(text)}")
            "dump" -> {
                val json = host.dumpJson()
                TurtleLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose" -> TurtleLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { TurtleLog.i(it) } ?: TurtleLog.i(NO_SCENE)
                for (panel in listOf("codePanel", "moreMenu", "snackbar", "blockedDialog", "coach")) {
                    for ((ctrl, b) in TurtleProbe.controlsOf(panel)) {
                        TurtleLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { TurtleLog.i(it) } ?: TurtleLog.i(NO_SCENE)
            "park" -> TurtleLog.i(scene?.parkPanel() ?: NO_SCENE)
            "fps" -> TurtleLog.i(scene?.setFpsLine(word == "on") ?: NO_SCENE)
            else -> TurtleLog.i("CMD unknown '$name'")
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
