package dev.lh.volsched.core

import java.time.DayOfWeek
import java.time.LocalTime

// Pure edits on a Schedule, for the editor. Each returns a new schedule and
// none of them validates: the editor runs validate() on the result and shows
// what's wrong, so a half-finished edit is representable rather than refused.

/** Somewhere a preset is referenced, so the editor can refuse a delete that would dangle. */
sealed interface PresetUsage {
    data class InProfile(val profile: String) : PresetUsage

    data class InBlock(val block: Block) : PresetUsage
}

fun Schedule.presetUsages(stream: AudioStream, name: String): List<PresetUsage> {
    val ref = LevelSpec.PresetRef(name)
    val inProfiles = profiles
        .filter { it.slots[stream] == ref }
        .map { PresetUsage.InProfile(it.name) }
    val inBlocks = blocks
        .filter { it.target == Target.Single(stream, ref) }
        .map { PresetUsage.InBlock(it) }
    return inProfiles + inBlocks
}

fun Schedule.profileUsages(name: String): List<Block> =
    blocks.filter { it.target == Target.ProfileRef(name) }

/**
 * Adds [preset] to [stream], or replaces the preset named [replacing] in place.
 *
 * A rename is carried into every profile slot and single-stream block on that
 * stream that used the old name. Presets are scoped per stream, so a preset of
 * the same name on another stream is untouched.
 */
fun Schedule.putPreset(stream: AudioStream, preset: Preset, replacing: String? = null): Schedule {
    val current = presets[stream].orEmpty()
    val updated =
        if (replacing != null && current.any { it.name == replacing }) {
            current.map { if (it.name == replacing) preset else it }
        } else {
            current + preset
        }
    val renamed =
        if (replacing != null && replacing != preset.name) renamePresetRefs(stream, replacing, preset.name) else this
    return renamed.copy(presets = presets + (stream to updated))
}

/** Doesn't touch references: check [presetUsages] first. */
fun Schedule.removePreset(stream: AudioStream, name: String): Schedule {
    val remaining = presets[stream].orEmpty().filterNot { it.name == name }
    return copy(presets = if (remaining.isEmpty()) presets - stream else presets + (stream to remaining))
}

/** Adds [profile], or replaces the one named [replacing] in place, carrying a rename into blocks. */
fun Schedule.putProfile(profile: Profile, replacing: String? = null): Schedule {
    val updated =
        if (replacing != null && profiles.any { it.name == replacing }) {
            profiles.map { if (it.name == replacing) profile else it }
        } else {
            profiles + profile
        }
    val renamedBlocks =
        if (replacing != null && replacing != profile.name) {
            val old = Target.ProfileRef(replacing)
            blocks.map { if (it.target == old) it.copy(target = Target.ProfileRef(profile.name)) else it }
        } else {
            blocks
        }
    return copy(profiles = updated, blocks = renamedBlocks)
}

/** Doesn't touch blocks: check [profileUsages] first. */
fun Schedule.removeProfile(name: String): Schedule =
    copy(profiles = profiles.filterNot { it.name == name })

/** The blocks starting on [day], earliest first, each with its index into [Schedule.blocks]. */
fun Schedule.blocksOn(day: DayOfWeek): List<IndexedValue<Block>> =
    blocks.withIndex().filter { it.value.day == day }.sortedBy { it.value.start }

/** Adds one identical block on each of [days]. */
fun Schedule.addBlocks(days: Collection<DayOfWeek>, start: LocalTime, durationMin: Int, target: Target): Schedule =
    copy(blocks = (blocks + days.distinct().map { Block(it, start, durationMin, target) }).ordered())

fun Schedule.removeBlockAt(index: Int): Schedule =
    copy(blocks = blocks.filterIndexed { i, _ -> i != index })

/**
 * Replaces all blocks on each day in [to] with copies of [from]'s blocks.
 * [from] itself is left alone even if it is also listed in [to].
 */
fun Schedule.copyDay(from: DayOfWeek, to: Collection<DayOfWeek>): Schedule {
    val targets = to.toSet() - from
    val source = blocks.filter { it.day == from }
    val copies = targets.flatMap { day -> source.map { it.copy(day = day) } }
    return copy(blocks = (blocks.filterNot { it.day in targets } + copies).ordered())
}

/** Monday first, then by start time, so the saved file reads like the week. */
private fun List<Block>.ordered(): List<Block> =
    sortedWith(compareBy({ it.day }, { it.start }))

private fun Schedule.renamePresetRefs(stream: AudioStream, from: String, to: String): Schedule {
    val old = LevelSpec.PresetRef(from)
    val new = LevelSpec.PresetRef(to)
    return copy(
        profiles = profiles.map { profile ->
            if (profile.slots[stream] == old) profile.copy(slots = profile.slots + (stream to new)) else profile
        },
        blocks = blocks.map { block ->
            if (block.target == Target.Single(stream, old)) block.copy(target = Target.Single(stream, new)) else block
        },
    )
}
