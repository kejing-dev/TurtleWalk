package io.embodit.turtlewalk.domain.model

import kotlin.math.abs

/** Why spatial features cannot be used (design `states.blocked`). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** The four blocks (design `lessons[].blocks`): forward (steps), turn left / right (degrees), repeat (times). */
enum class BlockKind(val tag: String) {
    FORWARD("F"),
    LEFT("L"),
    RIGHT("R"),
    REPEAT("X");

    /** The value a fresh block of this kind gets (design `rules.editing`: forward 1, turn 90, repeat 4). */
    val defaultValue: Float
        get() =
            when (this) {
                FORWARD -> 1f
                LEFT, RIGHT -> 90f
                REPEAT -> 4f
            }

    /** The value chips of the value bar (design `units.values`). */
    val values: List<Float>
        get() =
            when (this) {
                FORWARD -> TurtleRules.FORWARD_VALUES
                LEFT, RIGHT -> TurtleRules.TURN_VALUES
                REPEAT -> TurtleRules.REPEAT_VALUES
            }
}

/**
 * One block of a program. [id] is unique within a program (selection and the run highlight use it);
 * [value] = steps / degrees / times; [body] only for repeat blocks.
 */
data class Block(val id: Int, val kind: BlockKind, val value: Float, val body: List<Block> = emptyList()) {
    val isTurn: Boolean
        get() = kind == BlockKind.LEFT || kind == BlockKind.RIGHT

    /** Signed turn in degrees: right is positive (clockwise seen from above). */
    val signedTurn: Float
        get() =
            when (kind) {
                BlockKind.RIGHT -> value
                BlockKind.LEFT -> -value
                else -> 0f
            }
}

/** A point of the field in steps: origin = field centre, +x = right, +y = "front" (towards the code panel). */
data class P2(val x: Float, val y: Float) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)

    operator fun minus(o: P2) = P2(x - o.x, y - o.y)

    operator fun times(k: Float) = P2(x * k, y * k)

    fun dot(o: P2) = x * o.x + y * o.y

    val length: Float
        get() = kotlin.math.hypot(x, y)

    fun distanceTo(o: P2) = (this - o).length

    fun lerp(o: P2, t: Float) = P2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object {
        val ZERO = P2(0f, 0f)

        /** Unit direction of a turtle heading (degrees, 0 = front, right turns positive). */
        fun heading(deg: Float): P2 {
            val r = Math.toRadians(deg.toDouble())
            return P2(kotlin.math.sin(r).toFloat(), kotlin.math.cos(r).toFloat())
        }
    }
}

/** Where the turtle starts: a field point and a heading. */
data class TurtlePose(val at: P2, val heading: Float)

/** One lesson (design `lessons[]`). The target is the reference solution drawn from [start]. */
data class Lesson(
    val n: Int,
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val blocks: List<BlockKind>,
    val start: TurtlePose,
    val solution: List<Block>,
    val insightZh: String,
    val insightEn: String,
    val hint1Zh: String,
    val hint1En: String,
    /** Lesson 4: at most this many blocks (repeat included). */
    val maxBlocks: Int? = null,
    /** Lesson 5: the 60 degree mistake gets its own hint. */
    val sixtyHint: Boolean = false,
    /** Lesson 9: the walk is 11.25 steps, "you may just watch". */
    val walkOptional: Boolean = false,
    /** Lessons 1 and 2: a star at the end of the line (recommended direction A + B's opening). */
    val star: Boolean = false,
    /** Free mode (taste B21): the goal line of a generated route; the ten lessons use string resources. */
    val goalZh: String? = null,
    val goalEn: String? = null,
) {
    /** Free mode: a route made by rules, not one of the ten lessons. */
    val isFree: Boolean
        get() = n == 0

    fun name(zh: Boolean) = if (zh) nameZh else nameEn

    fun insight(zh: Boolean) = if (zh) insightZh else insightEn

    fun hint1(zh: Boolean) = if (zh) hint1Zh else hint1En
}

/** The verdict of a drawing (design `rules.check`). */
enum class Verdict(val tag: String) {
    MATCH("match"),
    OPEN("open"),
    SPIN("spin"),
    MIRROR("mirror"),
    TOO_MANY("tooMany"),
    DIFFERENT("different");

    companion object {
        fun fromTag(tag: String?): Verdict? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }
}

/** Run speed (design `rules.run`). */
enum class RunSpeed(val tag: String, val stepsPerS: Float, val degPerS: Float) {
    SLOW("slow", 0.4f, 60f),
    FAST("fast", 1.2f, 180f);

    companion object {
        fun fromTag(tag: String?): RunSpeed = entries.firstOrNull { it.tag == tag } ?: SLOW
    }
}

