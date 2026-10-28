package io.embodit.turtlewalk.domain

import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.Lessons
import io.embodit.turtlewalk.domain.model.Verdict
import io.embodit.turtlewalk.domain.usecase.CheckUseCase
import io.embodit.turtlewalk.domain.usecase.Cursor
import io.embodit.turtlewalk.domain.usecase.Interpreter
import io.embodit.turtlewalk.domain.usecase.Program
import io.embodit.turtlewalk.domain.usecase.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design `interactions.T11`: every lesson's answer matches; the canned mistakes give their verdicts. */
class CheckUseCaseTest {
    private val check = CheckUseCase()

    private fun p(text: String) = Program.parse(text) ?: error("parse $text")

    @Test
    fun everyReferenceAnswerMatches() {
        for (lesson in Lessons.all) {
            val r = check.check(lesson, lesson.solution)
            assertEquals("lesson ${lesson.n}", Verdict.MATCH, r.verdict)
            assertFalse("lesson ${lesson.n} stays in the field", r.outOfField)
        }
    }

    @Test
    fun turnTotalsAndLaps() {
        val expected = mapOf(1 to 0, 2 to -90, 3 to 360, 4 to 360, 5 to 360, 6 to 360, 7 to 720, 8 to 360, 9 to 2160, 10 to 360)
        for (lesson in Lessons.all) {
            val r = check.check(lesson, lesson.solution)
            assertEquals("lesson ${lesson.n}", expected[lesson.n]!!.toFloat(), r.turnTotal, 0.5f)
        }
        assertEquals(1, check.check(Lessons.byNumber(3), Lessons.byNumber(3).solution).laps)
        assertEquals(2, check.check(Lessons.byNumber(7), Lessons.byNumber(7).solution).laps)
        assertEquals(6, check.check(Lessons.byNumber(9), Lessons.byNumber(9).solution).laps)
        assertNull(check.check(Lessons.byNumber(1), Lessons.byNumber(1).solution).laps)
    }

    @Test
    fun alternativeAnswersAlsoMatch() {
        // the square written with repeat, or with left turns the other way round, is still the target shape
        assertEquals(Verdict.MATCH, check.check(Lessons.byNumber(3), p("R4[F1 R90]")).verdict)
        assertEquals(Verdict.MATCH, check.check(Lessons.byNumber(4), p("R4[F1 R90]")).verdict)
        assertEquals(Verdict.MATCH, check.check(Lessons.byNumber(1), p("F0.5 F0.5")).verdict)
    }

    @Test
    fun triangleWithSixtyIsOpenWithTheHint() {
        val l5 = Lessons.byNumber(5)
        for (text in listOf("R3[F1 R60]", "F1 R60 F1 R60 F1 R60", "R3[F1 L60]")) {
            val r = check.check(l5, p(text))
            assertTrue("$text open or different", r.verdict == Verdict.OPEN || r.verdict == Verdict.DIFFERENT)
            assertTrue("$text sixty hint", r.sixtyHint)
        }
        val r = check.check(l5, p("R3[F1 R60]"))
        assertEquals(Verdict.OPEN, r.verdict)
        assertTrue(r.gapSteps > 0.5f)
    }

    @Test
    fun squareWithoutTheLastTurnSpins() {
        val r = check.check(Lessons.byNumber(3), p("F1 R90 F1 R90 F1 R90 F1"))
        assertEquals(Verdict.SPIN, r.verdict)
        assertEquals(90f, r.spinDeg, 0.5f)
    }

    @Test
    fun squareWithLeftTurnsIsMirrored() {
        val r = check.check(Lessons.byNumber(3), p("F1 L90 F1 L90 F1 L90 F1 L90"))
        assertEquals(Verdict.MIRROR, r.verdict)
        assertTrue(r.mirrorToRight)
        val r2 = check.check(Lessons.byNumber(2), p("F1 R90 F1"))
        assertEquals(Verdict.MIRROR, r2.verdict)
        assertFalse("lesson 2 turns left", r2.mirrorToRight)
    }

    @Test
    fun lessonFourWithoutRepeatIsTooMany() {
        val r = check.check(Lessons.byNumber(4), p("F1 R90 F1 R90 F1 R90 F1 R90"))
        assertEquals(Verdict.TOO_MANY, r.verdict)
        assertEquals(8, r.blockCount)
    }

