package io.embodit.turtlewalk.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Four `ColorScheme` roles carry design values and are injected into `PicoTheme` by [TurtleTheme]; the
 * other twelve inherit SpatialUI Vibrant. The brand tokens are used by the blocks, the 3D materials and
 * the dark chips, where adaptive colours must not land.
 */
object TurtleColors {
    // ---- ColorScheme roles with a fixed design value
    val Interaction = Color(0xFFFFC94D) // design-style: fixed-figma-color interaction: main buttons, your ring, snap frame
    val Passable = Color(0xFF5FD39A) // design-style: fixed-figma-color passable: drawn right, walked well, standing right
    val Alert = Color(0xFFFF9F43) // design-style: fixed-figma-color alert: not back at the start, crowded edge (fills only)
    val Error = Color(0xFFFF6B6B) // design-style: fixed-figma-color error: out of the field, walk again, unavailable

    // ---- brand tokens (design `palette.brandTokens`): a block's colour is the colour it draws on the floor
    val InkYellow = Color(0xFFFFD25A) // design-style: fixed-figma-color inkYellow: the ink line, forward block
    val InkYellowText = Color(0xFF2A2200) // design-style: fixed-figma-color inkYellow text
    val TurnRight = Color(0xFFFF7A59) // design-style: fixed-figma-color turnRight: right wedges / ring, right block
    val TurnRightText = Color(0xFF2B0E05) // design-style: fixed-figma-color turnRight text
    val TurnLeft = Color(0xFF2EC4D6) // design-style: fixed-figma-color turnLeft: left wedges / ring, left block
    val TurnLeftText = Color(0xFF04262B) // design-style: fixed-figma-color turnLeft text
    val RepeatViolet = Color(0xFF8B6CFF) // design-style: fixed-figma-color repeatViolet: repeat block and bracket
    val RepeatText = Color(0xFFFFFFFF) // design-style: fixed-figma-color repeat text
    val TurtleShell = Color(0xFF3FA36B) // design-style: fixed-figma-color turtleShell
    val TurtlePatch = Color(0xFFA8E0BF) // design-style: fixed-figma-color turtleShell patches
    val TurtleSkin = Color(0xFF2C7A4E) // design-style: fixed-figma-color turtleShell head / flippers
    val Tape = Color(0xFFFFFFFF) // design-style: fixed-figma-color fieldTape: 60 % placing / 40 % placed
    val You = Color(0xFFFFFFFF) // design-style: fixed-figma-color you: your dot, trail and degree text
    val WarnChipBg = Color(0xFF4A2E0A) // design-style: fixed-figma-color warnChip background
    val WarnChipText = Color(0xFFFFD9A8) // design-style: fixed-figma-color warnChip text
    val InfoChipBg = Color(0xFF1E2A33) // design-style: fixed-figma-color infoChip / tagChip background
    val InfoChipText = Color(0xFFE6F1F7) // design-style: fixed-figma-color infoChip text
    val TagTurtle = Color(0xFFFFD25A) // design-style: fixed-figma-color tagChip turtle text
    val Highlight = Color(0xFFFFFFFF) // design-style: fixed-figma-color highlight: run / selection / cursor outline
    val Ink = Color(0xFF15181D) // design-style: fixed-figma-color ink: text on the interaction fill
    val PassableText = Color(0xFF06261A) // design-style: fixed-figma-color text on the passable chip
    val ErrorText = Color(0xFF2B0606) // design-style: fixed-figma-color text on the error chip
    val Gold = Color(0xFFFFE08A) // design-style: fixed-figma-color the ring's closing flash
}