/** What the app remembers per lesson (design `persistence`). */
data class LessonRecord(
    val drawnRight: Boolean = false,
    val walked: Boolean = false,
    val turned: Boolean = false,
    /** The last program of this lesson in the compact notation (`F1 R90`, `X4[F1 R90]`). */
    val program: String = "",
    val lastWalkVerdict: String? = null,
)

data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    /** Free mode (taste B21): the round under way and its seed (0 = not started), rounds drawn right, the last route (not repeated). */
    val freeRound: Int = 1,
    val freeSeed: Long = 0L,
    val freeDone: Int = 0,
    val freeLast: String = "",
    /** Last field size, metres (2, 1.5 or 1). */
    val fieldM: Float = 2f,
    val lastLesson: Int = 1,
    /** First-run coachmarks seen: 1 = canvas, 2 = blocks, 3 = turn along. */
    val coachSeen: Set<Int> = emptySet(),
    val records: Map<Int, LessonRecord> = emptyMap(),
) {
    fun record(n: Int): LessonRecord = records[n] ?: LessonRecord()
}

/** Every number of the design in one place (design `units`, `rules`, `entities3d`). */
object TurtleRules {
    val FORWARD_VALUES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)
    val TURN_VALUES = listOf(30f, 45f, 60f, 72f, 90f, 120f, 144f, 180f)
    val REPEAT_VALUES = listOf(2f, 3f, 4f, 5f, 6f, 8f, 12f)
    val FIELD_SIZES = listOf(2f, 1.5f, 1f)

    /** The field is always 2 x 2 steps; one step = field / 2. */
    const val FIELD_STEPS = 2f
    const val FIELD_MIN_M = 1f
    const val FIELD_MAX_M = 2f

    /** Free mode: a route must stay this far inside the field (steps) and have at most this many segments. */
    const val FREE_LIMIT_STEPS = 0.95f
    const val FREE_MAX_SEGMENTS = 16

    const val MAX_TOP_BLOCKS = 16
    const val MAX_NESTING = 2
    const val MAX_SEGMENTS = 120

    /** The turtle stops half a step outside the field. */
    const val OUT_OF_FIELD_STEPS = 0.5f

    // check (in steps / degrees)
    const val COVER_STEPS = 0.05f
    const val LENGTH_SLACK = 1.05f
    const val CLOSE_STEPS = 0.05f
    const val HEADING_TOL_DEG = 5f

    // turn along
    const val TURN_MATCH_DEG = 15f
    const val TURN_HOLD_MS = 500L
    const val TURN_LOWPASS_S = 0.2f

    // the field in the room
    const val FIELD_BACK_GAP_M = 0.4f
    const val FIELD_MIN_GAP_M = 0.2f
    const val PANEL_LEAN_DEG = 30f
    const val FRONT_WALL_MIN_M = 0.45f
    const val STICK_DEAD_ZONE = 0.15f
    const val STICK_DEG_S = 90f
    const val STICK_SCALE_M_S = 0.5f
    const val SNAP_WALL_RANGE_M = 1.5f
    const val SNAP_WALL_MIN_LEN_M = 1f
    const val SNAP_WALL_GAP_M = 0.3f
    const val SNAP_WALL_GAP_FRONT_M = 0.5f
    const val SNAP_IN_M = 0.3f
    const val SNAP_IN_DEG = 12f
    const val SNAP_OUT_M = 0.45f
    const val SNAP_OUT_STICK_DEG = 20f

    /** D7: no floor plane this long after the start -> the estimated floor (head height - 1.55 m). */
    const val EST_FLOOR_AFTER_MS = 5000L
    const val EYE_HEIGHT_M = 1.55f
    const val FLOOR_NUDGE_M = 0.02f
    const val FLOOR_NUDGE_MAX_M = 0.3f

    // degree tags and corners
    const val TAGS = 12
    const val WEDGE_PIECE_DEG = 15f
    const val WEDGE_BIG_PIECE_DEG = 20f
    const val WEDGE_MAX_PIECES = 12
    const val WEDGE_RADIUS_STEPS = 0.16f
    const val RING_DIAMETER_STEPS = 0.9f
    const val RING_MIN_DIAMETER_M = 0.45f
    const val RING_PIECES = 72
    const val RING_LAPS_SHOWN = 7

    const val UNDO_MS = 3000L
    const val SNACK_MS = 3000L
}

/** Wraps degrees into (-180, 180]. */
fun wrapDeg(d: Float): Float {
    var a = d % 360f
    if (a <= -180f) a += 360f
    if (a > 180f) a -= 360f
    return a
}

/** Formats a value of a block for display: `1`, `0.25`, `90`. */
fun formatValue(v: Float): String =
    if (abs(v - Math.round(v)) < 1e-4f) Math.round(v).toString()
    else java.lang.String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
