package io.embodit.turtlewalk.domain.usecase

import io.embodit.turtlewalk.domain.model.PlaneSurface
import io.embodit.turtlewalk.domain.model.SurfaceKind
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The field's place: centre on the floor and the heading of its "front" (yaw about the vertical, radians). */
data class FloorPose(val center: Vec3, val yaw: Float) {
    /** Unit vector of the field's +y (front) in Stage space. */
    val forward: Vec3
        get() = Vec3(-sin(yaw), 0f, -cos(yaw))

    /** Unit vector of the field's +x (right). */
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    fun toStage(x: Float, y: Float, lift: Float = 0f): Vec3 = center + right * x + forward * y + Vec3(0f, lift, 0f)

    /** Field-local (x, y) metres of a Stage point. */
    fun toLocal(p: Vec3): Pair<Float, Float> {
        val d = p - center
        return d.dot(right) to d.dot(forward)
    }

    companion object {
        /** Yaw of a horizontal heading. */
        fun yawOf(forward: Vec3, fallback: Float): Float =
            if (kotlin.math.hypot(forward.x, forward.z) < 0.05f) fallback else atan2(-forward.x, -forward.z)

        /**
         * Taste D10 (placement facing): the yaw whose front (+y of the field, the turtle's heading 0, the
         * chevron and the readable side of every floor mark) points from [content] to [user], level. Computed
         * once when something is placed and locked afterwards; never from a ray direction, the world origin or
         * an earlier yaw.
         */
        fun yawToward(content: Vec3, user: Vec3, fallback: Float = 0f): Float = yawOf(Vec3(user.x - content.x, 0f, user.z - content.z), fallback)

        fun wrapRad(a: Float): Float {
            var d = a
            while (d > PI.toFloat()) d -= (2 * PI).toFloat()
            while (d < -PI.toFloat()) d += (2 * PI).toFloat()
            return d
        }
    }
}

enum class Side { FRONT, BACK, LEFT, RIGHT }

/** Which side of the field is crowded by what, and how far to move away (design `+cramped`). */
data class Cramped(val side: Side, val what: SurfaceKind, val moveCm: Int)

data class PlaceCheck(
    val cramped: Cramped?,
    /** A wall in front of the front edge is nearer than 0.45 m: the code panel would not fit; cm to move. */
    val frontTooCloseCm: Int?,
    /** A wall runs through the field. */
    val wallCrosses: Boolean = false,
) {
    val canPlace: Boolean
        get() = frontTooCloseCm == null && !wallCrosses

    companion object {
        val CLEAR = PlaceCheck(null, null, false)
    }
}

/** A suggested place along a wall (design `entities3d.snapFrame`). */
data class SnapFrame(val pose: FloorPose)

/**
 * Placement rules (design `rules.placeField`, `entities3d.snapFrame`): furniture inside the field turns
 * that edge orange (still placeable); a wall through the field or a wall closer than 0.45 m in front of the
 * front edge (the code panel stands there) blocks the placement. Near a wall a frame of the same size is
 * offered parallel to it, 0.3 m off the wall (0.5 m when the front edge faces it).
 */
class PlaceFloorUseCase {

    fun check(pose: FloorPose, half: Float, planes: Collection<PlaneSurface>, floorY: Float): PlaceCheck {
        var cramped: Cramped? = null
        var crampedDepth = 0f
        var frontWall = Float.MAX_VALUE
        var crosses = false
        for (p in planes) {
            if (p.kind == SurfaceKind.FLOOR || p.kind == SurfaceKind.CEILING || p.vertices.size < 3) continue
            val vertical = abs(p.normal.y) < 0.5f
            if (!vertical && (p.minY < floorY + 0.05f || p.minY > floorY + 1.6f)) continue
            if (vertical && p.maxY < floorY + 0.2f) continue
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (v in p.vertices) {
                val (x, z) = pose.toLocal(v)
                minX = min(minX, x)
                maxX = max(maxX, x)
                minZ = min(minZ, z)
                maxZ = max(maxZ, z)
            }
            val wallLike = vertical && isWall(p.kind)
            if (wallLike && minX < half && maxX > -half && minZ > half && minZ < frontWall) frontWall = minZ
            val overlapX = min(maxX, half) - max(minX, -half)
            val overlapZ = min(maxZ, half) - max(minZ, -half)
            if (overlapX <= 0f || overlapZ <= 0f) continue
            if (wallLike) {
                crosses = true
                continue
            }
            val candidates = listOf(Side.RIGHT to half - minX, Side.LEFT to maxX + half, Side.FRONT to half - minZ, Side.BACK to maxZ + half)
            val (side, depth) = candidates.minByOrNull { it.second } ?: continue
            if (depth <= 0f) continue
            if (cramped == null || depth > crampedDepth) {
                cramped = Cramped(side, p.kind, roundUp5(depth))
                crampedDepth = depth
            }
        }
        val gap = frontWall - half
        val tooClose = if (gap < TurtleRules.FRONT_WALL_MIN_M) roundUp5(TurtleRules.FRONT_WALL_MIN_M - gap) else null
        return PlaceCheck(cramped, tooClose, crosses)
    }

