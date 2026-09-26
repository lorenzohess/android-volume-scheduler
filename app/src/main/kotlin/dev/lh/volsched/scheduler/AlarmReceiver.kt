package dev.lh.volsched.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lh.volsched.storage.EventLog
import dev.lh.volsched.widget.VolumeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Receives the single pending alarm, applies what was due, and re-arms. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val fireAtMillis = intent.getLongExtra(VolumeScheduler.EXTRA_FIRE_AT, 0L)

        scope.launch {
            try {
                // Prefer the instant the alarm was armed for over "now": under
                // Doze the delivery can lag by minutes, and the schedule edge
                // that matters is the intended one.
                val intendedAt =
                    if (fireAtMillis > 0L) {
                        Instant.ofEpochMilli(fireAtMillis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                    } else {
                        LocalDateTime.now()
                    }

                VolumeScheduler.applyDue(appContext, intendedAt)
                VolumeScheduler.rearm(appContext, "after firing")
                VolumeWidget.refresh(appContext)

                // Diagnostic for M3: a level that was set and then undone
                // shortly afterwards shows up here as a mismatch.
                delay(RECHECK_DELAY_MS)
                VolumeScheduler.logCurrentLevels(appContext, "${RECHECK_DELAY_MS / 1000}s after firing")
            } catch (e: Exception) {
                // Uncaught, this would kill the process and leave nothing in the
                // event log, which is the only record of what happened.
                EventLog(appContext).append("ERROR in alarm receiver: $e")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Well inside the time goAsync() allows a receiver to finish.
        const val RECHECK_DELAY_MS = 5_000L
    }
}
