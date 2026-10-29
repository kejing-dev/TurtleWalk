package io.embodit.turtlewalk.ui.turtle

import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.BlockReason
import io.embodit.turtlewalk.domain.model.LanguagePref
import io.embodit.turtlewalk.domain.model.Lesson
import io.embodit.turtlewalk.domain.model.Lessons
import io.embodit.turtlewalk.domain.model.RunSpeed
import io.embodit.turtlewalk.domain.model.Settings
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Vec3
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.usecase.CheckResult
import io.embodit.turtlewalk.domain.usecase.Cursor
import io.embodit.turtlewalk.domain.usecase.FloorPose
import io.embodit.turtlewalk.domain.usecase.PlaceCheck
import io.embodit.turtlewalk.domain.usecase.Program

/** The stages of the design package (`states[].kind == 阶段`). */
enum class Phase { STARTING, LESSONS, PLACING, CODING, RUNNING, DRAWN, TURN_ALONG, TURN_DONE, WALK_READY, WALKING, TURN_HOME, WALK_DONE, BLOCKED }

/** Snackbar kinds (design `panels.snackbar`, the overlays that speak through it). */
enum class SnackKind { SCANNING, EST_FLOOR, LOST, NOT_ON_START, OFF_PATH, SNAPPED, STEP_SIZE, CLEARED, TOO_LONG, LIMIT_BLOCKS, LIMIT_NESTING }

/** A short-lived snackbar message; [untilMs] = elapsedRealtime when it goes. */
data class Snack(val kind: SnackKind, val untilMs: Long, val number: Int = 0, val value: Float = 0f)

/** Turn along (:along) or "turn first and see" (:guess) (design `rules.turnAlong`, `rules.guessTurn`). */
enum class TurnMode(val tag: String) { ALONG("along"), GUESS("guess") }

/** What the right column shows while the turtle waits at the corners. */
data class TurnUi(
    val mode: TurnMode,
    val serial: Int,
    /** 0-based corner the turtle is at or walking to; == total when done. */
    val corner: Int = 0,
    val total: Int = 0,
    /** Your accumulated turn, signed degrees. */
    val youDeg: Float = 0f,
    /** The turtle waits at the corner for you. */
    val waiting: Boolean = false,
    /** What you turned at each corner and what the turtle turns there (signed degrees). */
    val yours: List<Float> = emptyList(),
    val turtles: List<Float> = emptyList(),
    /** "Turned it" presses; the scene confirms the current corner when this grows. */
    val confirms: Int = 0,
    /** Debug `turn_along perfect|lazy` and `turn <deg>`. */
    val synthetic: String? = null,
)

/** Walk along (optional) (design `rules.walking`, `rules.score`). */
enum class WalkVerdict(val tag: String) { GREAT("great"), OK("ok"), AGAIN("again") }

data class WalkUi(
    val serial: Int = 0,
    /** Walking the target before the drawing is right (:target). */
    val target: Boolean = false,
    /** walkReady: distance to the start, metres, and whether you face the arrow. */
    val toStartM: Float = 9f,
    val facing: Boolean = false,
    /** walking: 0-based segment under way, the segment count, your turn so far. */
    val segment: Int = 0,
    val segments: Int = 0,
    val youDeg: Float = 0f,
    val blockId: Int? = null,
    /** walkDone */
    val verdict: WalkVerdict? = null,
    val turtleDeg: Float = 0f,
    val meanDevCm: Int = 0,
    val cornerDegs: List<Float> = emptyList(),
    /** Debug walkers (design `qaHooks`). */
    val synthetic: String? = null,
)

/** What the right column shows during a run (10 Hz from the scene). */
data class RunUi(val serial: Int, val speed: RunSpeed, val blockId: Int? = null, val turned: Float = 0f, val instant: Boolean = false, val runToSegment: Int? = null)

