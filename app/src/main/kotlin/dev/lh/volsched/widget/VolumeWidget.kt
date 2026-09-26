package dev.lh.volsched.widget

import android.content.Context
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lh.volsched.audio.VolumeApplier
import dev.lh.volsched.core.AudioStream
import dev.lh.volsched.core.Firing
import dev.lh.volsched.scheduler.VolumeChangeService
import dev.lh.volsched.scheduler.VolumeScheduler
import dev.lh.volsched.storage.ScheduleStore
import java.time.format.DateTimeFormatter

private val NEXT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

/**
 * Home screen widget: current levels, the next change, and the master toggle.
 *
 * State is snapshotted in [provideGlance] rather than collected reactively -
 * every path that changes something calls `refresh`, so there is nothing for a
 * subscription to add beyond complexity.
 */
class VolumeWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val enabled = ScheduleStore.get(context).schedule.value.enabled
        val applier = VolumeApplier(context)
        val levels = applier.currentLevels()
        val maxLevels = applier.maxLevels()
        val next = VolumeScheduler.nextFiringOrNull(context)

        provideContent {
            WidgetBody(enabled = enabled, levels = levels, maxLevels = maxLevels, next = next)
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            // Before first unlock there is no launcher to draw the widget, and
            // Glance's state and WorkManager live in credential-encrypted
            // storage, so an update can only fail. BOOT_COMPLETED refreshes it
            // once the user unlocks.
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            VolumeWidget().updateAll(context)
        }
    }
}

class VolumeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = VolumeWidget()
}

/** Flips the master switch straight from the home screen. */
class ToggleEnabledAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val store = ScheduleStore.get(context)
        if (store.loadError != null) return

        val nowEnabled = !store.schedule.value.enabled
        store.setEnabled(nowEnabled)

        if (nowEnabled) {
            // Catch up to whatever the schedule says should be true right now,
            // then arm the next edge. Through the foreground service, because
            // no activity is visible and Android 17 would ignore the change.
            VolumeChangeService.reconcile(context, "widget toggle")
        } else {
            VolumeScheduler.cancel(context)
        }

        VolumeWidget().updateAll(context)
    }
}

@Composable
private fun WidgetBody(
    enabled: Boolean,
    levels: Map<AudioStream, Int>,
    maxLevels: Map<AudioStream, Int>,
    next: Firing?,
) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Volume Scheduler",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = if (enabled) "ON" else "OFF",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828)),
                    ),
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .clickable(actionRunCallback<ToggleEnabledAction>()),
                )
            }

            Text(
                text = AudioStream.entries.joinToString("   ") { stream ->
                    "${stream.name.take(4)} ${levels[stream] ?: 0}/${maxLevels[stream] ?: 0}"
                },
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.padding(top = 8.dp),
            )

            Text(
                text = when {
                    !enabled -> "Scheduling paused"
                    next == null -> "No upcoming change"
                    else -> "Next ${next.at.format(NEXT_FORMAT)}: " +
                        next.events.joinToString(", ") { "${it.stream.name.take(4)} ${it.level}" }
                },
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.padding(top = 6.dp),
            )
        }
    }
}
