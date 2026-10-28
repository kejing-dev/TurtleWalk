package io.embodit.turtlewalk.ui.turtle.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.turtlewalk.domain.model.P2
import io.embodit.turtlewalk.domain.model.Quat4
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.Vec3
import io.embodit.turtlewalk.domain.usecase.FloorPose
import io.embodit.turtlewalk.domain.usecase.Side
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Everything on the floor hangs under a group entity that carries a field pose: local x = the field's
 * right, local -z = the field's front, y = up from the floor. Field points come in steps and are turned into
 * local metres by `step` (one step = field / 2), so a new field size only lays the children out again.
 */

/** Local (x, z) metres of a field point in steps. */
internal fun P2.local(step: Float): Pair<Float, Float> = (x * step) to (-y * step)

/** Rotation of a thing built facing local -Z so that it faces turtle heading [deg] (0 = front, right positive). */
internal fun headingRotation(deg: Float): Quat4 = Quat4.axisAngle(Vec3.UP, -Math.toRadians(deg.toDouble()).toFloat())

/** A group that follows a field pose. */
internal class FieldGroup(root: Entity, name: String) {
    val entity = Entity().named(name)
    private var on = true

    init {
        root.addChild(entity)
        setShown(false)
    }

    fun setPose(pose: FloorPose, floorY: Float) {
        entity.setLocal(position = Vec3(pose.center.x, floorY, pose.center.z), rotation = Quat4.axisAngle(Vec3.UP, pose.yaw))
    }

    fun setShown(shown: Boolean) {
        if (shown != on) {
            on = shown
            entity.enabled = shown
        }
    }

    fun destroy() = runCatching { entity.destroy(true) }
}

/**
 * A pool of flat bars made from the unit cube: each bar lies on the floor between two local points.
 * Bars that are not used are disabled, never destroyed.
 */
internal class BarPool(parent: Entity, cube: MeshResource, private val material: Material, size: Int, name: String) {
    private val bars = ArrayList<ModelEntity>(size)
    private val on = BooleanArray(size)
    private val mats = arrayOfNulls<Material>(size)
    var used = 0
        private set

    init {
        for (i in 0 until size) {
            val e = ModelEntity(cube, material)
            e.named("${name}_$i")
            parent.addChild(e)
            e.enabled = false
            bars += e
            mats[i] = material
        }
    }

    val size: Int
        get() = bars.size

    /** Bar [i] from (ax, az) to (bx, bz) local metres, [width] wide, [thick] thick, its underside [lift] above the floor. */
    fun bar(i: Int, ax: Float, az: Float, bx: Float, bz: Float, width: Float, thick: Float, lift: Float, material: Material? = null) {
        if (i >= bars.size) return
        val dx = bx - ax
        val dz = bz - az
        val len = hypot(dx, dz)
        val e = bars[i]
        if (len < 1e-4f) {
            hide(i)
            return
        }
        val yaw = atan2(dx, dz)
        e.setLocal(position = Vec3((ax + bx) / 2f, lift + thick / 2f, (az + bz) / 2f), rotation = Quat4.axisAngle(Vec3.UP, yaw), scale = Vec3(width, thick, len))
        val m = material ?: this.material
        if (mats[i] !== m) {
            e.setMaterial(m)
            mats[i] = m
        }
        if (!on[i]) {
            e.enabled = true
            on[i] = true
        }
        used = max(used, i + 1)
    }

    /** Changes the material of bar [i] without moving it. */
    fun setMaterialOf(i: Int, m: Material) {
        if (i < bars.size && mats[i] !== m) {
            bars[i].setMaterial(m)
            mats[i] = m
        }
    }

    fun hide(i: Int) {
        if (i < bars.size && on[i]) {
            bars[i].enabled = false
            on[i] = false
        }
    }

    /** Hides every bar from [i] on. */
    fun hideFrom(i: Int) {
        for (k in i until min(used, bars.size)) hide(k)
        if (i < used) used = i
    }

