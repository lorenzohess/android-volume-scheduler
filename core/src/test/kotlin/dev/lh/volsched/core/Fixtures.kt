package dev.lh.volsched.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Stand-in for a device's AudioManager maxima. Pixel-ish numbers. */
val TestMaxLevels = mapOf(
    AudioStream.MEDIA to 25,
    AudioStream.RING to 7,
    AudioStream.NOTIFICATION to 7,
    AudioStream.ALARM to 7,
)

/** 2026-09-21 is a Monday, so these read the way they look. */
fun monday(hour: Int, minute: Int = 0): LocalDateTime =
    LocalDateTime.of(2026, 9, 21, hour, minute)

fun sunday(hour: Int, minute: Int = 0): LocalDateTime =
    LocalDateTime.of(2026, 9, 27, hour, minute)

fun at(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

fun block(
    day: DayOfWeek = DayOfWeek.MONDAY,
    start: LocalTime = at(9),
    durationMin: Int = 60,
    target: Target,
) = Block(day, start, durationMin, target)

fun single(stream: AudioStream, level: Int) =
    Target.Single(stream, LevelSpec.Raw(level))

fun presetRef(stream: AudioStream, name: String) =
    Target.Single(stream, LevelSpec.PresetRef(name))
