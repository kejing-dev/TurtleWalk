package io.embodit.turtlewalk.domain.usecase

import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.TurtlePose
import io.embodit.turtlewalk.domain.model.TurtleRules
import kotlin.math.abs
import kotlin.math.max

/** One executed step of a program, in field steps / degrees. */
sealed class Move {
    abstract val blockId: Int

    data class Forward(val from: P2, val to: P2, val heading: Float, override val blockId: Int) : Move() {
        val length: Float
            get() = from.distanceTo(to)
    }

    /** [by] is signed: right turns are positive. */
    data class Turn(val at: P2, val from: Float, val by: Float, override val blockId: Int) : Move()
}

/** Consecutive turns with no step between them: one corner (the turtle waits there once while you turn along). */
data class Corner(val at: P2, val from: Float, val by: Float, val moveIndex: Int, val moveCount: Int, val blockIds: List<Int>) {
    val to: Float
        get() = from + by
}

/** A program run from a start pose (design `rules.run`, `rules.check`). */
data class Execution(
    val moves: List<Move>,
    /** Forward segments after expansion. */
    val segmentCount: Int,
    /** More than [TurtleRules.MAX_SEGMENTS] forward segments: the program does not run (+tooLong). */
    val tooLong: Boolean,
    val start: TurtlePose,
    val end: TurtlePose,
) {
    val forwards: List<Move.Forward>
        get() = moves.filterIsInstance<Move.Forward>()

    /** Right turns minus left turns, degrees (design `rules.turnTotal`). */
    val turnTotal: Float
        get() = moves.filterIsInstance<Move.Turn>().sumOf { it.by.toDouble() }.toFloat()

    val corners: List<Corner> by lazy { cornersOf(moves) }

    /**
     * Where the turtle leaves the field by more than half a step: the forward move's index and the share of
     * it that is drawn, or null when it stays inside (design `rules.run`, +outOfField).
     */
    val exit: Pair<Int, Float>? by lazy {
        val lim = TurtleRules.FIELD_STEPS / 2f + TurtleRules.OUT_OF_FIELD_STEPS
        moves.forEachIndexed { i, m ->
            if (m is Move.Forward) {
                val t = leaveAt(m.from, m.to, lim)
                if (t != null) return@lazy i to t
            }
        }
        null
    }

    val outOfField: Boolean
        get() = exit != null

    companion object {
        fun cornersOf(moves: List<Move>): List<Corner> {
            val out = ArrayList<Corner>()
            var i = 0
            while (i < moves.size) {
                val m = moves[i]
                if (m is Move.Turn) {
                    var by = 0f
                    val ids = ArrayList<Int>()
                    var j = i
                    while (j < moves.size && moves[j] is Move.Turn) {
                        val t = moves[j] as Move.Turn
                        by += t.by
                        ids += t.blockId
                        j++
                    }
                    out += Corner(m.at, m.from, by, i, j - i, ids)
                    i = j
                } else {
                    i++
                }
            }
            return out
        }

        /** Parameter in 0..1 where the segment first leaves the square |x|, |y| <= lim, or null. */
        fun leaveAt(a: P2, b: P2, lim: Float): Float? {
            fun inside(p: P2) = abs(p.x) <= lim + 1e-4f && abs(p.y) <= lim + 1e-4f
            if (!inside(a)) return 0f
            if (inside(b)) return null
            var t = 1f
            val d = b - a
            if (d.x > 0f) t = minOf(t, (lim - a.x) / d.x)
            if (d.x < 0f) t = minOf(t, (-lim - a.x) / d.x)
            if (d.y > 0f) t = minOf(t, (lim - a.y) / d.y)
            if (d.y < 0f) t = minOf(t, (-lim - a.y) / d.y)
            return t.coerceIn(0f, 1f)
        }
    }
}