    /** Lays [p] .. [q] (local metres) as dashes of [dash] with gaps of [gapM] starting at bar [first]; returns the next free bar. */
    fun dashes(first: Int, ax: Float, az: Float, bx: Float, bz: Float, dash: Float, gapM: Float, width: Float, thick: Float, lift: Float, material: Material? = null): Int {
        val len = hypot(bx - ax, bz - az)
        if (len < 1e-4f) return first
        var k = first
        var t = 0f
        while (t < len && k < bars.size) {
            val e = min(len, t + dash)
            bar(k++, ax + (bx - ax) * t / len, az + (bz - az) * t / len, ax + (bx - ax) * e / len, az + (bz - az) * e / len, width, thick, lift, material)
            t += dash + gapM
        }
        return k
    }

    fun destroy() = bars.forEach { runCatching { it.destroy(true) } }
}

/**
 * The field tape (design `entities3d.field`): an F x F square of 25 mm white dashed tape, a chevron at the
 * middle of the front edge, a small cross at every whole-step point. Laid out again when the size changes;
 * 60 % while placing, 40 % placed, 80 % snapped, the crowded edge orange. Also used for the snap frame (yellow).
 */
internal class TapeView(parent: Entity, cube: MeshResource, private val materials: SceneMaterials, private val frame: Boolean) {
    val group = FieldGroup(parent, if (frame) "snapFrame" else "fieldTape")
    private val pool = BarPool(group.entity, cube, if (frame) materials.snapFrame else materials.tapePlacing, 90, if (frame) "frame" else "tape")
    private var laidFor = -1f
    private var laidSolid = false
    private var edgeOf = IntArray(0)
    private var style: Material? = null
    private var alert: Side? = null

    /** Lays the tape for [sizeM]; [solid] = snapped: the dashes close into solid lines (design +snapped). */
    fun layout(sizeM: Float, solid: Boolean = false) {
        if (abs(sizeM - laidFor) < 1e-4f && solid == laidSolid) return
        laidFor = sizeM
        laidSolid = solid
        val h = sizeM / 2f
        val width = if (frame) 0.02f else 0.025f
        val n = 10
        val pitch = sizeM / n
        val dash = if (solid) pitch else pitch * 0.6f
        val sides = ArrayList<Int>()
        var k = 0
        // edges: front (local -z), back, left, right
        val edges = listOf(Side.FRONT to floatArrayOf(-h, -h, h, -h), Side.BACK to floatArrayOf(-h, h, h, h), Side.LEFT to floatArrayOf(-h, -h, -h, h), Side.RIGHT to floatArrayOf(h, -h, h, h))
        for ((side, e) in edges) {
            for (i in 0 until n) {
                val t0 = i * pitch + (pitch - dash) / 2f
                val t1 = t0 + dash
                val ax = e[0] + (e[2] - e[0]) * t0 / sizeM
                val az = e[1] + (e[3] - e[1]) * t0 / sizeM
                val bx = e[0] + (e[2] - e[0]) * t1 / sizeM
                val bz = e[1] + (e[3] - e[1]) * t1 / sizeM
                pool.bar(k, ax, az, bx, bz, width, 0.002f, 0f, style)
                sides += side.ordinal
                k++
            }
        }
        if (!frame) {
            // the "front" chevron, pointing ahead, just outside the front edge
            val tipZ = -h - 0.16f
            for (sign in listOf(-1f, 1f)) {
                pool.bar(k, 0f, tipZ, sign * 0.12f, tipZ + 0.12f, width, 0.002f, 0f, style)
                sides += Side.FRONT.ordinal
                k++
            }
            // a 60 mm cross at every whole-step point (steps -1, 0, 1)
            val step = sizeM / TurtleRules.FIELD_STEPS
            for (ix in -1..1) for (iy in -1..1) {
                val cx = ix * step
                val cz = iy * step
                pool.bar(k, cx - 0.03f, cz, cx + 0.03f, cz, 0.012f, 0.002f, 0f, style)
                sides += -1
                k++
                pool.bar(k, cx, cz - 0.03f, cx, cz + 0.03f, 0.012f, 0.002f, 0f, style)
                sides += -1
                k++
            }
        }
        pool.hideFrom(k)
        edgeOf = sides.toIntArray()
        restyle()
    }

