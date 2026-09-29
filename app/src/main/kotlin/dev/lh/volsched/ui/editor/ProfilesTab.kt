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
import dev.lh.volsched.core.LevelSpec
import dev.lh.volsched.core.Profile
import dev.lh.volsched.core.Schedule
import dev.lh.volsched.core.label
import dev.lh.volsched.core.levelOf
import dev.lh.volsched.core.profileUsages
import dev.lh.volsched.core.putProfile
import dev.lh.volsched.core.removeProfile

/** A profile being edited, or null for a new one. */
private data class ProfileEditing(val profile: Profile?)

@Composable
fun ProfilesTab(
    draft: Schedule,
    maxLevels: Map<AudioStream, Int>,
    onChange: (Schedule) -> Unit,
) {
    var editing by remember { mutableStateOf<ProfileEditing?>(null) }

    Text(
        "A profile sets some or all streams at once. Streams it leaves out keep whatever level they have.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (draft.profiles.isEmpty()) {
        Text("No profiles yet.", style = MaterialTheme.typography.bodySmall)
    }
    draft.profiles.forEach { profile ->
        val uses = draft.profileUsages(profile.name).size
        Card(Modifier.fillMaxWidth().clickable { editing = ProfileEditing(profile) }) {
            Column(Modifier.padding(12.dp)) {
                Text(profile.name, fontWeight = FontWeight.Bold)
                Text(
                    profile.slots.entries
                        .sortedBy { it.key.ordinal }
                        .joinToString { "${it.key} ${it.value.label()}" }
                        .ifEmpty { "Sets nothing" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Used by $uses block${if (uses == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Button(onClick = { editing = ProfileEditing(null) }) { Text("Add profile") }

    editing?.let { current ->
        ProfileDialog(
            draft = draft,
            original = current.profile,
            maxLevels = maxLevels,
            onDismiss = { editing = null },
            onSave = {
                onChange(it)
                editing = null
            },
        )
    }
}

@Composable
private fun ProfileDialog(
    draft: Schedule,
    original: Profile?,
    maxLevels: Map<AudioStream, Int>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var slots by remember { mutableStateOf(original?.slots ?: emptyMap()) }

    val trimmed = name.trim()
    val usages = original?.let { draft.profileUsages(it.name) }.orEmpty()
    // Android mutes notifications while the ringer is muted, so a muted-ring
    // profile leaves NOTIFICATION unset: it then returns to its own level
    // when a later block unmutes ring.
    val ringMuted = slots[AudioStream.RING]?.let { draft.levelOf(AudioStream.RING, it) } == 0
    val problem = when {
        trimmed.isEmpty() -> "Needs a name"
        draft.profiles.any { it.name == trimmed && it.name != original?.name } -> "Another profile is called $trimmed"
        slots.isEmpty() -> "Set at least one stream"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "New profile" else "Edit profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                AudioStream.entries.forEach { stream ->
                    Text(stream.name, fontWeight = FontWeight.Bold)
                    if (stream == AudioStream.NOTIFICATION && ringMuted) {
                        Text(
                            "Muted with RING. Android mutes notifications while the ringer is muted; " +
                                "they return to their previous level when ring is unmuted.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LevelSpecPicker(
                            stream = stream,
                            spec = slots[stream],
                            presets = draft.presets[stream].orEmpty(),
                            maxLevel = maxLevels[stream] ?: 15,
                            allowUnset = true,
                            onChange = { spec -> slots = if (spec == null) slots - stream else slots + (stream to spec) },
                        )
                    }
                }
                if (usages.isNotEmpty()) {
                    Text(
                        "Used by ${usages.size} block${if (usages.size == 1) "" else "s"}. " +
                            "Renaming updates them; deleting is disabled until none use it.",
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
                onClick = {
                    // Stream order, so the saved file lists slots consistently.
                    val ordered: Map<AudioStream, LevelSpec> = AudioStream.entries
                        .filterNot { it == AudioStream.NOTIFICATION && ringMuted }
                        .mapNotNull { stream -> slots[stream]?.let { stream to it } }
                        .toMap()
                    onSave(draft.putProfile(Profile(trimmed, ordered), replacing = original?.name))
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (original != null) {
                    TextButton(
                        enabled = usages.isEmpty(),
                        onClick = { onSave(draft.removeProfile(original.name)) },
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
