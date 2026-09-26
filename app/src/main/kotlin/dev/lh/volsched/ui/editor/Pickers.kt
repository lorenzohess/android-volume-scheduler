package dev.lh.volsched.ui.editor

import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.LevelSpec
import dev.lh.volsched.core.Preset
import dev.lh.volsched.core.label
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.roundToInt

/** A button showing [label] that opens a menu of [options]. */
@Composable
fun <T> OptionMenu(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * Opens the platform's 24-hour time picker. Used instead of Material 3's
 * TimePicker, which is still experimental in this Compose version.
 */
@Composable
fun TimeButton(label: String, time: LocalTime, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onPick(LocalTime.of(hour, minute)) },
                time.hour,
                time.minute,
                true,
            ).show()
        },
    ) { Text("$label $time") }
}

/** Seven toggle buttons, Monday first. Filled when selected. */
@Composable
fun DayToggles(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    val padding = PaddingValues(horizontal = 10.dp)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            val text = day.label().take(3)
            if (day in selected) {
                Button(onClick = { onToggle(day) }, contentPadding = padding) { Text(text) }
            } else {
                OutlinedButton(onClick = { onToggle(day) }, contentPadding = padding) { Text(text) }
            }
        }
    }
}

/** A slider over the levels this app may set on [stream], with an "n / max" label. */
@Composable
fun LevelSlider(stream: AudioStream, level: Int, maxLevel: Int, onChange: (Int) -> Unit) {
    val min = stream.minLevel
    val max = maxOf(min + 1, maxLevel)
    Column {
        Text("$level / $max", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = level.coerceIn(min, max).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
        )
    }
}

/**
 * Picks how a stream's level is given: one of its presets, a custom level, or,
 * when [allowUnset], nothing at all, which leaves the stream alone.
 */
@Composable
fun LevelSpecPicker(
    stream: AudioStream,
    spec: LevelSpec?,
    presets: List<Preset>,
    maxLevel: Int,
    allowUnset: Boolean,
    onChange: (LevelSpec?) -> Unit,
) {
    val choices = buildList<LevelChoice> {
        if (allowUnset) add(LevelChoice.Unset)
        presets.forEach { add(LevelChoice.UsePreset(it)) }
        add(LevelChoice.Custom)
    }
    val current = when (spec) {
        null -> LevelChoice.Unset.label
        is LevelSpec.PresetRef ->
            presets.firstOrNull { it.name == spec.name }?.let { LevelChoice.UsePreset(it).label }
                ?: "${spec.name} (missing preset)"
        is LevelSpec.Raw -> "Custom: ${spec.level}"
    }

    Column {
        OptionMenu(
            label = current,
            options = choices,
            optionLabel = { it.label },
            onSelect = { choice ->
                onChange(
                    when (choice) {
                        LevelChoice.Unset -> null
                        is LevelChoice.UsePreset -> LevelSpec.PresetRef(choice.preset.name)
                        LevelChoice.Custom -> LevelSpec.Raw(defaultLevel(stream, maxLevel, spec))
                    },
                )
            },
        )
        if (spec is LevelSpec.Raw) {
            LevelSlider(stream, spec.level, maxLevel) { onChange(LevelSpec.Raw(it)) }
        }
    }
}

/** Keeps a custom level being edited; otherwise starts mid-range. */
private fun defaultLevel(stream: AudioStream, maxLevel: Int, current: LevelSpec?): Int {
    val max = maxOf(stream.minLevel, maxLevel)
    return ((current as? LevelSpec.Raw)?.level ?: (max / 2)).coerceIn(stream.minLevel, max)
}

private sealed interface LevelChoice {
    val label: String

    data object Unset : LevelChoice {
        override val label = "Don't change"
    }

    data class UsePreset(val preset: Preset) : LevelChoice {
        override val label: String
            get() = "${preset.name} (${preset.level})"
    }

    data object Custom : LevelChoice {
        override val label = "Custom level"
    }
}
