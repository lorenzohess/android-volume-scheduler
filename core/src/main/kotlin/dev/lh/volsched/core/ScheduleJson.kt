package dev.lh.volsched.core

import kotlinx.serialization.json.Json

/**
 * The single JSON codec for the schedule file, which is also the export format.
 *
 * [Json.ignoreUnknownKeys] stays false on purpose: this file is meant to be
 * hand-editable, and a silently ignored typo would show up much later as a
 * missing volume change.
 */
val ScheduleJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = false
}

fun Schedule.toJson(): String = ScheduleJson.encodeToString(Schedule.serializer(), this)

/** Throws [kotlinx.serialization.SerializationException] on malformed input. */
fun scheduleFromJson(text: String): Schedule =
    ScheduleJson.decodeFromString(Schedule.serializer(), text)

/** Result of importing a file: either a usable schedule or the reasons it isn't. */
sealed interface ImportResult {
    data class Ok(val schedule: Schedule) : ImportResult
    data class Invalid(val errors: List<ValidationError>) : ImportResult
    data class Unreadable(val reason: String) : ImportResult
}

/** Parses and validates in one step. Used by both file import and startup load. */
fun importSchedule(text: String, maxLevels: Map<AudioStream, Int>): ImportResult =
    try {
        val schedule = scheduleFromJson(text)
        if (schedule.version > SCHEMA_VERSION) {
            ImportResult.Unreadable(
                "File uses schema version ${schedule.version}, this build understands $SCHEMA_VERSION",
            )
        } else {
            val errors = schedule.validate(maxLevels)
            if (errors.isEmpty()) ImportResult.Ok(schedule) else ImportResult.Invalid(errors)
        }
    } catch (e: Exception) {
        ImportResult.Unreadable(e.message ?: e.toString())
    }