    /** The largest size (2, 1.5, 1 m) whose field at [center] / [yaw] no wall crosses (design: auto-pick). */
    fun largestFitting(center: Vec3, yaw: Float, planes: Collection<PlaneSurface>, floorY: Float, preferred: Float): Float {
        for (size in TurtleRules.FIELD_SIZES) {
            if (size > preferred + 1e-3f) continue
            val c = check(FloorPose(center, yaw), size / 2f, planes, floorY)
            if (c.canPlace) return size
        }
        return TurtleRules.FIELD_SIZES.last()
    }

    /**
     * The snap frame for a field of [half] at [pose]: the nearest wall within 1.5 m of the centre whose
     * length is at least 1 m, the field turned to the wall's square nearest its yaw, the wall-side edge
     * 0.3 m off the wall (0.5 m if that edge is the front). Null when there is no such wall.
     */
    fun snapFrame(pose: FloorPose, half: Float, planes: Collection<PlaneSurface>): SnapFrame? {
        var best: SnapFrame? = null
        var bestD = TurtleRules.SNAP_WALL_RANGE_M
        for (p in planes) {
            if (abs(p.normal.y) > 0.5f || !isWall(p.kind)) continue
            val n0 = Vec3(p.normal.x, 0f, p.normal.z)
            if (n0.length < 0.1f) continue
            var n = n0.normalized()
            // the normal pointing from the wall towards the field centre
            if ((pose.center - p.centroid).dot(n) < 0f) n = -n
            val along = Vec3(-n.z, 0f, n.x)
            val ts = p.vertices.map { (it - p.centroid).dot(along) }
            val length = (ts.maxOrNull() ?: 0f) - (ts.minOrNull() ?: 0f)
            if (length < TurtleRules.SNAP_WALL_MIN_LEN_M) continue
            val dist = (pose.center - p.centroid).dot(n)
            if (dist > bestD || dist < 0f) continue
            // the field's four sides: which one would face the wall for each square turn
            val wallYaw = FloorPose.yawOf(-n, pose.yaw) // yaw whose front faces the wall
            var bestTurn = 0
            var bestDiff = Float.MAX_VALUE
            for (k in 0 until 4) {
                val y = wallYaw + k * (PI / 2).toFloat()
                val d = abs(FloorPose.wrapRad(y - pose.yaw))
                if (d < bestDiff) {
                    bestDiff = d
                    bestTurn = k
                }
            }
            val yaw = wallYaw + bestTurn * (PI / 2).toFloat()
            val frontFacesWall = bestTurn == 0
            val gap = if (frontFacesWall) TurtleRules.SNAP_WALL_GAP_FRONT_M else TurtleRules.SNAP_WALL_GAP_M
            val wallPoint = pose.center - n * dist
            val center = wallPoint + n * (gap + half)
            bestD = dist
            best = SnapFrame(FloorPose(Vec3(center.x, pose.center.y, center.z), FloorPose.wrapRad(yaw)))
        }
        return best
    }

    private fun isWall(kind: SurfaceKind) = kind == SurfaceKind.WALL || kind == SurfaceKind.DOOR || kind == SurfaceKind.WINDOW || kind == SurfaceKind.OTHER

    private fun roundUp5(m: Float): Int = (ceil(m * 100f / 5f).toInt() * 5).coerceAtLeast(5)
}
