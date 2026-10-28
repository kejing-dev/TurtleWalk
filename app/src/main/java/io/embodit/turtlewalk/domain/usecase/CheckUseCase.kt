package io.embodit.turtlewalk.domain.usecase

import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.Lesson
import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.model.wrapDeg
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The result card of a drawing (design `panels.codePanel.layouts.drawn`). */
data class CheckResult(
    val verdict: Verdict,
    val outOfField: Boolean,
    /** Signed degrees the turtle turned (right positive). */
    val turnTotal: Float,
    /** :open: how far the end is from the start, steps. */
    val gapSteps: Float = 0f,
    /** :spin: how much more to turn (signed, right positive). */
    val spinDeg: Float = 0f,
    /** :mirror: the first corner of the target turns right (else left). */
    val mirrorToRight: Boolean = true,
    /** Lesson 5: a 60 degree turn in a drawing that is not right. */
    val sixtyHint: Boolean = false,
    val blockCount: Int = 0,
) {
    /** Whole laps of a closed match ("= 1 圈"), else null. */
    val laps: Int?
        get() {
            if (verdict != Verdict.MATCH) return null
            val l = abs(turnTotal) / 360f
            return if (l >= 0.99f && abs(l - l.roundToInt()) < 0.02f) l.roundToInt() else null
        }
}

/**
 * The check of a drawing (design `rules.check`), always on the full program geometry, also when the turtle
 * stopped outside the field:
 * 1. cover: every target piece lies on the program's route (0.05 step) and the route's merged length is
 *    at most 1.05 x the target's -> the shape is right;
 * 2. closed target: shape right, back at the start (0.05 step) and facing as at the start (5 degrees) -> match;
 *    shape right but facing elsewhere -> spin; end away from the start -> open. Open target: shape right -> match;
 * 3. lesson 4: a match with more than 3 blocks -> too many;
 * 4. the shape is wrong but the program with left and right swapped draws it -> mirror;
 * 5. anything else -> different.
 */
class CheckUseCase {

    fun check(lesson: Lesson, program: List<Block>): CheckResult {
        val run = Interpreter.run(program, lesson.start)
        val target = Interpreter.run(lesson.solution, lesson.start)
        val blocks = Program.count(program)
        val closedTarget = target.end.at.distanceTo(target.start.at) <= TurtleRules.CLOSE_STEPS
        val shapeOk = sameShape(run, target)
        val gap = run.end.at.distanceTo(run.start.at)
        val headingOff = wrapDeg(run.end.heading - run.start.heading)
        var verdict: Verdict? = null
        if (closedTarget) {
            if (shapeOk && gap <= TurtleRules.CLOSE_STEPS && abs(headingOff) <= TurtleRules.HEADING_TOL_DEG) verdict = Verdict.MATCH
            else if (shapeOk && gap <= TurtleRules.CLOSE_STEPS) verdict = Verdict.SPIN
            else if (gap > TurtleRules.CLOSE_STEPS) verdict = Verdict.OPEN
        } else if (shapeOk) {
            verdict = Verdict.MATCH
        }
        if (verdict == Verdict.MATCH && lesson.maxBlocks != null && blocks > lesson.maxBlocks) verdict = Verdict.TOO_MANY
        if (verdict == null) {
            val mirror = Interpreter.run(Program.mirrored(program), lesson.start)
            verdict = if (program.isNotEmpty() && sameShape(mirror, target)) Verdict.MIRROR else Verdict.DIFFERENT
        }
        // an open drawing whose mirror is the target is still reported as open (it did not come back)
        val firstTurn = target.corners.firstOrNull()?.by ?: 1f
        val sixty = lesson.sixtyHint && (verdict == Verdict.OPEN || verdict == Verdict.DIFFERENT) && hasSixty(program)
        return CheckResult(
            verdict = verdict,
            outOfField = run.outOfField,
            turnTotal = run.turnTotal,
            gapSteps = gap,
            spinDeg = wrapDeg(run.start.heading - run.end.heading),
            mirrorToRight = firstTurn > 0f,
            sixtyHint = sixty,
            blockCount = blocks,
        )
    }

    private fun hasSixty(blocks: List<Block>): Boolean = blocks.any { (it.isTurn && abs(it.value - 60f) < 0.5f) || hasSixty(it.body) }

    /** Rule 1: the target is covered and nothing much is drawn besides it. */
    fun sameShape(run: Execution, target: Execution): Boolean {
        if (run.tooLong) return false
        val routeSegs = run.forwards.map { it.from to it.to }
        val targetSegs = target.forwards.map { it.from to it.to }
        if (routeSegs.isEmpty()) return targetSegs.isEmpty()
        // every target piece within 0.05 step of the route
        for ((a, b) in targetSegs) {
            val len = a.distanceTo(b)
            val n = max(1, (len / 0.05f).toInt())
            for (k in 0..n) {
                val p = a.lerp(b, k / n.toFloat())
                if (routeSegs.minOf { (c, d) -> distToSegment(p, c, d) } > TurtleRules.COVER_STEPS) return false
            }
        }
        return mergedLength(routeSegs) <= mergedLength(targetSegs) * TurtleRules.LENGTH_SLACK + 1e-3f
    }

    companion object {
        fun distToSegment(p: P2, a: P2, b: P2): Float {
            val d = b - a
            val l2 = d.dot(d)
            if (l2 < 1e-9f) return p.distanceTo(a)
            val t = ((p - a).dot(d) / l2).coerceIn(0f, 1f)
            return p.distanceTo(a + d * t)
        }

        /**
         * Total length of segments with overlaps counted once: segments on the same line (direction within
         * 0.5 degree, offset within 0.01 step) are merged as intervals along that line.
         */
        fun mergedLength(segs: List<Pair<P2, P2>>): Float {
            data class Key(val angle: Int, val offset: Int)
            val lines = HashMap<Key, MutableList<Pair<Float, Float>>>()
            for ((a, b) in segs) {
                val d = b - a
                val len = d.length
                if (len < 1e-5f) continue
                var ang = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat()
                if (ang < 0f) ang += 180f
                if (ang >= 179.75f) ang -= 180f
                val ux = kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat()
                val uy = kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat()
                val offset = a.x * -uy + a.y * ux
                val key = Key(Math.round(ang * 2f), floor(offset / 0.01f + 0.5f).toInt())
                val s0 = a.x * ux + a.y * uy
                val s1 = b.x * ux + b.y * uy
                lines.getOrPut(key) { ArrayList() } += min(s0, s1) to max(s0, s1)
            }
            var total = 0f
            for (intervals in lines.values) {
                intervals.sortBy { it.first }
                var curA = intervals[0].first
                var curB = intervals[0].second
                for (k in 1 until intervals.size) {
                    val (a, b) = intervals[k]
                    if (a <= curB + 1e-4f) curB = max(curB, b)
                    else {
                        total += curB - curA
                        curA = a
                        curB = b
                    }
                }
                total += curB - curA
            }
            return total
        }

        @Suppress("unused")
        private fun hyp(a: Float, b: Float) = hypot(a, b)
    }
}
