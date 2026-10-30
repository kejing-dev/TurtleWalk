package io.embodit.turtlewalk.domain.usecase

import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.Lesson
import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.TurtlePose
import io.embodit.turtlewalk.domain.model.TurtleRules
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** One generated free-mode route (taste B21). */
data class FreeRoute(val round: Int, val seed: Long, val tier: Int, val family: String, val lesson: Lesson) {
    val solutionText: String
        get() = Program.encode(lesson.solution)
}

/**
 * Free mode (taste B21, user 2026-10-29 "加个自由模式，路线由 AI 或者 PCG 生成，可以一直玩的"): routes made by
 * rules, offline, endless, never the same twice in a row. A route is a [Lesson] with `n == 0`, so the whole course
 * flow (blocks, draw, check, turn along, walk) is reused unchanged.
 *
 * Rules:
 * - **Seed**: `(seed, round)` fully determines the route; "next round" draws a new seed.
 * - **Difficulty**: tier = (round - 1) / 3, capped at 4: 0 = a short path to a star with 90° corners (F / L / R only),
 *   1 = regular polygons and rectangles (repeat appears), 2 = star, circle, house, 45° paths, 3 = nested repeats
 *   (flowers, rings of squares), 4 = everything, big parameters, two-shape bow ties. 70 % of rounds take the
 *   current tier, 30 % an earlier one so a run stays varied.
 * - **Solvable**: every route is run by the [Interpreter] from its start and must (a) fit inside the field with
 *   [TurtleRules.FREE_LIMIT_STEPS] to spare, after being centred; (b) have 1..[TurtleRules.FREE_MAX_SEGMENTS] segments;
 *   (c) if closed, come back to the start facing the start way with a total turn that is a whole number of 360°;
 *   (d) if open, end at least half a step from the start without drawing over itself. A side length that does not fit
 *   is stepped down; a route that still fails, or repeats [avoid], is dropped and the next candidate is drawn.
 *   After 60 failed draws the square of lesson 3 stands in (never happens with the families below, but a route is
 *   always returned).
 */
object FreeRoutes {
    const val LESSON_N = 0
    const val MAX_TIER = 4

    /** QA seed of `state ...@F<round>`: the same route for every screenshot of that round. */
    fun qaSeed(round: Int): Long = 1000L + round

    fun tierOf(round: Int): Int = ((round - 1) / 3).coerceIn(0, MAX_TIER)

