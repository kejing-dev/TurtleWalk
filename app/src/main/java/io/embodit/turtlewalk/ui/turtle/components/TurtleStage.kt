package io.embodit.turtlewalk.ui.turtle.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.turtlewalk.ui.turtle.TurtleEvent
import io.embodit.turtlewalk.ui.turtle.TurtleUiState
import io.embodit.turtlewalk.ui.turtle.debug.LocalProbePanel
import io.embodit.turtlewalk.ui.turtle.scene.TurtleScene
import io.embodit.turtlewalk.ui.turtle.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popups. The code panel is the only standing panel; menu, snackbar,
 * dialog and coachmark are floating surfaces hung on it; the degree tags stand at the corners on the floor.
 */
@Composable
internal fun TurtleStage(state: TurtleUiState, scene: TurtleScene, onEvent: (TurtleEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                // taste D21: the code panel's handle is dragged with a pinch or the controller's trigger
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value -> scene.onSpatialDrag("move", value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CODE) {
                PoolPanel(PanelSpec.CODE, scene, watched = true) { inView -> CodePanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene) { inView -> AppDialog(reason = if (inView) state.blocked else null, exitConfirm = inView && state.exitConfirm, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.SNACK) {
                PoolPanel(PanelSpec.SNACK, scene, hugs = true) { inView ->
                    SnackPanel(
                        snack = if (inView) state.snackNow(SystemClock.elapsedRealtime()) else null,
                        applied = scene.panelStretch[PanelSpec.SNACK] ?: 1f,
                        onWidth = { scene.reportSnack(it) },
                        onEvent = onEvent,
                    )
                }
            }
            AttachmentPanel(PanelSpec.COACH) {
                PoolPanel(PanelSpec.COACH, scene) { inView -> CoachPanel(which = if (inView) state.coach else null, onEvent = onEvent) }
            }
            for ((i, id) in PanelSpec.tags.withIndex()) {
                AttachmentPanel(id) {
                    PoolPanel(id, scene) { inView -> TagPanel(text = if (inView) scene.tagText[i] else null) }
                }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled
 * panel must draw its content only then (what it draws while parked out of sight stays broken on the
 * emulator). [watched]: presses on it hold the lazy follow still. [hugs]: the content is narrower
 * than the panel (snackbar capsule) and reports its width to the scene.
 */
@Composable
private fun PoolPanel(id: String, scene: TurtleScene, watched: Boolean = false, hugs: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched && !hugs) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
