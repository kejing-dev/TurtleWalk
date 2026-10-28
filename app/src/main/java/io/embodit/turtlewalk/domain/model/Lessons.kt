package io.embodit.turtlewalk.domain.model

import io.embodit.turtlewalk.domain.usecase.Program

/**
 * The ten lessons (design `lessons[]`, direction A with B's opening: lessons 1 and 2 walk the turtle to a star).
 * Every target is the reference solution run from the lesson's start; nothing else is stored.
 */
object Lessons {
    private val FLR = listOf(BlockKind.FORWARD, BlockKind.LEFT, BlockKind.RIGHT)
    private val ALL = listOf(BlockKind.FORWARD, BlockKind.LEFT, BlockKind.RIGHT, BlockKind.REPEAT)

    private fun p(text: String): List<Block> = Program.parse(text) ?: error("bad lesson program $text")

    val all: List<Lesson> =
        listOf(
            Lesson(
                1, "line", "走到星星", "Walk to the Star", listOf(BlockKind.FORWARD), TurtlePose(P2(0f, -0.5f), 0f), p("F1"),
                "1 步就是你脚下的 1 米（或本房间的步长）。", "One step is one metre on your floor (or this room's step).",
                "前进几步才能到星星？", "How many steps forward to reach the star?", star = true,
            ),
            Lesson(
                2, "corner", "拐弯拿星星", "Turn to the Star", FLR, TurtlePose(P2(0.5f, -0.5f), 0f), p("F1 L90 F1"),
                "左转是逆时针，转角环往另一边填。", "A left turn goes anticlockwise: the turn ring fills the other way.",
                "走到拐角，往哪边转？", "At the corner, which way do you turn?", star = true,
            ),
            Lesson(
                3, "square", "正方形", "Square", FLR, TurtlePose(P2(-0.5f, -0.5f), 0f), p("F1 R90 F1 R90 F1 R90 F1 R90"),
                "转了 4 次 90°，一共 360°——正好一整圈。", "Four turns of 90° make 360°: exactly one full turn.",
                "每个角都一样：前进 1，转 90°，做 4 次。", "Every corner is the same: forward 1, turn 90°, four times.",
            ),
            Lesson(
                4, "repeat", "用重复", "Use Repeat", ALL, TurtlePose(P2(-0.5f, -0.5f), 0f), p("R4[F1 R90]"),
                "一样的事做 4 遍，就用「重复」。", "Doing the same thing four times? Use Repeat.",
                "把一样的两块放进「重复」里。", "Put the two blocks that repeat inside Repeat.", maxBlocks = 3,
            ),
            Lesson(
                5, "triangle", "三角形", "Triangle", ALL, TurtlePose(P2(-0.433f, -0.5f), 0f), p("R3[F1 R120]"),
                "三角形的角是 60°，可海龟每次要转 120°——转的是「方向改变了多少」。3 × 120° 还是 360°。",
                "A triangle's corner is 60°, but the turtle turns 120°: it turns by how much its direction changes. 3 × 120° is again 360°.",
                "转 60° 试试看会怎样？再想想你走的方向改变了多少。", "Try 60° and see. Then think: how much does your direction change?",
                sixtyHint = true,
            ),
            Lesson(
                6, "pentagon", "五边形", "Pentagon", ALL, TurtlePose(P2(-0.769f, -0.5f), 0f), p("R5[F1 R72]"),
                "360 ÷ 5 = 72。几条边都一样：每次转 360 ÷ 边数。", "360 ÷ 5 = 72. Equal sides: each turn is 360 ÷ the number of sides.",
                "5 次转弯加起来要一整圈。", "Five turns together make one full turn.",
            ),
            Lesson(
                7, "star", "五角星", "Star", ALL, TurtlePose(P2(-0.168f, -0.75f), 0f), p("R5[F1.5 R144]"),
                "回到起点时转了 720°——两整圈。封闭图形转的总是整圈数。", "Back at the start you turned 720°: two full turns. A closed shape always turns whole turns.",
                "星的尖很尖：转的角比 90° 大得多。", "The star's points are sharp: the turn is much bigger than 90°.",
            ),
            Lesson(
                8, "circle", "圆", "Circle", ALL, TurtlePose(P2(-0.467f, -0.125f), 0f), p("R12[F0.25 R30]"),
                "很多个小弯也拼成一整圈 360°。", "Many small turns also add up to one full turn of 360°.",
                "很多个小步、小弯，加起来一圈。", "Many small steps and small turns make one turn.",
            ),
            Lesson(
                9, "flower", "花", "Flower", ALL, TurtlePose(P2(0f, 0f), 0f), p("R5[R3[F0.75 R120] R72]"),
                "5 片花瓣各转一圈，再加上整朵花转一圈：6 圈 = 2160°。这课可以只看海龟画。",
                "Five petals turn once each, and the whole flower turns once more: 6 turns = 2160°. You may just watch this one.",
                "先写一片花瓣（三角形），再把它重复。", "Write one petal (a triangle) first, then repeat it.", walkOptional = true,
            ),
            Lesson(
                10, "house", "小房子", "House", ALL, TurtlePose(P2(-0.5f, -0.933f), 0f), p("F1 R30 F1 R120 F1 R30 F1 R90 F1 R90"),
                "形状再怪，只要回到起点、朝回原来的方向，一共就是 360°。", "However odd the shape, back at the start and facing the same way makes 360°.",
                "先画墙，到墙顶转一点点，屋顶的尖要转很多。", "Draw the wall first; at its top turn a little, the roof's point needs a big turn.",
            ),
        )

    fun byNumber(n: Int): Lesson = all.firstOrNull { it.n == n } ?: all.first()

    fun byId(id: String): Lesson? = all.firstOrNull { it.id == id || it.n.toString() == id }
}