    fun generate(seed: Long, round: Int, avoid: Set<String> = emptySet()): FreeRoute {
        val rng = Random(seed xor (round.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
        val tier = tierOf(round)
        repeat(60) {
            val t = if (tier > 0 && rng.nextFloat() < 0.3f) rng.nextInt(tier) else tier
            val families = FAMILIES.filter { it.tier == t }
            val family = families[rng.nextInt(families.size)]
            val draft = family.draw(rng, t) ?: return@repeat
            val route = fit(draft, round, seed, t, family.name) ?: return@repeat
            if (route.solutionText in avoid) return@repeat
            return route
        }
        val square = Draft("square", "正方形", "Square", "R4[F1 R90]", true, "每个角都一样：前进 1，转 90°，做 4 次。", "Every corner is the same: forward 1, turn 90°, four times.", Draft.ALL, listOf(1f, 0.75f, 0.5f))
        return fit(square, round, seed, tier, "square") ?: error("the fallback square must fit")
    }

    // ------------------------------------------------------------------------------------------
    // families
    // ------------------------------------------------------------------------------------------

    /** A candidate before fitting: [text] uses `S` for the side length, replaced by each of [sides] in turn until it fits. */
    internal class Draft(
        val id: String,
        val nameZh: String,
        val nameEn: String,
        val text: String,
        val closed: Boolean,
        val hintZh: String,
        val hintEn: String,
        val blocks: List<BlockKind>,
        val sides: List<Float>,
        /** How many pieces the name counts (for the goal line). */
        val count: Int = 0,
    ) {
        companion object {
            val FLR = listOf(BlockKind.FORWARD, BlockKind.LEFT, BlockKind.RIGHT)
            val ALL = listOf(BlockKind.FORWARD, BlockKind.LEFT, BlockKind.RIGHT, BlockKind.REPEAT)
        }
    }

    internal class Family(val name: String, val tier: Int, val draw: (Random, Int) -> Draft?)

    private fun fmt(v: Float): String = io.embodit.turtlewalk.domain.model.formatValue(v)

    private fun <T> Random.pick(list: List<T>): T = list[nextInt(list.size)]

    private val POLY_NAMES = mapOf(3 to ("三角形" to "Triangle"), 4 to ("正方形" to "Square"), 5 to ("五边形" to "Pentagon"), 6 to ("六边形" to "Hexagon"), 8 to ("八边形" to "Octagon"), 12 to ("圆" to "Circle"))

    internal val FAMILIES: List<Family> =
        listOf(
            // tier 0: a path to the star with 90° corners
            Family("path", 0) { rng, t ->
                val n = if (t == 0) rng.pick(listOf(2, 3)) else rng.pick(listOf(3, 4))
                val turns = if (t >= 2) listOf(45f, 90f, 90f, 120f) else listOf(90f)
                val sb = StringBuilder()
                var lastSign = 0
                for (i in 0 until n) {
                    sb.append("FS ")
                    if (i < n - 1) {
                        var sign = if (rng.nextBoolean()) 1 else -1
                        // two left or two right 90s in a row fold the path back onto itself: alternate
                        if (sign == lastSign && t == 0) sign = -sign
                        lastSign = sign
                        sb.append(if (sign > 0) "R" else "L").append(fmt(rng.pick(turns))).append(' ')
                    }
                }
                Draft(
                    "path$n", "找星星 · $n 段", "Star hunt · $n legs", sb.toString().trim(), false,
                    "照着虚线走：$n 段，拐 ${n - 1} 次弯，走到星星。", "Follow the dashes: $n legs and ${n - 1} corners to the star.",
                    Draft.FLR, listOf(1f, 0.75f, 0.5f), n,
                )
            },
            // tier 1: regular polygons and rectangles
            Family("polygon", 1) { rng, t ->
                val n = rng.pick(if (t >= 2) listOf(3, 4, 5, 6, 8) else listOf(3, 4, 5, 6))
                val turn = 360f / n
                val dir = if (rng.nextBoolean()) "R" else "L"
                val (zh, en) = POLY_NAMES.getValue(n)
                Draft(
                    "poly$n", zh, en, "R$n[FS $dir${fmt(turn)}]", true,
                    "$n 条边一样长，每个角转 ${fmt(turn)}°（360 ÷ $n）。", "$n equal sides; every corner turns ${fmt(turn)}° (360 ÷ $n).",
                    Draft.ALL, listOf(1f, 0.75f, 0.5f), n,
                )
            },
            Family("rect", 1) { rng, _ ->
                val a = rng.pick(listOf(1f, 1.5f))
                val b = rng.pick(listOf(0.5f, 0.75f))
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "rect", "长方形", "Rectangle", "R2[F${fmt(a)} ${dir}90 FS ${dir}90]", true,
                    "两条长边 ${fmt(a)} 步、两条短边，每个角转 90°。", "Two long sides of ${fmt(a)}, two short ones, 90° at every corner.",
                    Draft.ALL, listOf(b, 0.5f), 4,
                )
            },
            Family("rhombus", 1) { rng, _ ->
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "rhombus", "菱形", "Rhombus", "R2[FS ${dir}60 FS ${dir}120]", true,
                    "四条边一样长，角是 60° 和 120° 交替。", "Four equal sides; the turns alternate 60° and 120°.",
                    Draft.ALL, listOf(1f, 0.75f, 0.5f), 4,
                )
            },
            // tier 2: star, circle, house, 45° paths
            Family("star", 2) { rng, _ ->
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "star5", "五角星", "Star", "R5[FS ${dir}144]", true,
                    "尖角很尖：每次转 144°，5 次回到起点，一共两圈。", "Sharp points: turn 144° five times; back at the start you turned two full turns.",
                    Draft.ALL, listOf(1.5f, 1f, 0.75f), 5,
                )
            },
            Family("circle", 2) { rng, _ ->
                val dir = if (rng.nextBoolean()) "R" else "L"
                val n = rng.pick(listOf(8, 12))
                Draft(
                    "circle$n", if (n == 12) "圆" else "八边形", if (n == 12) "Circle" else "Octagon", "R$n[FS $dir${fmt(360f / n)}]", true,
                    "很多个小步、小弯，加起来正好一圈。", "Many small steps and small turns add up to exactly one turn.",
                    Draft.ALL, if (n == 12) listOf(0.5f, 0.25f) else listOf(0.5f, 0.25f), n,
                )
            },
            Family("house", 2) { rng, _ ->
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "house", "小房子", "House", "FS ${dir}30 FS ${dir}120 FS ${dir}30 FS ${dir}90 FS ${dir}90", true,
                    "先画墙，到墙顶转一点点，屋顶的尖要转很多。", "Draw the wall first; turn a little at its top, the roof's point needs a big turn.",
                    Draft.ALL, listOf(1f, 0.75f, 0.5f), 5,
                )
            },
            Family("path45", 2) { rng, _ ->
                val n = rng.pick(listOf(3, 4))
                val sb = StringBuilder()
                for (i in 0 until n) {
                    sb.append("FS ")
                    if (i < n - 1) sb.append(if (rng.nextBoolean()) "R" else "L").append(fmt(rng.pick(listOf(45f, 90f, 45f, 120f)))).append(' ')
                }
                Draft(
                    "path45_$n", "斜着找星星 · $n 段", "Slanted star hunt · $n legs", sb.toString().trim(), false,
                    "有的弯只转 45°：照着虚线上的小箭头走。", "Some corners turn only 45°: follow the little arrows on the dashes.",
                    Draft.FLR, listOf(1f, 0.75f, 0.5f), n,
                )
            },
            // tier 3: nested repeats
            Family("flower", 3) { rng, _ ->
                val p = rng.pick(listOf(3, 4, 5, 6))
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "flower$p", "$p 瓣花", "Flower · $p petals", "R$p[R3[FS ${dir}120] $dir${fmt(360f / p)}]", true,
                    "一片花瓣是三角形；画完一片转 ${fmt(360f / p)}°，重复 $p 次。", "One petal is a triangle; after each one turn ${fmt(360f / p)}°, $p times.",
                    Draft.ALL, listOf(0.75f, 0.5f), p,
                )
            },
            Family("ring", 3) { rng, _ ->
                val p = rng.pick(listOf(4, 6, 8))
                val dir = if (rng.nextBoolean()) "R" else "L"
                Draft(
                    "ring$p", "$p 个方块的环", "Ring of $p squares", "R$p[R4[FS ${dir}90] $dir${fmt(360f / p)}]", true,
                    "一个正方形，画完转 ${fmt(360f / p)}°，重复 $p 次。", "One square; after it turn ${fmt(360f / p)}°, $p times.",
                    Draft.ALL, listOf(0.5f, 0.25f), p,
                )
            },
            // tier 4: two shapes back to back
            Family("bowtie", 4) { rng, _ ->
                val n = rng.pick(listOf(3, 4))
                val dir = if (rng.nextBoolean()) "R" else "L"
                val (zh, en) = POLY_NAMES.getValue(n)
                Draft(
                    "bowtie$n", "双$zh", "Double $en", "R2[R$n[FS $dir${fmt(360f / n)}] ${dir}180]", true,
                    "画完一个$zh，转 180°，再画一个。", "Draw one $en, turn 180°, draw another.",
                    Draft.ALL, listOf(0.75f, 0.5f), n,
                )
            },
        )

    // ------------------------------------------------------------------------------------------
    // fitting and checks
    // ------------------------------------------------------------------------------------------

    /** Places the route in the field (centred) with the largest side that fits and checks it; null if none fits. */
    internal fun fit(d: Draft, round: Int, seed: Long, tier: Int, family: String): FreeRoute? {
        for (side in d.sides) {
            val text = d.text.replace("S", fmt(side))
            val program = Program.parse(text) ?: continue
            val probe = Interpreter.run(program, TurtlePose(P2.ZERO, 0f))
            if (probe.tooLong || probe.segmentCount !in 1..TurtleRules.FREE_MAX_SEGMENTS) continue
            val pts = probe.forwards.flatMap { listOf(it.from, it.to) }
            if (pts.isEmpty()) continue
            val minX = pts.minOf { it.x }
            val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }
            val maxY = pts.maxOf { it.y }
            if ((maxX - minX) / 2f > TurtleRules.FREE_LIMIT_STEPS || (maxY - minY) / 2f > TurtleRules.FREE_LIMIT_STEPS) continue
            val start = TurtlePose(P2(round3(-(minX + maxX) / 2f), round3(-(minY + maxY) / 2f)), 0f)
            val exec = Interpreter.run(program, start)
            if (!valid(exec, d.closed)) continue
            val laps = (abs(exec.turnTotal) / 360f).roundToInt()
            val insightZh = if (d.closed) "回到起点一共转了 ${exec.turnTotal.roundToInt()}° = $laps 圈。封闭图形转的总是整圈数。" else "走了 ${fmt(exec.forwards.sumOf { it.length.toDouble() }.toFloat())} 步、转了 ${exec.turnTotal.roundToInt()}°，星星到手。"
            val insightEn = if (d.closed) "Back at the start you turned ${exec.turnTotal.roundToInt()}° = $laps full turn(s). A closed shape always turns whole turns." else "${fmt(exec.forwards.sumOf { it.length.toDouble() }.toFloat())} steps and ${exec.turnTotal.roundToInt()}° of turning: the star is yours."
            val lesson =
                Lesson(
                    n = LESSON_N, id = "free-$round-${d.id}", nameZh = d.nameZh, nameEn = d.nameEn, blocks = d.blocks, start = start, solution = program,
                    insightZh = insightZh, insightEn = insightEn, hint1Zh = d.hintZh, hint1En = d.hintEn, star = !d.closed,
                    goalZh = if (d.closed) "画出这个${d.nameZh}，边长 ${fmt(side)} 步" else "沿虚线走到星星，每段 ${fmt(side)} 步",
                    goalEn = if (d.closed) "Draw this ${d.nameEn.lowercase()}, ${fmt(side)} a side" else "Follow the dashes to the star, ${fmt(side)} a leg",
                )
            return FreeRoute(round, seed, tier, family, lesson)
        }
        return null
    }

    /** The solvability check of a placed route. */
    internal fun valid(exec: Execution, closed: Boolean): Boolean {
        if (exec.tooLong || exec.segmentCount !in 1..TurtleRules.FREE_MAX_SEGMENTS || exec.outOfField) return false
        val lim = TurtleRules.FREE_LIMIT_STEPS + 1e-3f
        if (exec.forwards.any { abs(it.to.x) > lim || abs(it.to.y) > lim || abs(it.from.x) > lim || abs(it.from.y) > lim }) return false
        val gap = exec.end.at.distanceTo(exec.start.at)
        val total = abs(exec.turnTotal)
        if (closed) {
            if (gap > TurtleRules.CLOSE_STEPS) return false
            if (total < 359f) return false
            val rem = total % 360f
            if (rem > 0.5f && rem < 359.5f) return false
            return true
        }
        if (gap < 0.5f) return false
        // open routes must not draw over themselves (the star hunt reads as one line)
        val segs = exec.forwards.map { it.from to it.to }
        val sum = exec.forwards.sumOf { it.length.toDouble() }.toFloat()
        return CheckUseCase.mergedLength(segs) >= sum * 0.98f
    }

    private fun round3(v: Float): Float = Math.round(v * 1000f) / 1000f
}