object Interpreter {
    fun run(program: List<Block>, start: TurtlePose): Execution {
        val moves = ArrayList<Move>()
        var at = start.at
        var heading = start.heading
        var segments = 0
        var tooLong = false

        fun exec(list: List<Block>) {
            for (b in list) {
                if (tooLong) return
                when (b.kind) {
                    BlockKind.FORWARD -> {
                        segments++
                        if (segments > TurtleRules.MAX_SEGMENTS) {
                            tooLong = true
                            return
                        }
                        val to = at + P2.heading(heading) * b.value
                        moves += Move.Forward(at, to, heading, b.id)
                        at = to
                    }
                    BlockKind.LEFT, BlockKind.RIGHT -> {
                        moves += Move.Turn(at, heading, b.signedTurn, b.id)
                        heading += b.signedTurn
                    }
                    BlockKind.REPEAT -> repeat(b.value.toInt().coerceAtLeast(0)) { exec(b.body) }
                }
            }
        }
        exec(program)
        // count the segments of a too long program without building them (the message names the number)
        if (tooLong) segments = countSegments(program)
        return Execution(moves, segments, tooLong, start, TurtlePose(at, heading))
    }

    fun countSegments(program: List<Block>): Int =
        program.sumOf { b ->
            when (b.kind) {
                BlockKind.FORWARD -> 1
                BlockKind.REPEAT -> b.value.toInt() * countSegments(b.body)
                else -> 0
            }
        }
}

/**
 * The turtle's timeline at one speed: where it is at time t, how far the ink has got and which block runs.
 * Pure, so `run_to` and the tests can ask for any moment.
 */
class Timeline(val execution: Execution, stepsPerS: Float, degPerS: Float) {
    private val starts = FloatArray(execution.moves.size)
    private val durations = FloatArray(execution.moves.size)
    val total: Float

    init {
        var t = 0f
        execution.moves.forEachIndexed { i, m ->
            starts[i] = t
            durations[i] =
                when (m) {
                    is Move.Forward -> m.length / stepsPerS
                    is Move.Turn -> abs(m.by) / degPerS
                }
            t += durations[i]
        }
        total = t
    }

    /** Start time of move [i]. */
    fun startOf(i: Int): Float = if (i >= starts.size) total else starts[i]

    data class Frame(
        val at: P2,
        val heading: Float,
        /** Index of the move under way ([Execution.moves] size when finished). */
        val moveIndex: Int,
        /** Share of that move done. */
        val moveShare: Float,
        val blockId: Int?,
        /** Signed degrees turned so far. */
        val turned: Float,
        val done: Boolean,
    )

    fun at(t: Float): Frame {
        val moves = execution.moves
        var turned = 0f
        if (moves.isEmpty()) return Frame(execution.start.at, execution.start.heading, 0, 1f, null, 0f, true)
        for (i in moves.indices) {
            val m = moves[i]
            val end = starts[i] + durations[i]
            if (t < end) {
                val share = if (durations[i] <= 0f) 1f else ((t - starts[i]) / durations[i]).coerceIn(0f, 1f)
                return when (m) {
                    is Move.Forward -> Frame(m.from.lerp(m.to, share), m.heading, i, share, m.blockId, turned, false)
                    is Move.Turn -> Frame(m.at, m.from + m.by * share, i, share, m.blockId, turned + m.by * share, false)
                }
            }
            if (m is Move.Turn) turned += m.by
        }
        return Frame(execution.end.at, execution.end.heading, moves.size, 1f, null, turned, true)
    }

    /** The time at which forward move [i] is drawn for [share] (0..1) of its length. */
    fun timeAt(i: Int, share: Float): Float = startOf(i) + durations.getOrElse(i) { 0f } * share

    companion object {
        /** Time when the turtle has finished the [n]-th forward segment (1-based; `run_to`). */
        fun segmentEnd(t: Timeline, n: Int): Float {
            var k = 0
            t.execution.moves.forEachIndexed { i, m ->
                if (m is Move.Forward) {
                    k++
                    if (k == n) return t.startOf(i) + max(0f, t.durations[i]) - 1e-4f
                }
            }
            return t.total
        }
    }
}
