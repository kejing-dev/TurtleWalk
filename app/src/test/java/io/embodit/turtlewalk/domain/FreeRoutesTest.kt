package io.embodit.turtlewalk.domain

import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.usecase.CheckUseCase
import io.embodit.turtlewalk.domain.usecase.FreeRoutes
import io.embodit.turtlewalk.domain.usecase.Interpreter
import io.embodit.turtlewalk.domain.usecase.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Taste B21: every generated route is solvable, fits the field, follows the difficulty curve and does not repeat. */
class FreeRoutesTest {

    private val checker = CheckUseCase()

    @Test
    fun everyRouteIsSolvableByItsOwnSolutionAndFitsTheField() {
        var seed = 7L
        for (round in 1..60) {
            val r = FreeRoutes.generate(seed, round)
            val lesson = r.lesson
            assertEquals(0, lesson.n)
            val exec = Interpreter.run(lesson.solution, lesson.start)
            assertTrue("round $round ${r.solutionText}: segments ${exec.segmentCount}", exec.segmentCount in 1..TurtleRules.FREE_MAX_SEGMENTS)
            assertTrue("round $round ${r.solutionText}: left the field", !exec.outOfField)
            for (f in exec.forwards) {
                assertTrue("round $round ${r.solutionText}: ${f.to}", abs(f.to.x) <= TurtleRules.FREE_LIMIT_STEPS + 1e-3f && abs(f.to.y) <= TurtleRules.FREE_LIMIT_STEPS + 1e-3f)
            }
            // the reference solution is a match under the course's own check (shared scoring)
            val result = checker.check(lesson, lesson.solution)
            assertEquals("round $round ${r.solutionText}: ${result.verdict}", Verdict.MATCH, result.verdict)
            // closed routes turn whole laps; open ones end on a star away from the start
            if (!lesson.star) {
                val rem = abs(exec.turnTotal) % 360f
                assertTrue("round $round ${r.solutionText}: turn ${exec.turnTotal}", abs(exec.turnTotal) >= 359f && (rem < 0.5f || rem > 359.5f))
                assertTrue(exec.end.at.distanceTo(exec.start.at) <= TurtleRules.CLOSE_STEPS)
            } else {
                assertTrue(exec.end.at.distanceTo(exec.start.at) >= 0.5f)
            }
            // program limits of the editor
            assertTrue(Program.count(lesson.solution) <= TurtleRules.MAX_TOP_BLOCKS)
            assertTrue(lesson.solution.all { Program.nestingOf(it) <= TurtleRules.MAX_NESTING })
            // every block of the solution is offered on the palette
            fun kinds(blocks: List<io.embodit.turtlewalk.domain.model.Block>): Set<BlockKind> = blocks.flatMap { listOf(it.kind) + kinds(it.body) }.toSet()
            assertTrue(kinds(lesson.solution).all { it in lesson.blocks })
            seed = seed * 6364136223846793005L + 1442695040888963407L
        }
    }

    @Test
    fun difficultyRisesWithTheRound() {
        // the first rounds are star paths with F / L / R only; later rounds bring repeat and closed shapes
        for (round in 1..3) {
            val r = FreeRoutes.generate(42L, round)
            assertEquals(0, r.tier)
            assertTrue(r.lesson.star)
            assertTrue(BlockKind.REPEAT !in r.lesson.blocks)
        }
        assertEquals(1, FreeRoutes.tierOf(4))
        assertEquals(2, FreeRoutes.tierOf(7))
        assertEquals(3, FreeRoutes.tierOf(10))
        assertEquals(4, FreeRoutes.tierOf(13))
        assertEquals(4, FreeRoutes.tierOf(99))
        var closed = 0
        var nested = 0
        for (round in 10..30) {
            val r = FreeRoutes.generate(42L, round)
            if (!r.lesson.star) closed++
            if (r.lesson.solution.any { Program.nestingOf(it) >= 2 }) nested++
        }
        assertTrue("closed shapes $closed", closed >= 15)
        assertTrue("nested repeats $nested", nested >= 3)
    }

    @Test
    fun sameSeedSameRoute_newSeedOrRoundDifferentRoute() {
        val a = FreeRoutes.generate(5L, 4)
        val b = FreeRoutes.generate(5L, 4)
        assertEquals(a.solutionText, b.solutionText)
        assertEquals(a.lesson.start, b.lesson.start)
        var different = 0
        for (s in 1L..20L) if (FreeRoutes.generate(s, 4).solutionText != a.solutionText) different++
        assertTrue("only $different of 20 seeds differ", different >= 10)
    }

    @Test
    fun theLastRouteIsNotRepeated() {
        val first = FreeRoutes.generate(11L, 2)
        val next = FreeRoutes.generate(11L, 2, avoid = setOf(first.solutionText))
        assertNotEquals(first.solutionText, next.solutionText)
    }

    @Test
    fun qaSeedIsStablePerRound() {
        assertEquals(FreeRoutes.generate(FreeRoutes.qaSeed(3), 3).solutionText, FreeRoutes.generate(FreeRoutes.qaSeed(3), 3).solutionText)
    }
}