data class TurtleUiState(
    val phase: Phase = Phase.STARTING,
    val lessonN: Int = 1,
    val program: List<Block> = emptyList(),
    val cursor: Cursor = Cursor(null, 0),
    val selected: Int? = null,
    val hintLevel: Int = 0,
    /** The program before "clear", kept for [TurtleRules.UNDO_MS]. */
    val undoProgram: List<Block>? = null,
    /** The verdict of the last drawing (drawn) and the program it was made from. */
    val result: CheckResult? = null,
    val resultProgram: String? = null,
    /** After an edit, the last ink stays at 30 % until the next run. */
    val inkDimmed: Boolean = false,
    /** Ink of the last run is on the floor (kept through coding until a new run or a lesson change). */
    val inkShown: Boolean = false,
    val run: RunUi? = null,
    val runSpeed: RunSpeed = RunSpeed.SLOW,
    val turn: TurnUi? = null,
    val walk: WalkUi? = null,
    val settings: Settings = Settings(),
    /** The placed field; null until "place here". */
    val field: FloorPose? = null,
    /** Field edge length, metres (1 .. 2); one step = field / 2. */
    val fieldM: Float = 2f,
    /** Head height when the field was placed (the code panel's height follows it). */
    val headYAtPlace: Float = 1.6f,
    /** "Bring the panel to me" presses (taste D21); the scene places the panel again when this grows. */
    val panelRecalls: Int = 0,
    /** Placing: the controller ray (or a pinch) has put a ghost field on the floor; "place here" needs it. */
    val ghost: Boolean = false,
    val floorY: Float = 0f,
    val hasFloor: Boolean = false,
    /** No floor plane after 5 s: an estimated floor (head - 1.55 m) stands in (taste D7). */
    val estFloor: Boolean = false,
    val floorNudge: Float = 0f,
    val planeCount: Int = 0,
    val placeCheck: PlaceCheck = PlaceCheck.CLEAR,
    val snapped: Boolean = false,
    /** The controller drags / turns / scales the placed field (+canvasMove). */
    val canvasMoving: Boolean = false,
    val lost: Boolean = false,
    val notOnStart: Boolean = false,
    val offPath: Boolean = false,
    val menuOpen: Boolean = false,
    /** First-run coachmark showing: 1 canvas, 2 blocks, 3 turn along; null = none. */
    val coach: Int? = null,
    val blocked: BlockReason? = null,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val snack: Snack? = null,
) {
    val lesson: Lesson
        get() = Lessons.byNumber(lessonN)

    /** One step in metres. */
    val stepM: Float
        get() = fieldM / TurtleRules.FIELD_STEPS

    val blockCount: Int
        get() = Program.count(program)

    /** The last run is a match and the program has not changed since: "turn along" instead of "turn first". */
    val drawnRight: Boolean
        get() = result?.verdict == Verdict.MATCH && !result.outOfField && resultProgram == Program.encode(program)

    val scanning: Boolean
        get() = !hasFloor && (phase == Phase.LESSONS || phase == Phase.PLACING)

    val canPlace: Boolean
        get() = hasFloor && ghost && placeCheck.canPlace

    /** A block of [kind] may not be added at the cursor (16 top blocks / 2 nesting levels). */
    fun refusal(kind: BlockKind): Program.Refusal? = Program.refusal(program, cursor, kind)

    /** The one snackbar (design `informationArchitecture.densityCap`: at most one). */
    fun snackNow(nowMs: Long): Snack? {
        val fixed =
            when {
                lost && phase != Phase.LESSONS && phase != Phase.BLOCKED -> SnackKind.LOST
                scanning -> SnackKind.SCANNING
                notOnStart && phase == Phase.WALK_READY -> SnackKind.NOT_ON_START
                offPath && phase == Phase.WALKING -> SnackKind.OFF_PATH
                canvasMoving -> SnackKind.STEP_SIZE
                else -> null
            }
        if (fixed != null) return Snack(fixed, Long.MAX_VALUE, value = stepM)
        val s = snack
        if (s != null && s.untilMs > nowMs) return s
        if (estFloor && (phase == Phase.PLACING || phase == Phase.LESSONS)) return Snack(SnackKind.EST_FLOOR, Long.MAX_VALUE)
        return null
    }

    /** QA name of this state: phase[+overlay...][:mode]@Ln (design `stateNaming`). */
    val qaName: String
        get() {
            val sb =
                StringBuilder(
                    when (phase) {
                        Phase.TURN_ALONG -> "turnAlong"
                        Phase.TURN_DONE -> "turnDone"
                        Phase.WALK_READY -> "walkReady"
                        Phase.TURN_HOME -> "turnHome"
                        Phase.WALK_DONE -> "walkDone"
                        else -> phase.name.lowercase()
                    }
                )
            if (scanning) sb.append("+scanning")
            if (estFloor && (phase == Phase.PLACING || phase == Phase.CODING)) sb.append("+estFloor")
            if (phase == Phase.PLACING && (placeCheck.cramped != null || placeCheck.frontTooCloseCm != null || placeCheck.wallCrosses)) sb.append("+cramped")
            if (phase == Phase.PLACING && snapped) sb.append("+snapped")
            if (selected != null && (phase == Phase.CODING || phase == Phase.DRAWN)) sb.append("+blockSelected")
            if (canvasMoving) sb.append("+canvasMove")
            if (hintLevel > 0 && (phase == Phase.CODING || phase == Phase.DRAWN)) sb.append("+hint")
            if (undoProgram != null && phase == Phase.CODING) sb.append("+undo")
            if (phase == Phase.DRAWN && result?.outOfField == true) sb.append("+outOfField")
            if (notOnStart && phase == Phase.WALK_READY) sb.append("+notOnStart")
            if (offPath && phase == Phase.WALKING) sb.append("+offPath")
            if (lost) sb.append("+lost")
            if (menuOpen) sb.append("+menu")
            if (coach != null) sb.append("+coach")
            when (phase) {
                Phase.DRAWN -> result?.let { sb.append(":" + it.verdict.tag) }
                Phase.TURN_ALONG, Phase.TURN_DONE -> turn?.let { if (it.mode == TurnMode.GUESS) sb.append(":guess") else if (phase == Phase.TURN_DONE) sb.append(":along") }
                Phase.WALK_READY -> if (walk?.target == true) sb.append(":target")
                Phase.WALK_DONE -> walk?.let { if (it.target) sb.append(":target") else it.verdict?.let { v -> sb.append(":" + v.tag) } }
                else -> Unit
            }
            if (phase != Phase.STARTING && phase != Phase.LESSONS && phase != Phase.BLOCKED) sb.append("@L$lessonN")
            return sb.toString()
        }
}

