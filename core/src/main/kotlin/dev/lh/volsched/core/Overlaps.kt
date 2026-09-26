package dev.lh.volsched.core

import java.time.DayOfWeek

/**
 * Two blocks whose spans intersect and that set at least one stream in common.
 *
 * Only a warning. At runtime a block is just its leading edge, so [second]
 * simply takes over [streams] from [first] when it starts. But when the spans
 * overlap that is usually a typo in a time rather than the intent. Blocks that
 * overlap on disjoint streams (a Work profile for ring, a media-only block)
 * are the normal per-stream case and are not reported. Two blocks starting in
 * the same minute are not reported here either: when they share a stream,
 * [validate] already rejects that as a conflict.
 */
data class Overlap(val first: Block, val second: Block, val streams: Set<AudioStream>) {
    val message: String
        get() = "${first.day.label()} ${first.spanLabel()} (${first.target.label()}) overlaps " +
            "${second.day.label()} ${second.spanLabel()} (${second.target.label()}) on " +
            streams.sortedBy { it.ordinal }.joinToString()
}

/** Every [Overlap], with [Overlap.first] being the block that started earlier. */
fun Schedule.overlaps(): List<Overlap> {
    val candidates = blocks
        .filter { it.durationMin in 1..MINUTES_PER_WEEK }
        .sortedBy { it.startWeekMinute }
    val result = mutableListOf<Overlap>()
    for (i in candidates.indices) {
        for (j in i + 1 until candidates.size) {
            val a = candidates[i]
            val b = candidates[j]
            if (a.startWeekMinute == b.startWeekMinute) continue

            val shared = streamsOf(a) intersect streamsOf(b)
            if (shared.isEmpty()) continue

            // On the circular week: does b start inside a, or a inside b?
            val bAfterA = Math.floorMod(b.startWeekMinute - a.startWeekMinute, MINUTES_PER_WEEK)
            val aAfterB = Math.floorMod(a.startWeekMinute - b.startWeekMinute, MINUTES_PER_WEEK)
            when {
                bAfterA < a.durationMin -> result += Overlap(a, b, shared)
                aAfterB < b.durationMin -> result += Overlap(b, a, shared)
            }
        }
    }
    return result
}

/** Minutes since Monday 00:00 at which this block starts. */
val Block.startWeekMinute: Int
    get() = (day.value - 1) * MINUTES_PER_DAY + start.hour * 60 + start.minute

/** "07:00–09:00", plus "(next day)" or "(+N days)" when it ends on a later day. */
fun Block.spanLabel(): String {
    val endOffsetDays = (start.hour * 60 + start.minute + durationMin) / MINUTES_PER_DAY
    val end = start.plusMinutes(durationMin.toLong())
    val suffix = when (endOffsetDays) {
        0 -> ""
        1 -> " (next day)"
        else -> " (+$endOffsetDays days)"
    }
    return "$start–$end$suffix"
}

/** "Work" for a profile block, "RING Quiet" or "RING 3" for a single-stream one. */
fun Target.label(): String =
    when (this) {
        is Target.ProfileRef -> name
        is Target.Single -> "$stream ${spec.label()}"
    }

fun LevelSpec.label(): String =
    when (this) {
        is LevelSpec.PresetRef -> name
        is LevelSpec.Raw -> level.toString()
    }

/** "Monday". Locale-independent on purpose, like the rest of the file format. */
fun DayOfWeek.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun Schedule.streamsOf(block: Block): Set<AudioStream> =
    when (val target = block.target) {
        is Target.Single -> setOf(target.stream)
        is Target.ProfileRef -> profiles.firstOrNull { it.name == target.name }?.slots?.keys.orEmpty()
    }