    /** Placing / placed / snapped and the crowded side (field tape only). */
    fun setStyle(material: Material, alert: Side?) {
        if (material === style && alert == this.alert) return
        style = material
        this.alert = alert
        restyle()
    }

    private fun restyle() {
        if (frame) return
        val base = style ?: materials.tapePlacing
        // re-lay with the same geometry is cheap enough: only the materials change
        for (i in edgeOf.indices) {
            val m = if (alert != null && edgeOf[i] == alert!!.ordinal) materials.tapeAlert else base
            pool.setMaterialOf(i, m)
        }
    }

    fun destroy() = group.destroy()
}

/**
 * The turtle (design `entities3d.turtle`): a pointer made of basic shapes, not a character. A flattened
 * half-sphere shell (240 x 80 mm) with six light patches, a head ball (70 mm) ahead, four flat flippers and a
 * yellow V on the floor ahead of its nose. 13 entities; its size does not follow the step.
 */
internal class TurtleBody(parent: Entity, sphere: MeshResource, cylinder: MeshResource, cube: MeshResource, materials: SceneMaterials) {
    val group = Entity().named("turtle")
    private var at = P2(Float.NaN, 0f)
    private var heading = Float.NaN
    private var step = -1f
    private var on = true

    init {
        parent.addChild(group)
        fun part(mesh: MeshResource, m: Material, name: String, pos: Vec3, scale: Vec3, rot: Quat4 = Quat4.IDENTITY) {
            val e = ModelEntity(mesh, m)
            e.named("turtle_$name")
            group.addChild(e)
            e.setLocal(position = pos, rotation = rot, scale = scale)
        }
        part(sphere, materials.shell, "shell", Vec3(0f, 0f, 0f), Vec3(0.12f, 0.08f, 0.12f))
        for (i in 0 until 6) {
            val a = 2 * PI * i / 6
            val r = 0.055f
            val x = (kotlin.math.sin(a) * r).toFloat()
            val z = (-kotlin.math.cos(a) * r).toFloat()
            // the patches sit on the shell's slope at r: height of the ellipsoid there
            val y = 0.08f * kotlin.math.sqrt((1f - (r / 0.12f) * (r / 0.12f)).coerceAtLeast(0f)) - 0.004f
            part(cylinder, materials.patch, "patch$i", Vec3(x, y, z), Vec3(1f, 1f, 1f))
        }
        part(sphere, materials.skin, "head", Vec3(0f, 0.035f, -0.15f), Vec3(0.035f, 0.035f, 0.035f))
        for ((i, p) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).withIndex()) {
            val (sx, sz) = p
            part(sphere, materials.skin, "flipper$i", Vec3(sx * 0.11f, 0.01f, sz * 0.07f), Vec3(0.045f, 0.012f, 0.028f), Quat4.axisAngle(Vec3.UP, sx * sz * 0.6f))
        }
        // the heading V on the floor ahead of the nose
        for (sign in listOf(-1f, 1f)) {
            part(cube, materials.nose, "nose$sign", Vec3(sign * 0.03f, 0.002f, -0.24f), Vec3(0.012f, 0.003f, 0.09f), Quat4.axisAngle(Vec3.UP, sign * 0.6f))
        }
    }

    /** At field point [p] (steps) facing [deg]. */
    fun place(p: P2, deg: Float, stepM: Float) {
        if (p == at && deg == heading && stepM == step) return
        at = p
        heading = deg
        step = stepM
        val (x, z) = p.local(stepM)
        group.setLocal(position = Vec3(x, 0.003f, z), rotation = headingRotation(deg))
    }

    fun setShown(shown: Boolean) {
        if (shown != on) {
            on = shown
            group.enabled = shown
        }
    }
}

