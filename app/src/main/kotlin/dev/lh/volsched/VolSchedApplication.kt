package dev.lh.volsched

import android.app.Application
import dev.lh.volsched.scheduler.VolumeScheduler
import dev.lh.volsched.storage.ScheduleStore

class VolSchedApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Touch the store so a parse failure is recorded early rather than on
        // whichever receiver happens to run first.
        ScheduleStore.get(this)

        // Cheap insurance: if an alarm was somehow lost without a boot or a
        // reinstall, opening the app puts one back. Deliberately does NOT
        // reconcile - that would clobber a manual override every launch.
        VolumeScheduler.rearm(this, "app start")
    }
}
