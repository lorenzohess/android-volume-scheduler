package dev.lh.volsched.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

const val SCHEMA_VERSION = 1

/**
 * The four streams this app schedules.
 *
 * Named [AudioStream] rather than `Stream` to avoid colliding with
 * `java.util.stream.Stream` at call sites that use star imports.
 */
enum class AudioStream {
    MEDIA,
    RING,
    NOTIFICATION,
    ALARM,
    ;

    /**
     * Lowest level this app will set, matching Android's own floors.
     *
     * RING at 0 mutes the ringer: Android switches to vibrate, which needs no
     * permission, and mutes NOTIFICATION along with it until ring is unmuted
     * (see [ValidationError.NotificationWhileRingMuted]). Fully silent mode,
     * with no vibration, would need Do Not Disturb access; the app never asks
     * for it. ALARM stops at 1 because Android won't silence the alarm stream.
     */
    val minLevel: Int
        get() = if (this == ALARM) 1 else 0
}

/** A named volume level, scoped to one stream because each stream's maximum differs. */
@Serializable
data class Preset(val name: String, val level: Int)

/** How a block supplies a level: by preset name, or as a literal number. */
@Serializable
sealed interface LevelSpec {
    @Serializable
    @SerialName("preset")
    data class PresetRef(val name: String) : LevelSpec

    @Serializable
    @SerialName("raw")
    data class Raw(val level: Int) : LevelSpec
}

/**
 * A named bundle covering some or all streams.
 *
 * A stream absent from [slots] means "don't touch", which is what makes partial
 * profiles useful - e.g. a Sleep profile that leaves alarm alone.
 */
@Serializable
data class Profile(
    val name: String,
    val slots: Map<AudioStream, LevelSpec> = emptyMap(),
)

/** What a block applies when it starts. */
@Serializable
sealed interface Target {
    @Serializable
    @SerialName("profile")
    data class ProfileRef(val name: String) : Target

    @Serializable
    @SerialName("single")
    data class Single(val stream: AudioStream, val spec: LevelSpec) : Target
}

/**
 * A scheduled span.
 *
 * [durationMin] exists only so the editor can draw bars and warn about
 * overlaps; the runtime never reads it. Only the leading edge has any effect,
 * because a manual volume change sticks until the next edge (notes.md Q1/Q4).
 * Durations may run past midnight and spill into the following day.
 */
@Serializable
data class Block(
    @Serializable(with = DayOfWeekSerializer::class) val day: DayOfWeek,
    @Serializable(with = LocalTimeSerializer::class) val start: LocalTime,
    val durationMin: Int,
    val target: Target,
)

/** The whole persisted document. Also the export format. */
@Serializable
data class Schedule(
    val version: Int = SCHEMA_VERSION,
    val enabled: Boolean = true,
    val presets: Map<AudioStream, List<Preset>> = emptyMap(),
    val profiles: List<Profile> = emptyList(),
    val blocks: List<Block> = emptyList(),
)

object LocalTimeSerializer : KSerializer<LocalTime> {
    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) =
        encoder.encodeString(value.format(format))

    override fun deserialize(decoder: Decoder): LocalTime =
        LocalTime.parse(decoder.decodeString(), format)
}

object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DayOfWeek", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DayOfWeek) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): DayOfWeek =
        DayOfWeek.valueOf(decoder.decodeString().uppercase())
}