    @Test
    fun differentAndOutOfField() {
        assertEquals(Verdict.DIFFERENT, check.check(Lessons.byNumber(3), p("R4[F0.5 R90]")).verdict)
        assertEquals(Verdict.OPEN, check.check(Lessons.byNumber(3), p("F0.5")).verdict)
        val r = check.check(Lessons.byNumber(3), p("R12[F2 R30]"))
        assertTrue(r.outOfField)
        val exec = Interpreter.run(p("R12[F2 R30]"), Lessons.byNumber(3).start)
        val exit = exec.exit
        assertNotNull(exit)
        // the turtle stops half a step outside the field
        val f = exec.moves[exit!!.first] as io.embodit.turtlewalk.domain.usecase.Move.Forward
        val at = f.from.lerp(f.to, exit.second)
        assertEquals(1.5f, maxOf(kotlin.math.abs(at.x), kotlin.math.abs(at.y)), 0.01f)
    }

    @Test
    fun tooLongProgramIsRefused() {
        val exec = Interpreter.run(p("R12[R12[F0.25 R30]]"), Lessons.byNumber(8).start)
        assertTrue(exec.tooLong)
        assertEquals(144, exec.segmentCount)
    }

    @Test
    fun cornersMergeConsecutiveTurns() {
        val exec = Interpreter.run(Lessons.byNumber(9).solution, Lessons.byNumber(9).start)
        // 5 petals x 3 corners, the last corner of each petal merged with the 72 between petals
        assertEquals(15, exec.corners.size)
        assertEquals(192f, exec.corners[2].by, 0.01f)
    }

    @Test
    fun timelineEndsWhereTheProgramEnds() {
        val exec = Interpreter.run(p("R4[F1 R90]"), Lessons.byNumber(3).start)
        val tl = Timeline(exec, 0.4f, 60f)
        assertEquals(4 * 2.5f + 4 * 1.5f, tl.total, 1e-3f)
        val end = tl.at(tl.total + 1f)
        assertTrue(end.done)
        assertEquals(360f, end.turned, 1e-3f)
        val mid = tl.at(1.25f)
        assertEquals(0f, mid.at.x + 0.5f, 1e-3f)
        assertEquals(0f, mid.at.y, 1e-3f)
        assertEquals(2.5f, Timeline.segmentEnd(tl, 1), 1e-3f)
    }

    @Test
    fun codecRoundTrips() {
        for (text in listOf("F1 R90", "R4[F1 R90]", "R5[R3[F0.75 R120] R72]", "F0.25 L45")) {
            assertEquals(text, Program.encode(p(text)))
        }
        assertEquals("R4[F1 R90]", Program.encode(p("X4[F1 R90]")))
        assertNull(Program.parse("F1 Q2"))
        assertNull(Program.parse("R4[F1"))
    }

    @Test
    fun editingInsertsMovesAndNests() {
        var prog = p("F1 R4[R90]")
        // insert at the end of the repeat body
        val (p1, id, cur) = Program.insert(prog, Cursor(2, 1), BlockKind.FORWARD)!!
        assertEquals("F1 R4[R90 F1]", Program.encode(p1))
        assertEquals(Cursor(2, 2), cur)
        // moving the first top block down enters the repeat at its start
        prog = Program.move(p1, 1, up = false)
        assertEquals("R4[F1 R90 F1]", Program.encode(prog))
        // moving it up again leaves the bracket
        prog = Program.move(prog, 1, up = true)
        assertEquals("F1 R4[R90 F1]", Program.encode(prog))
        // deleting the repeat removes its body
        val (p2, at) = Program.delete(prog, 2)
        assertEquals("F1", Program.encode(p2))
        assertEquals(Cursor(null, 1), at)
        // nesting limit: a repeat inside two repeats is refused
        val deep = p("R2[R2[F1]]")
        assertEquals(Program.Refusal.TOO_DEEP, Program.refusal(deep, Cursor(2, 0), BlockKind.REPEAT))
        assertNull(Program.refusal(deep, Cursor(2, 0), BlockKind.FORWARD))
        assertTrue(id > 0)
    }
}
