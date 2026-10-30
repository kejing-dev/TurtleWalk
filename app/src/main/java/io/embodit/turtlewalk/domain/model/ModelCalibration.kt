package io.embodit.turtlewalk.domain.model

import kotlin.math.abs

/** An axis-aligned box (model units or metres). */
data class Box3(val min: Vec3, val max: Vec3) {
    val size: Vec3
        get() = max - min

    val center: Vec3
        get() = (min + max) * 0.5f

    /** Centre of the bottom face: where a standing character touches the floor. */
    val bottomCenter: Vec3
        get() = Vec3((min.x + max.x) / 2f, min.y, (min.z + max.z) / 2f)

    val isEmpty: Boolean
        get() = !(max.x > min.x && max.y > min.y && max.z > min.z)

    fun corners(): List<Vec3> =
        listOf(
            Vec3(min.x, min.y, min.z), Vec3(max.x, min.y, min.z), Vec3(min.x, max.y, min.z), Vec3(max.x, max.y, min.z),
            Vec3(min.x, min.y, max.z), Vec3(max.x, min.y, max.z), Vec3(min.x, max.y, max.z), Vec3(max.x, max.y, max.z),
        )

    companion object {
        fun of(points: Iterable<Vec3>): Box3 {
            var lo = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            var hi = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            for (p in points) {
                lo = Vec3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z))
                hi = Vec3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z))
            }
            return Box3(lo, hi)
        }
    }
}

/**
 * What an imported model is (taste D25): its real length and where its head points in the file's own space.
 * Pivot, axes and units of a Sketchfab / Tripo / editor export are never trusted as "origin under the feet, face
 * ahead, one unit = one metre"; [ModelCalibration] measures the file and corrects it.
 */
data class ModelSpec(
    /** Nose-to-tail length in metres, from the design package. */
    val lengthM: Float,
    /** The head's direction in the file's own space (after our export: -Z). */
    val forward: Vec3,
    /**
     * The head's direction if the loader ignored the export's root transform and the raw scan's axes show through
     * (the raw turtle scan has its head along -X). Null = no fallback.
     */
    val rawForward: Vec3? = null,
)

/**
 * The local transform of the calibration node (position [offset], [rotation], uniform [scale]); it is applied to a
 * model-space point as scale, then rotate, then move, which is what an entity's TRS does.
 */
data class Calibration(val scale: Float, val rotation: Quat4, val offset: Vec3, val forward: Vec3, val fromRaw: Boolean) {
    fun apply(p: Vec3): Vec3 = rotation.rotate(p * scale) + offset

    fun apply(box: Box3): Box3 = Box3.of(box.corners().map(::apply))
}

/**
 * Taste D25: one calibration node between the mover and the model. From the model's measured bounding box it
 * puts the bottom centre on the origin (a walking character stands on its feet), turns the head to the app's
 * forward ([Vec3.AHEAD], local -Z, the direction every floor thing is built facing), keeps the back up and scales
 * the model to the designed length. Walking, turning and the waddle move the node's parents, never the model.
 */
object ModelCalibration {
    fun calibrate(box: Box3, spec: ModelSpec): Calibration {
        val size = box.size
        fun along(axis: Vec3): Float = abs(size.x * axis.x) + abs(size.y * axis.y) + abs(size.z * axis.z)
        var fwd = spec.forward.normalized()
        var fromRaw = false
        val raw = spec.rawForward
        // a turtle is longer than it is wide: a longer side axis means the file's root transform did not come through
        if (raw != null && along(fwd) < along(Vec3.UP.cross(fwd))) {
            fwd = raw.normalized()
            fromRaw = true
        }
        val length = along(fwd)
        val scale = if (length > 1e-6f) spec.lengthM / length else 1f
        val rotation = Quat4.fromTo(fwd, Vec3.AHEAD)
        val turned = Box3.of(box.corners().map { rotation.rotate(it * scale) })
        return Calibration(scale, rotation, -turned.bottomCenter, fwd, fromRaw)
    }
}

/** The turtle asset (ASSETS.md, `tools/turtle_model.py export`): every number about it lives here. */
object TurtleAsset {
    const val PATH = "models/turtle.glb"

    /** Design: the shell is about 240 mm plus the head; the user asked for a bigger one (deviation v0.1.3): 0.35 m nose to tail. */
    const val LENGTH_M = 0.35f

    val SPEC = ModelSpec(lengthM = LENGTH_M, forward = Vec3.AHEAD, rawForward = Vec3(-1f, 0f, 0f))

    /** The exported file's box (`turtle_model.py inspect`): Y-up, feet on y = 0, head along -Z, length 1. Fallback when the SDK reports no bounds. */
    val EXPORTED_BOX = Box3(Vec3(-0.3246f, 0f, -0.5f), Vec3(0.3246f, 0.2998f, 0.5f))

    /** The raw scan's box, for the "root transform dropped" fallback test. */
    val RAW_BOX = Box3(Vec3(-0.4608f, 0.0425f, -0.6317f), Vec3(-0.1699f, 0.1297f, -0.4429f))
}
