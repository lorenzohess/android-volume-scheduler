package dev.lh.volsched.core

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

const val MINUTES_PER_DAY = 24 * 60
const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

/** Minutes since Monday 00:00, matching [Event.weekMinute]. */
fun LocalDateTime.weekMinute(): Int =
    (dayOfWeek.value - 1) * MINUTES_PER_DAY + hour * 60 + minute

/**
 * The next moment the scheduler must wake up, and everything due then.
 *
 * Multiple events share one firing when a profile block sets several streams at
 * once - they must be applied together, not as separate alarms.
 */
data class Firing(val at: LocalDateTime, val events: List<Event>)

/**
 * Finds the earliest event strictly after [now], wrapping across the week.
 *
 * "Strictly after" means an event in the current minute is treated as a week
 * away. That is what makes re-arming after a firing terminate instead of
 * immediately rescheduling itself. The event isn't lost: [currentLevels]
 * reports it as the active one for that same minute.
 */
fun nextFiring(events: List<Event>, now: LocalDateTime): Firing? {
    if (events.isEmpty()) return null

    fun delta(event: Event): Int {
        val raw = Math.floorMod(event.weekMinute - now.weekMinute(), MINUTES_PER_WEEK)
        return if (raw == 0) MINUTES_PER_WEEK else raw
    }

    val soonest = events.minOf(::delta)
    return Firing(
        at = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(soonest.toLong()),
        events = events.filter { delta(it) == soonest }.sortedBy { it.stream.ordinal },
    )
}

/**
 * Every event falling on the same minute as [at].
 *
 * The alarm receiver uses this to re-derive what was due at the instant it was
 * woken for, rather than trusting values carried in the Intent - which can go
 * stale if the schedule was edited between arming and firing.
 */
fun eventsAt(events: List<Event>, at: LocalDateTime): List<Event> {
    val target = at.weekMinute()
    return events.filter { it.weekMinute == target }.sortedBy { it.stream.ordinal }
}

/**
 * What every stream *should* be set to right now, per the schedule.
 *
 * Walks backwards to each stream's most recent event at or before [now],
 * wrapping into the previous week if needed. Streams with no events at all are
 * absent from the result and must be left alone.
 *
 * This is the self-healing half of the scheduler: it runs on boot and on
 * re-enable, so volumes converge on the schedule even if alarms were missed.
 */
fun currentLevels(events: List<Event>, now: LocalDateTime): Map<AudioStream, Int> {
    val nowWeekMinute = now.weekMinute()
    return events
        .groupBy { it.stream }
        .mapValues { (_, streamEvents) ->
            streamEvents
                .minBy { Math.floorMod(nowWeekMinute - it.weekMinute, MINUTES_PER_WEEK) }
                .level
        }
}
