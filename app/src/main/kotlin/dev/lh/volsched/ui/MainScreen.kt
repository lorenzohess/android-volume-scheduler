package dev.lh.volsched.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lh.volsched.audio.VolumeApplier
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.ImportResult
import dev.lh.volsched.core.importSchedule
import dev.lh.volsched.scheduler.VolumeScheduler
import dev.lh.volsched.storage.EventLog
import dev.lh.volsched.storage.ScheduleStore
import dev.lh.volsched.widget.VolumeWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val LOG_LINES = 60
private val NEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val store = remember { ScheduleStore.get(context) }
    val applier = remember { VolumeApplier(context) }
    val eventLog = remember { EventLog(context) }
    val scope = rememberCoroutineScope()

    val schedule by store.schedule.collectAsState()
    val maxLevels = remember { applier.maxLevels() }

    var levels by remember { mutableStateOf(applier.currentLevels()) }
    var logLines by remember { mutableStateOf(eventLog.recent(LOG_LINES)) }
    var nextFiring by remember { mutableStateOf(VolumeScheduler.nextFiringOrNull(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        levels = applier.currentLevels()
        logLines = eventLog.recent(LOG_LINES)
        nextFiring = VolumeScheduler.nextFiringOrNull(context)
    }

    // The hardware volume keys change levels behind our back, so poll rather
    // than let the sliders drift out of sync with reality.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            levels = applier.currentLevels()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(store.exportJson().toByteArray())
                }
            }.fold({ "Exported schedule" }, { "Export failed: ${it.message}" })
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()

            message = when {
                text == null -> "Could not read that file"
                else -> when (val result = importSchedule(text, maxLevels)) {
                    is ImportResult.Ok -> {
                        store.replaceFromImport(result.schedule)
                        VolumeScheduler.reconcile(context, "import")
                        VolumeScheduler.rearm(context, "import")
                        scope.launch { VolumeWidget.refresh(context) }
                        refresh()
                        "Imported ${result.schedule.blocks.size} blocks"
                    }

                    is ImportResult.Invalid ->
                        "Rejected: " + result.errors.joinToString("; ") { it.message }

                    is ImportResult.Unreadable -> "Rejected: ${result.reason}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Volume Scheduler", style = MaterialTheme.typography.headlineSmall)

        store.loadError?.let { error ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Schedule file is unreadable", fontWeight = FontWeight.Bold)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Running on an empty schedule. The file has not been overwritten - " +
                            "fix it and reopen, or import a known-good export.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---- Master switch -------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Scheduling", fontWeight = FontWeight.Bold)
                Text(
                    if (schedule.enabled) "On" else "Off - no alarms armed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = schedule.enabled,
                enabled = store.loadError == null,
                onCheckedChange = { on ->
                    store.setEnabled(on)
                    if (on) {
                        VolumeScheduler.reconcile(context, "main switch")
                        VolumeScheduler.rearm(context, "main switch")
                    } else {
                        VolumeScheduler.cancel(context)
                    }
                    scope.launch { VolumeWidget.refresh(context) }
                    refresh()
                },
            )
        }

        Text(
            nextFiring?.let { firing ->
                "Next change ${firing.at.format(NEXT_FORMAT)} - " +
                    firing.events.joinToString(", ") { "${it.stream} to ${it.level}" }
            } ?: "No upcoming change",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        // ---- Live sliders --------------------------------------------------
        Text("Volumes", fontWeight = FontWeight.Bold)
        Text(
            "Changing a slider takes effect now and sticks until the next scheduled change.",
            style = MaterialTheme.typography.bodySmall,
        )

        AudioStream.entries.forEach { stream ->
            val max = maxLevels[stream] ?: 1
            val min = stream.minLevel
            val level = levels[stream] ?: min

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stream.name)
                    Text("$level / $max")
                }
                Slider(
                    value = level.coerceIn(min, max).toFloat(),
                    onValueChange = { raw ->
                        applier.apply(stream, raw.roundToInt())
                        levels = applier.currentLevels()
                    },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                )
                if (min > 0) {
                    Text(
                        "Floor is $min: zero would switch the phone to vibrate/silent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider()

        // ---- Debug tools ---------------------------------------------------
        Text("Tools", fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                VolumeScheduler.reconcile(context, "manual")
                refresh()
            }) { Text("Apply schedule now") }

            OutlinedButton(onClick = {
                VolumeScheduler.fireNextNow(context)
                refresh()
            }) { Text("Fire next event") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                VolumeScheduler.rearm(context, "manual")
                refresh()
            }) { Text("Re-arm") }

            OutlinedButton(onClick = {
                eventLog.clear()
                refresh()
            }) { Text("Clear log") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("volume-schedule.json") }) {
                Text("Export")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text("Import")
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        // ---- Event log -----------------------------------------------------
        Text("Log (newest first)", fontWeight = FontWeight.Bold)
        Button(onClick = { refresh() }) { Text("Refresh") }

        if (logLines.isEmpty()) {
            Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            Card {
                Column(Modifier.padding(8.dp)) {
                    logLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