/**
 * The turn ring (design `entities3d.turnRing`, signature): 72 pieces of 5 degrees on a ring 0.9 step across
 * (at least 0.45 m), base 20 % white; a right turn lights clockwise in coral, a left one anticlockwise in cyan;
 * every full lap flashes white -> gold for 0.6 s and adds a dot outside the ring at 12 o'clock (up to 7).
 */
internal class RingView(parent: Entity, arc: MeshResource, dot: MeshResource, private val materials: SceneMaterials) {
    val group = Entity().named("turnRing")
    private val pieces = ArrayList<ModelEntity>(TurtleRules.RING_PIECES)
    private val pieceMat = arrayOfNulls<Material>(TurtleRules.RING_PIECES)
    private val dots = ArrayList<ModelEntity>()
    private var shownLaps = -1
    private var lit = -1
    private var sign = 0
    private var flashUntil = 0L
    private var lastLaps = 0
    private var radius = -1f
    private var on = true

    init {
        parent.addChild(group)
        for (i in 0 until TurtleRules.RING_PIECES) {
            val e = ModelEntity(arc, materials.ringBase)
            e.named("ring_$i")
            group.addChild(e)
            e.setLocal(rotation = headingRotation(i * 5f))
            pieces += e
            pieceMat[i] = materials.ringBase
        }
        for (i in 0 until TurtleRules.RING_LAPS_SHOWN) {
            val e = ModelEntity(dot, materials.ringWhite)
            e.named("lap_$i")
            group.addChild(e)
            e.enabled = false
            dots += e
        }
    }

    /** Centre (local metres) and radius from the step. */
    fun place(x: Float, z: Float, stepM: Float) {
        val r = max(TurtleRules.RING_DIAMETER_STEPS * stepM, TurtleRules.RING_MIN_DIAMETER_M) / 2f
        group.setLocal(position = Vec3(x, 0.004f, z))
        if (abs(r - radius) > 1e-4f) {
            radius = r
            for (p in pieces) p.setLocal(scale = Vec3(r, 1f, r))
            for ((i, d) in dots.withIndex()) d.setLocal(position = Vec3((i - (dots.size - 1) / 2f) * 0.045f, 0f, -r - 0.06f))
        }
    }

    /** Shows [deg] (signed, right positive); [laps] full laps are dots; a new lap flashes. */
    fun show(deg: Float, nowMs: Long) {
        val a = abs(deg)
        val laps = floor(a / 360f + 1e-4f).toInt()
        val rem = a - laps * 360f
        if (laps > lastLaps) flashUntil = nowMs + 600L
        lastLaps = laps
        // a whole number of laps shows the ring closed (the shape is done), not empty
        val n = if (laps > 0 && rem < 0.5f) 72 else floor(rem / 5f + 1e-3f).toInt().coerceIn(0, 72)
        val s = if (deg >= 0f) 1 else -1
        val flashing = nowMs < flashUntil
        val key = if (flashing) -2 else n
        if (key != lit || s != sign) {
            lit = key
            sign = s
            for (i in 0 until TurtleRules.RING_PIECES) {
                val m =
                    when {
                        flashing -> materials.ringFlash
                        s > 0 && i < n -> materials.ringRight
                        s < 0 && i >= TurtleRules.RING_PIECES - n -> materials.ringLeft
                        else -> materials.ringBase
                    }
                if (pieceMat[i] !== m) {
                    pieces[i].setMaterial(m)
                    pieceMat[i] = m
                }
            }
        }
        val shown = min(laps, dots.size)
        if (shown != shownLaps) {
            shownLaps = shown
            for ((i, d) in dots.withIndex()) d.enabled = i < shown
        }
    }

    /** Starts from zero (no flash). */
    fun reset() {
        lastLaps = 0
        flashUntil = 0L
        lit = -1
    }

    /** Forces a closing flash now (turn home / a closed drawing). */
    fun flash(nowMs: Long) {
        flashUntil = nowMs + 600L
        lit = -1
    }

    fun setShown(shown: Boolean) {
        if (shown != on) {
            on = shown
            group.enabled = shown
        }
    }
}

