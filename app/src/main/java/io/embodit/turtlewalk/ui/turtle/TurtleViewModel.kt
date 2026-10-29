package io.embodit.turtlewalk.ui.turtle

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.turtlewalk.data.TurtleRepository
import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.BlockReason
import io.embodit.turtlewalk.domain.model.Lessons
import io.embodit.turtlewalk.domain.model.RunSpeed
import io.embodit.turtlewalk.domain.model.Settings
import io.embodit.turtlewalk.domain.model.SurfaceKind
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Vec3
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.usecase.CheckResult
import io.embodit.turtlewalk.domain.usecase.CheckUseCase
import io.embodit.turtlewalk.domain.usecase.Cramped
import io.embodit.turtlewalk.domain.usecase.Cursor
import io.embodit.turtlewalk.domain.usecase.FloorPose
import io.embodit.turtlewalk.domain.usecase.Interpreter
import io.embodit.turtlewalk.domain.usecase.PlaceCheck
import io.embodit.turtlewalk.domain.usecase.Program
import io.embodit.turtlewalk.domain.usecase.Side
import io.embodit.turtlewalk.platform.TurtleGraph
import io.embodit.turtlewalk.platform.TurtleLog
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states[]`, `rules`). Events come from the panels, the
 * scene (planes, tracking, head samples, run / turn / walk progress) and the debug receiver; the state
 * is rendered by the panels and by `TurtleScene.render`. Per-frame work never passes through here.
 */
class TurtleViewModel(private val repository: TurtleRepository) : ViewModel() {

    private val _state = MutableStateFlow(TurtleUiState())
    val state: StateFlow<TurtleUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TurtleUiEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<TurtleUiEffect> = _effects.asSharedFlow()

    private val checker = CheckUseCase()
    private var sceneReady = false
    private var settingsLoaded = false
    private var latestPlacing: FloorPose? = null
    private var latestHead: Vec3? = null
    private var latestHeadYaw = 0f
    private var serial = 0
    private var realHasFloor = false
    private var sensedFloorY = 0f
    private var estFloor = false
    private var undoJob: Job? = null
    private var snackJob: Job? = null

    /** Debug: a forced overlay is not cleared by live sensing until the next `state`. */
    private var debugHold = false
    private var lastStateName = ""

    init {
        viewModelScope.launch {
            val s = repository.load()
            settingsLoaded = true
            update { it.copy(settings = s, language = s.language, fieldM = s.fieldM, lessonN = s.lastLesson) }
            maybeStart()
        }
    }

    private inline fun update(f: (TurtleUiState) -> TurtleUiState) {
        val before = _state.value
        val after = f(before)
        if (after !== before) {
            _state.value = after
            val name = after.qaName
            if (name != lastStateName) {
                lastStateName = name
                TurtleLog.i("STATE $name phase=${after.phase} lesson=${after.lessonN} program='${Program.encode(after.program)}' step=${TurtleLog.f(after.stepM)}")
            }
        }
    }

    private fun maybeStart() {
        if (sceneReady && settingsLoaded && _state.value.phase == Phase.STARTING) {
            update { it.copy(phase = Phase.LESSONS) }
            armEstFloor()
        }
    }

    /** Taste D7: no floor plane 5 s after the start -> an estimated floor (head - 1.55 m); a real floor replaces it. */
    private fun armEstFloor() {
        viewModelScope.launch {
            delay(TurtleRules.EST_FLOOR_AFTER_MS)
            if (realHasFloor || estFloor) return@launch
            useEstimatedFloor()
        }
    }

    private fun useEstimatedFloor() {
        estFloor = true
        val headY = latestHead?.y ?: 1.6f
        sensedFloorY = headY - TurtleRules.EYE_HEIGHT_M
        TurtleLog.i("FLOOR estimated y=${TurtleLog.f(sensedFloorY)} headY=${TurtleLog.f(headY)}")
        applyFloor()
    }

    private fun applyFloor() {
        update {
            it.copy(
                floorY = sensedFloorY + it.floorNudge,
                hasFloor = if (debugHold) it.hasFloor else realHasFloor || estFloor,
                estFloor = estFloor && !realHasFloor,
                field = it.field?.let { f -> f.copy(center = Vec3(f.center.x, sensedFloorY + it.floorNudge, f.center.z)) },
            )
        }
    }

    fun onEvent(event: TurtleEvent) {
        if (event !is TurtleEvent.HeadSample && event !is TurtleEvent.FieldFollow && event !is TurtleEvent.RunProgress && event !is TurtleEvent.TurnProgress && event !is TurtleEvent.WalkProgress && event !is TurtleEvent.WalkReadyStatus && event !is TurtleEvent.FieldMoved) {
            TurtleLog.i("EVENT ${event::class.simpleName}")
        }
        val s = _state.value
        when (event) {
            TurtleEvent.SceneReady -> {
                sceneReady = true
                maybeStart()
            }
            is TurtleEvent.PlanesChanged -> {
                realHasFloor = event.hasFloor
                if (event.hasFloor) {
                    if (estFloor) TurtleLog.i("FLOOR real replaces estimate y=${TurtleLog.f(event.floorY)}")
                    estFloor = false
                    sensedFloorY = event.floorY
                }
                update { it.copy(planeCount = event.count) }
                applyFloor()
            }
            is TurtleEvent.TrackingChanged -> setLost(event.lost)
            is TurtleEvent.Blocked -> {
                if (event.reason == BlockReason.WITHOUT_PERMISSION || event.reason == BlockReason.DEVICE_NOT_SUPPORTED) {
                    update { it.copy(phase = Phase.BLOCKED, blocked = event.reason, run = null, turn = null, walk = null, menuOpen = false) }
                }
            }
            is TurtleEvent.FieldFollow -> {
                latestPlacing = event.pose
                latestHead = latestHead?.let { Vec3(it.x, event.headY, it.z) }
                if (s.phase == Phase.PLACING && !s.ghost) update { it.copy(ghost = true) }
                if (!debugHold && s.phase == Phase.PLACING && (event.check != s.placeCheck || event.snapped != s.snapped || event.sizeM != s.fieldM)) {
                    if (event.snapped && !s.snapped) snack(SnackKind.SNAPPED, 800L)
                    update { it.copy(placeCheck = event.check, snapped = event.snapped, fieldM = event.sizeM) }
                }
            }
            is TurtleEvent.FieldMoved -> {
                if (s.field == null) return
                if (!event.moving) TurtleLog.i("FIELD moved center=${TurtleLog.pos(event.pose.center)} yaw=${deg(event.pose.yaw)} size=${TurtleLog.f(event.sizeM)}")
                update { it.copy(field = event.pose, fieldM = event.sizeM, canvasMoving = if (debugHold) it.canvasMoving else event.moving) }
                if (!event.moving) saveSettings { it.copy(fieldM = event.sizeM) }
            }
            is TurtleEvent.HeadSample -> {
                latestHead = event.position
                latestHeadYaw = event.yaw
            }
            is TurtleEvent.RunProgress -> {
                val run = s.run ?: return
                if (run.serial != event.serial || s.phase != Phase.RUNNING) return
                if (run.blockId != event.blockId || run.turned != event.turned) update { it.copy(run = run.copy(blockId = event.blockId, turned = event.turned)) }
            }
            is TurtleEvent.RunFinished -> if (s.phase == Phase.RUNNING && s.run?.serial == event.serial) finishRun()
            is TurtleEvent.TurnProgress -> {
                val t = s.turn ?: return
                if (t.serial != event.serial || s.phase != Phase.TURN_ALONG) return
                update { it.copy(turn = t.copy(corner = event.corner, youDeg = event.youDeg, waiting = event.waiting), run = it.run?.copy(blockId = event.blockId)) }
            }
            is TurtleEvent.TurnCornerDone -> {
                val t = s.turn ?: return
                if (t.serial != event.serial) return
                TurtleLog.i("TURN corner=${event.corner} yours=${TurtleLog.f(event.yours, 1)} turtle=${t.turtles.getOrNull(event.corner)}")
                update { it.copy(turn = t.copy(yours = t.yours + event.yours, corner = event.corner + 1, waiting = false)) }
            }
            is TurtleEvent.TurnFinished -> if (s.phase == Phase.TURN_ALONG && s.turn?.serial == event.serial) finishTurn()
            is TurtleEvent.WalkReadyStatus -> {
                val w = s.walk ?: return
                if (s.phase != Phase.WALK_READY) return
                val toStart = Math.round(event.toStartM * 20f) / 20f
                if (w.toStartM != toStart || w.facing != event.facing) update { it.copy(walk = w.copy(toStartM = toStart, facing = event.facing)) }
                if (s.notOnStart && onStart(event.toStartM, event.facing) && !debugHold) update { it.copy(notOnStart = false) }
            }
            is TurtleEvent.WalkProgress -> {
                val w = s.walk ?: return
                if (w.serial != event.serial || (s.phase != Phase.WALKING && s.phase != Phase.TURN_HOME)) return
                update { it.copy(walk = w.copy(segment = event.segment, youDeg = event.youDeg, blockId = event.blockId), offPath = if (debugHold) it.offPath else event.offPath) }
            }
            is TurtleEvent.WalkAtEnd -> if (s.phase == Phase.WALKING && s.walk?.serial == event.serial && event.closedWithTurn) update { it.copy(phase = Phase.TURN_HOME, offPath = false) }
            is TurtleEvent.WalkFinished -> if ((s.phase == Phase.WALKING || s.phase == Phase.TURN_HOME) && s.walk?.serial == event.serial) finishWalk(event)
            is TurtleEvent.FloorPinched -> if (s.phase == Phase.PLACING) {
                // taste D1 / D10: put down where you pinched, its front facing you
                val at = Vec3(event.at.x, s.floorY, event.at.z)
                latestPlacing = FloorPose(at, latestHead?.let { h -> FloorPose.yawToward(at, h, latestHeadYaw) } ?: latestHeadYaw)
                update { it.copy(ghost = true) }
                TurtleLog.i("FIELD pinched at=${TurtleLog.pos(event.at)}")
                placeHere()
            }

            is TurtleEvent.LessonClicked -> openLesson(event.n)
            is TurtleEvent.FieldSizeSelected -> if (s.phase == Phase.PLACING) {
                update { it.copy(fieldM = event.sizeM) }
                saveSettings { it.copy(fieldM = event.sizeM) }
            }
            TurtleEvent.Rotate45Clicked -> if (s.phase == Phase.PLACING) TurtleGraph.rotate45Requests++
            TurtleEvent.PlaceHereClicked -> placeHere()
            TurtleEvent.BackClicked -> back()
            TurtleEvent.MoreClicked -> update { it.copy(menuOpen = !it.menuOpen) }
            TurtleEvent.MenuDismissed -> update { it.copy(menuOpen = false) }
            is TurtleEvent.LanguageSelected -> {
                update { it.copy(language = event.language) }
                saveSettings { it.copy(language = event.language) }
            }
            TurtleEvent.RecallPanelClicked -> {
                TurtleLog.i("PANEL recall requested")
                update { it.copy(panelRecalls = it.panelRecalls + 1, menuOpen = false) }
            }
            TurtleEvent.ReplaceFieldClicked -> {
                if (s.phase in setOf(Phase.LESSONS, Phase.CODING, Phase.DRAWN, Phase.WALK_DONE, Phase.TURN_DONE)) {
                    update { it.copy(menuOpen = false, field = null, ghost = false, phase = Phase.PLACING, run = null, result = null, turn = null, walk = null, inkShown = false, selected = null) }
                }
            }
            TurtleEvent.LessonListClicked -> toLessons()
            TurtleEvent.ExitClicked -> _effects.tryEmit(TurtleUiEffect.ExitApp)
            is TurtleEvent.FloorNudged -> {
                val max = TurtleRules.FLOOR_NUDGE_MAX_M
                update { it.copy(floorNudge = (it.floorNudge + event.steps * TurtleRules.FLOOR_NUDGE_M).coerceIn(-max, max)) }
                applyFloor()
                TurtleLog.i("FLOOR nudge=${TurtleLog.f(_state.value.floorNudge)} y=${TurtleLog.f(_state.value.floorY)}")
            }

            is TurtleEvent.PaletteClicked -> addBlock(event.kind)
            is TurtleEvent.SlotClicked -> if (editable()) update { it.copy(cursor = event.cursor, selected = null) }
            is TurtleEvent.BlockClicked -> if (editable()) {
                if (s.selected == event.id) update { it.copy(selected = null) }
                else {
                    val at = Program.locate(s.program, event.id) ?: return
                    update { it.copy(selected = event.id, cursor = Cursor(at.parent, at.index + 1)) }
                }
            }
            is TurtleEvent.ValueSelected -> {
                val id = s.selected ?: return
                if (!editable()) return
                edit(Program.setValue(s.program, id, event.value), keepSelection = true)
            }
            is TurtleEvent.MoveSelected -> {
                val id = s.selected ?: return
                if (!editable()) return
                val moved = Program.move(s.program, id, event.up)
                val at = Program.locate(moved, id)
                edit(moved, keepSelection = true, cursor = at?.let { Cursor(it.parent, it.index + 1) })
            }
            TurtleEvent.DeleteSelected -> {
                val id = s.selected ?: return
                if (!editable()) return
                val (p, at) = Program.delete(s.program, id)
                edit(p, keepSelection = false, cursor = at)
            }
            TurtleEvent.ClearClicked -> if (editable() && s.program.isNotEmpty()) {
                val before = s.program
                edit(emptyList(), keepSelection = false, cursor = Cursor(null, 0))
                update { it.copy(undoProgram = before) }
                snack(SnackKind.CLEARED, TurtleRules.UNDO_MS)
                undoJob?.cancel()
                undoJob = viewModelScope.launch {
                    delay(TurtleRules.UNDO_MS)
                    update { it.copy(undoProgram = null) }
                }
            }
            TurtleEvent.UndoClicked -> {
                val p = s.undoProgram ?: return
                undoJob?.cancel()
                edit(p, keepSelection = false, cursor = Cursor(null, p.size))
                update { it.copy(undoProgram = null, snack = null) }
            }
            TurtleEvent.HintClicked -> if (s.phase == Phase.CODING || s.phase == Phase.DRAWN) update { it.copy(hintLevel = (it.hintLevel + 1).coerceAtMost(2)) }
            TurtleEvent.RunClicked -> startRun(null)
            is TurtleEvent.SpeedSelected -> update { it.copy(runSpeed = event.speed, run = it.run?.copy(speed = event.speed)) }
            TurtleEvent.FinishRunClicked -> if (s.phase == Phase.RUNNING) update { it.copy(run = it.run?.copy(instant = true, runToSegment = null)) }
            TurtleEvent.StopClicked -> stop()
            TurtleEvent.NextLessonClicked -> if (s.lessonN >= Lessons.all.size) toLessons() else openLesson(s.lessonN + 1)

            TurtleEvent.TurnAlongClicked -> startTurn(TurnMode.ALONG, null)
            TurtleEvent.GuessTurnClicked -> startTurn(TurnMode.GUESS, null)
            TurtleEvent.TurnedClicked -> s.turn?.let { t -> if (s.phase == Phase.TURN_ALONG) update { it.copy(turn = t.copy(confirms = t.confirms + 1)) } }
            TurtleEvent.SkipToEndClicked -> if (s.phase == Phase.TURN_ALONG) finishTurn()

            TurtleEvent.WalkClicked -> startWalkReady()
            TurtleEvent.StartWalkClicked -> startWalk(force = false)
            TurtleEvent.BackToBlocksClicked -> update { it.copy(phase = Phase.CODING, turn = null, walk = null, run = null, notOnStart = false, offPath = false) }
            TurtleEvent.CoachDismissed -> {
                val c = s.coach ?: return
                saveSettings { it.copy(coachSeen = it.coachSeen + c) }
                TurtleLog.i("COACH dismissed $c")
                val next = if (c == 1 && s.phase == Phase.CODING && 2 !in _state.value.settings.coachSeen) 2 else null
                update { it.copy(coach = next) }
            }
            TurtleEvent.RetryClicked -> {
                update { it.copy(phase = Phase.STARTING, blocked = null) }
                sceneReady = false
                _effects.tryEmit(TurtleUiEffect.RetryTracking)
            }

            TurtleEvent.WentBackground -> background()
            TurtleEvent.CameForeground -> Unit

            is TurtleEvent.DebugState -> debugState(event.name)
            is TurtleEvent.DebugProgram -> {
                val p = Program.parse(event.text)
                if (p == null) TurtleLog.i("CMD program parse error '${event.text}'")
                else {
                    if (s.field == null) debugPlace(null)
                    if (_state.value.phase !in setOf(Phase.CODING, Phase.DRAWN)) update { it.copy(phase = Phase.CODING, run = null, turn = null, walk = null) }
                    edit(p, keepSelection = false, cursor = Cursor(null, p.size))
                }
            }
            TurtleEvent.DebugSolve -> onEvent(TurtleEvent.DebugProgram(Program.encode(s.lesson.solution)))
            is TurtleEvent.DebugPlace -> debugPlace(event.sizeM)
            is TurtleEvent.DebugRun -> {
                if (s.field == null) debugPlace(null)
                if (_state.value.phase !in setOf(Phase.CODING, Phase.DRAWN)) update { it.copy(phase = Phase.CODING) }
                val speed = if (event.speed == "fast") RunSpeed.FAST else RunSpeed.SLOW
                update { it.copy(runSpeed = speed) }
                startRun(null)
                if (event.speed == "instant") update { it.copy(run = it.run?.copy(instant = true)) }
            }
            is TurtleEvent.DebugRunTo -> {
                if (s.field == null) debugPlace(null)
                if (_state.value.phase !in setOf(Phase.CODING, Phase.DRAWN)) update { it.copy(phase = Phase.CODING) }
                startRun(event.segment)
            }
            is TurtleEvent.DebugTurnAlong -> {
                if (s.field == null) debugPlace(null)
                if (event.how.startsWith("guess")) startTurn(TurnMode.GUESS, event.how)
                else {
                    makeMatch()
                    startTurn(TurnMode.ALONG, event.how)
                }
            }
            is TurtleEvent.DebugWalk -> {
                if (s.field == null) debugPlace(null)
                makeMatch()
                startWalkReady()
                update { it.copy(walk = it.walk?.copy(synthetic = event.how)) }
                startWalk(force = true)
            }
            is TurtleEvent.DebugTracking -> {
                TurtleGraph.head.forceLost = event.lost == true
                if (event.lost != null) setLost(event.lost)
            }
            TurtleEvent.DebugCoachReset -> {
                saveSettings { it.copy(coachSeen = emptySet()) }
                TurtleLog.i("COACH reset")
            }
            TurtleEvent.DebugNoFloor -> {
                realHasFloor = false
                debugHold = true
                useEstimatedFloor()
                update { it.copy(hasFloor = true, estFloor = true) }
            }
        }
    }

    // =========================================================================================
    // lessons, placing
    // =========================================================================================

    private fun openLesson(n: Int) {
        val lesson = Lessons.byNumber(n)
        val record = _state.value.settings.record(n)
        val program = Program.parse(record.program) ?: emptyList()
        update {
            val next = if (it.field != null) Phase.CODING else Phase.PLACING
            it.copy(
                lessonN = lesson.n,
                program = program,
                cursor = Cursor(null, program.size),
                selected = null,
                hintLevel = 0,
                undoProgram = null,
                result = null,
                resultProgram = null,
                inkShown = false,
                inkDimmed = false,
                run = null,
                turn = null,
                walk = null,
                phase = next,
                menuOpen = false,
                coach = if (next == Phase.CODING && 2 !in it.settings.coachSeen) 2 else null,
                notOnStart = false,
                offPath = false,
            )
        }
        saveSettings { it.copy(lastLesson = lesson.n) }
    }

    private fun toLessons() {
        val s = _state.value
        if (s.phase == Phase.BLOCKED || s.phase == Phase.STARTING) return
        update { it.copy(phase = Phase.LESSONS, run = null, turn = null, walk = null, menuOpen = false, coach = null, selected = null, hintLevel = 0, notOnStart = false, offPath = false, canvasMoving = false) }
    }

    private fun back() {
        when (_state.value.phase) {
            Phase.PLACING, Phase.CODING, Phase.DRAWN, Phase.WALK_READY, Phase.WALK_DONE, Phase.TURN_DONE -> toLessons()
            else -> Unit
        }
    }

    private fun placeHere() {
        val s = _state.value
        if (s.phase != Phase.PLACING || !s.canPlace) return
        val pose = latestPlacing ?: return
        val headY = latestHead?.y ?: 1.6f
        TurtleLog.i("FIELD placed center=${TurtleLog.pos(pose.center)} yaw=${deg(pose.yaw)} size=${TurtleLog.f(s.fieldM)} headY=${TurtleLog.f(headY)}")
        update {
            val coach = if (1 !in it.settings.coachSeen) 1 else if (2 !in it.settings.coachSeen) 2 else null
            // the code panel stays where you put it (taste D21); only the field is placed
            it.copy(field = pose, headYAtPlace = headY, phase = Phase.CODING, coach = coach, snapped = false, ghost = false)
        }
        saveSettings { it.copy(fieldM = s.fieldM) }
    }

    /**
     * Debug only (`place`, `state ...`): a field 0.4 m ahead of the feet, its front facing the head (taste D10).
     * Product placement never uses the head direction: the ghost follows the controller ray or a pinch (taste D1).
     */
    private fun debugFrontOf(head: Vec3, yaw: Float, sizeM: Float, floorY: Float): FloorPose {
        val f = FloorPose(Vec3(head.x, floorY, head.z), yaw)
        val c = f.toStage(0f, TurtleRules.FIELD_BACK_GAP_M + sizeM / 2f)
        val center = Vec3(c.x, floorY, c.z)
        return FloorPose(center, FloorPose.yawToward(center, head, yaw))
    }

    // =========================================================================================
    // editing
    // =========================================================================================

    private fun editable(): Boolean = _state.value.phase == Phase.CODING || _state.value.phase == Phase.DRAWN

    private fun addBlock(kind: BlockKind) {
        val s = _state.value
        if (!editable()) return
        if (kind !in s.lesson.blocks) return
        when (s.refusal(kind)) {
            Program.Refusal.TOO_MANY_BLOCKS -> {
                snack(SnackKind.LIMIT_BLOCKS, TurtleRules.SNACK_MS)
                return
            }
            Program.Refusal.TOO_DEEP -> {
                snack(SnackKind.LIMIT_NESTING, TurtleRules.SNACK_MS)
                return
            }
            null -> Unit
        }
        val (p, id, cursor) = Program.insert(s.program, s.cursor, kind) ?: return
        edit(p, keepSelection = false, cursor = cursor)
        update { it.copy(selected = id) }
    }

    /** Any change of the program: back to coding, the result goes, the old ink fades to 30 %; the program is saved. */
    private fun edit(program: List<Block>, keepSelection: Boolean, cursor: Cursor? = null) {
        update {
            it.copy(
                program = program,
                cursor = cursor ?: it.cursor,
                selected = if (keepSelection) it.selected else null,
                phase = Phase.CODING,
                result = null,
                hintLevel = if (it.program != program) 0 else it.hintLevel,
                inkDimmed = it.inkShown,
                run = null,
            )
        }
        val n = _state.value.lessonN
        val text = Program.encode(program)
        saveSettings { it.copy(records = it.records + (n to it.record(n).copy(program = text))) }
    }

    // =========================================================================================
    // run
    // =========================================================================================

    private fun startRun(runTo: Int?) {
        val s = _state.value
        if (s.phase != Phase.CODING && s.phase != Phase.DRAWN) return
        if (s.program.isEmpty()) return
        val exec = Interpreter.run(s.program, s.lesson.start)
        if (exec.tooLong) {
            snack(SnackKind.TOO_LONG, TurtleRules.SNACK_MS, number = exec.segmentCount)
            TurtleLog.i("RUN refused too long segments=${exec.segmentCount}")
            return
        }
        TurtleLog.i("RUN start lesson=${s.lessonN} program='${Program.encode(s.program)}' speed=${s.runSpeed.tag} runTo=$runTo")
        update {
            it.copy(
                phase = Phase.RUNNING,
                run = RunUi(++serial, it.runSpeed, runToSegment = runTo),
                result = null,
                hintLevel = 0,
                selected = null,
                inkShown = true,
                inkDimmed = false,
                menuOpen = false,
                coach = null,
                turn = null,
                walk = null,
            )
        }
    }

    private fun finishRun() {
        val s = _state.value
        val result = checker.check(s.lesson, s.program)
        TurtleLog.i("RUN finish verdict=${result.verdict.tag} outOfField=${result.outOfField} turned=${TurtleLog.f(result.turnTotal, 0)} gap=${TurtleLog.f(result.gapSteps)} spin=${TurtleLog.f(result.spinDeg, 0)} blocks=${result.blockCount}")
        val right = result.verdict == Verdict.MATCH && !result.outOfField
        update {
            it.copy(
                phase = Phase.DRAWN,
                result = result,
                resultProgram = Program.encode(it.program),
                run = it.run?.copy(blockId = null, turned = result.turnTotal),
                coach = if (right && 3 !in it.settings.coachSeen) 3 else null,
            )
        }
        if (right) {
            val n = s.lessonN
            saveSettings { it.copy(records = it.records + (n to it.record(n).copy(drawnRight = true))) }
        }
    }

    private fun stop() {
        val s = _state.value
        when (s.phase) {
            Phase.RUNNING -> update { it.copy(phase = Phase.CODING, run = null, inkDimmed = true) }
            Phase.TURN_ALONG -> update { it.copy(phase = if (it.turn?.mode == TurnMode.GUESS) Phase.CODING else Phase.DRAWN, turn = null) }
            Phase.WALKING, Phase.TURN_HOME -> update { it.copy(phase = Phase.WALK_READY, walk = it.walk?.copy(segment = 0, youDeg = 0f, synthetic = null), offPath = false) }
            Phase.WALK_READY -> update { it.copy(phase = if (it.result != null) Phase.DRAWN else Phase.CODING, walk = null, notOnStart = false) }
            else -> Unit
        }
    }

    // =========================================================================================
    // turn along / turn first and see
    // =========================================================================================

    private fun startTurn(mode: TurnMode, synthetic: String?) {
        val s = _state.value
        val program = if (mode == TurnMode.GUESS) s.lesson.solution else s.program
        if (mode == TurnMode.ALONG && !s.drawnRight) return
        if (mode == TurnMode.GUESS && s.phase != Phase.CODING && s.phase != Phase.DRAWN && s.phase != Phase.TURN_DONE) return
        val corners = Interpreter.run(program, s.lesson.start).corners
        TurtleLog.i("TURN start mode=${mode.tag} corners=${corners.size} synthetic=$synthetic")
        update {
            it.copy(
                phase = Phase.TURN_ALONG,
                turn = TurnUi(mode, ++serial, corner = 0, total = corners.size, turtles = corners.map { c -> c.by }, synthetic = synthetic),
                run = RunUi(serial, RunSpeed.SLOW),
                menuOpen = false,
                coach = null,
                selected = null,
                hintLevel = 0,
                walk = null,
            )
        }
        if (corners.isEmpty()) finishTurn()
    }

    private fun finishTurn() {
        val s = _state.value
        val t = s.turn ?: return
        TurtleLog.i("TURN finish mode=${t.mode.tag} yours=${t.yours.map { Math.round(it) }} turtle=${t.turtles.map { Math.round(it) }}")
        update { it.copy(phase = Phase.TURN_DONE, turn = t.copy(corner = t.total, waiting = false), run = null) }
        if (t.mode == TurnMode.ALONG) {
            val n = s.lessonN
            saveSettings { it.copy(records = it.records + (n to it.record(n).copy(turned = true))) }
        }
    }

    // =========================================================================================
    // walk along (optional)
    // =========================================================================================

    private fun startWalkReady() {
        val s = _state.value
        if (!s.drawnRight) return
        if (s.phase != Phase.DRAWN && s.phase != Phase.TURN_DONE && s.phase != Phase.WALK_DONE && s.phase != Phase.WALKING && s.phase != Phase.TURN_HOME) return
        val exec = Interpreter.run(s.program, s.lesson.start)
        update { it.copy(phase = Phase.WALK_READY, walk = WalkUi(serial = ++serial, segments = exec.forwards.size, turtleDeg = exec.turnTotal), turn = null, menuOpen = false, coach = null, notOnStart = false) }
    }

    private fun onStart(toStartM: Float, facing: Boolean): Boolean = toStartM <= 0.25f * _state.value.stepM && facing

    private fun startWalk(force: Boolean) {
        val s = _state.value
        val w = s.walk ?: return
        if (s.phase != Phase.WALK_READY) return
        if (!force && (s.lost || !onStart(w.toStartM, w.facing))) {
            update { it.copy(notOnStart = true) }
            TurtleLog.i("WALK refused: not on the start to=${TurtleLog.f(w.toStartM)} facing=${w.facing}")
            return
        }
        TurtleLog.i("WALK start synthetic=${w.synthetic}")
        update { it.copy(phase = Phase.WALKING, walk = w.copy(serial = ++serial, segment = 0, youDeg = 0f), notOnStart = false, offPath = false) }
    }

    private fun finishWalk(e: TurtleEvent.WalkFinished) {
        val s = _state.value
        val w = s.walk ?: return
        val step = s.stepM
        val verdict =
            when {
                e.meanDevM <= max(0.06f, 0.10f * step) -> WalkVerdict.GREAT
                e.meanDevM <= max(0.12f, 0.20f * step) -> WalkVerdict.OK
                else -> WalkVerdict.AGAIN
            }
        TurtleLog.i("WALK finish verdict=${verdict.tag} you=${TurtleLog.f(e.youDeg, 0)} turtle=${TurtleLog.f(w.turtleDeg, 0)} dev=${TurtleLog.f(e.meanDevM, 3)} corners=${e.cornerDegs.map { Math.round(it) }}")
        update {
            it.copy(
                phase = Phase.WALK_DONE,
                walk = w.copy(verdict = verdict, youDeg = e.youDeg, meanDevCm = Math.round(e.meanDevM * 100f), cornerDegs = e.cornerDegs, synthetic = null),
                offPath = false,
            )
        }
        val n = s.lessonN
        saveSettings { it.copy(records = it.records + (n to it.record(n).copy(walked = true, lastWalkVerdict = verdict.tag))) }
    }

    // =========================================================================================
    // tracking, lifecycle
    // =========================================================================================

    private fun setLost(lost: Boolean) {
        if (lost == _state.value.lost) return
        update { it.copy(lost = lost) }
        TurtleLog.i("TRACK lost=$lost")
    }

    /** Background (design `rules.background`): a run is drawn at once, a walk goes back to its start, turning stops. */
    private fun background() {
        val s = _state.value
        when (s.phase) {
            Phase.RUNNING -> update { it.copy(run = it.run?.copy(instant = true, runToSegment = null)) }
            Phase.WALKING, Phase.TURN_HOME -> stop()
            Phase.TURN_ALONG -> stop()
            else -> Unit
        }
    }

    private fun snack(kind: SnackKind, ms: Long, number: Int = 0) {
        update { it.copy(snack = Snack(kind, SystemClock.elapsedRealtime() + ms, number)) }
        snackJob?.cancel()
        snackJob = viewModelScope.launch {
            delay(ms + 50L)
            update { if (it.snack?.kind == kind && it.snack.untilMs <= SystemClock.elapsedRealtime()) it.copy(snack = null) else it }
        }
    }

    private fun saveSettings(f: (Settings) -> Settings) {
        val next = f(_state.value.settings)
        update { it.copy(settings = next) }
        viewModelScope.launch { runCatching { repository.save(next) }.onFailure { TurtleLog.w("PREFS save failed: $it") } }
    }

    // =========================================================================================
    // debug (design qaHooks): straight to a named state
    // =========================================================================================

    private fun debugPlace(sizeM: Float?) {
        val head = latestHead ?: Vec3(0f, 1.6f, 0f)
        val size = sizeM ?: _state.value.fieldM
        val pose = debugFrontOf(head, latestHeadYaw, size, _state.value.floorY)
        update { it.copy(field = pose, fieldM = size, headYAtPlace = head.y, phase = if (it.phase == Phase.PLACING || it.phase == Phase.LESSONS) Phase.CODING else it.phase) }
        TurtleLog.i("FIELD placed by debug center=${TurtleLog.pos(pose.center)} size=${TurtleLog.f(size)}")
    }

    /** The lesson's solution as the program and a fresh match result (debug: states that need a right drawing). */
    private fun makeMatch() {
        val s = _state.value
        val p = s.lesson.solution
        update { it.copy(program = p, cursor = Cursor(null, p.size), phase = Phase.DRAWN, inkShown = true, inkDimmed = false) }
        val result = checker.check(s.lesson, p)
        update { it.copy(result = result, resultProgram = Program.encode(p)) }
    }

    /** The solution as a flat list of blocks (no repeat), for the canned wrong programs. */
    private fun flat(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>()
        fun walk(list: List<Block>) {
            for (b in list) if (b.kind == BlockKind.REPEAT) repeat(b.value.toInt()) { walk(b.body) } else out += b
        }
        walk(blocks)
        return out.mapIndexed { i, b -> b.copy(id = i + 1) }
    }

    private fun cannedProgram(mode: String): List<Block>? {
        val lesson = _state.value.lesson
        return when (mode) {
            "match", "" -> lesson.solution
            "open" -> if (lesson.n == 5) Program.parse("R3[F1 R60]") else flat(lesson.solution).let { f -> f.take(f.indexOfLast { it.kind == BlockKind.FORWARD }) }
            "spin" -> flat(lesson.solution).let { f -> if (f.lastOrNull()?.isTurn == true) f.dropLast(1) else f }
            "mirror" -> Program.mirrored(lesson.solution)
            "tooMany" -> flat(lesson.solution)
            "different" -> Program.parse("R4[F0.5 R90]")
            else -> null
        }
    }

    private fun debugState(raw: String) {
        val name = raw.trim()
        val lessonPart = name.substringAfter("@L", "")
        val core = name.substringBefore("@")
        val base = core.substringBefore('+').substringBefore(':')
        val overlays = core.substringAfter('+', "").substringBefore(':').split('+').filter { it.isNotEmpty() }
        val mode = core.substringAfter(':', "")
        debugHold = false
        TurtleGraph.head.forceLost = false
        update { it.copy(lost = false, hasFloor = realHasFloor || estFloor, estFloor = estFloor && !realHasFloor, menuOpen = false, coach = null, notOnStart = false, offPath = false, blocked = null, canvasMoving = false, placeCheck = PlaceCheck.CLEAR, snapped = false, hintLevel = 0, undoProgram = null, selected = null, snack = null) }
        lessonPart.toIntOrNull()?.let { n -> if (n != _state.value.lessonN || base == "coding") openLesson(n) }
        val needsField = base !in setOf("starting", "lessons", "placing", "blocked")
        if (needsField && _state.value.field == null) debugPlace(null)
        when (base) {
            "starting" -> update { it.copy(phase = Phase.STARTING) }
            "lessons" -> toLessons()
            "placing" -> update { it.copy(phase = Phase.PLACING, field = null, ghost = false, run = null, result = null, turn = null, walk = null, inkShown = false) }
            "coding" -> update { it.copy(phase = Phase.CODING, run = null, result = null, turn = null, walk = null) }
            "running" -> {
                update { it.copy(phase = Phase.CODING, run = null, turn = null, walk = null) }
                if (_state.value.program.isEmpty()) cannedProgram("match")?.let { p -> edit(p, false, Cursor(null, p.size)) }
                startRun(null)
            }
            "drawn" -> {
                val p = if ("outOfField" in overlays) Program.parse("R12[F2 R30]") else cannedProgram(mode)
                if (p != null) {
                    update { it.copy(phase = Phase.CODING, run = null, turn = null, walk = null) }
                    edit(p, false, Cursor(null, p.size))
                    startRun(null)
                    update { it.copy(run = it.run?.copy(instant = true)) }
                }
            }
            "turnAlong" -> {
                if (mode == "guess") {
                    update { it.copy(phase = Phase.CODING) }
                    startTurn(TurnMode.GUESS, "hold")
                } else {
                    makeMatch()
                    startTurn(TurnMode.ALONG, "hold")
                }
            }
            "turnDone" -> {
                if (mode == "guess") {
                    update { it.copy(phase = Phase.CODING) }
                    startTurn(TurnMode.GUESS, "hold")
                } else {
                    makeMatch()
                    startTurn(TurnMode.ALONG, "hold")
                }
                val t = _state.value.turn
                if (t != null) {
                    val yours = t.turtles.mapIndexed { i, d -> if (mode == "guess") d * 0.85f else d + (if (i % 2 == 0) -2f else 1f) }
                    update { it.copy(turn = t.copy(yours = yours)) }
                    finishTurn()
                }
            }
            "walkReady" -> {
                makeMatch()
                startWalkReady()
            }
            "walking", "turnHome" -> onEvent(TurtleEvent.DebugWalk(if (base == "turnHome") "turn_home_hold" else "hold"))
            "walkDone" -> {
                makeMatch()
                startWalkReady()
                val w = _state.value.walk
                if (w != null) {
                    update { it.copy(phase = Phase.WALKING) }
                    val turtle = w.turtleDeg
                    val corners = Interpreter.run(_state.value.program, _state.value.lesson.start).corners.map { c -> c.by + 2f }
                    val dev = when (mode) { "ok" -> 0.1f * _state.value.stepM + 0.02f; "again" -> 0.35f; else -> 0.04f }
                    finishWalk(TurtleEvent.WalkFinished(w.serial, turtle - 8f, dev, corners))
                }
            }
            "blocked" -> update { it.copy(phase = Phase.BLOCKED, blocked = BlockReason.WITHOUT_PERMISSION, run = null) }
            else -> TurtleLog.i("CMD state unknown '$base'")
        }
        for (o in overlays) {
            when (o) {
                "scanning" -> {
                    debugHold = true
                    update { it.copy(hasFloor = false) }
                }
                "estFloor" -> onEvent(TurtleEvent.DebugNoFloor)
                "cramped" -> {
                    debugHold = true
                    update { it.copy(placeCheck = PlaceCheck(Cramped(Side.RIGHT, SurfaceKind.WALL, 30), null)) }
                }
                "snapped" -> {
                    debugHold = true
                    update { it.copy(snapped = true) }
                    snack(SnackKind.SNAPPED, 60_000L)
                }
                "blockSelected" -> {
                    if (_state.value.program.isEmpty()) cannedProgram("match")?.let { p -> edit(p, false, Cursor(null, p.size)) }
                    val first = _state.value.program.firstOrNull()?.let { b -> b.body.firstOrNull() ?: b }
                    if (first != null) onEvent(TurtleEvent.BlockClicked(first.id))
                }
                "canvasMove" -> {
                    debugHold = true
                    update { it.copy(canvasMoving = true) }
                }
                "hint" -> update { it.copy(hintLevel = if (mode == "2") 2 else 1) }
                "hint2" -> update { it.copy(hintLevel = 2) }
                "undo" -> {
                    if (_state.value.program.isEmpty()) cannedProgram("match")?.let { p -> edit(p, false, Cursor(null, p.size)) }
                    onEvent(TurtleEvent.ClearClicked)
                    undoJob?.cancel()
                    update { it.copy(snack = it.snack?.copy(untilMs = SystemClock.elapsedRealtime() + 60_000L)) }
                }
                "tooLong" -> {
                    update { it.copy(phase = Phase.CODING) }
                    snack(SnackKind.TOO_LONG, 60_000L, number = 144)
                }
                "outOfField" -> Unit
                "notOnStart" -> {
                    debugHold = true
                    update { it.copy(notOnStart = true) }
                }
                "offPath" -> {
                    debugHold = true
                    update { it.copy(offPath = true) }
                }
                "lost" -> {
                    debugHold = true
                    TurtleGraph.head.forceLost = true
                    update { it.copy(lost = true) }
                }
                "menu" -> update { it.copy(menuOpen = true) }
                "coach" -> update { it.copy(coach = when (it.phase) { Phase.DRAWN -> 3; Phase.CODING -> if (mode == "1") 1 else 2; else -> 1 }) }
                else -> TurtleLog.i("CMD state unknown overlay '$o'")
            }
        }
    }

    /** The whole model as one JSON line (debug `dump`). */
    fun dumpJson(): String {
        val s = _state.value
        val o = JSONObject()
        o.put("state", s.qaName)
        o.put("phase", s.phase.name)
        o.put("lesson", s.lessonN)
        o.put("program", Program.encode(s.program))
        o.put("blocks", s.blockCount)
        o.put("cursor", JSONObject().put("parent", s.cursor.parent ?: -1).put("index", s.cursor.index))
        o.put("selected", s.selected ?: -1)
        o.put("hint", s.hintLevel)
        o.put("fieldM", s.fieldM.toDouble())
        o.put("stepM", s.stepM.toDouble())
        o.put("hasFloor", s.hasFloor)
        o.put("estFloor", s.estFloor)
        o.put("floorY", s.floorY.toDouble())
        o.put("planes", s.planeCount)
        s.field?.let { f -> o.put("field", JSONObject().put("x", f.center.x.toDouble()).put("y", f.center.y.toDouble()).put("z", f.center.z.toDouble()).put("yawDeg", Math.toDegrees(f.yaw.toDouble()))) }
        s.result?.let { r -> o.put("result", JSONObject().put("verdict", r.verdict.tag).put("outOfField", r.outOfField).put("turned", r.turnTotal.toDouble()).put("gap", r.gapSteps.toDouble()).put("spin", r.spinDeg.toDouble()).put("laps", r.laps ?: -1).put("sixty", r.sixtyHint)) }
        s.run?.let { r -> o.put("run", JSONObject().put("block", r.blockId ?: -1).put("turned", r.turned.toDouble()).put("speed", r.speed.tag)) }
        s.turn?.let { t -> o.put("turn", JSONObject().put("mode", t.mode.tag).put("corner", t.corner).put("total", t.total).put("you", t.youDeg.toDouble()).put("yours", JSONArray(t.yours.map { it.toDouble() })).put("turtle", JSONArray(t.turtles.map { it.toDouble() }))) }
        s.walk?.let { w -> o.put("walk", JSONObject().put("toStart", w.toStartM.toDouble()).put("facing", w.facing).put("segment", w.segment).put("you", w.youDeg.toDouble()).put("verdict", w.verdict?.tag ?: "").put("devCm", w.meanDevCm).put("corners", JSONArray(w.cornerDegs.map { it.toDouble() }))) }
        o.put("records", JSONObject().apply { for ((n, r) in s.settings.records) put(n.toString(), "${if (r.drawnRight) "D" else "-"}${if (r.turned) "T" else "-"}${if (r.walked) "W" else "-"}") })
        o.put("lost", s.lost)
        o.put("menu", s.menuOpen)
        o.put("coach", s.coach ?: 0)
        o.put("language", s.language.tag)
        o.put("snack", s.snackNow(SystemClock.elapsedRealtime())?.kind?.name ?: "")
        return o.toString()
    }

    private fun deg(rad: Float) = String.format(Locale.US, "%.1f", Math.toDegrees(rad.toDouble()))

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TurtleViewModel(TurtleGraph.repository(context)) as T
    }
}
