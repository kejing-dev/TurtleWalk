package io.embodit.turtlewalk.platform

import android.content.Context
import io.embodit.turtlewalk.data.TurtleRepository
import io.embodit.turtlewalk.data.PrefsTurtleRepository
import io.embodit.turtlewalk.platform.input.ControllerInput
import io.embodit.turtlewalk.platform.sense.PlaneTracker
import io.embodit.turtlewalk.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `TurtleScene.attach` / `detach`).
 */
object TurtleGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    /** "Turn 45 degrees" presses while placing; the scene turns the ghost field once per increment. */
    @Volatile var rotate45Requests = 0

    @Volatile private var repository: TurtleRepository? = null

    fun repository(context: Context): TurtleRepository =
        repository ?: synchronized(this) { repository ?: PrefsTurtleRepository(context).also { repository = it } }
}
