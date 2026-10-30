# Turtle Walk (小海龟编程)

Logo-turtle coding for kids on PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.turtlewalk`. Snap "forward / turn / repeat" blocks on a code panel; a turtle draws
the route 1:1 on your floor; then you turn on the spot along with it and feel that a closed shape is 360°.

Design package (binding): `../spatial-app-factory/apps/turtle-walk/design-package.json` (archetype D, STAGE_MIXED,
single_panel_with_popup, direction A with B's opening: lessons 1–2 "walk to the star"). Mockup:
`../spatial-app-factory/apps/turtle-walk/sample.html`. Built from `../dance-steps` (same skeleton as the design's
reference `../spatial-ruler`: plane / head trackers, controller input, pooled attachment panels, lazy follow).

## v0.1.3 (2026-10-30, headset feedback on v0.1.2: "model bigger, centred; facing and centre wrong, walks oddly"; "add a free mode")

- **Model calibration (taste D25)**: `TurtleBody` is `turtle` (field point + heading) > `turtle_body` (waddle) > `turtle_cal`
  (calibration node) > the GLB. After loading, the model's own bounding box is measured (`getVisualBounds(model)`), and
  `ModelCalibration.calibrate(box, TurtleAsset.SPEC)` sets the node: bottom centre on the origin, head along local -Z
  (`Vec3.AHEAD`, the forward every floor thing is built facing), back up, length `TurtleAsset.LENGTH_M` = 0.35 m (design said
  ~0.24 m shell + head; the user asked for bigger: deviation). If the loader dropped the export's root transform (raw scan axes:
  head along -X, pivot 0.6 m away), the calibration detects it (the side axis is longer than the head axis) and corrects it too.
  Logs: `MODEL calibrate ...` (box, scale, offset, rotation) and `MODEL check ...` (the calibrated box measured again by the SDK:
  bottom centre, size, length error). Walking / turning only move `turtle` and `turtle_body`. `tools/turtle_model.py export` now
  writes a TRS root node instead of a `matrix` (not every loader applies `matrix`). Every number about the asset lives in
  `TurtleAsset` (domain/model/ModelCalibration.kt). Gate: `ModelCalibrationTest` (bottom centre ± 1 cm, head < 2°, length < 10 %,
  no mirror, heading 90 = field right) + the kept `PlaceFacingTest`.
- **Free mode (taste B21)**: a wide card under the ten lessons ("自由模式 · 第 N 局"). `FreeRoutes.generate(seed, round)` makes
  a `Lesson` with `n == 0` by rules: seed + round determine the route; tier = (round − 1) / 3 capped at 4 (0 star paths with 90°
  corners and F / L / R only → 1 polygons, rectangles, rhombus with repeat → 2 star, circle, house, 45° paths → 3 flowers and
  rings of squares (nested repeat) → 4 double shapes); 30 % of rounds pick an earlier tier for variety. Every candidate is run
  by the Interpreter, centred in the field and must fit inside ± 0.95 step (a side length that does not fit steps down), have
  1..16 segments, and, if closed, come back to the start facing the start way with a whole number of 360° turns; open routes
  end ≥ 0.5 step from the start on a star without drawing over themselves. The previous route is never repeated. Completely
  offline. The course flow is reused unchanged (blocks, draw, check, turn along, walk, score); "next lesson" becomes "再来一局"
  (new seed, round + 1). Settings keep `freeRound`, `freeSeed`, `freeDone`, `freeLast`. Gate: `FreeRoutesTest` (60 rounds all
  solvable by their own solution under `CheckUseCase`, fit, difficulty curve, determinism, no repeat).
- **Settings (taste D26)**: the More menu is the settings panel: language, bring the panel here, floor height (estimated floor),
  lessons, move the field, **reset progress**, about (version + model credit), and at the bottom **exit app** → a confirmation
  dialog ("退出小乌龟？" [取消] [退出]) on the dialog panel; confirming closes the Stage, `finishAndRemoveTask()` on every app
  task, then `exitProcess(0)` 0.6 s later (`TurtleUiEffect.ExitProcess`). The blocked dialog's exit does the same. No volume /
  mute: the app makes no sound.
- Debug: `free [round] [seed]`, `state <x>@F<n>` (free round n with the QA seed 1000 + n, the same route every time),
  `exit_confirm` (the dialog; `ui dialog_exit` / `ui dialog_cancel`), `reset_progress`; `dump` adds `free{round, seed, done,
  solution, name}` and `exitConfirm`. State names: `...@F<n>`, overlay `+exitConfirm`.

## v0.1.2 (2026-10-28, headset feedback: facing reversed, UI stuck far away, ugly upside-down turtle)

- **Facing (taste D10)**: the field's front (+y), the turtle at heading 0, the chevron and the target arrows face you:
  `FloorPose.yawToward(content, user)` (level), computed while the ghost follows the ray and locked when it is put down.
  `PlaceFacingTest` places from east / south / west / north (< 10°, proper rotations only).
- **Placing (taste D1)**: nothing appears in front of the head. The ghost field appears where the controller ray meets the
  floor; trigger or "place here" puts it down; a pinch on the floor puts it there directly. Once placed it is locked;
  More → "move the field" re-enters placing. The old "bring the field to me" (head based) and the free drag are gone.
- **Code panel (taste D21)**: fixed in the room. First shown 1 m ahead of the head, 15° below the eyes, facing you;
  a white handle 4 cm under its bottom edge drags it (system drag: pinch, or the controller trigger), with a gain that grows
  with distance (1..6) so a far panel comes back in one reach; on release it faces you again. "Bring here" on every title bar
  and in More puts it back in front of you. It no longer moves when the field is placed.
- **Turtle (taste B12)**: a CC BY 4.0 pond turtle scan, see `ASSETS.md`; `tools/turtle_model.py` inspects, renders and exports
  it (Y-up, head −Z, feet y = 0). Walking = the body moving with a waddle; turning = turning on the spot with a rock. The
  shape-made turtle stays only as the fallback if the model cannot load (log `MODEL turtle loaded=false`).
- Debug: `recall` (as "bring here"), `panel_move dx,dy,dz` (as a drag, Stage metres), `panel_near` = recall.
- Head-pose gate: `../spatial-app-factory/apps/turtle-walk/build/headpose-audit.md`.

## Structure

```
app/src/main/java/io/embodit/turtlewalk/
  domain/model     TurtleModel (Block, P2 field steps, Lesson, Verdict, TurtleRules = every number), Lessons (10),
                   ModelCalibration (Box3, ModelSpec, Calibration, TurtleAsset: taste D25)
  domain/usecase   Program (tree edits, cursor, move across brackets, compact notation F1 R90 R4[...]),
                   Interpreter (Execution, corners, exit point) + Timeline (pure t -> turtle), CheckUseCase (6 verdicts),
                   PlaceFloorUseCase (wall crossing / front wall / furniture, snap frame, largest fitting size),
                   FreeRoutes (free mode: seeded PCG routes, difficulty tiers, solvability check)
  data             TurtleRepository (prefs JSON: language, field size, coach seen, per lesson drawn/turned/walked/program)
  platform         HeadTracker, PlaneTracker, ControllerInput (ray, trigger, stick x/y)
  ui/turtle        TurtleViewModel (state machine), TurtleUiState / TurtleEvent, TurtleScreen
  ui/turtle/scene  TurtleScene (ECS frame system: placing, the fixed code panel and its handle, run timeline, turn along,
                   walk), FieldViews (tape, bars, turtle model, ring, wedges, arrows), PanelRig / PanelSpec, SceneKit
  ui/turtle/components  CodePanel (lessons, placing, 3 columns + value bar), MoreMenu, Overlays (dialog, snackbar,
                   coach, degree tags), Common (Glass, Key, WordButton, Segments, Chip, type roles)
