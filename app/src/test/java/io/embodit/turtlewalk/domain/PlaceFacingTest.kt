package io.embodit.turtlewalk.domain

import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.Quat4
import io.embodit.turtlewalk.domain.model.Vec3
import io.embodit.turtlewalk.domain.usecase.FloorPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Taste D10 hard gate: the "four directions" placement test. The user stands east, south, west and north of
 * the place where the field is put down; the field's front, the turtle's head at its start heading and the
 * chevron must all point at the user within 10 degrees, and nothing may carry a negative scale (a mirror).
 */
class PlaceFacingTest {

    private fun angleDeg(a: Vec3, b: Vec3): Float {
        val c = (a.normalized().dot(b.normalized())).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }

    /** Rotation matrix determinant of a unit quaternion (+1 = a proper rotation, -1 would be a mirror). */
    private fun det(q: Quat4): Float {
        val x = q.rotate(Vec3.RIGHT)
        val y = q.rotate(Vec3.UP)
        val z = q.rotate(Vec3(0f, 0f, 1f))
        return x.cross(y).dot(z)
    }

    /** The turtle model is built facing local -Z; the scene turns it by -heading about +Y inside the field group. */
    private fun turtleHeadWorld(field: FloorPose, headingDeg: Float): Vec3 {
        val group = Quat4.axisAngle(Vec3.UP, field.yaw)
        val local = Quat4.axisAngle(Vec3.UP, -Math.toRadians(headingDeg.toDouble()).toFloat())
        return (group * local).rotate(Vec3.AHEAD)
    }

    @Test
    fun fieldTurtleAndChevronFaceTheUserFromAllFourSides() {
        val center = Vec3(0.7f, 0f, -1.3f)
        val sides = mapOf("east" to Vec3(1f, 0f, 0f), "south" to Vec3(0f, 0f, 1f), "west" to Vec3(-1f, 0f, 0f), "north" to Vec3(0f, 0f, -1f))
        for ((name, dir) in sides) {
            val user = center + dir * 2.2f + Vec3(0f, 1.6f, 0f)
            val yaw = FloorPose.yawToward(center, user)
            val field = FloorPose(center, yaw)
            val toUser = Vec3(user.x - center.x, 0f, user.z - center.z)
            // the field's front (+y): where the chevron points and where the code panel's readable side faces
            assertTrue("$name: field front ${angleDeg(field.forward, toUser)} deg off", angleDeg(field.forward, toUser) < 10f)
            // the turtle at its start heading (0 = the field's front) looks at the user
            val head = turtleHeadWorld(field, 0f)
            assertTrue("$name: turtle head ${angleDeg(head, toUser)} deg off", angleDeg(head, toUser) < 10f)
            // a field point one step ahead of the centre lies towards the user
            val ahead = field.toStage(0f, 1f) - center
            assertTrue("$name: +y is not towards the user", angleDeg(ahead, toUser) < 10f)
            // no mirror: proper rotations only, right stays on the user's left as seen from the turtle
            assertEquals(1f, det(Quat4.axisAngle(Vec3.UP, field.yaw)), 1e-4f)
            assertEquals(1f, det(Quat4.axisAngle(Vec3.UP, field.yaw) * Quat4.axisAngle(Vec3.UP, -0.4f)), 1e-4f)
            assertTrue("$name: right x forward must be up", field.right.cross(field.forward).dot(Vec3.UP) > 0.99f)
        }
    }

    @Test
    fun facingIgnoresHeightAndKeepsLevel() {
        val center = Vec3(0f, 0f, 0f)
        val user = Vec3(cos(1.1).toFloat() * 2f, 1.7f, sin(1.1).toFloat() * 2f)
        val field = FloorPose(center, FloorPose.yawToward(center, user))
        assertEquals(0f, field.forward.y, 1e-6f)
        assertTrue(angleDeg(field.forward, Vec3(user.x, 0f, user.z)) < 0.5f)
    }

    @Test
    fun aRightTurnOfTheTurtleIsClockwiseSeenFromAbove() {
        // heading 90 = the turtle's right; for a field facing the user that is the user's left (no mirror)
        val center = Vec3(0f, 0f, 0f)
        val user = Vec3(0f, 1.6f, 2f)
        val field = FloorPose(center, FloorPose.yawToward(center, user))
        val right = turtleHeadWorld(field, 90f)
        val fromField = field.toStage(P2.heading(90f).x, P2.heading(90f).y) - center
        assertTrue(angleDeg(right, fromField) < 1f)
        // clockwise from above: heading 0 -> 90 turns forward towards right; cross(forward, right) points down
        assertTrue(field.forward.cross(right).y < -0.99f)
    }
}