/** Everything that can happen: panel controls, scene callbacks, lifecycle, debug hooks. */
sealed class TurtleEvent {
    // scene
    data object SceneReady : TurtleEvent()

    data class PlanesChanged(val count: Int, val hasFloor: Boolean, val floorY: Float) : TurtleEvent()

    data class TrackingChanged(val lost: Boolean) : TurtleEvent()

    data class Blocked(val reason: BlockReason?) : TurtleEvent()

    /** Ten times a second while placing: the ghost field and its check. */
    data class FieldFollow(val pose: FloorPose, val sizeM: Float, val headY: Float, val check: PlaceCheck, val snapped: Boolean) : TurtleEvent()

    /** The controller moved the placed field (10 Hz while it moves, once when it stops). */
    data class FieldMoved(val pose: FloorPose, val sizeM: Float, val moving: Boolean) : TurtleEvent()

    /** Ten times a second: head position (Stage) and its level heading (yaw, radians). */
    data class HeadSample(val position: Vec3, val yaw: Float) : TurtleEvent()

    /** Ten times a second during a run. */
    data class RunProgress(val serial: Int, val blockId: Int?, val turned: Float) : TurtleEvent()

    data class RunFinished(val serial: Int) : TurtleEvent()

    data class TurnProgress(val serial: Int, val corner: Int, val youDeg: Float, val waiting: Boolean, val blockId: Int?) : TurtleEvent()