app/src/debug/    DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/     CheckUseCaseTest (13: every lesson matches, open/spin/mirror/tooMany/different/outOfField, editing),
                  PlaceFacingTest (3: the four-direction placement gate), ModelCalibrationTest (5: taste D25 gate),
                  FreeRoutesTest (5: taste B21 gate)
tools/turtle_model.py  turtle GLB inspect / render / export (see ASSETS.md)
```

Field coordinates are steps: origin = field centre, +x right, +y = front (towards the code panel); one step =
field / 2. Local forward of everything built on the floor (turtle, chevron, arrows) is **-Z** (`Vec3.AHEAD`); `headingRotation(deg)`
turns it by -deg about +Y. 3D runtime lives in `TurtleScene.onFrame` only; Compose gets 10 Hz progress events.

## QA hooks (debug build)

```
adb -s <serial> shell "am broadcast -a io.embodit.turtlewalk.DEBUG -p io.embodit.turtlewalk --es cmd '<command>'"
```

| command | effect |
|---|---|
| `state <name>` | any designed state, held until the next `state`: `phase[+overlay][:mode][@Ln]` |
| `lesson n`, `place [2/1.5/1]`, `program F1 R90`, `solve`, `run [slow/fast/instant]`, `run_to n` | as the controls |
| `free [round] [seed]`, `exit_confirm`, `reset_progress` | free mode (taste B21), settings -> exit dialog / reset (taste D26) |
| `turn_along perfect/lazy/guess`, `turn <deg>` | synthetic turning on the spot |
| `walk_perfect/noisy/cut/overshoot/off`, `walk_to <steps>` | optional walk with a synthetic walker |
| `stick x[,y],ms`, `canvas move dx,dy / rot deg / scale m`, `snap`, `nofloor`, `lost`/`found`, `coach_reset` | |
| `ui <probe name>` | presses a control (lesson_3, palette_F, block_2, slot_0_1, value_90, run, turn_along, place_here, …) |
| `dump`, `pose`, `bounds`, `planes`, `fps on/off` | queries (log tag `TurtleWalk`) |

Screenshot list (design `states[].qaCapture`): `starting`, `lessons`, `lessons+menu`, `placing`, `placing+cramped`,
`placing+snapped`, `placing+estFloor`, `coding@L1`, `coding+blockSelected@L4`, `coding+hint@L5`, `coding+undo@L3`,
`coding+canvasMove@L3`, `coding+coach@L3`, `running@L3`, `drawn:match@L3`, `drawn:match@L7`, `drawn:open@L5`,
`drawn:spin@L3`, `drawn:mirror@L3`, `drawn:mirror@L2`, `drawn:tooMany@L4`, `drawn:different@L3`, `drawn+outOfField@L3`,
`turnAlong@L3`, `turnAlong:guess@L5`, `turnDone:along@L3`, `turnDone:guess@L5`, `walkReady@L3`, `walkReady+notOnStart@L3`,
`walking@L3`, `walking+offPath@L3`, `turnHome@L3`, `walkDone:great@L3`, `walkDone:ok@L3`, `walkDone:again@L3`,
`coding+lost@L3`, `blocked`. v0.1.3 adds: `lessons` (now with the free-mode card), `coding@F1`, `coding+hint@F5`, `drawn:match@F4`,
`drawn:different@F4`, `turnDone:along@F4`, `walkDone:great@F7`, `coding@F10` (nested repeat), `lessons+menu` (= settings, taste D26),
`lessons+exitConfirm`, `coding+exitConfirm@L3`. Pitch the camera down to see the floor.

## Build

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` prints nothing.
