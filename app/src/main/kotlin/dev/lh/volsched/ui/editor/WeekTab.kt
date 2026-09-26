package dev.lh.volsched.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.Block
import dev.lh.volsched.core.LevelSpec
import dev.lh.volsched.core.MINUTES_PER_DAY
import dev.lh.volsched.core.Overlap
import dev.lh.volsched.core.Schedule
import dev.lh.volsched.core.Target
import dev.lh.volsched.core.addBlocks
import dev.lh.volsched.core.blocksOn
import dev.lh.volsched.core.copyDay
import dev.lh.volsched.core.label
import dev.lh.volsched.core.removeBlockAt
import dev.lh.volsched.core.spanLabel
import java.time.DayOfWeek
import java.time.LocalTime

/** A block being edited: [index] into Schedule.blocks, or null for a new one. */
private data class BlockEditing(val index: Int?, val block: Block?)

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

@Composable
fun WeekTab(
    draft: Schedule,
    overlaps: List<Overlap>,
    maxLevels: Map<AudioStream, Int>,
    onChange: (Schedule) -> Unit,
) {
    var day by rememberSaveable { mutableStateOf(DayOfWeek.MONDAY) }
    var editing by remember { mutableStateOf<BlockEditing?>(null) }
    var copying by remember { mutableStateOf(false) }

    DayToggles(selected = setOf(day), onToggle = { day = it })
    Text(day.label(), style = MaterialTheme.typography.titleMedium)

    val blocks = draft.blocksOn(day)
    if (blocks.isEmpty()) {
        Text("Nothing scheduled on ${day.label()}.", style = MaterialTheme.typography.bodySmall)
    }
    blocks.forEach { (index, block) ->
        val warnings = overlaps.filter { it.first == block || it.second == block }
        BlockCard(block, warnings, onClick = { editing = BlockEditing(index, block) })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { editing = BlockEditing(null, null) }) { Text("Add block") }
        OutlinedButton(onClick = { copying = true }, enabled = blocks.isNotEmpty()) { Text("Copy day to…") }
    }
    Text(
        "A block acts only when it starts. Its end time is for the overview and the overlap " +
            "warnings; levels then stay until the next block that sets the same stream.",
        style = MaterialTheme.typography.bodySmall,
    )

    editing?.let { current ->
        BlockDialog(
            draft = draft,
            day = day,
            editing = current,
            maxLevels = maxLevels,
            onDismiss = { editing = null },
            onSave = {
                onChange(it)
                editing = null
            },
        )
    }

    if (copying) {
        CopyDayDialog(
            from = day,
            onDismiss = { copying = false },
            onCopy = { targets ->
                onChange(draft.copyDay(day, targets))
                copying = false
            },
        )
    }
}

