package dev.lh.volsched.core

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A single resolved volume change: "on this weekday at this time, set this
 * stream to this level".
 *
 * This is the only shape the runtime scheduler understands. Presets, profiles
 * and blocks are authoring-time indirection that [compile] erases.
 */
data class Event(
    val day: DayOfWeek,
    val time: LocalTime,
    val stream: AudioStream,
    val level: Int,
) {
    /** Minutes since Monday 00:00. Makes week-wrapping arithmetic trivial. */
    val weekMinute: Int
        get() = (day.value - 1) * MINUTES_PER_DAY + time.hour * 60 + time.minute
}

/** Thrown when a schedule references a preset or profile that doesn't exist. */
class ScheduleException(message: String) : IllegalStateException(message)

/**
 * Flattens presets, profiles and blocks into a sorted list of [Event]s.
 *
 * A profile block expands into one event per defined slot, all sharing the same
 * instant - which is exactly how all-stream profiles and independent per-stream
 * timelines coexist without conflicting.
 *
 * Throws [ScheduleException] on dangling references. Call [validate] first;
 * anything that survives validation compiles cleanly.
 */
fun Schedule.compile(): List<Event> =
    blocks
        .flatMap { block ->
            when (val target = block.target) {
                is Target.Single ->
                    listOf(
                        Event(
                            block.day,
                            block.start,
                            target.stream,
                            resolve(target.stream, target.spec),
                        ),
                    )

                is Target.ProfileRef -> {
                    val profile = profiles.firstOrNull { it.name == target.name }
                        ?: throw ScheduleException("Block references unknown profile '${target.name}'")
                    profile.slots.map { (stream, spec) ->
                        Event(block.day, block.start, stream, resolve(stream, spec))
                    }
                }
            }
        }
        .sortedWith(compareBy({ it.weekMinute }, { it.stream.ordinal }))

private fun Schedule.resolve(stream: AudioStream, spec: LevelSpec): Int =
    when (spec) {
        is LevelSpec.Raw -> spec.level
        is LevelSpec.PresetRef ->
            presets[stream]?.firstOrNull { it.name == spec.name }?.level
                ?: throw ScheduleException("Unknown preset '${spec.name}' for $stream")
    }

/**
 * Clamps a level into the range this app is willing to set.
 *
 * Validation should already guarantee this, but the apply path calls it too:
 * a level that slipped through would otherwise throw SecurityException on ring
 * or notification.
 */
fun AudioStream.clamp(level: Int, maxLevel: Int): Int =
    level.coerceIn(minLevel, maxOf(minLevel, maxLevel))
