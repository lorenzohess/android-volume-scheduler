package dev.lh.volsched.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lh.volsched.audio.VolumeApplier
import dev.lh.volsched.core.Schedule
import dev.lh.volsched.core.overlaps
import dev.lh.volsched.core.scheduleFromJson
import dev.lh.volsched.core.toJson
import dev.lh.volsched.core.validate
import dev.lh.volsched.scheduler.VolumeScheduler
import dev.lh.volsched.storage.ScheduleStore
import dev.lh.volsched.widget.VolumeWidget
import kotlinx.coroutines.launch

private val TABS = listOf("Week", "Profiles", "Presets")

/** Survives rotation and dark-mode switches, which recreate the activity. */
private val ScheduleSaver = Saver<Schedule, String>(save = { it.toJson() }, restore = { scheduleFromJson(it) })

/**
 * Edits a draft copy of the schedule; nothing touches the store until Save.
 *
 * Save is refused while validation reports errors. Overlaps are only warnings,
 * shown on the blocks themselves.
 */
@Composable
fun EditorScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ScheduleStore.get(context) }
    val maxLevels = remember { VolumeApplier(context).maxLevels() }
    val scope = rememberCoroutineScope()

    val saved = rememberSaveable(saver = ScheduleSaver) { store.schedule.value }
    var draft by rememberSaveable(stateSaver = ScheduleSaver) { mutableStateOf(saved) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val errors = remember(draft) { draft.validate(maxLevels) }
    val overlaps = remember(draft) { draft.overlaps() }
    val dirty = draft != saved

    fun close() {
        if (dirty) confirmDiscard = true else onClose()
    }

    fun save() {
        saveError = runCatching {
            // Keep the on/off switch as it is now: the widget may have flipped
            // it while this screen was open.
            store.save(draft.copy(enabled = store.schedule.value.enabled))
            // Arm only. Reconciling would override a volume the user just set
            // by hand (PLAN.md trigger table: "Schedule edited").
            VolumeScheduler.rearm(context, "schedule edited")
        }.exceptionOrNull()?.let { "Save failed: ${it.message}" }

        if (saveError == null) {
            scope.launch { VolumeWidget.refresh(context) }
            onClose()
        }
    }

    BackHandler { close() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { close() }) { Text(if (dirty) "Discard" else "Close") }
            Text("Edit schedule", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { save() }, enabled = dirty && errors.isEmpty()) { Text("Save") }
        }

        if (errors.isNotEmpty() || saveError != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Can't save yet", fontWeight = FontWeight.Bold)
                    saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    errors.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                0 -> WeekTab(draft, overlaps, maxLevels, onChange = { draft = it })
                1 -> ProfilesTab(draft, maxLevels, onChange = { draft = it })
                else -> PresetsTab(draft, maxLevels, onChange = { draft = it })
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onClose()
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}