@Composable
private fun BlockCard(block: Block, warnings: List<Overlap>, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(block.spanLabel(), fontWeight = FontWeight.Bold)
            Text(block.target.label())
            warnings.forEach { overlap ->
                val other = if (overlap.first == block) overlap.second else overlap.first
                Text(
                    "Overlaps ${other.day.label()} ${other.spanLabel()} (${other.target.label()}) on " +
                        overlap.streams.sortedBy { it.ordinal }.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BlockDialog(
    draft: Schedule,
    day: DayOfWeek,
    editing: BlockEditing,
    maxLevels: Map<AudioStream, Int>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
) {
    val original = editing.block
    fun defaultSpec(stream: AudioStream): LevelSpec =
        draft.presets[stream]?.firstOrNull()?.let { LevelSpec.PresetRef(it.name) }
            ?: LevelSpec.Raw(maxOf(stream.minLevel, (maxLevels[stream] ?: 0) / 2))

    var days by remember { mutableStateOf(setOf(original?.day ?: day)) }
    var start by remember { mutableStateOf(original?.start ?: LocalTime.of(9, 0)) }
    // Kept as a duration, not an end time, so a block longer than a day
    // survives being edited as long as its end isn't re-picked.
    var durationMin by remember { mutableStateOf(original?.durationMin ?: 60) }
    var target by remember {
        mutableStateOf(
            original?.target
                ?: draft.profiles.firstOrNull()?.let { Target.ProfileRef(it.name) }
                ?: Target.Single(AudioStream.RING, defaultSpec(AudioStream.RING)),
        )
    }

    val problem = when {
        days.isEmpty() -> "Pick at least one day"
        target is Target.ProfileRef && draft.profiles.none { it.name == (target as Target.ProfileRef).name } ->
            "Choose a profile"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "New block" else "Edit block") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (original == null) "Days" else "Days (picking more adds copies)",
                    fontWeight = FontWeight.Bold,
                )
                DayToggles(selected = days, onToggle = { d -> days = if (d in days) days - d else days + d })

                Text("Time", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeButton("Start", start) { start = it }
                    TimeButton("End", start.plusMinutes(durationMin.toLong())) { end ->
                        val minutes = Math.floorMod((end.toSecondOfDay() - start.toSecondOfDay()) / 60, MINUTES_PER_DAY)
                        durationMin = if (minutes == 0) MINUTES_PER_DAY else minutes
                    }
                }
                Text(
                    Block(day, start, durationMin, target).spanLabel(),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("Sets", fontWeight = FontWeight.Bold)
                val current = target
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = current is Target.ProfileRef,
                        onClick = {
                            if (current !is Target.ProfileRef) {
                                target = Target.ProfileRef(draft.profiles.firstOrNull()?.name ?: "")
                            }
                        },
                    )
                    Text("Profile")
                    RadioButton(
                        selected = current is Target.Single,
                        onClick = {
                            if (current !is Target.Single) {
                                target = Target.Single(AudioStream.RING, defaultSpec(AudioStream.RING))
                            }
                        },
                    )
                    Text("One stream")
                }

                when (current) {
                    is Target.ProfileRef ->
                        if (draft.profiles.isEmpty()) {
                            Text(
                                "No profiles yet. Create one in the Profiles tab, or set one stream.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            OptionMenu(
                                label = current.name.ifEmpty { "Choose profile" },
                                options = draft.profiles,
                                optionLabel = { it.name },
                                onSelect = { target = Target.ProfileRef(it.name) },
                            )
                        }

                    is Target.Single -> {
                        OptionMenu(
                            label = current.stream.name,
                            options = AudioStream.entries,
                            optionLabel = { it.name },
                            onSelect = { stream ->
                                if (stream != current.stream) target = Target.Single(stream, defaultSpec(stream))
                            },
                        )
                        LevelSpecPicker(
                            stream = current.stream,
                            spec = current.spec,
                            presets = draft.presets[current.stream].orEmpty(),
                            maxLevel = maxLevels[current.stream] ?: 15,
                            allowUnset = false,
                            onChange = { spec -> if (spec != null) target = current.copy(spec = spec) },
                        )
                    }
                }

                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    val base = editing.index?.let { draft.removeBlockAt(it) } ?: draft
                    onSave(base.addBlocks(days, start, durationMin, target))
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                editing.index?.let { index ->
                    TextButton(onClick = { onSave(draft.removeBlockAt(index)) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CopyDayDialog(from: DayOfWeek, onDismiss: () -> Unit, onCopy: (Set<DayOfWeek>) -> Unit) {
    var targets by remember { mutableStateOf(emptySet<DayOfWeek>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy ${from.label()} to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Replaces everything on the chosen days with ${from.label()}'s blocks.",
                    style = MaterialTheme.typography.bodySmall,
                )
                DayToggles(
                    selected = targets,
                    onToggle = { d -> if (d != from) targets = if (d in targets) targets - d else targets + d },
                )
                Row {
                    TextButton(onClick = { targets = WEEKDAYS - from }) { Text("Weekdays") }
                    TextButton(onClick = { targets = DayOfWeek.entries.toSet() - from }) { Text("All days") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = targets.isNotEmpty(), onClick = { onCopy(targets) }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
