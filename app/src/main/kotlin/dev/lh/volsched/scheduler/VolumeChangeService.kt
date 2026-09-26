package dev.lh.volsched.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import dev.lh.volsched.R
import dev.lh.volsched.storage.EventLog
import dev.lh.volsched.widget.VolumeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Applies scheduled volume changes while the app is in the background.
 *
 * Android 17 silently ignores setStreamVolume from an app that has neither a
 * visible activity nor a running foreground service (shortService doesn't
 * count), so the alarm, boot and widget paths hand their work to this service
 * rather than doing it in a receiver. It stops itself within seconds; the
 * system defers foreground service notifications for about 10 s, so normally
 * none is ever shown.
 *
 * targetSdk must stay below 37. Apps targeting 37 additionally need the service
 * to hold while-in-use capability, which a service started from an alarm or
 * boot receiver does not get.
 * https://developer.android.com/about/versions/17/changes/bg-audio
 */
class VolumeChangeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any volume change: being in the foreground is what lets it
        // through. Also has to happen promptly after startForegroundService().
        try {
            startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // Still run the job below: the re-arm matters even if the volume
            // change is then ignored, and the log will show both.
            EventLog(applicationContext).append("ERROR entering the foreground: $e")
        }

        scope.launch {
            // One job at a time, in start order, so stopSelf(startId) only
            // succeeds once the most recently started job has finished.
            jobLock.withLock {
                if (intent != null) runJob(applicationContext, intent)
            }
            // Every non-null intent came through start(), which took the lock.
            if (intent != null) releaseWakeLock(applicationContext)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_FIRE = "dev.lh.volsched.action.FIRE"
        private const val ACTION_RECONCILE = "dev.lh.volsched.action.RECONCILE"
        private const val EXTRA_REASON = "dev.lh.volsched.REASON"
        private const val CHANNEL_ID = "volume_changes"
        private const val NOTIFICATION_ID = 1

        // Generous: a job is a few seconds at most, but a leaked lock must not
        // hold the CPU awake indefinitely.
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val jobLock = Mutex()

        private var wakeLock: PowerManager.WakeLock? = null

        /** Apply what was due when the alarm armed for [fireAtMillis] fired, then re-arm. */
        fun fire(context: Context, fireAtMillis: Long) =
            start(
                context,
                Intent(context, VolumeChangeService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra(VolumeScheduler.EXTRA_FIRE_AT, fireAtMillis),
            )

        /** Converge every stream on the schedule, then re-arm. */
        fun reconcile(context: Context, reason: String) =
            start(
                context,
                Intent(context, VolumeChangeService::class.java)
                    .setAction(ACTION_RECONCILE)
                    .putExtra(EXTRA_REASON, reason),
            )

        /**
         * Call straight from onReceive or a widget action. Exact alarms, boot
         * broadcasts and widget taps exempt the app from the background start
         * restriction on foreground services, but only briefly.
         */
        private fun start(context: Context, intent: Intent) {
            val appContext = context.applicationContext
            // Keep the CPU awake from here until the job finishes. The alarm's
            // own wake lock is dropped as soon as onReceive returns, and a
            // foreground service doesn't prevent sleep, so without this the
            // job stalls until something else wakes the phone. Seen on the
            // device: a 5 s delay taking up to 106 s, and a firing 34 s late.
            wakeLock(appContext).acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                appContext.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException lands here. Run the
                // job anyway: the re-arm matters even if Android then ignores
                // the volume change, and the log will show both.
                EventLog(appContext).append("ERROR starting volume service, running without it: $e")
                scope.launch {
                    jobLock.withLock { runJob(appContext, intent) }
                    releaseWakeLock(appContext)
                }
            }
        }

        /** Reference counted, so overlapping jobs each hold it until they finish. */
        @Synchronized
        private fun wakeLock(context: Context): PowerManager.WakeLock =
            wakeLock ?: context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volsched:volume-change")
                .also { wakeLock = it }

        private fun releaseWakeLock(context: Context) {
            // Can only throw if releases outnumber acquires, which would be a
            // bug here - not worth crashing the job over.
            runCatching { wakeLock(context).release() }
                .onFailure { EventLog(context).append("ERROR releasing wake lock: $it") }
        }

        private suspend fun runJob(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_FIRE -> {
                        val fireAtMillis = intent.getLongExtra(VolumeScheduler.EXTRA_FIRE_AT, 0L)
                        // Prefer the instant the alarm was armed for over "now":
                        // under Doze the delivery can lag by minutes, and the
                        // schedule edge that matters is the intended one.
                        val intendedAt =
                            if (fireAtMillis > 0L) {
                                Instant.ofEpochMilli(fireAtMillis)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDateTime()
                            } else {
                                LocalDateTime.now()
                            }

                        VolumeScheduler.applyDue(context, intendedAt, retry = true)
                        VolumeScheduler.rearm(context, "after firing")
                        VolumeWidget.refresh(context)
                    }

                    ACTION_RECONCILE -> {
                        val reason = intent.getStringExtra(EXTRA_REASON) ?: "unknown"
                        VolumeScheduler.reconcile(context, reason, retry = true)
                        VolumeScheduler.rearm(context, reason)
                        VolumeWidget.refresh(context)
                    }
                }
            } catch (e: Exception) {
                // Uncaught, this would kill the process and leave nothing in the
                // event log, which is the only record of what happened.
                EventLog(context).append("ERROR in volume service (${intent.action}): $e")
            }
        }

        private fun notification(context: Context): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled volume changes", NotificationManager.IMPORTANCE_MIN),
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Applying scheduled volume")
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                .build()
        }
    }
}
