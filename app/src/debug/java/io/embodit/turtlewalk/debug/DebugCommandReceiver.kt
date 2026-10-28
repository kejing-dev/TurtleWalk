package io.embodit.turtlewalk.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.turtlewalk.platform.TurtleLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.turtlewalk.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb shell "am broadcast -a io.embodit.turtlewalk.DEBUG -p io.embodit.turtlewalk --es cmd 'state ready'"
 *
 * Extras: `cmd` = command name with its arguments after it, or `s` = the argument text on its own.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val s = intent.getStringExtra("s")
        TurtleLog.i("CMD recv '$cmd' s='$s'")
        DebugCommands.run(context.applicationContext, cmd, s)
    }
}
