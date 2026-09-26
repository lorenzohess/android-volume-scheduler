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
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val action = intent.action ?: "unknown"

        scope.launch {
            try {
                EventLog(appContext).append("received $action")

                when (action) {
                    // Boot, and timezone moves, change what "should" be true
                    // right now - so converge on it, then arm.
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    -> {
                        VolumeScheduler.reconcile(appContext, action)
                        VolumeScheduler.rearm(appContext, action)
                    }

                    // A reinstall or a clock correction invalidates the pending
                    // alarm but not the current volumes, so don't reconcile -
                    // that would clobber a manual override.
                    else -> VolumeScheduler.rearm(appContext, action)
                }

                VolumeWidget.refresh(appContext)
            } catch (e: Exception) {
                // Uncaught, this would kill the process and leave nothing in the
                // event log, which is the only record of what happened.
                EventLog(appContext).append("ERROR handling $action: $e")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
