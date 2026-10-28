package io.embodit.turtlewalk.ui.turtle.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.turtlewalk.domain.model.Lesson
import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.Quat4
import io.embodit.turtlewalk.domain.model.RunSpeed
import io.embodit.turtlewalk.domain.model.SurfaceKind
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Vec3
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.model.wrapDeg
import io.embodit.turtlewalk.domain.usecase.Corner
import io.embodit.turtlewalk.domain.usecase.Execution
import io.embodit.turtlewalk.domain.usecase.FloorPose
import io.embodit.turtlewalk.domain.usecase.Interpreter
import io.embodit.turtlewalk.domain.usecase.Move
import io.embodit.turtlewalk.domain.usecase.PanelFacing
import io.embodit.turtlewalk.domain.usecase.PlaceCheck
import io.embodit.turtlewalk.domain.usecase.PlaceFloorUseCase
import io.embodit.turtlewalk.domain.usecase.Program
import io.embodit.turtlewalk.domain.usecase.Timeline
import io.embodit.turtlewalk.platform.TurtleGraph
import io.embodit.turtlewalk.platform.TurtleLog
import io.embodit.turtlewalk.platform.input.ControllerSample
import io.embodit.turtlewalk.platform.sense.PlaneTracker
import io.embodit.turtlewalk.platform.tracking.HeadPose
import io.embodit.turtlewalk.ui.turtle.Phase
import io.embodit.turtlewalk.ui.turtle.TurnMode
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.TurtleUiState
import io.embodit.turtlewalk.ui.turtle.debug.TurtleProbe
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: TurtleScene? = null
}

/** The one per-frame ECS system of the app. */
class TurtleFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the field tape, the target, the turtle, its ink, the corner wedges and degree tags,
 * the turn ring, you (dot, trail, arrows), and the placement of the pre-built panels. Compose state is rendered
 * through [render] (idempotent diff); per-frame work (placing, dragging the field, the run timeline, turning
 * along, walking) happens in [onFrame] only and reaches Compose at most ten times a second. Everything started
 * in [attach] is stopped in [detach]. Main thread only.
 */
