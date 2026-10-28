package io.embodit.turtlewalk.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.turtlewalk.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TurtleGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
