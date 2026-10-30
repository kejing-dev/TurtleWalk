package io.embodit.turtlewalk.domain

import io.embodit.turtlewalk.domain.model.Box3
import io.embodit.turtlewalk.domain.model.Calibration
import io.embodit.turtlewalk.domain.model.ModelCalibration
import io.embodit.turtlewalk.domain.model.ModelSpec
import io.embodit.turtlewalk.domain.model.Quat4
import io.embodit.turtlewalk.domain.model.TurtleAsset
import io.embodit.turtlewalk.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

/**
 * Taste D25 gate: after calibration the model's bottom centre is on the origin (± 1 cm), its head points along the
 * app's forward (< 2°), its length is the designed one (< 10 %), it stands upright and nothing is mirrored.
 */
class ModelCalibrationTest {

    private fun angleDeg(a: Vec3, b: Vec3): Float = Math.toDegrees(acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f)).toDouble()).toFloat()

    private fun det(q: Quat4): Float = q.rotate(Vec3.RIGHT).cross(q.rotate(Vec3.UP)).dot(q.rotate(Vec3(0f, 0f, 1f)))

    private fun check(name: String, box: Box3, spec: ModelSpec, headInModel: Vec3): Calibration {
        val cal = ModelCalibration.calibrate(box, spec)
        val out = cal.apply(box)
        val bc = out.bottomCenter
        assertTrue("$name: bottom centre $bc not on the origin", abs(bc.x) <= 0.01f && abs(bc.y) <= 0.01f && abs(bc.z) <= 0.01f)
        // the head: a point at the box centre pushed along the file's head axis must end up ahead of the centre
        val headPoint = cal.apply(box.center + headInModel * 0.3f)
        val centre = cal.apply(box.center)
        val dir = Vec3(headPoint.x - centre.x, 0f, headPoint.z - centre.z)
        assertTrue("$name: head ${angleDeg(dir, Vec3.AHEAD)} deg off forward", angleDeg(dir, Vec3.AHEAD) < 2f)
        // length along the app's forward within 10 %
        assertTrue("$name: length ${out.size.z}", abs(out.size.z - spec.lengthM) / spec.lengthM < 0.10f)
        // upright: the box's height stays the vertical extent and feet are the lowest point
        assertTrue("$name: not upright", out.size.y > 0f && out.size.y < out.size.z)
        assertEquals("$name: mirrored", 1f, det(cal.rotation), 1e-4f)
        assertTrue("$name: scale must be positive", cal.scale > 0f)
        return cal
    }

    @Test
    fun exportedTurtleStandsOnTheOriginFacingAhead() {
        val cal = check("exported", TurtleAsset.EXPORTED_BOX, TurtleAsset.SPEC, Vec3.AHEAD)
        assertFalse(cal.fromRaw)
        assertEquals(TurtleAsset.LENGTH_M, cal.scale, 1e-4f) // the export is length 1
        assertEquals(1f, cal.rotation.w, 1e-4f) // the export already faces -Z: no turn
    }

    @Test
    fun rawScanWithDroppedRootTransformIsStillCalibrated() {
        // the loader ignored the export's root node: the raw scan shows through (head along -X, pivot 0.6 m away)
        val cal = check("raw", TurtleAsset.RAW_BOX, TurtleAsset.SPEC, Vec3(-1f, 0f, 0f))
        assertTrue(cal.fromRaw)
        assertEquals(1.2035f, cal.scale, 0.01f) // 0.35 / 0.2908
    }

    @Test
    fun aModelFacingPlusZIsTurnedHalfWayWithoutMirroring() {
        val box = Box3(Vec3(-0.2f, 0f, -0.5f), Vec3(0.2f, 0.25f, 0.5f))
        val cal = check("plusZ", box, ModelSpec(lengthM = 0.35f, forward = Vec3(0f, 0f, 1f)), Vec3(0f, 0f, 1f))
        assertTrue(angleDeg(cal.rotation.rotate(Vec3(0f, 0f, 1f)), Vec3.AHEAD) < 0.1f)
        assertTrue(angleDeg(cal.rotation.rotate(Vec3.UP), Vec3.UP) < 0.1f)
    }

    @Test
    fun aModelFacingPlusXWithAnOffsetPivotIsCentred() {
        val box = Box3(Vec3(1f, 0.5f, 2f), Vec3(2f, 0.8f, 2.4f))
        val cal = check("plusX", box, ModelSpec(lengthM = 0.5f, forward = Vec3.RIGHT), Vec3.RIGHT)
        assertTrue(cal.offset.length > 0.5f) // the far pivot is corrected by the node, not by the mover
    }

    @Test
    fun theTurtleAtHeadingNinetyFacesTheFieldsRight() {
        // the mover turns the calibrated turtle by -heading about +Y (FieldViews.headingRotation): heading 90 = local +X
        val cal = ModelCalibration.calibrate(TurtleAsset.EXPORTED_BOX, TurtleAsset.SPEC)
        val mover = Quat4.axisAngle(Vec3.UP, -Math.toRadians(90.0).toFloat())
        val head = mover.rotate(cal.rotation.rotate(Vec3.AHEAD))
        assertTrue(angleDeg(head, Vec3.RIGHT) < 1f)
    }
}
