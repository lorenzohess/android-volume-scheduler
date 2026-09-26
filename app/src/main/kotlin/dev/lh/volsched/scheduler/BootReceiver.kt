package dev.lh.volsched.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lh.volsched.storage.EventLog
import dev.lh.volsched.widget.VolumeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Everything that can invalidate a pending alarm.
 *
 * Alarms do not survive a reboot, and on GrapheneOS the device reboots itself
 * after idling (~18h by default), so this receiver is load-bearing rather than
 * an edge case.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action ?: "unknown"
        EventLog(appContext).append("received $action")

        when (action) {
            // Boot, and timezone moves, change what "should" be true right
            // now - so converge on it, then arm. The volume change needs a
            // foreground service on Android 17, started here while this
            // broadcast still exempts the app from the background start limit.
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> VolumeChangeService.reconcile(appContext, action)

            // A reinstall or a clock correction invalidates the pending
            // alarm but not the current volumes, so don't reconcile -
            // that would clobber a manual override.
            else -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        VolumeScheduler.rearm(appContext, action)
                        VolumeWidget.refresh(appContext)
                    } catch (e: Exception) {
                        // Uncaught, this would kill the process and leave nothing in
                        // the event log, which is the only record of what happened.
                        EventLog(appContext).append("ERROR handling $action: $e")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
