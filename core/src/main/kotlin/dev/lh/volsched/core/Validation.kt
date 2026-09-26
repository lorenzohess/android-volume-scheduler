package dev.lh.volsched.core

import java.time.DayOfWeek
import java.time.LocalTime

sealed interface ValidationError {
    val message: String

    data class UnknownPreset(val stream: AudioStream, val name: String) : ValidationError {
        override val message = "Preset '$name' does not exist for $stream"
    }

    data class UnknownProfile(val name: String) : ValidationError {
        override val message = "Profile '$name' does not exist"
    }

    data class DuplicatePreset(val stream: AudioStream, val name: String) : ValidationError {
        override val message = "Duplicate preset '$name' for $stream"
    }

    data class DuplicateProfile(val name: String) : ValidationError {
        override val message = "Duplicate profile '$name'"
    }

    data class LevelTooLow(val stream: AudioStream, val level: Int) : ValidationError {
        override val message =
            "$stream level $level is below the minimum of ${stream.minLevel}" +
                if (stream.minLevel == 1) " (0 would trigger silent/vibrate, which needs DND access)" else ""
    }

    data class LevelTooHigh(val stream: AudioStream, val level: Int, val max: Int) : ValidationError {
        override val message = "$stream level $level exceeds the device maximum of $max"
    }

    data class InvalidDuration(val block: Block) : ValidationError {
        override val message =
            "Block at ${block.day} ${block.start} has invalid duration ${block.durationMin}"
    }

    data class Conflict(
        val day: DayOfWeek,
        val time: LocalTime,
        val stream: AudioStream,
    ) : ValidationError {
        override val message = "Two events set $stream at $day $time"
    }

    data class EmptyProfile(val name: String) : ValidationError {
        override val message = "Profile '$name' sets no streams"
    }
}

/**
 * Checks a schedule against the device's actual stream maxima.
 *
 * [maxLevels] is passed in rather than read from AudioManager so this module
 * stays free of Android dependencies and testable on the JVM.
 *
 * Returns every problem found, so the editor can show them all at once.
 */
fun Schedule.validate(maxLevels: Map<AudioStream, Int>): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()

    presets.forEach { (stream, list) ->
        list.groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .forEach { errors += ValidationError.DuplicatePreset(stream, it) }
        list.forEach { errors += checkLevel(stream, it.level, maxLevels) }
    }

    profiles.groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { errors += ValidationError.DuplicateProfile(it) }

    profiles.forEach { profile ->
        if (profile.slots.isEmpty()) errors += ValidationError.EmptyProfile(profile.name)
        profile.slots.forEach { (stream, spec) ->
            errors += checkSpec(stream, spec, maxLevels)
        }
    }

    blocks.forEach { block ->
        if (block.durationMin <= 0 || block.durationMin > MINUTES_PER_WEEK) {
            errors += ValidationError.InvalidDuration(block)
        }
        when (val target = block.target) {
            is Target.Single -> errors += checkSpec(target.stream, target.spec, maxLevels)
            is Target.ProfileRef ->
                if (profiles.none { it.name == target.name }) {
                    errors += ValidationError.UnknownProfile(target.name)
                }
        }
    }

    // Conflict detection needs compiled events, and compiling throws on the
    // dangling references collected above. Only attempt it once refs are clean.
    if (errors.none { it is ValidationError.UnknownPreset || it is ValidationError.UnknownProfile }) {
        compile()
            .groupBy { Triple(it.day, it.time, it.stream) }
            .filterValues { it.size > 1 }
            .keys
            .forEach { (day, time, stream) ->
                errors += ValidationError.Conflict(day, time, stream)
            }
    }

    return errors
}

private fun Schedule.checkSpec(
    stream: AudioStream,
    spec: LevelSpec,
    maxLevels: Map<AudioStream, Int>,
): List<ValidationError> =
    when (spec) {
        is LevelSpec.Raw -> checkLevel(stream, spec.level, maxLevels)
        is LevelSpec.PresetRef ->
            // The preset's own level is checked where presets are declared, so
            // only existence matters here.
            if (presets[stream]?.any { it.name == spec.name } == true) {
                emptyList()
            } else {
                listOf(ValidationError.UnknownPreset(stream, spec.name))
            }
    }

private fun checkLevel(
    stream: AudioStream,
    level: Int,
    maxLevels: Map<AudioStream, Int>,
): List<ValidationError> {
    if (level < stream.minLevel) return listOf(ValidationError.LevelTooLow(stream, level))
    val max = maxLevels[stream] ?: return emptyList()
    if (level > max) return listOf(ValidationError.LevelTooHigh(stream, level, max))
    return emptyList()
}