    data class TurnCornerDone(val serial: Int, val corner: Int, val yours: Float) : TurtleEvent()

    data class TurnFinished(val serial: Int) : TurtleEvent()

    data class WalkReadyStatus(val toStartM: Float, val facing: Boolean) : TurtleEvent()

    data class WalkProgress(val serial: Int, val segment: Int, val youDeg: Float, val offPath: Boolean, val blockId: Int?) : TurtleEvent()

    data class WalkAtEnd(val serial: Int, val closedWithTurn: Boolean) : TurtleEvent()

    data class WalkFinished(val serial: Int, val youDeg: Float, val meanDevM: Float, val cornerDegs: List<Float>) : TurtleEvent()

    /** A system-pointer pinch on the floor while placing: the field goes there and is placed. */
    data class FloorPinched(val at: Vec3) : TurtleEvent()

    // lessons / placing
    data class LessonClicked(val n: Int) : TurtleEvent()

    data class FieldSizeSelected(val sizeM: Float) : TurtleEvent()

    data object Rotate45Clicked : TurtleEvent()

    data object PlaceHereClicked : TurtleEvent()

    data object BackClicked : TurtleEvent()

    data object MoreClicked : TurtleEvent()

    data object MenuDismissed : TurtleEvent()

    data class LanguageSelected(val language: LanguagePref) : TurtleEvent()

    data object RecallPanelClicked : TurtleEvent()

    data object ReplaceFieldClicked : TurtleEvent()

    data object LessonListClicked : TurtleEvent()

    data object ExitClicked : TurtleEvent()

    data class FloorNudged(val steps: Int) : TurtleEvent()

    // coding
    data class PaletteClicked(val kind: BlockKind) : TurtleEvent()

    data class SlotClicked(val cursor: Cursor) : TurtleEvent()

    data class BlockClicked(val id: Int) : TurtleEvent()

    data class ValueSelected(val value: Float) : TurtleEvent()

    data class MoveSelected(val up: Boolean) : TurtleEvent()

    data object DeleteSelected : TurtleEvent()

    data object ClearClicked : TurtleEvent()

    data object UndoClicked : TurtleEvent()

    data object HintClicked : TurtleEvent()

    data object RunClicked : TurtleEvent()

    data class SpeedSelected(val speed: RunSpeed) : TurtleEvent()

    data object FinishRunClicked : TurtleEvent()

    data object StopClicked : TurtleEvent()

    data object NextLessonClicked : TurtleEvent()

    // turn along / guess
    data object TurnAlongClicked : TurtleEvent()

    data object GuessTurnClicked : TurtleEvent()

    data object TurnedClicked : TurtleEvent()

    data object SkipToEndClicked : TurtleEvent()

    // walk (optional)
    data object WalkClicked : TurtleEvent()

    data object StartWalkClicked : TurtleEvent()

    data object BackToBlocksClicked : TurtleEvent()

    data object CoachDismissed : TurtleEvent()

    data object RetryClicked : TurtleEvent()

    // lifecycle
    data object WentBackground : TurtleEvent()

    data object CameForeground : TurtleEvent()

    // debug
    data class DebugState(val name: String) : TurtleEvent()

    data class DebugProgram(val text: String) : TurtleEvent()

    data object DebugSolve : TurtleEvent()

    data class DebugPlace(val sizeM: Float?) : TurtleEvent()

    data class DebugRun(val speed: String?) : TurtleEvent()

    data class DebugRunTo(val segment: Int) : TurtleEvent()

    data class DebugTurnAlong(val how: String) : TurtleEvent()

    data class DebugWalk(val how: String) : TurtleEvent()

    data class DebugTracking(val lost: Boolean?) : TurtleEvent()

    data object DebugCoachReset : TurtleEvent()

    data object DebugNoFloor : TurtleEvent()
}

sealed class TurtleUiEffect {
    data object ExitApp : TurtleUiEffect()

    /** Restart the trackers after the "unavailable" dialog's retry. */
    data object RetryTracking : TurtleUiEffect()
}
