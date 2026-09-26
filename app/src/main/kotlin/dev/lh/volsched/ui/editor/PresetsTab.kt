package dev.lh.volsched.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.Preset
import dev.lh.volsched.core.PresetUsage
import dev.lh.volsched.core.Schedule
import dev.lh.volsched.core.label
import dev.lh.volsched.core.presetUsages
import dev.lh.volsched.core.putPreset
import dev.lh.volsched.core.removePreset

/** A preset being edited on [stream], or a new one when [preset] is null. */
private data class PresetEditing(val stream: AudioStream, val preset: Preset?)

@Composable
fun PresetsTab(
    draft: Schedule,
    maxLevels: Map<AudioStream, Int>,
    onChange: (Schedule) -> Unit,
) {
    var editing by remember { mutableStateOf<PresetEditing?>(null) }

    Text(
        "Named levels per stream, such as RING Quiet = 2. Changing a preset's level " +
            "changes it everywhere it is used.",
        style = MaterialTheme.typography.bodySmall,
    )
    AudioStream.entries.forEach { stream ->
        Text(stream.name, fontWeight = FontWeight.Bold)
        val presets = draft.presets[stream].orEmpty()
        if (presets.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodySmall)
        }
        presets.forEach { preset ->
            Card(Modifier.fillMaxWidth().clickable { editing = PresetEditing(stream, preset) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(preset.name)
                    Text("${preset.level} / ${maxLevels[stream] ?: "?"}")
                }
            }
        }
        OutlinedButton(onClick = { editing = PresetEditing(stream, null) }) { Text("Add ${stream.name} preset") }
    }

    editing?.let { current ->
        PresetDialog(
            draft = draft,
            stream = current.stream,
            original = current.preset,
            maxLevel = maxLevels[current.stream] ?: 15,
            onDismiss = { editing = null },
            onSave = {
                onChange(it)
                editing = null
            },
        )
    }
}

@Composable
private fun PresetDialog(
    draft: Schedule,
    stream: AudioStream,
    original: Preset?,
    maxLevel: Int,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var level by remember {
        mutableStateOf(original?.level ?: (maxLevel / 2).coerceIn(stream.minLevel, maxOf(stream.minLevel, maxLevel)))
    }

    val trimmed = name.trim()
    val usages = original?.let { draft.presetUsages(stream, it.name) }.orEmpty()
    val problem = when {
        trimmed.isEmpty() -> "Needs a name"
        draft.presets[stream].orEmpty().any { it.name == trimmed && it.name != original?.name } ->
            "$stream already has a preset called $trimmed"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "New $stream preset" else "Edit $stream preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                LevelSlider(stream, level, maxLevel) { level = it }
                if (usages.isNotEmpty()) {
                    Text(
                        "Used by " + usages.joinToString { usage ->
                            when (usage) {
                                is PresetUsage.InProfile -> "profile ${usage.profile}"
                                is PresetUsage.InBlock -> "${usage.block.day.label()} ${usage.block.start}"
                            }
                        } + ". Renaming updates them; deleting is disabled until none use it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onSave(draft.putPreset(stream, Preset(trimmed, level), replacing = original?.name)) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (original != null) {
                    TextButton(
                        enabled = usages.isEmpty(),
                        onClick = { onSave(draft.removePreset(stream, original.name)) },
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
