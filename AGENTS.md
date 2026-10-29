# Turtle Walk (小海龟编程)

Logo-turtle coding for kids on PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.turtlewalk`. Snap "forward / turn / repeat" blocks on a code panel; a turtle draws
the route 1:1 on your floor; then you turn on the spot along with it and feel that a closed shape is 360°.

Design package (binding): `../spatial-app-factory/apps/turtle-walk/design-package.json` (archetype D, STAGE_MIXED,
single_panel_with_popup, direction A with B's opening: lessons 1–2 "walk to the star"). Mockup:
`../spatial-app-factory/apps/turtle-walk/sample.html`. Built from `../dance-steps` (same skeleton as the design's
reference `../spatial-ruler`: plane / head trackers, controller input, pooled attachment panels, lazy follow).

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
  domain/model     TurtleModel (Block, P2 field steps, Lesson, Verdict, TurtleRules = every number), Lessons (10)
  domain/usecase   Program (tree edits, cursor, move across brackets, compact notation F1 R90 R4[...]),
                   Interpreter (Execution, corners, exit point) + Timeline (pure t -> turtle), CheckUseCase (6 verdicts),
                   PlaceFloorUseCase (wall crossing / front wall / furniture, snap frame, largest fitting size)
  data             TurtleRepository (prefs JSON: language, field size, coach seen, per lesson drawn/turned/walked/program)
  platform         HeadTracker, PlaneTracker, ControllerInput (ray, trigger, stick x/y)
  ui/turtle        TurtleViewModel (state machine), TurtleUiState / TurtleEvent, TurtleScreen
  ui/turtle/scene  TurtleScene (ECS frame system: placing, the fixed code panel and its handle, run timeline, turn along,
                   walk), FieldViews (tape, bars, turtle model, ring, wedges, arrows), PanelRig / PanelSpec, SceneKit
  ui/turtle/components  CodePanel (lessons, placing, 3 columns + value bar), MoreMenu, Overlays (dialog, snackbar,
                   coach, degree tags), Common (Glass, Key, WordButton, Segments, Chip, type roles)
app/src/debug/    DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/     CheckUseCaseTest (13: every lesson matches, open/spin/mirror/tooMany/different/outOfField, editing),
                  PlaceFacingTest (3: the four-direction placement gate)
tools/turtle_model.py  turtle GLB inspect / render / export (see ASSETS.md)
```

Field coordinates are steps: origin = field centre, +x right, +y = front (towards the code panel); one step =
field / 2. 3D runtime lives in `TurtleScene.onFrame` only; Compose gets 10 Hz progress events.

## QA hooks (debug build)

```
adb -s <serial> shell "am broadcast -a io.embodit.turtlewalk.DEBUG -p io.embodit.turtlewalk --es cmd '<command>'"
```

| command | effect |
|---|---|
| `state <name>` | any designed state, held until the next `state`: `phase[+overlay][:mode][@Ln]` |
| `lesson n`, `place [2/1.5/1]`, `program F1 R90`, `solve`, `run [slow/fast/instant]`, `run_to n` | as the controls |
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
`coding+lost@L3`, `blocked`. Pitch the camera down to see the floor.

## Build

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` prints nothing.
