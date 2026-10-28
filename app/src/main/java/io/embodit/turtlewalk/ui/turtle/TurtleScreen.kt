package io.embodit.turtlewalk.ui.turtle

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.turtlewalk.platform.TurtleLog
import io.embodit.turtlewalk.ui.turtle.components.TurtleStage
import io.embodit.turtlewalk.ui.turtle.components.LocalTurtleResources
import io.embodit.turtlewalk.ui.turtle.components.localizedResources
import io.embodit.turtlewalk.ui.turtle.debug.TurtleProbe
import io.embodit.turtlewalk.ui.turtle.scene.TurtleFrameSystem
import io.embodit.turtlewalk.ui.turtle.scene.TurtleScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [TurtleScene] turns state into entities and reports the head, planes
 * and beats, and [TurtleStage] declares the panels.
 */
@Composable
fun TurtleScreen() {
    val context = LocalContext.current
    val viewModel: TurtleViewModel = viewModel(factory = TurtleViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        TurtleProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { TurtleScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<TurtleFrameSystem>()
        TurtleProbe.host =
            object : TurtleProbe.Host {
                override fun dispatch(event: TurtleEvent) = viewModel.onEvent(event)

                override fun uiState(): TurtleUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): TurtleProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = TurtleProbe.click(name)
            }
        TurtleLog.i("STAGE enter")
        onDispose {
            TurtleProbe.host = null
            unregisterSystem<TurtleFrameSystem>()
            scene.detach()
            TurtleLog.i("STAGE disposed")
        }
    }

    // background / foreground: a run pauses by itself, the audio stream is flushed (design `rules.background`)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, scene) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            viewModel.onEvent(TurtleEvent.WentBackground)
                            scene.onBackground()
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            viewModel.onEvent(TurtleEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: TurtleUiEffect ->
            when (effect) {
                TurtleUiEffect.ExitApp -> {
                    TurtleLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { TurtleLog.e("STAGE close failed: $it", it) }
                }
                TurtleUiEffect.RetryTracking -> scene.restartTracking()
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalTurtleResources provides resources) {
        TurtleStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