/**
 * Corner wedges (design `entities3d.turnWedge`): for the first 12 corners in run order, pie pieces of 15
 * degrees (radius 0.16 step) from the old heading to the new one, coral right / cyan left at 50 %. A corner
 * over 180 degrees is split into 12 equal pieces.
 */
internal class WedgeView(parent: Entity, sector: MeshResource, private val materials: SceneMaterials) {
    private val pieces = ArrayList<ModelEntity>()
    private val on = BooleanArray(TurtleRules.TAGS * TurtleRules.WEDGE_MAX_PIECES)
    private val mats = arrayOfNulls<Material>(on.size)

    init {
        for (i in on.indices) {
            val e = ModelEntity(sector, materials.wedgeRight)
            e.named("wedge_$i")
            parent.addChild(e)
            e.enabled = false
            pieces += e
            mats[i] = materials.wedgeRight
        }
    }

    /** Corner [c] at local (x, z) from heading [from] turned by [by] so far (signed), radius in metres. */
    fun corner(c: Int, x: Float, z: Float, from: Float, by: Float, radius: Float) {
        if (c >= TurtleRules.TAGS) return
        val a = abs(by)
        val n = if (a < 0.5f) 0 else min(TurtleRules.WEDGE_MAX_PIECES, ceil(a / TurtleRules.WEDGE_PIECE_DEG - 1e-3f).toInt())
        val span = if (n == 0) 0f else if (a > 180f) a / n else TurtleRules.WEDGE_PIECE_DEG
        val m = if (by >= 0f) materials.wedgeRight else materials.wedgeLeft
        for (k in 0 until TurtleRules.WEDGE_MAX_PIECES) {
            val i = c * TurtleRules.WEDGE_MAX_PIECES + k
            val e = pieces[i]
            if (k < n) {
                // piece k covers [start, start + 15] clockwise from its start heading; the last one ends at the new heading
                val startCw = if (by >= 0f) min(from + k * span, from + a - TurtleRules.WEDGE_PIECE_DEG) else max(from - (k + 1) * span, from - a)
                e.setLocal(position = Vec3(x, 0.0025f + c * 0.0001f, z), rotation = headingRotation(startCw), scale = Vec3(radius, 1f, radius))
                if (mats[i] !== m) {
                    e.setMaterial(m)
                    mats[i] = m
                }
                if (!on[i]) {
                    e.enabled = true
                    on[i] = true
                }
            } else if (on[i]) {
                e.enabled = false
                on[i] = false
            }
        }
    }

    fun hideFrom(c: Int) {
        for (i in c * TurtleRules.WEDGE_MAX_PIECES until on.size) {
            if (on[i]) {
                pieces[i].enabled = false
                on[i] = false
            }
        }
    }
}

/** An arrow on the floor: a shaft and a two-bar head, all from one bar pool. */
internal class FloorArrow(private val pool: BarPool, private val first: Int) {
    /** From (ax, az) to (bx, bz) local metres; [width] of the bars. */
    fun draw(ax: Float, az: Float, bx: Float, bz: Float, width: Float, material: Material) {
        val dx = bx - ax
        val dz = bz - az
        val len = hypot(dx, dz)
        if (len < 1e-3f) {
            hide()
            return
        }
        val ux = dx / len
        val uz = dz / len
        val head = min(0.12f, len * 0.4f)
        pool.bar(first, ax, az, bx, bz, width, 0.003f, 0.006f, material)
        for ((k, s) in listOf(-1f, 1f).withIndex()) {
            // the head legs at 35 degrees back from the tip
            val c = kotlin.math.cos(0.61f)
            val sn = kotlin.math.sin(0.61f) * s
            val lx = -(ux * c - uz * sn)
            val lz = -(uz * c + ux * sn)
            pool.bar(first + 1 + k, bx, bz, bx + lx * head, bz + lz * head, width, 0.003f, 0.006f, material)
        }
    }

    fun hide() {
        for (k in 0 until 3) pool.hide(first + k)
    }
}
