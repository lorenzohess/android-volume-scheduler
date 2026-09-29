# Volume Scheduler

Per-day, time-of-day volume scheduling for media, ring, notification and alarm
streams. Personal, sideloaded, no Play Services, no runtime permissions.

Design rationale lives in [PLAN.md](PLAN.md); the requirements conversation is
in [notes.md](notes.md).

## Build status

| Module | State |
|---|---|
| `:core` | Builds and passes **79 unit tests** on the JVM |
| `:app` | Builds and installs (Pixel 10a, Android 17). M2 verified on device: sliders are independent (ring and notification are not linked), volume keys sync. Ring and notification at 0 (muting) not yet verified. M3 verified: alarms fire and apply on time with the screen on, with the screen off and locked, after a reinstall, after a reboot before first unlock, and through 7 hours of deep Doze (14 of 14 firings, each delivered within 1 s). M4 and M7 import verified in passing: imported JSON schedules drove every change. M6 verified: the widget shows the live on/off state and its toggle works both ways. Export (M7) **not yet verified**; M5 in progress |

Treat the Android layer as a first draft until the checks under "Verifying it
actually works" have passed on the device.

## Building

`:core` needs nothing but a JDK:

```sh
./gradlew :core:test
```

`:app` needs the Android SDK. `settings.gradle.kts` skips it automatically when
no SDK is found, so the command above works on any machine.

The simplest path is to open the project folder in **Android Studio**, let it
install the SDK and sync, then Run. Otherwise point `ANDROID_HOME` at an SDK, or
write `sdk.dir=/path/to/sdk` into `local.properties`, and:

```sh
./gradlew :app:installDebug        # build and push over USB
adb shell am start -n dev.lh.volsched.debug/dev.lh.volsched.ui.MainActivity
```

`compileSdk` is 35 and `minSdk` is 34. Bump both to 36 once you're on AGP 8.9+.

## Architecture in one paragraph

Presets, profiles and blocks are authoring conveniences. `compile()` flattens
them into a list of `Event(day, time, stream, level)` - the only thing the
runtime understands. Because a manual volume change sticks until the next
scheduled change, a block's *end* does nothing, so a block is only its leading
edge. That makes all-stream profiles and independent per-stream timelines
coexist without precedence rules, and reduces the scheduler to two functions:
`nextFiring()` arms a single alarm and re-arms after it fires, and
`currentLevels()` walks backwards to work out what should be true right now.

## Layout

```
core/     Pure Kotlin. Model, JSON, validation, compile, timeline maths.
          No Android dependencies, so it is fully unit-testable.
app/      Android. AudioManager, storage, alarms, receivers, Compose UI, widget.
sample-schedule.json
          A full week. Verified by core's test suite, so it always loads.
```

`core` deliberately takes device stream maxima as a parameter rather than
reading `AudioManager`, which is what keeps it JVM-testable.

## Editing the schedule

**Edit schedule** on the main screen opens the editor (milestone M5), with three
tabs:

- **Week** - pick a day to see its blocks in time order. Add a block to one or
  several days at once, tap a block to change or delete it, or copy a whole day
  over others. Blocks that overlap on a stream they both set are flagged.
- **Profiles** - named sets of levels for some or all streams.
- **Presets** - named levels per stream, such as RING Quiet = 2.

Renaming a preset or profile updates everything that uses it. Deleting one is
disabled while something still uses it. Edits apply to a draft: **Save** is
refused while there are errors, and saving re-arms the next alarm without
touching current volumes.

JSON still works too: **Import** a file, starting from `sample-schedule.json`
if you like, and **Export** a backup.

An overlap is only a warning. A block acts when it starts; its end does
nothing. So in the sample, Saturday's one-off MEDIA 20 from 13:00 to 16:00
leaves media at 20 until Sleep at 23:30, not until 16:00.

Import is strict: unknown keys, dangling preset or profile names, levels
outside the device's range, two events touching the same stream in the same
minute, and a moment that mutes ring while setting notification are all
rejected with a message rather than half-applied.

**Muting.** RING at 0 mutes the ringer: Android switches to vibrate, and
mutes notifications along with it. That's the "unavailable because ring is
muted" in the system settings. So a profile or moment that mutes ring can't
also set NOTIFICATION above 0; leave NOTIFICATION unset there and it returns to
its own level when a later block unmutes ring. NOTIFICATION at 0 on its own is
fine. Vibrate needs no permission. Fully silent (no vibration) would need Do Not
Disturb access, which the app doesn't request; if you switch the phone to
silent by hand, scheduled ring changes are refused and logged until you switch
it back. ALARM can't go below 1, because Android won't silence the alarm stream.

## Verifying it actually works

The event log on the main screen records intended time, actual time, stream and
level for every change. That is the instrument for milestone M3 - the point of
it is to tell "the alarm never fired" apart from "the alarm fired and set the
wrong value" after the fact, instead of trying to reproduce a misfire.

To test alarms without waiting for real schedule times, generate a schedule
whose events start a few minutes from now and import it:

```sh
python3 tools/test-schedule.py > test-schedule.json   # --start/--every/--count to adjust
adb push test-schedule.json /sdcard/Download/
```

Worth confirming in this order, per PLAN.md section 8:

1. Four sliders move all four streams independently, and ring/notification stop
   at 1.
2. An event fires while the screen is off (Doze is the main unknown).
3. An event survives a reinstall - `MY_PACKAGE_REPLACED` should re-arm.
4. Reboot the phone and leave it locked past a scheduled time. This is the one
   most likely to be broken, and the one GrapheneOS auto-reboot will hit nightly.
5. Change a volume by hand mid-block and confirm it sticks until the next edge.

## Known trade-offs

- **A foreground service for every background volume change.** Android 17
  silently ignores volume changes from apps that are neither visible nor
  running a foreground service, so the alarm, boot and widget paths start a
  short-lived `specialUse` service to do the change. It needs `targetSdk`
  below 37; see the comment in `app/build.gradle.kts`.

- **Reboot beats manual override.** Reconcile runs on boot, so an overnight
  auto-reboot resets volumes to schedule. Deliberate: the alternative is volumes
  drifting out of sync after every restart.
- **DST.** Scheduling is wall-clock, recomputed on every fire, and re-armed on
  `TIMEZONE_CHANGED` / `TIME_SET`. A transition can still skip or repeat an
  hour once; the next edge corrects it.
- **`setExactAndAllowWhileIdle`, not `setAlarmClock`.** The latter is more
  Doze-resistant but posts a permanent status-bar alarm icon, which is wrong for
  something firing a dozen times a day. If the log shows misfires under Doze,
  try a battery-optimisation exemption before switching.

## Remaining milestones

- **M5** - the editor is written but not yet verified on device.
- **M7** - export is not yet verified on device.
- Quick Settings tile, if you ever want the toggle from the notification shade
  as well as the home screen.
