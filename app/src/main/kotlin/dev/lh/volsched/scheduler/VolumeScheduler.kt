package dev.lh.volsched.scheduler

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import dev.lh.volsched.audio.ApplyResult
import dev.lh.volsched.audio.VolumeApplier
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.Event
import dev.lh.volsched.core.Firing
import dev.lh.volsched.core.ScheduleException
import dev.lh.volsched.core.compile
import dev.lh.volsched.core.currentLevels
import dev.lh.volsched.core.eventsAt
import dev.lh.volsched.core.nextFiring
import dev.lh.volsched.storage.EventLog
import dev.lh.volsched.storage.ScheduleStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * All scheduling behaviour, driven by the trigger table in PLAN.md section 3.
 *
 * Exactly one alarm is pending at any time - the next event across all four
 * streams - and it is re-armed after each firing. Registering an alarm per
 * event would mean tracking N PendingIntents and reconciling them on every
 * edit, for no benefit.
 */
object VolumeScheduler {

    const val EXTRA_FIRE_AT = "dev.lh.volsched.FIRE_AT"
    private const val REQUEST_CODE = 1001
    private const val MAX_ATTEMPTS = 8
    private const val RETRY_DELAY_MS = 250L

    /** Arms the next alarm, replacing any pending one. */
    fun rearm(context: Context, reason: String) {
        val log = EventLog(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        cancel(context)

        if (!ScheduleStore.get(context).schedule.value.enabled) {
            log.append("re-arm [$reason]: scheduling is disabled, nothing armed")
            return
        }

        val firing = nextFiring(compiledEvents(context, log), LocalDateTime.now())
        if (firing == null) {
            log.append("re-arm [$reason]: schedule has no events")
            return
        }

        val triggerAtMillis = firing.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = alarmIntent(context, triggerAtMillis, mutable = false)

        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            // Should not happen: USE_EXACT_ALARM is granted at install. If it
            // ever does, an inexact alarm beats no alarm.
            log.append("WARNING: exact alarms unavailable, falling back to inexact")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }

        log.append("re-arm [$reason]: next ${firing.at} -> ${firing.events.describe()}")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        // FLAG_NO_CREATE matches on request code and Intent.filterEquals, which
        // ignores extras, so this finds whatever is currently pending.
        val existing = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) {
            alarmManager.cancel(existing)
            existing.cancel()
        }
    }

    /**
     * Applies the events due at [intendedAt] - the instant the alarm was armed
     * for, not the instant it actually fired.
     *
     * Only the due streams are touched. Applying everything [currentLevels]
     * reports would clobber a manual change made on some other stream.
     *
     * [retry]: see [applyLevels]. Only for background callers; it sleeps.
     */
    fun applyDue(context: Context, intendedAt: LocalDateTime, retry: Boolean = false) {
        val log = EventLog(context)
        if (!ScheduleStore.get(context).schedule.value.enabled) {
            log.append("alarm fired for $intendedAt but scheduling is disabled, ignoring")
            return
        }

        val due = eventsAt(compiledEvents(context, log), intendedAt)
        if (due.isEmpty()) {
            log.append("alarm fired for $intendedAt but nothing is due (schedule edited since arming?)")
            return
        }

        applyLevels(context, due.associate { it.stream to it.level }, log, "fired  due=$intendedAt", retry)
    }

    /**
     * Forces every stream onto what the schedule says should be true right now.
     *
     * Runs on boot and on re-enable. This is what keeps volumes correct after a
     * reboot, when pending alarms are gone and nothing would otherwise fire
     * until the next edge.
     *
     * [retry]: see [applyLevels]. Only for background callers; it sleeps.
     */
    fun reconcile(context: Context, reason: String, retry: Boolean = false) {
        val log = EventLog(context)
        if (!ScheduleStore.get(context).schedule.value.enabled) {
            log.append("reconcile [$reason]: scheduling is disabled, skipping")
            return
        }

        val levels = currentLevels(compiledEvents(context, log), LocalDateTime.now())
        if (levels.isEmpty()) {
            log.append("reconcile [$reason]: schedule has no events")
            return
        }

        applyLevels(context, levels, log, "reconcile [$reason]", retry)
    }

    /** Debug affordance: apply the next firing immediately without waiting. */
    fun fireNextNow(context: Context) {
        val log = EventLog(context)
        val firing = nextFiring(compiledEvents(context, log), LocalDateTime.now())
        if (firing == null) {
            log.append("DEBUG fire-now: no events")
            return
        }
        applyLevels(
            context,
            firing.events.associate { it.stream to it.level },
            log,
            "DEBUG fire-now (was due ${firing.at})",
        )
    }

    /** For the UI and widget's "next change" line. */
    fun nextFiringOrNull(context: Context): Firing? {
        val schedule = ScheduleStore.get(context).schedule.value
        if (!schedule.enabled) return null
        return nextFiring(compiledEvents(context, EventLog(context)), LocalDateTime.now())
    }

    /**
     * With [retry], a change that didn't stick is tried again a few times.
     * Android 17 ignores volume changes from the background unless a
     * foreground service is running, and the service's foreground state may
     * reach AudioService a moment after startForeground() returns. Blocks
     * the calling thread, so never retry on the main thread.
     */
    private fun applyLevels(
        context: Context,
        levels: Map<AudioStream, Int>,
        log: EventLog,
        note: String,
        retry: Boolean = false,
    ) {
        val applier = VolumeApplier(context)
        log.append("$note  state: ${deviceState(context, applier)}")
        levels.forEach { (stream, level) ->
            var result = applier.apply(stream, level)
            var attempts = 1
            while (retry && attempts < MAX_ATTEMPTS && result is ApplyResult.Applied && result.after != result.requested) {
                Thread.sleep(RETRY_DELAY_MS)
                result = applier.apply(stream, level)
                attempts++
            }
            when (result) {
                is ApplyResult.Applied ->
                    log.append(
                        "$note  $stream ${result.before} -> ${result.after}" +
                            (if (result.clamped) " (clamped from $level)" else "") +
                            (if (result.after != result.requested) "  DID NOT STICK, asked for ${result.requested}" else "") +
                            (if (attempts > 1) "  [attempt $attempts]" else ""),
                    )

                is ApplyResult.Refused ->
                    log.append("$note  $stream REFUSED ($level): ${result.reason}")
            }
        }
    }

    /** Logs all four levels as they are right now, e.g. to catch a later revert. */
    fun logCurrentLevels(context: Context, label: String) {
        val levels = VolumeApplier(context).currentLevels()
        EventLog(context).append("$label: " + levels.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    /**
     * Screen, lock, Doze, charging, Do Not Disturb and ringer state, so a change
     * that didn't stick, or arrived late, can be matched against what the phone
     * was doing at the time. Doze never engages while charging, so a Doze test
     * only counts if these lines show doze=deep or doze=light.
     */
    private fun deviceState(context: Context, applier: VolumeApplier): String {
        val power = context.getSystemService(PowerManager::class.java)
        val screenOn = power.isInteractive
        val doze = when {
            power.isDeviceIdleMode -> "deep"
            power.isDeviceLightIdleMode -> "light"
            else -> "off"
        }
        val charging = context.getSystemService(BatteryManager::class.java).isCharging
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val dnd = when (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total"
            else -> "unknown"
        }
        return "screen=${if (screenOn) "on" else "off"} locked=${if (locked) "yes" else "no"} " +
            "doze=$doze charging=${if (charging) "yes" else "no"} " +
            "dnd=$dnd ringer=${applier.ringerModeName()}"
    }

    /**
     * Compiles the stored schedule, logging rather than throwing.
     *
     * The store should only ever hold validated schedules, but a receiver that
     * crashes on a bad file would be far harder to diagnose than one that logs
     * and does nothing.
     */
    private fun compiledEvents(context: Context, log: EventLog): List<Event> =
        try {
            ScheduleStore.get(context).schedule.value.compile()
        } catch (e: ScheduleException) {
            log.append("ERROR compiling schedule: ${e.message}")
            emptyList()
        }

    private fun alarmIntent(context: Context, fireAtMillis: Long, mutable: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_FIRE_AT, fireAtMillis)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun List<Event>.describe(): String =
        joinToString(", ") { "${it.stream}=${it.level}" }
}