class TurtleScene(private val sink: (TurtleEvent) -> Unit) : TurtleProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Share of its panel's width that a panel's content fills (snackbar capsule). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** Degree tag texts (index -> "120°"), written at most ten times a second. */
    val tagText = mutableStateMapOf<Int, String>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = TurtleGraph.planes
    private val headTracker = TurtleGraph.head
    private val controller = TurtleGraph.controller
    private val placeRule = PlaceFloorUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var attached = false

    // views
    private var tape: TapeView? = null
    private var snapTape: TapeView? = null
    private var field: FieldGroup? = null
    private var targetBars: BarPool? = null
    private var inkBars: BarPool? = null
    private var gapBars: BarPool? = null
    private var guideBars: BarPool? = null
    private var trailBars: BarPool? = null
    private var wedges: WedgeView? = null
    private var ring: RingView? = null
    private var turtle: TurtleBody? = null
    private var starGroup: Entity? = null
    private var starFace: Entity? = null
    private var youDot: Entity? = null
    private var youRing: Entity? = null
    private var feet: List<Entity> = emptyList()
    private var floorPad: Entity? = null
    private var turnArrow: FloorArrow? = null
    private var guideArrow: FloorArrow? = null
    private var homeArrow: FloorArrow? = null
    private var startArrow: FloorArrow? = null

    private val codeFollower = Follower("code", Follower.CODE_OFFSET, CODE_HALF_EXTENTS, Quat4.axisAngle(Vec3.RIGHT, -Math.toRadians(12.0).toFloat()), nearM = 0.6f, farM = 1.6f)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = TurtleUiState()
    private var head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var lastFrameMs = 0L
    private var lastHeadSentMs = 0L
    private var lastPlaneCount = -1
    private var lastFloor = false
    private var lastPlanesEmitMs = 0L
    private var lastLost = false
    private var lastBlock: Any? = null
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var dialogPlaced = false
    private var fpsOn = false
    private var frames = 0
    private var fpsStartMs = 0L

    // the field as drawn now (placing ghost or a field being dragged), its size
    private var livePose: FloorPose? = null
    private var liveSize = 2f
    private var laidPose: FloorPose? = null
    private var laidSize = -1f
    private var laidFloorY = Float.NaN

    // placing
    private var placingCenter: Vec3? = null
    private var placingTarget: Vec3? = null
    private var placingYaw = 0f
    private var stickTurnDeg = 0f
    private var snapped = false
    private var lastRotateRequests = 0
    private var lastPlacingSentMs = 0L
    private var lastTrigger = false
    private var placingSize = 2f

    // canvas move (placed field)
    private var dragOffset: Vec3? = null
    private var moving = false
    private var lastMovedSentMs = 0L
    private var debugStick: Pair<Float, Float>? = null
    private var debugStickUntil = 0L

    // what the floor shows
    private var lessonDrawn: Lesson? = null
    private var stepDrawn = -1f
    private var targetExec: Execution? = null
    private var shownExec: Execution? = null
    private var inkMaterialDim = false
    private var inkLaidMoves = -1
    private var inkLaidShare = -1f
    private var inkLaidStep = -1f
    private var inkBase = IntArray(0)

    // the run
    private var runSerial = -1
    private var runExec: Execution? = null
    private var timeline: Timeline? = null
    private var runSpeed = RunSpeed.SLOW
    private var runT = 0f
    private var runStopT = 0f
    private var runFinished = false
    private var lastRunSentMs = 0L

    // turning along
    private var turnSerial = -1
    private var turnSession: TurnSession? = null

    // walking along
    private var walk: WalkSession? = null
    private var walkSerial = -1
    private var lastWalkSentMs = 0L

    // your heading, unwrapped and low-passed (degrees, right positive)
    private var youYawRaw = Float.NaN
    private var youYawFiltered = Float.NaN
    private var youTurn = 0f
    private var debugYawDeg = 0f

    /** The code panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin. Move the root once so that its
        // local space equals Stage space (Spatial Ruler finding).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        TurtleLog.i("STAGE root offset=${TurtleLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h)
        }
        startTrackers()
        ActiveScene.scene = this
        bindPanels(attachments)
        codeFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) TurtleLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    private fun startTrackers() {
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
            }
        )
    }

    /** After the "unavailable" dialog's retry: the trackers start again. */
    fun restartTracking() {
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        lastBlock = null
        lastLost = false
        lastPlaneCount = -1
        startTrackers()
        colliders?.let { c -> planeTracker.all.forEach(c::upsert) }
        if (attached) sink(TurtleEvent.SceneReady)
    }

    /**
     * Builds every mesh and the entity pools in six batches, giving the main thread back between them (the idle
     * emulator takes about a second per mesh; several in a row raise an ANR: Dance Steps finding).
     */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        val pad = Entity().named("floorpad")
        pad.tapBox(Vec3(12f, 0.006f, 12f))
        root.addChild(pad)
        floorPad = pad
        yield()
        val cube = pacedMesh { sh.keep(MeshFactory.unitCube("cube")) }
        // batch 1: field tape and snap frame
        tape = TapeView(root, cube, m, frame = false)
        snapTape = TapeView(root, cube, m, frame = true)
        yield()
        // batch 2: target, star, arrows
        val fg = FieldGroup(root, "field")
        field = fg
        targetBars = BarPool(fg.entity, cube, m.target, 140, "target")
        guideBars = BarPool(fg.entity, cube, m.arrow, 15, "guide")
        startArrow = FloorArrow(guideBars!!, 0)
        turnArrow = FloorArrow(guideBars!!, 3)
        guideArrow = FloorArrow(guideBars!!, 6)
        homeArrow = FloorArrow(guideBars!!, 9)
        val starMesh = pacedMesh { sh.keep(MeshFactory.star("star")) }
        val pole = pacedMesh { sh.keep(MeshFactory.cylinder(0.006f, 0.3f, "pole", segments = 8)) }
        val sg = Entity().named("starGroup")
        fg.entity.addChild(sg)
        sg.addChild(ModelEntity(pole, m.star).also { it.named("starPole") })
        val face = ModelEntity(starMesh, m.star).also { it.named("star") }
        sg.addChild(face)
        face.setLocal(position = Vec3(0f, 0.36f, 0f), scale = Vec3(0.08f, 0.08f, 1f))
        sg.enabled = false
        starGroup = sg
        starFace = face
        yield()
        // batch 3: ink and the open gap
        inkBars = BarPool(fg.entity, cube, m.ink, 180, "ink")
        gapBars = BarPool(fg.entity, cube, m.gap, 24, "gap")
        yield()
        // batch 4: wedges and the ring
        val sector = pacedMesh { sh.keep(MeshFactory.sector(TurtleRules.WEDGE_PIECE_DEG, "sector15")) }
        wedges = WedgeView(fg.entity, sector, m)
        yield()
        val arc = pacedMesh { sh.keep(MeshFactory.arc(0.944f, 1.056f, 5f, "arc5", segments = 2)) }
        val dot = pacedMesh { sh.keep(MeshFactory.disc(0.014f, "lapDot", segments = 12)) }
        ring = RingView(fg.entity, arc, dot, m).also { it.setShown(false) }
        yield()
        // batch 5: the turtle, you
        val sphere = pacedMesh { sh.keep(MeshFactory.sphere("sphere")) }
        val patch = pacedMesh { sh.keep(MeshFactory.cylinder(0.018f, 0.006f, "patch", segments = 6)) }
        turtle = TurtleBody(fg.entity, sphere, patch, cube, m).also { it.setShown(false) }
        val youDisc = pacedMesh { sh.keep(MeshFactory.disc(0.06f, "youDisc", segments = 28)) }
        val youRingMesh = pacedMesh { sh.keep(MeshFactory.ring(0.066f, 0.078f, "youRing", segments = 28)) }
        youDot = ModelEntity(youDisc, m.you).also { it.named("youDot"); fg.entity.addChild(it); it.enabled = false }
        youRing = ModelEntity(youRingMesh, m.youRing).also { it.named("youRing"); fg.entity.addChild(it); it.enabled = false }
        feet =
            listOf(-1f, 1f).flatMap { sx ->
                listOf(0.05f to 0.11f, 0.045f to 0.06f).mapIndexed { k, (rx, rz) ->
                    ModelEntity(sphere, m.feet).also { e ->
                        e.named("foot_${if (sx < 0) "l" else "r"}$k")
                        fg.entity.addChild(e)
                        e.enabled = false
                        e.setLocal(scale = Vec3(rx, 0.003f, rz))
                    }
                }
            }
        yield()
        // batch 6: your trail
        trailBars = BarPool(fg.entity, cube, m.trail, 200, "trail")
        attached = true
        TurtleLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(TurtleEvent.SceneReady)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        panelStretch.clear()
        tagText.clear()
        runCatching { rig?.release() }
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        tape = null
        snapTape = null
        field = null
        targetBars = null
        inkBars = null
        gapBars = null
        guideBars = null
        trailBars = null
        wedges = null
        ring = null
        turtle = null
        starGroup = null
        youDot = null
        youRing = null
        feet = emptyList()
        floorPad = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        dialogPlaced = false
        runSerial = -1
        turnSerial = -1
        walkSerial = -1
        turnSession = null
        walk = null
        lessonDrawn = null
        laidPose = null
        TurtleLog.i("SCENE detached")
    }

    fun onBackground() = Unit

    fun onForeground() = Unit

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: TurtleUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        if (state.phase == Phase.PLACING && (previous.phase != Phase.PLACING || force)) enterPlacing(state)
        if (state.phase != Phase.PLACING) {
            snapTape?.group?.setShown(false)
            if (!moving || state.field == null) {
                livePose = state.field
                liveSize = state.fieldM
            }
        } else {
            liveSize = state.fieldM
        }
        if (state.phase != Phase.PLACING && state.field == null) livePose = null

        // a run starts
        val run = state.run
        if (state.phase == Phase.RUNNING && run != null && run.serial != runSerial) startRun(state)
        if (state.phase == Phase.RUNNING && run != null && run.speed != runSpeed) changeSpeed(run.speed)
        if (state.phase == Phase.RUNNING && run?.instant == true) runT = runStopT
        // a turn session starts
        val turn = state.turn
        if (state.phase == Phase.TURN_ALONG && turn != null && turn.serial != turnSerial) startTurn(state)
        if (state.phase == Phase.TURN_ALONG && turn != null) turnSession?.confirms(turn.confirms)
        if (state.phase != Phase.TURN_ALONG && state.phase != Phase.TURN_DONE) turnSession = null
        // a walk starts
        val w = state.walk
        if ((state.phase == Phase.WALKING) && w != null && w.serial != walkSerial) startWalk(state)
        if (state.phase == Phase.WALK_READY || state.phase == Phase.DRAWN || state.phase == Phase.CODING) {
            if (walk != null && state.phase != Phase.WALK_DONE) walk = null
        }
        if (state.phase == Phase.WALK_READY && previous.phase != Phase.WALK_READY) trailBars?.hideFrom(0)
        layFloor(state, SystemClock.elapsedRealtime(), force)
        syncPanels(state)
    }

    /** What lies on the floor for this phase (cheap when nothing changed). */
    private fun layFloor(state: TurtleUiState, now: Long, force: Boolean) {
        val pose = livePose
        val tape = tape ?: return
        val fg = field ?: return
        if (pose == null) {
            tape.group.setShown(false)
            fg.setShown(false)
            return
        }
        val step = liveSize / TurtleRules.FIELD_STEPS
        if (force || pose != laidPose || liveSize != laidSize || state.floorY != laidFloorY) {
            tape.group.setPose(pose, state.floorY)
            fg.setPose(pose, state.floorY)
            tape.layout(liveSize)
            laidPose = pose
            laidFloorY = state.floorY
        }
        tape.group.setShown(true)
        val m = materials ?: return
        if (state.phase == Phase.PLACING) {
            tape.layout(liveSize, solid = snapped || state.snapped)
            tape.setStyle(if (snapped || state.snapped) m.tapeSnapped else m.tapePlacing, state.placeCheck.cramped?.side)
            fg.setShown(false)
            return
        }
        tape.setStyle(m.tapePlaced, null)
        fg.setShown(state.phase != Phase.LESSONS && state.phase != Phase.STARTING && state.phase != Phase.BLOCKED)

        val lesson = state.lesson
        val stepChanged = abs(step - stepDrawn) > 1e-4f || force
        if (lesson !== lessonDrawn || stepChanged) {
            lessonDrawn = lesson
            stepDrawn = step
            targetExec = Interpreter.run(lesson.solution, lesson.start)
            layTarget(targetExec!!, lesson, step)
            inkLaidMoves = -1
        }
        // the target: hidden once drawn right, while turning along a right drawing and on the walk
        val match = state.result?.verdict == Verdict.MATCH
        val targetOn =
            when (state.phase) {
                Phase.DRAWN -> !match
                Phase.TURN_ALONG, Phase.TURN_DONE -> state.turn?.mode == TurnMode.GUESS
                Phase.WALK_READY, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE -> false
                else -> true
            }
        setTargetShown(targetOn, lesson)

        // the ink of the last run (not while guessing)
        val guess = state.turn?.mode == TurnMode.GUESS && (state.phase == Phase.TURN_ALONG || state.phase == Phase.TURN_DONE)
        val inkOn = state.inkShown && !guess && state.phase != Phase.RUNNING
        if (state.phase != Phase.RUNNING) {
            if (inkOn) {
                val exec = shownExec ?: Interpreter.run(state.program, lesson.start).also { shownExec = it }
                val dim = state.inkDimmed && state.phase == Phase.CODING
                val end = exec.exit
                drawInk(exec, end?.first ?: exec.moves.size, end?.second ?: 1f, step, dim)
                drawCorners(exec, end?.first ?: exec.moves.size, end?.second ?: 1f, step, !dim)
            } else {
                inkBars?.hideFrom(0)
                inkLaidMoves = -1
                wedges?.hideFrom(0)
                clearTags()
            }
        }
        // the open gap
        val open = state.phase == Phase.DRAWN && state.result?.verdict == Verdict.OPEN && state.result.outOfField.not()
        val gaps = gapBars
        if (open && gaps != null) {
            val exec = shownExec ?: Interpreter.run(state.program, lesson.start)
            val (ax, az) = exec.end.at.local(step)
            val (bx, bz) = exec.start.at.local(step)
            gaps.hideFrom(gaps.dashes(0, ax, az, bx, bz, 0.06f, 0.05f, 0.025f, 0.003f, 0.002f))
        } else {
            gaps?.hideFrom(0)
        }

        // the turtle
        val t = turtle
        when (state.phase) {
            Phase.CODING, Phase.LESSONS -> t?.place(lesson.start.at, lesson.start.heading, step)
            Phase.DRAWN -> {
                val exec = shownExec
                if (exec != null) {
                    val stop = exec.exit
                    if (stop != null) {
                        val f = exec.moves[stop.first] as Move.Forward
                        t?.place(f.from.lerp(f.to, stop.second), f.heading, step)
                    } else {
                        t?.place(exec.end.at, exec.end.heading, step)
                    }
                }
            }
            Phase.WALK_READY, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE -> t?.place(lesson.start.at, lesson.start.heading, step)
            else -> Unit
        }
        t?.setShown(state.phase != Phase.LESSONS)

        // the ring
        val r = ring
        if (r != null) {
            when (state.phase) {
                Phase.DRAWN -> {
                    val exec = shownExec
                    if (exec != null) {
                        val at = exec.exit?.let { (i, s) -> (exec.moves[i] as Move.Forward).let { f -> f.from.lerp(f.to, s) } } ?: exec.end.at
                        val (x, z) = at.local(step)
                        r.place(x, z, step)
                        val total = state.result?.turnTotal ?: exec.turnTotal
                        r.show(total, now)
                        if (state.result?.laps != null && force.not() && previousDrawnSerial != state.run?.serial) {
                            previousDrawnSerial = state.run?.serial
                            r.flash(now)
                        }
                    }
                    r.setShown(true)
                }
                Phase.RUNNING, Phase.TURN_ALONG, Phase.TURN_DONE, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE -> r.setShown(true)
                else -> r.setShown(false)
            }
        }
        // start feet on the walk
        val feetOn = state.phase == Phase.WALK_READY
        for (f in feet) f.enabled = feetOn
        if (feetOn) layFeet(lesson, step, state.walk?.let { it.toStartM <= 0.25f * step && it.facing } == true)
        else startArrow?.hide()
        if (state.phase != Phase.WALKING && state.phase != Phase.TURN_HOME) guideArrow?.hide()
        if (state.phase != Phase.TURN_HOME) homeArrow?.hide()
        if (state.phase != Phase.TURN_ALONG) turnArrow?.hide()
        val youOn = state.phase in setOf(Phase.TURN_ALONG, Phase.TURN_DONE, Phase.WALK_READY, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE)
        youDot?.enabled = youOn
        youRing?.enabled = youOn
        if (state.phase !in setOf(Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE)) trailBars?.hideFrom(0)
    }

    private var previousDrawnSerial: Int? = null

    private fun layTarget(exec: Execution, lesson: Lesson, step: Float) {
        val bars = targetBars ?: return
        var k = 0
        for (f in exec.forwards) {
            val (ax, az) = f.from.local(step)
            val (bx, bz) = f.to.local(step)
            k = bars.dashes(k, ax, az, bx, bz, 0.06f, 0.05f, 0.02f, 0.002f, 0.001f)
        }
        // the start arrow (0.15 step along the first heading) and a short arrow of the new heading at each corner
        val m = materials ?: return
        val first = exec.forwards.firstOrNull()
        if (first != null) {
            val a = first.from + P2.heading(first.heading) * 0.05f
            val b = first.from + P2.heading(first.heading) * (0.05f + 0.15f)
            k = arrowBars(bars, k, a, b, step, m.target)
        }
        for (c in exec.corners) {
            val side = P2.heading(c.from + (if (c.by > 0) 90f else -90f)) * 0.08f
            val a = c.at + side
            val b = a + P2.heading(c.to) * 0.12f
            k = arrowBars(bars, k, a, b, step, m.target)
        }
        bars.hideFrom(k)
        // the star at the end (lessons 1 and 2)
        val sg = starGroup
        if (sg != null) {
            val (x, z) = exec.end.at.local(step)
            sg.setLocal(position = Vec3(x, 0f, z))
        }
        TurtleLog.i("TARGET lesson=${lesson.n} dashes=$k step=${TurtleLog.f(step)}")
    }

    private fun arrowBars(bars: BarPool, first: Int, a: P2, b: P2, step: Float, m: com.pico.spatial.core.ecs.resource.Material): Int {
        val (ax, az) = a.local(step)
        val (bx, bz) = b.local(step)
        val len = hypot(bx - ax, bz - az)
        if (len < 1e-3f) return first
        val ux = (bx - ax) / len
        val uz = (bz - az) / len
        bars.bar(first, ax, az, bx, bz, 0.015f, 0.002f, 0.001f, m)
        val head = min(0.06f, len * 0.5f)
        var k = first + 1
        for (s in listOf(-1f, 1f)) {
            val c = cos(0.61f)
            val sn = sin(0.61f) * s
            bars.bar(k++, bx, bz, bx - (ux * c - uz * sn) * head, bz - (uz * c + ux * sn) * head, 0.015f, 0.002f, 0.001f, m)
        }
        return k
    }

    private var targetShown = true
    private var starShown = false

    private fun setTargetShown(on: Boolean, lesson: Lesson) {
        val bars = targetBars ?: return
        if (on != targetShown) {
            targetShown = on
            if (!on) bars.hideFrom(0) else targetExec?.let { layTarget(it, lesson, stepDrawn) }
        }
        val star = on && lesson.star || (lesson.star && ui.phase == Phase.DRAWN)
        if (star != starShown) {
            starShown = star
            starGroup?.enabled = star
        }
    }

    /** Lays the ink of [exec] up to forward move [moveIndex] at [share]; segments beyond the field are red. */
    private fun drawInk(exec: Execution, moveIndex: Int, share: Float, step: Float, dim: Boolean) {
        val bars = inkBars ?: return
        val m = materials ?: return
        val restart = exec !== inkExec || dim != inkMaterialDim || abs(step - inkLaidStep) > 1e-4f || moveIndex < inkLaidMoves || inkLaidMoves < 0
        if (restart) {
            inkExec = exec
            inkMaterialDim = dim
            inkLaidStep = step
            inkLaidMoves = 0
            inkLaidShare = -1f
            inkBase = IntArray(exec.moves.size + 1)
            inkNext = 0
        }
        if (!restart && moveIndex == inkLaidMoves && share == inkLaidShare) return
        val inMat = if (dim) m.inkDim else m.ink
        val outMat = if (dim) m.inkOutDim else m.inkOut
        var i = inkLaidMoves
        while (i <= moveIndex && i < exec.moves.size) {
            val mv = exec.moves[i]
            val sh = if (i < moveIndex) 1f else share
            inkBase[i] = if (i == inkLaidMoves && !restart && inkLaidShare >= 0f) inkBase[i] else inkNext
            var k = inkBase[i]
            if (mv is Move.Forward && sh > 0f) {
                val to = mv.from.lerp(mv.to, sh)
                for ((a, b, inside) in clipToField(mv.from, to)) {
                    val (ax, az) = a.local(step)
                    val (bx, bz) = b.local(step)
                    bars.bar(k++, ax, az, bx, bz, 0.03f, 0.003f, 0.0015f, if (inside) inMat else outMat)
                }
            }
            inkNext = k
            if (i < moveIndex) inkLaidMoves = i + 1
            i++
        }
        inkLaidShare = share
        if (moveIndex >= exec.moves.size) inkLaidMoves = exec.moves.size
        bars.hideFrom(inkNext)
    }

    private var inkExec: Execution? = null
    private var inkNext = 0

    /** [a]..[b] split at the field's edge (1 step): pieces with "inside" flags. */
    private fun clipToField(a: P2, b: P2): List<Triple<P2, P2, Boolean>> {
        val lim = TurtleRules.FIELD_STEPS / 2f
        val d = b - a
        var t0 = 0f
        var t1 = 1f
        fun clip(p: Float, q: Float): Boolean {
            if (abs(p) < 1e-7f) return q >= 0f
            val r = q / p
            if (p < 0f) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
            return true
        }
        val visible = clip(-d.x, a.x + lim) && clip(d.x, lim - a.x) && clip(-d.y, a.y + lim) && clip(d.y, lim - a.y)
        if (!visible) return listOf(Triple(a, b, false))
        val out = ArrayList<Triple<P2, P2, Boolean>>(3)
        if (t0 > 1e-4f) out += Triple(a, a.lerp(b, t0), false)
        out += Triple(a.lerp(b, t0), a.lerp(b, t1), true)
        if (t1 < 1f - 1e-4f) out += Triple(a.lerp(b, t1), b, false)
        return out
    }

    /** Wedges and degree tags of the first 12 corners up to [moveIndex] / [share]. */
    private fun drawCorners(exec: Execution, moveIndex: Int, share: Float, step: Float, tags: Boolean) {
        val w = wedges ?: return
        val corners = exec.corners
        val radius = TurtleRules.WEDGE_RADIUS_STEPS * step
        var shown = 0
        for ((c, corner) in corners.withIndex()) {
            if (c >= TurtleRules.TAGS) break
            val turned = turnedAt(exec, corner, moveIndex, share) ?: break
            val (x, z) = corner.at.local(step)
            w.corner(c, x, z, corner.from, turned, radius)
            if (tags) setTag(c, turned)
            shown = c + 1
        }
        w.hideFrom(shown)
        for (c in shown until TurtleRules.TAGS) clearTag(c)
        if (!tags) clearTags()
        tagCorners = corners
        tagStep = step
    }

    /** Degrees turned at [corner] when the run stands at [moveIndex] / [share]; null when it has not been reached. */
    private fun turnedAt(exec: Execution, corner: Corner, moveIndex: Int, share: Float): Float? {
        if (moveIndex < corner.moveIndex) return null
        var sum = 0f
        for (k in corner.moveIndex until corner.moveIndex + corner.moveCount) {
            val t = exec.moves[k] as Move.Turn
            sum += when {
                k < moveIndex -> t.by
                k == moveIndex -> t.by * share
                else -> 0f
            }
        }
        return sum
    }

    private var tagCorners: List<Corner> = emptyList()
    private var tagStep = 1f

    private fun setTag(c: Int, deg: Float) {
        val text = String.format(Locale.US, "%d°", Math.round(abs(deg)))
        if (tagText[c] != text) tagText[c] = text
    }

    private fun clearTag(c: Int) {
        if (tagText.containsKey(c)) tagText.remove(c)
    }

    private fun clearTags() {
        if (tagText.isNotEmpty()) tagText.clear()
    }

    private fun layFeet(lesson: Lesson, step: Float, ok: Boolean) {
        val m = materials ?: return
        val h = lesson.start.heading
        val dir = P2.heading(h)
        val side = P2.heading(h + 90f)
        for ((i, f) in feet.withIndex()) {
            val sx = if (i < 2) -1f else 1f
            val ball = i % 2 == 0
            val p = lesson.start.at + side * (sx * 0.09f / step) + dir * ((if (ball) 0.05f else -0.08f) / step)
            val (x, z) = p.local(step)
            f.setLocal(position = Vec3(x, 0.004f, z), rotation = headingRotation(h))
            f.setMaterial(if (ok) m.feetOk else m.feet)
        }
        val a = lesson.start.at + dir * (0.2f / step)
        val b = a + dir * (0.25f / step)
        val (ax, az) = a.local(step)
        val (bx, bz) = b.local(step)
        startArrow?.draw(ax, az, bx, bz, 0.025f, m.arrow)
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    private fun syncPanels(state: TurtleUiState) {
        val rig = rig ?: return
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (state.coach == null) rig[PanelSpec.COACH]?.hide()
        if (state.snackNow(SystemClock.elapsedRealtime()) == null) rig[PanelSpec.SNACK]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.blocked != null) {
            if (!dialogPlaced) {
                val flat = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                dialog?.show(head.position + flat * PanelSpec.DIALOG_DISTANCE_M, PanelSpec.DIALOG_SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
    }

    /** Width of the snackbar's capsule, dp; called by the panel. */
    fun reportSnack(widthDp: Float) {
        val slot = rig?.get(PanelSpec.SNACK) ?: return
        if (widthDp <= 0f) return
        slot.widthFactor = widthDp / slot.widthDp
        if (panelStretch[PanelSpec.SNACK] != slot.widthFactor) panelStretch[PanelSpec.SNACK] = slot.widthFactor
    }

    private var codePos = Vec3.ZERO
    private var codeRot = Quat4.IDENTITY

    /** Menu, snackbar and coachmark hang on the code panel; the degree tags stand at their corners. */
    private fun placePanels(state: TurtleUiState, rig: PanelRig, now: Long) {
        if (!hasHead) return
        val k = PanelSpec.CODE_SCALE
        val (hw, hh) = PanelSpec.halfM(PanelSpec.CODE, k)
        val right = codeRot.rotate(Vec3.RIGHT)
        val up = codeRot.rotate(Vec3.UP)
        val out = codeRot.rotate(Vec3(0f, 0f, 1f))
        if (state.snackNow(now) != null) {
            val (_, sh) = PanelSpec.halfM(PanelSpec.SNACK, k)
            rig[PanelSpec.SNACK]?.place(codePos + up * (hh + SNACK_GAP_M + sh), codeRot, k)
        } else {
            rig[PanelSpec.SNACK]?.hide()
        }
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            val keyDrop = (16f + 64f + 8f) * PanelRig.DP_M * k
            rig[PanelSpec.MENU]?.place(codePos + right * (hw - mw - 16f * PanelRig.DP_M * k) + up * (hh - keyDrop - mh) + out * 0.015f, codeRot, k)
        }
        if (state.coach != null) {
            val (cw, ch) = PanelSpec.halfM(PanelSpec.COACH, k)
            rig[PanelSpec.COACH]?.place(codePos - right * (hw + COACH_GAP_M + cw) - up * (hh - ch), codeRot, k)
        }
        // degree tags: outside their corner, 0.15 m up, turned to the head every frame
        val pose = livePose
        val tagsOn = pose != null && state.phase in setOf(Phase.RUNNING, Phase.DRAWN, Phase.TURN_ALONG, Phase.TURN_DONE, Phase.WALK_READY, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE)
        for (i in 0 until TurtleRules.TAGS) {
            val slot = rig[PanelSpec.tag(i)] ?: continue
            val c = tagCorners.getOrNull(i)
            if (!tagsOn || c == null || tagText[i] == null || pose == null) {
                slot.hide()
                continue
            }
            val inDir = P2.heading(c.from + 180f)
            val outDir = P2.heading(c.to)
            var bis = inDir + outDir
            if (bis.length < 1e-3f) bis = P2.heading(c.from + 90f)
            val b = bis * (1f / bis.length)
            val spot = c.at - b * (0.22f / tagStep)
            val p = pose.toStage(spot.x * tagStep, spot.y * tagStep)
            if (slot.position.distanceTo(Vec3(p.x, state.floorY + TAG_LIFT_M, p.z)) > 0.002f || !slot.shown) slot.show(Vec3(p.x, state.floorY + TAG_LIFT_M, p.z), PanelSpec.TAG_SCALE)
        }
        rig.faceViewer(head.position)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.2f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        countFrame(now)
        if (!attached) return
        pollTracking(now)
        pollPlanes(now)
        trackYourTurn(state, dt)
        floorPad?.setLocal(position = Vec3(head.position.x, state.floorY - 0.003f, head.position.z))

        // the code panel: lazy follow until the field is placed, fixed afterwards
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.blocked != null
        val at = if (debugPanelNear) null else state.panelAt
        if (at == null) {
            if (hasHead && head.position.length > 1e-3f) codeFollower.follow(head, planeTracker.all, planeTracker.count, now, hold)
            codePos = codeFollower.pose.position
            codeRot = codeFollower.rotation
        } else {
            codePos = at.center
            codeRot = PanelFacing(at.yaw, Math.toRadians(TurtleRules.PANEL_LEAN_DEG.toDouble()).toFloat()).rotation
        }
        if (hasHead && state.phase != Phase.BLOCKED) rig[PanelSpec.CODE]?.place(codePos, codeRot, PanelSpec.CODE_SCALE)
        else rig[PanelSpec.CODE]?.hide()

        val sample = controller.sample()
        val stick = stickOf(sample, now)
        if (state.phase == Phase.PLACING && hasHead) followRay(state, sample, stick, now, dt)
        else if (state.field != null && state.phase in CANVAS_PHASES) moveCanvas(state, sample, stick, now, dt)
        lastTrigger = (sample?.pressed == true) || (sample?.trigger ?: 0f) > 0.6f

        if (state.phase == Phase.RUNNING) stepRun(state, now, dt)
        if (state.phase == Phase.TURN_ALONG || state.phase == Phase.TURN_DONE) stepTurn(state, now, dt)
        if (state.phase in setOf(Phase.WALK_READY, Phase.WALKING, Phase.TURN_HOME, Phase.WALK_DONE)) stepWalk(state, now, dt)
        if (state.phase == Phase.DRAWN || state.phase == Phase.TURN_DONE || state.phase == Phase.WALK_DONE) ring?.let { r -> if (state.phase == Phase.DRAWN) r.show(state.result?.turnTotal ?: 0f, now) }
        if (livePose != laidPose || liveSize != laidSize) layFloor(state, now, false)
        laidSize = liveSize

        // the star turns to face you
        val sg = starGroup
        if (sg != null && starShown) {
            val pose = livePose
            if (pose != null) {
                val star = targetExec?.end?.at ?: P2.ZERO
                val p = pose.toStage(star.x * stepDrawn, star.y * stepDrawn)
                val yaw = atan2(head.position.x - p.x, head.position.z - p.z) - pose.yaw
                starFace?.setLocal(rotation = Quat4.axisAngle(Vec3.UP, yaw))
            }
        }

        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(TurtleEvent.HeadSample(head.position, FloorPose.yawOf(head.forward, placingYaw)))
        }
        placePanels(state, rig, now)
    }

    // ---------------------------------------------------------------- placing and moving the field

    private fun enterPlacing(state: TurtleUiState) {
        val yaw = FloorPose.yawOf(head.forward, 0f)
        placingYaw = yaw
        stickTurnDeg = 0f
        snapped = false
        lastRotateRequests = TurtleGraph.rotate45Requests
        val size = placeRule.largestFitting(frontCenter(state.fieldM, yaw, state.floorY), yaw, planeTracker.all, state.floorY, state.fieldM)
        placingSize = size
        placingCenter = frontCenter(size, yaw, state.floorY)
        placingTarget = null
        livePose = FloorPose(placingCenter!!, yaw)
        liveSize = size
        TurtleLog.i("PLACE enter size=$size center=${TurtleLog.pos(placingCenter)} yaw=${deg(yaw)}")
        if (size != state.fieldM) sink(TurtleEvent.FieldFollow(livePose!!, size, head.position.y, PlaceCheck.CLEAR, false))
    }

    /** The field in front of the head: back edge 0.4 m ahead of the feet. */
    private fun frontCenter(sizeM: Float, yaw: Float, floorY: Float): Vec3 {
        val f = FloorPose(Vec3(head.position.x, floorY, head.position.z), yaw)
        val c = f.toStage(0f, TurtleRules.FIELD_BACK_GAP_M + sizeM / 2f)
        return Vec3(c.x, floorY, c.z)
    }

    /** Stick (x, y) after the dead zone; the debug stick wins while it lasts. */
    private fun stickOf(sample: ControllerSample?, now: Long): Pair<Float, Float> {
        val d = debugStick
        if (d != null && now < debugStickUntil) return d
        if (d != null) debugStick = null
        fun dz(v: Float): Float = if (abs(v) < TurtleRules.STICK_DEAD_ZONE) 0f else (abs(v) - TurtleRules.STICK_DEAD_ZONE) / (1f - TurtleRules.STICK_DEAD_ZONE) * kotlin.math.sign(v)
        return dz(sample?.stickX ?: 0f) to dz(sample?.stickY ?: 0f)
    }

    /** Where the controller ray meets the floor, or null (nothing within 8 m, or the ray points at the code panel first). */
    private fun rayOnFloor(sample: ControllerSample?, floorY: Float): Vec3? {
        val ray = sample?.ray ?: return null
        val dy = ray.direction.y
        if (dy > -0.05f) return null
        val t = (floorY - ray.origin.y) / dy
        if (t <= 0f || t > 8f) return null
        val hit = ray.origin + ray.direction * t
        // the code panel in the way?
        val n = codeRot.rotate(Vec3(0f, 0f, 1f))
        val denom = ray.direction.dot(n)
        if (abs(denom) > 1e-4f) {
            val tp = (codePos - ray.origin).dot(n) / denom
            if (tp > 0f && tp < t) {
                val p = ray.origin + ray.direction * tp - codePos
                val (hw, hh) = PanelSpec.halfM(PanelSpec.CODE, PanelSpec.CODE_SCALE)
                if (abs(p.dot(codeRot.rotate(Vec3.RIGHT))) <= hw && abs(p.dot(codeRot.rotate(Vec3.UP))) <= hh) return null
            }
        }
        return hit
    }

    /**
     * Placing (design `rules.placeField`, taste D1): the ghost field's centre follows the controller ray on the
     * floor (0.1 s smoothing), starting right in front of you; its heading is fixed on entry and only the stick
     * (90 degrees a second) and "turn 45" change it; near a wall it snaps to the suggested frame; the trigger on
     * the floor places it. Frozen while tracking is lost.
     */
    private fun followRay(state: TurtleUiState, sample: ControllerSample?, stick: Pair<Float, Float>, now: Long, dt: Float) {
        if (state.lost) return
        val size = state.fieldM
        val half = size / 2f
        val hit = rayOnFloor(sample, state.floorY)
        if (hit != null) placingTarget = Vec3(hit.x, state.floorY, hit.z)
        val target = placingTarget ?: placingCenter ?: frontCenter(size, placingYaw, state.floorY)
        val c = placingCenter ?: target
        val k = (dt / 0.1f).coerceIn(0f, 1f)
        var center = c + (target - c) * k
        // stick turns about the field's own vertical axis; "turn 45" clockwise
        val turn = -stick.first * TurtleRules.STICK_DEG_S * dt
        if (turn != 0f) stickTurnDeg += abs(turn)
        placingYaw += Math.toRadians(turn.toDouble()).toFloat()
        val requests = TurtleGraph.rotate45Requests
        if (requests != lastRotateRequests) {
            placingYaw -= Math.toRadians(45.0 * (requests - lastRotateRequests)).toFloat()
            lastRotateRequests = requests
            stickTurnDeg += 45f
        }
        // taste D14: never under your feet; the back edge stays 0.2 m ahead of you
        val probe = FloorPose(center, placingYaw)
        val (fx, fy) = probe.toLocal(Vec3(head.position.x, state.floorY, head.position.z))
        val m = half + TurtleRules.FIELD_MIN_GAP_M
        if (abs(fx) < m && abs(fy) < m) center = center + probe.forward * (fy + m)
        placingCenter = center
        var pose = FloorPose(center, placingYaw)
        // snap to the frame by the wall
        val frame = placeRule.snapFrame(pose, half, planeTracker.all)
        val st = snapTape
        if (frame != null && st != null) {
            st.layout(size, solid = snapped)
            st.group.setPose(frame.pose, state.floorY)
            st.group.setShown(true)
            val d = hypot(frame.pose.center.x - center.x, frame.pose.center.z - center.z)
            var dy = Math.toDegrees(FloorPose.wrapRad(frame.pose.yaw - placingYaw).toDouble()).toFloat()
            dy = ((dy % 90f) + 135f) % 90f - 45f
            if (!snapped && d <= TurtleRules.SNAP_IN_M && abs(dy) <= TurtleRules.SNAP_IN_DEG) {
                snapped = true
                stickTurnDeg = 0f
                TurtleLog.i("SNAP on frame=${TurtleLog.pos(frame.pose.center)} yaw=${deg(frame.pose.yaw)}")
            } else if (snapped && (d >= TurtleRules.SNAP_OUT_M || stickTurnDeg >= TurtleRules.SNAP_OUT_STICK_DEG)) {
                snapped = false
                TurtleLog.i("SNAP off d=${TurtleLog.f(d)} stick=${TurtleLog.f(stickTurnDeg, 0)}")
            }
            if (snapped) {
                pose = frame.pose
                placingYaw = frame.pose.yaw
            }
        } else {
            st?.group?.setShown(false)
            if (snapped) TurtleLog.i("SNAP off (no wall)")
            snapped = false
        }
        livePose = pose
        liveSize = size
        // the trigger on the floor places the field
        val trigger = (sample?.pressed == true) || (sample?.trigger ?: 0f) > 0.6f
        if (trigger && !lastTrigger && hit != null) {
            TurtleLog.i("PLACE trigger at=${TurtleLog.pos(hit)}")
            sink(TurtleEvent.FieldFollow(pose, size, head.position.y, check(pose, half, state), snapped))
            sink(TurtleEvent.PlaceHereClicked)
        }
        if (now - lastPlacingSentMs >= PLACING_EMIT_MS) {
            lastPlacingSentMs = now
            sink(TurtleEvent.FieldFollow(pose, size, head.position.y, check(pose, half, state), snapped))
        }
    }

    private fun check(pose: FloorPose, half: Float, state: TurtleUiState): PlaceCheck =
        if (state.hasFloor) placeRule.check(pose, half, planeTracker.all, state.floorY) else PlaceCheck.CLEAR

    /**
     * The placed field moves (design `rules.canvasMove`, taste B17 / D14): trigger held with the ray on the field
     * drags it along the ray's floor point; the stick's x turns it about its centre (90 degrees a second), its y
     * scales the field between 1 and 2 m (the routes scale with it, the turtle does not). The code panel stays.
     */
    private fun moveCanvas(state: TurtleUiState, sample: ControllerSample?, stick: Pair<Float, Float>, now: Long, dt: Float) {
        var pose = livePose ?: state.field ?: return
        var size = liveSize
        val trigger = (sample?.pressed == true) || (sample?.trigger ?: 0f) > 0.6f
        val hit = rayOnFloor(sample, state.floorY)
        var active = false
        if (trigger && !lastTrigger && hit != null) {
            val (x, y) = pose.toLocal(hit)
            if (abs(x) <= size / 2f + 0.2f && abs(y) <= size / 2f + 0.2f) {
                dragOffset = pose.center - hit
                TurtleLog.i("CANVAS drag start at=${TurtleLog.pos(hit)}")
            }
        }
        if (!trigger) dragOffset = null
        val off = dragOffset
        if (off != null && hit != null) {
            val c = hit + off
            pose = pose.copy(center = Vec3(c.x, state.floorY, c.z))
            active = true
        } else if (off != null) {
            active = true
        }
        if (stick.first != 0f) {
            pose = pose.copy(yaw = pose.yaw - Math.toRadians((stick.first * TurtleRules.STICK_DEG_S * dt).toDouble()).toFloat())
            active = true
        }
        if (stick.second != 0f) {
            size = (size + stick.second * TurtleRules.STICK_SCALE_M_S * dt).coerceIn(TurtleRules.FIELD_MIN_M, TurtleRules.FIELD_MAX_M)
            active = true
        }
        if (active) {
            livePose = pose
            liveSize = size
            if (!moving) TurtleLog.i("CANVAS move start")
            moving = true
            if (now - lastMovedSentMs >= PLACING_EMIT_MS) {
                lastMovedSentMs = now
                sink(TurtleEvent.FieldMoved(pose, size, true))
            }
        } else if (moving) {
            moving = false
            TurtleLog.i("CANVAS move end center=${TurtleLog.pos(pose.center)} size=${TurtleLog.f(size)}")
            sink(TurtleEvent.FieldMoved(pose, size, false))
        }
    }

    // ---------------------------------------------------------------- the run

    private fun startRun(state: TurtleUiState) {
        val run = state.run ?: return
        runSerial = run.serial
        val exec = Interpreter.run(state.program, state.lesson.start)
        runExec = exec
        shownExec = exec
        runSpeed = run.speed
        timeline = Timeline(exec, run.speed.stepsPerS, run.speed.degPerS)
        runT = 0f
        val tl = timeline!!
        runStopT = exec.exit?.let { (i, s) -> tl.timeAt(i, s) } ?: tl.total
        runFinished = false
        ring?.reset()
        inkBars?.hideFrom(0)
        inkExec = null
        wedges?.hideFrom(0)
        clearTags()
        TurtleLog.i("RUN timeline total=${TurtleLog.f(tl.total)}s stop=${TurtleLog.f(runStopT)}s moves=${exec.moves.size}")
    }

    private fun changeSpeed(speed: RunSpeed) {
        val exec = runExec ?: return
        val old = timeline ?: return
        val f = old.at(runT)
        val tl = Timeline(exec, speed.stepsPerS, speed.degPerS)
        timeline = tl
        runSpeed = speed
        runT = tl.timeAt(f.moveIndex, f.moveShare)
        runStopT = exec.exit?.let { (i, s) -> tl.timeAt(i, s) } ?: tl.total
    }

    private fun stepRun(state: TurtleUiState, now: Long, dt: Float) {
        val exec = runExec ?: return
        val tl = timeline ?: return
        val run = state.run ?: return
        if (run.instant) runT = runStopT
        val limit = run.runToSegment?.let { Timeline.segmentEnd(tl, it) } ?: runStopT
        runT = min(runT + dt, min(limit, runStopT))
        val f = tl.at(runT)
        val step = liveSize / TurtleRules.FIELD_STEPS
        drawInk(exec, f.moveIndex, f.moveShare, step, false)
        drawCorners(exec, f.moveIndex, f.moveShare, step, true)
        turtle?.place(f.at, f.heading, step)
        val (x, z) = f.at.local(step)
        ring?.place(x, z, step)
        ring?.show(f.turned, now)
        if (now - lastRunSentMs >= RUN_EMIT_MS) {
            lastRunSentMs = now
            sink(TurtleEvent.RunProgress(run.serial, f.blockId, f.turned))
        }
        if (runT >= runStopT - 1e-4f && run.runToSegment == null && !runFinished) {
            runFinished = true
            sink(TurtleEvent.RunProgress(run.serial, null, exec.turnTotal))
            sink(TurtleEvent.RunFinished(run.serial))
        }
    }

    // ---------------------------------------------------------------- your level heading

    /** Your heading in field degrees (0 = the field's front, right positive), or null before a head pose. */
    private fun yourHeading(pose: FloorPose?): Float? {
        if (!hasHead) return null
        val yaw = FloorPose.yawOf(head.forward, Float.NaN)
        if (yaw.isNaN()) return null
        val base = pose?.yaw ?: 0f
        return -Math.toDegrees(FloorPose.wrapRad(yaw - base).toDouble()).toFloat() + debugYawDeg
    }

    /** Accumulates your turn from the level head heading: 0.2 s low pass, frame-by-frame wrapped increments. */
    private fun trackYourTurn(state: TurtleUiState, dt: Float) {
        if (state.lost) return
        val h = yourHeading(livePose) ?: return
        if (youYawRaw.isNaN()) {
            youYawRaw = h
            youYawFiltered = h
            return
        }
        // unwrap
        val raw = youYawRaw + wrapDeg(h - wrapDeg(youYawRaw))
        youYawRaw = raw
        val k = (dt / TurtleRules.TURN_LOWPASS_S).coerceIn(0f, 1f)
        val before = youYawFiltered
        youYawFiltered += (raw - youYawFiltered) * k
        youTurn += youYawFiltered - before
    }

    /** Your floor point in field steps. */
    private fun yourPoint(pose: FloorPose, step: Float): P2 {
        val (x, y) = pose.toLocal(Vec3(head.position.x, pose.center.y, head.position.z))
        return P2(x / step, y / step)
    }

    // ---------------------------------------------------------------- turning along

    /**
     * The turtle replays the program (or walks the target while guessing) and waits at each corner until you
     * have turned on the spot (design `rules.turnAlong`, `rules.guessTurn`).
     */
    private inner class TurnSession(val mode: TurnMode, val exec: Execution, val serial: Int, val synthetic: String?) {
        val tl = Timeline(exec, 0.8f, 120f)
        val corners = exec.corners
        var t = 0f
        var corner = 0
        var waiting = false
        var turning = false
        var entry = 0f
        var heldSince = Long.MIN_VALUE
        var confirmsSeen = -1
        var pendingConfirm = false
        var done = false
        var synthYou = 0f
        var turtleSum = 0f
        var lastSent = 0L

        fun confirms(n: Int) {
            if (confirmsSeen < 0) confirmsSeen = n
            if (n > confirmsSeen) {
                confirmsSeen = n
                if (waiting) pendingConfirm = true
            }
        }
    }

    private fun startTurn(state: TurtleUiState) {
        val turn = state.turn ?: return
        turnSerial = turn.serial
        val program = if (turn.mode == TurnMode.GUESS) state.lesson.solution else state.program
        val exec = Interpreter.run(program, state.lesson.start)
        val s = TurnSession(turn.mode, exec, turn.serial, turn.synthetic)
        s.confirms(turn.confirms)
        turnSession = s
        youTurn = 0f
        ring?.reset()
        if (turn.mode == TurnMode.ALONG) shownExec = exec
        if (s.synthetic == "hold" && s.corners.isNotEmpty()) {
            // a still picture for QA: the turtle waits at the third corner (or the last), you have turned half of it
            val c = min(2, s.corners.size - 1)
            s.corner = c
            s.t = s.tl.startOf(s.corners[c].moveIndex)
            s.turtleSum = s.corners.take(c).sumOf { it.by.toDouble() }.toFloat()
            youTurn = s.turtleSum + s.corners[c].by * 0.5f
            s.entry = s.turtleSum
            s.waiting = true
        }
        TurtleLog.i("TURN session mode=${turn.mode.tag} corners=${s.corners.size} synthetic=${s.synthetic}")
    }

    private fun stepTurn(state: TurtleUiState, now: Long, dt: Float) {
        val s = turnSession ?: return
        val pose = livePose ?: return
        val step = liveSize / TurtleRules.FIELD_STEPS
        val m = materials ?: return
        if (state.phase == Phase.TURN_ALONG && !s.done) {
            val hold = s.synthetic == "hold"
            if (s.waiting) {
                val c = s.corners[s.corner]
                val goal = s.entry + c.by
                // synthetic turners (design qaHooks turn_along perfect | lazy)
                when (s.synthetic) {
                    "perfect", "lazy" -> {
                        val aim = if (s.synthetic == "lazy") goal - 12f * kotlin.math.sign(c.by) else goal
                        val d = aim - youTurn
                        youTurn += kotlin.math.sign(d) * min(abs(d), 120f * dt)
                    }
                }
                val within = s.mode == TurnMode.ALONG && abs(youTurn - goal) <= TurtleRules.TURN_MATCH_DEG && !state.lost
                if (within) {
                    if (s.heldSince == Long.MIN_VALUE) s.heldSince = now
                } else {
                    s.heldSince = Long.MIN_VALUE
                }
                val heldLongEnough = s.heldSince != Long.MIN_VALUE && now - s.heldSince >= TurtleRules.TURN_HOLD_MS
                val guessDone = s.mode == TurnMode.GUESS && s.synthetic == "perfect" && abs(youTurn - goal) < 1f
                if (!hold && (s.pendingConfirm || heldLongEnough || guessDone)) {
                    val yours = youTurn - s.entry
                    sink(TurtleEvent.TurnCornerDone(s.serial, s.corner, yours))
                    s.pendingConfirm = false
                    s.waiting = false
                    s.turning = true
                    s.heldSince = Long.MIN_VALUE
                    s.turtleSum += c.by
                }
                // arrows at your feet: the new heading (along) or your own heading (guess)
                val you = yourPoint(pose, step)
                val dirDeg = if (s.mode == TurnMode.ALONG) goal else youTurn
                val a = you + P2.heading(dirDeg) * (0.15f / step)
                val b = you + P2.heading(dirDeg) * (0.55f / step)
                val (ax, az) = a.local(step)
                val (bx, bz) = b.local(step)
                turnArrow?.draw(ax, az, bx, bz, if (s.mode == TurnMode.ALONG) 0.03f else 0.012f, if (s.mode == TurnMode.ALONG) m.arrow else m.arrowYou)
            } else {
                turnArrow?.hide()
                if (!hold) s.t += dt
                // stop at the next corner
                val next = s.corners.getOrNull(s.corner)
                if (next != null) {
                    val startT = s.tl.startOf(next.moveIndex)
                    val endT = s.tl.startOf(next.moveIndex + next.moveCount)
                    if (!s.turning && s.t >= startT) {
                        s.t = startT
                        s.waiting = true
                        s.entry = youTurn
                        if (s.mode == TurnMode.GUESS) s.entry = youTurn
                    } else if (s.turning && s.t >= endT) {
                        s.turning = false
                        s.corner++
                    }
                } else if (s.t >= s.tl.total) {
                    s.t = s.tl.total
                    s.done = true
                    sink(TurtleEvent.TurnFinished(s.serial))
                }
            }
            val f = s.tl.at(s.t)
            turtle?.place(f.at, f.heading, step)
            if (now - s.lastSent >= RUN_EMIT_MS) {
                s.lastSent = now
                sink(TurtleEvent.TurnProgress(s.serial, min(s.corner, s.corners.size), youTurn, s.waiting, f.blockId))
            }
            if (s.mode == TurnMode.GUESS) {
                drawCorners(s.exec, f.moveIndex, f.moveShare, step, true)
            }
        }
        // you and your ring
        val you = yourPoint(pose, step)
        val (x, z) = you.local(step)
        youDot?.setLocal(position = Vec3(x, 0.005f, z))
        youRing?.setLocal(position = Vec3(x, 0.006f, z))
        ring?.place(x, z, step)
        ring?.show(youTurn, now)
    }

    // ---------------------------------------------------------------- walking along (optional)

    private class Sample(val p: P2, val s: Float)

    private inner class WalkSession(val exec: Execution, val serial: Int, val synthetic: String?, val step: Float, val closed: Boolean) {
        val segs = exec.forwards
        val cum = FloatArray(segs.size + 1).also { a -> for (i in segs.indices) a[i + 1] = a[i] + segs[i].length }
        val total = cum.last()
        var progress = 0f
        var lastSampleMs = 0L
        val samples = ArrayList<Sample>()
        val trail = ArrayList<P2>()
        var offSince = Long.MIN_VALUE
        var offPath = false
        var atEnd = false
        var homeSince = Long.MIN_VALUE
        var homeYaw = 0f
        var synthT = 0f
        var posture: P2? = null
        val postureSum = ArrayList<P2>()
        var startMs = 0L
        var tangentYou = 0f
        var lastTanPoint: P2? = null
        var lastTanDir: Float? = null
        var finished = false

        fun pointAt(s: Float): Pair<P2, Float> {
            val c = s.coerceIn(0f, total)
            for (i in segs.indices) {
                if (c <= cum[i + 1] + 1e-5f || i == segs.size - 1) {
                    val f = segs[i]
                    val share = if (f.length < 1e-6f) 0f else ((c - cum[i]) / f.length).coerceIn(0f, 1f)
                    return f.from.lerp(f.to, share) to f.heading
                }
            }
            return exec.end.at to exec.end.heading
        }

        fun segmentOf(s: Float): Int {
            for (i in segs.indices) if (s < cum[i + 1]) return i
            return segs.size - 1
        }

        /** Arc length of the nearest route point within [lo, hi], and its distance. */
        fun project(p: P2, lo: Float, hi: Float): Pair<Float, Float> {
            var best = lo
            var bestD = Float.MAX_VALUE
            for (i in segs.indices) {
                val a0 = cum[i]
                val a1 = cum[i + 1]
                if (a1 < lo || a0 > hi) continue
                val f = segs[i]
                val d = f.to - f.from
                val l2 = d.dot(d)
                var t = if (l2 < 1e-9f) 0f else ((p - f.from).dot(d) / l2).coerceIn(0f, 1f)
                var s = a0 + t * f.length
                if (s < lo) s = lo
                if (s > hi) s = hi
                t = if (f.length < 1e-6f) 0f else ((s - a0) / f.length).coerceIn(0f, 1f)
                val q = f.from.lerp(f.to, t)
                val dist = q.distanceTo(p)
                if (dist < bestD) {
                    bestD = dist
                    best = s
                }
            }
            return best to bestD
        }

        fun distToRoute(p: P2): Float = segs.minOfOrNull { io.embodit.turtlewalk.domain.usecase.CheckUseCase.distToSegment(p, it.from, it.to) } ?: 0f
    }

    private fun startWalk(state: TurtleUiState) {
        val w = state.walk ?: return
        walkSerial = w.serial
        val exec = Interpreter.run(state.program, state.lesson.start)
        val closed = exec.end.at.distanceTo(exec.start.at) <= TurtleRules.CLOSE_STEPS
        val step = liveSize / TurtleRules.FIELD_STEPS
        walk = WalkSession(exec, w.serial, w.synthetic, step, closed).also { it.startMs = SystemClock.elapsedRealtime() }
        youTurn = 0f
        ring?.reset()
        trailBars?.hideFrom(0)
        TurtleLog.i("WALK session length=${TurtleLog.f(walk!!.total)} steps closed=$closed synthetic=${w.synthetic}")
    }

    /** A synthetic walker's floor point (steps) and heading at route length [s] (design qaHooks walk_*). */
    private fun syntheticPoint(w: WalkSession, s: Float): Pair<P2, Float> {
        val (p, h) = w.pointAt(s)
        return when (w.synthetic) {
            "noisy" -> {
                noise = noise * 1103515245 + 12345
                val nx = (((noise ushr 8) and 0xFFFF) / 65535f - 0.5f) * 0.16f / w.step
                noise = noise * 1103515245 + 12345
                val ny = (((noise ushr 8) and 0xFFFF) / 65535f - 0.5f) * 0.16f / w.step
                P2(p.x + nx, p.y + ny) to h
            }
            "cut" -> {
                // cut each corner by 0.3 step: near a corner walk the chord between the two points 0.3 step away
                val i = w.segmentOf(s)
                val toEnd = w.cum[i + 1] - s
                val fromStart = s - w.cum[i]
                if (toEnd < 0.3f && i + 1 < w.segs.size) {
                    val a = w.pointAt(w.cum[i + 1] - 0.3f).first
                    val b = w.pointAt(w.cum[i + 1] + 0.3f).first
                    a.lerp(b, (0.3f - toEnd) / 0.6f) to h
                } else if (fromStart < 0.3f && i > 0) {
                    val a = w.pointAt(w.cum[i] - 0.3f).first
                    val b = w.pointAt(w.cum[i] + 0.3f).first
                    a.lerp(b, (0.3f + fromStart) / 0.6f) to h
                } else p to h
            }
            "overshoot" -> {
                // at each corner go 0.2 m past it and come back
                val i = w.segmentOf(s)
                val over = 0.2f / w.step
                val toEnd = w.cum[i + 1] - s
                if (toEnd < over && i + 1 < w.segs.size) {
                    val f = w.segs[i]
                    val corner = f.to
                    val past = corner + P2.heading(f.heading) * (over - toEnd)
                    past to h
                } else p to h
            }
            "off" -> if (s > w.total * 0.3f && s < w.total * 0.5f) (p + P2.heading(h + 90f) * (0.6f / w.step)) to h else p to h
            else -> p to h
        }
    }

    private var noise = 12345

    private fun stepWalk(state: TurtleUiState, now: Long, dt: Float) {
        val pose = livePose ?: return
        val step = liveSize / TurtleRules.FIELD_STEPS
        val m = materials ?: return
        val lesson = state.lesson
        if (state.phase == Phase.WALK_READY) {
            val you = yourPoint(pose, step)
            val d = you.distanceTo(lesson.start.at) * step
            val h = yourHeading(pose) ?: 0f
            val facing = abs(wrapDeg(h - lesson.start.heading)) <= 30f
            val (x, z) = you.local(step)
            youDot?.setLocal(position = Vec3(x, 0.005f, z))
            youRing?.setLocal(position = Vec3(x, 0.006f, z))
            ring?.place(x, z, step)
            ring?.show(0f, now)
            if (now - lastWalkSentMs >= 200L) {
                lastWalkSentMs = now
                sink(TurtleEvent.WalkReadyStatus(d, facing))
            }
            return
        }
        val w = walk ?: return
        if (state.phase == Phase.WALK_DONE) return
        val holdAt = when (w.synthetic) {
            "hold" -> w.total * 0.6f
            "turn_home_hold" -> w.total
            else -> null
        }
        // where you stand (synthetic walkers fill the 100 ms samples the idle emulator's 1 fps would miss)
        val points = ArrayList<P2>()
        if (w.synthetic != null) {
            if (!state.lost && !w.atEnd) {
                val speed = 0.5f / step
                val from = w.synthT
                w.synthT = min(w.total, w.synthT + speed * dt)
                if (holdAt != null) w.synthT = min(w.synthT, holdAt)
                var s = from
                while (s < w.synthT) {
                    s = min(w.synthT, s + speed * 0.1f)
                    points += syntheticPoint(w, s).first
                }
            }
            if (points.isEmpty()) points += syntheticPoint(w, w.synthT).first
        } else if (!state.lost) {
            points += yourPoint(pose, step)
        }
        if (state.phase == Phase.WALKING && !state.lost) {
            for (raw in points) {
                // posture: the first second's mean offset from the start, at most 0.12 m
                if (now - w.startMs < 1000L && w.synthetic == null) {
                    w.postureSum += raw - lesson.start.at
                    val mean = w.postureSum.fold(P2.ZERO) { acc, q -> acc + q } * (1f / w.postureSum.size)
                    val cap = 0.12f / step
                    w.posture = if (mean.length > cap) mean * (cap / mean.length) else mean
                }
                val p = raw - (w.posture ?: P2.ZERO)
                val window = 0.6f + 1.2f / step * dt
                val (s, dist) = w.project(p, max(0f, w.progress - 0.3f), w.progress + window)
                w.progress = max(w.progress, s)
                if (w.trail.isEmpty() || w.trail.last().distanceTo(p) * step >= 0.05f) {
                    w.trail += p
                    w.samples += Sample(p, w.progress)
                }
                // tangent turn: resample every 0.1 step, direction over 0.3 step
                val tp = w.lastTanPoint
                if (tp == null) w.lastTanPoint = p
                else if (tp.distanceTo(p) >= 0.1f) {
                    val back = w.trail.lastOrNull { it.distanceTo(p) >= 0.3f } ?: tp
                    val dir = Math.toDegrees(atan2((p.x - back.x).toDouble(), (p.y - back.y).toDouble())).toFloat()
                    val last = w.lastTanDir
                    if (last != null) w.tangentYou += wrapDeg(dir - last)
                    w.lastTanDir = dir
                    w.lastTanPoint = p
                }
                // off the path
                val off = dist > 0.4f
                if (off) {
                    if (w.offSince == Long.MIN_VALUE) w.offSince = now
                    w.offPath = now - w.offSince >= 1500L
                } else {
                    w.offSince = Long.MIN_VALUE
                    w.offPath = false
                }
            }
            youTurn = w.tangentYou
            // the end
            val last = points.lastOrNull()
            if (!w.atEnd && last != null && w.progress >= w.total - 0.15f && last.distanceTo(w.exec.end.at) <= 0.25f) {
                w.atEnd = true
                val finalTurn = w.closed && w.exec.moves.lastOrNull() is Move.Turn
                TurtleLog.i("WALK at end progress=${TurtleLog.f(w.progress)} closedTurn=$finalTurn")
                sink(TurtleEvent.WalkAtEnd(w.serial, finalTurn))
                if (!finalTurn) finishWalk(w, null)
            }
        }
        if (state.phase == Phase.TURN_HOME && !state.lost && !w.finished) {
            // the last turn: your head heading within 20 degrees of the start heading for 0.5 s
            val h = if (w.synthetic != null) { if (w.synthetic == "turn_home_hold") null else synthHome(w, dt) } else yourHeading(pose)
            if (h != null) {
                val within = abs(wrapDeg(h - lesson.start.heading)) <= 20f
                if (within) {
                    if (w.homeSince == Long.MIN_VALUE) w.homeSince = now
                    if (now - w.homeSince >= 500L) finishWalk(w, h)
                } else {
                    w.homeSince = Long.MIN_VALUE
                }
            }
        }
        // draw you, your trail, the ring and the guide arrows
        val you = (if (w.synthetic != null) syntheticPoint(w, w.synthT).first else yourPoint(pose, step))
        val (x, z) = you.local(step)
        youDot?.setLocal(position = Vec3(x, 0.005f, z))
        youRing?.setLocal(position = Vec3(x, 0.006f, z))
        ring?.place(x, z, step)
        ring?.show(youTurn, now)
        drawTrail(w, step)
        if (state.phase == Phase.WALKING && (w.offPath || state.offPath)) {
            val (q, _) = w.project(you, 0f, w.total)
            val target = w.pointAt(q).first
            val dir = target - you
            val len = (dir.length * step).coerceIn(0.3f, 1f) / step
            val b = you + dir * (len / max(dir.length, 1e-4f))
            val (ax, az) = you.local(step)
            val (bx, bz) = b.local(step)
            guideArrow?.draw(ax, az, bx, bz, 0.03f, m.arrow)
        } else {
            guideArrow?.hide()
        }
        if (state.phase == Phase.TURN_HOME) {
            val a = you + P2.heading(lesson.start.heading) * (0.15f / step)
            val b = you + P2.heading(lesson.start.heading) * (0.55f / step)
            val (ax, az) = a.local(step)
            val (bx, bz) = b.local(step)
            homeArrow?.draw(ax, az, bx, bz, 0.035f, m.arrow)
        }
        if (now - lastWalkSentMs >= RUN_EMIT_MS) {
            lastWalkSentMs = now
            val seg = w.segmentOf(w.progress)
            sink(TurtleEvent.WalkProgress(w.serial, seg, youTurn, w.offPath, w.segs.getOrNull(seg)?.blockId))
        }
    }

    /** The synthetic walker turns its head to the start heading at 90 degrees a second. */
    private fun synthHome(w: WalkSession, dt: Float): Float {
        val lastHeading = w.segs.lastOrNull()?.heading ?: 0f
        if (w.homeYaw == 0f) w.homeYaw = lastHeading
        val goal = ui.lesson.start.heading
        val d = wrapDeg(goal - w.homeYaw)
        w.homeYaw += kotlin.math.sign(d) * min(abs(d), 90f * dt)
        return w.homeYaw
    }

    private fun drawTrail(w: WalkSession, step: Float) {
        val bars = trailBars ?: return
        val pts = w.trail
        if (pts.size < 2) return
        val stride = max(1, (pts.size + bars.size - 1) / bars.size)
        var k = 0
        var i = 0
        while (i + stride < pts.size && k < bars.size) {
            val (ax, az) = pts[i].local(step)
            val (bx, bz) = pts[i + stride].local(step)
            bars.bar(k++, ax, az, bx, bz, 0.015f, 0.002f, 0.003f)
            i += stride
        }
        bars.hideFrom(k)
    }

    /**
     * The walk ends (design `rules.yourTurn`, `rules.score`): your turn at each corner from the trail's heading in
     * the middle of the segments before and after it (disambiguated around the turtle's turn), plus the last turn;
     * lessons with short segments use the tangent sum. The mean deviation is the trail's mean distance to the route.
     */
    private fun finishWalk(w: WalkSession, homeHeading: Float?) {
        if (w.finished) return
        w.finished = true
        val corners = w.exec.corners
        val short = w.segs.any { it.length < 0.5f }
        val cornerDegs = ArrayList<Float>()
        var you: Float
        fun midHeading(i: Int): Float? {
            val a0 = w.cum[i] + w.segs[i].length * 0.25f
            val a1 = w.cum[i] + w.segs[i].length * 0.75f
            val inside = w.samples.filter { it.s in a0..a1 }
            if (inside.size < 2) return null
            val d = inside.last().p - inside.first().p
            if (d.length < 1e-4f) return null
            return Math.toDegrees(atan2(d.x.toDouble(), d.y.toDouble())).toFloat()
        }
        if (!short) {
            you = 0f
            for (c in corners) {
                // the forward segments before and after this corner
                val before = w.exec.moves.subList(0, c.moveIndex).count { it is Move.Forward } - 1
                val after = before + 1
                if (after >= w.segs.size) continue
                val h0 = midHeading(before) ?: w.segs[before].heading
                val h1 = midHeading(after) ?: w.segs[after].heading
                val measured = c.by + wrapDeg(wrapDeg(h1 - h0) - c.by)
                cornerDegs += measured
                you += measured
            }
        } else {
            you = w.tangentYou
        }
        // the last turn at the end of a closed route
        val last = corners.lastOrNull()
        if (homeHeading != null && last != null && w.exec.moves.lastOrNull() is Move.Turn) {
            val y0 = midHeading(w.segs.size - 1) ?: w.segs.last().heading
            val measured = last.by + wrapDeg(wrapDeg(homeHeading - y0) - last.by)
            if (!short) cornerDegs += measured
            you += measured
            ring?.flash(SystemClock.elapsedRealtime())
        }
        youTurn = you
        val dev = if (w.samples.isEmpty()) 0f else w.samples.map { w.distToRoute(it.p) }.average().toFloat() * w.step
        TurtleLog.i("WALK done you=${TurtleLog.f(you, 0)} dev=${TurtleLog.f(dev, 3)} samples=${w.samples.size}")
        sink(TurtleEvent.WalkFinished(w.serial, you, dev, cornerDegs))
    }

    // ---------------------------------------------------------------- planes, tracking, fps

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val planes = planeTracker.all
        val hasFloor = planes.any { it.kind == SurfaceKind.FLOOR }
        if (count == lastPlaneCount && hasFloor == lastFloor) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastFloor = hasFloor
        val floorY = planes.filter { it.kind == SurfaceKind.FLOOR }.map { it.centroid.y }.minOrNull() ?: 0f
        TurtleLog.i("PLANES n=$count floor=$hasFloor floorY=${TurtleLog.f(floorY)}")
        sink(TurtleEvent.PlanesChanged(count, hasFloor, floorY))
    }

    private fun pollTracking(now: Long) {
        val block = headTracker.blockReason()
        if (block != lastBlock) {
            lastBlock = block
            sink(TurtleEvent.Blocked(block))
        }
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(TurtleEvent.TrackingChanged(lost))
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        frames++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            TurtleLog.i("FPS app=${frames * 1000f / (now - fpsStartMs)} frames=$frames ms=${now - fpsStartMs}")
            frames = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer: a pinch on the floor places the field; a pinch elsewhere closes the menu
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        TurtleLog.i("TAP target=$name kind=${value.interactionKind}")
        if (ui.menuOpen && target != null && !name.startsWith("panel:") && !name.startsWith("holder:")) sink(TurtleEvent.MenuDismissed)
        if (ui.phase == Phase.PLACING && (name == "floorpad" || name.startsWith("plane:FLOOR"))) {
            val at = toStage(value.position.x, value.position.y, value.position.z) ?: return
            sink(TurtleEvent.FloorPinched(at))
        }
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { TurtleLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${TurtleLog.pos5(head.position)} ${TurtleLog.quat5(head.rotation)} fwd=${TurtleLog.pos5(head.forward)} valid=$hasHead you=${TurtleLog.f(youTurn, 1)} field=${livePose?.let { TurtleLog.pos(it.center) }} size=${TurtleLog.f(liveSize)}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, TurtleLog.pos5(slot.position), TurtleLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, TurtleLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${TurtleLog.pos(p.centroid)} normal=${TurtleLog.pos(p.normal)} verts=${p.vertices.size}" }

    override fun parkPanel(): String {
        codeFollower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW code park requested"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        frames = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    override fun stick(x: Float, y: Float, ms: Long): String {
        debugStick = x to y
        debugStickUntil = SystemClock.elapsedRealtime() + ms
        return "STICK x=${TurtleLog.f(x)} y=${TurtleLog.f(y)} ms=$ms"
    }

    override fun canvas(kind: String, a: Float, b: Float): String {
        val s = ui
        val pose = livePose ?: return "CANVAS no field"
        if (s.phase == Phase.PLACING) return "CANVAS placing: use stick"
        var p = pose
        var size = liveSize
        when (kind) {
            "move" -> p = p.copy(center = p.center + p.right * a + p.forward * b)
            "rot" -> p = p.copy(yaw = p.yaw - Math.toRadians(a.toDouble()).toFloat())
            "scale" -> size = a.coerceIn(TurtleRules.FIELD_MIN_M, TurtleRules.FIELD_MAX_M)
            else -> return "CANVAS unknown $kind"
        }
        livePose = p
        liveSize = size
        sink(TurtleEvent.FieldMoved(p, size, false))
        return "CANVAS $kind center=${TurtleLog.pos(p.center)} yaw=${deg(p.yaw)} size=${TurtleLog.f(size)}"
    }

    override fun turnBy(deg: Float): String {
        debugYawDeg += deg
        return "TURN debug yaw offset=${TurtleLog.f(debugYawDeg, 0)}"
    }

    override fun walkTo(steps: Float): String {
        val w = walk ?: return "WALK no session"
        w.synthT = steps.coerceIn(0f, w.total)
        return "WALK synthetic at ${TurtleLog.f(w.synthT)} / ${TurtleLog.f(w.total)}"
    }

    private var debugPanelNear = false

    override fun panelNear(on: Boolean): String {
        debugPanelNear = on
        if (on) codeFollower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "PANEL near=$on"
    }

    override fun snap(): String {
        val pose = livePose ?: return "SNAP no field"
        val frame = placeRule.snapFrame(pose, liveSize / 2f, planeTracker.all) ?: return "SNAP no wall frame"
        placingCenter = frame.pose.center
        placingTarget = frame.pose.center
        placingYaw = frame.pose.yaw
        return "SNAP to ${TurtleLog.pos(frame.pose.center)} yaw=${deg(frame.pose.yaw)}"
    }

    private fun deg(rad: Float) = String.format(Locale.US, "%.1f", Math.toDegrees(rad.toDouble()))

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val HEAD_SAMPLE_MS = 100L
        private const val PLACING_EMIT_MS = 100L
        private const val RUN_EMIT_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val SNACK_GAP_M = 0.04f
        private const val COACH_GAP_M = 0.03f
        private const val TAG_LIFT_M = 0.15f
        private val CANVAS_PHASES = setOf(Phase.CODING, Phase.RUNNING, Phase.DRAWN, Phase.TURN_ALONG, Phase.TURN_DONE, Phase.LESSONS)

        /** Half sizes of the code panel (0.787 x 0.538 m) with a little air, for the surface rule. */
        private val CODE_HALF_EXTENTS = listOf(0.41f, 0.28f, 0.01f)
    }
}
