package dev.lh.volsched.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the single pending alarm and hands it straight to
 * [VolumeChangeService], which applies what was due and re-arms.
 *
 * The work can't happen here: Android 17 ignores volume changes from a
 * receiver running in the background. Starting the service from onReceive,
 * rather than later, keeps it inside the window in which an exact alarm
 * exempts the app from the background start restriction.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        VolumeChangeService.fire(context, intent.getLongExtra(VolumeScheduler.EXTRA_FIRE_AT, 0L))
    }
}
