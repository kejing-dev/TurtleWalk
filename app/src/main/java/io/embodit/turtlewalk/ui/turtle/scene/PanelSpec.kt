package io.embodit.turtlewalk.ui.turtle.scene

import io.embodit.turtlewalk.domain.model.TurtleRules

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val CODE = "codePanel"
    const val MENU = "moreMenu"
    const val SNACK = "snackbar"
    const val DIALOG = "blockedDialog"
    const val COACH = "coach"

    /** Width / height in dp. The code panel is 820 x 560 at scale 1.2 (1 dp = 0.96 mm: 0.787 x 0.538 m). */
    val CODE_SIZE = 820f to 560f
    val MENU_SIZE = 280f to 360f
    val SNACK_SIZE = 600f to 72f
    val DIALOG_SIZE = 480f to 240f
    val COACH_SIZE = 400f to 136f
    val TAG_SIZE = 120f to 56f

    const val CODE_SCALE = 1.2f

    /** Degree tags: 28 sp at 1.6 (1 dp = 1.28 mm) reads at 3 m (design `entities3d.turnWedge`, checked at 3 m). */
    const val TAG_SCALE = 1.6f

    /** The dialog stands 0.9 m ahead at 1.125. */
    const val DIALOG_SCALE = 1.125f
    const val DIALOG_DISTANCE_M = 0.9f

    fun tag(i: Int) = "tag$i"

    val tags: List<String> = (0 until TurtleRules.TAGS).map { tag(it) }

    val pool: List<String> = listOf(CODE, MENU, SNACK, DIALOG, COACH) + tags

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CODE -> CODE_SIZE
            MENU -> MENU_SIZE
            SNACK -> SNACK_SIZE
            DIALOG -> DIALOG_SIZE
            COACH -> COACH_SIZE
            else -> TAG_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
