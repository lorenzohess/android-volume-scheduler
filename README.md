# Volume Scheduler

Per-day, time-of-day volume scheduling for media, ring, notification and alarm
streams. Personal, sideloaded, no Play Services, no runtime permissions.

Design rationale lives in [PLAN.md](PLAN.md); the requirements conversation is
in [notes.md](notes.md).

## Build status

| Module | State |
|---|---|
| `:core` | Builds and passes **55 unit tests** on the JVM |
| `:app` | Builds and installs (Pixel 10a, Android 17). M2 verified on device: sliders are independent (ring and notification are not linked), ring/notification floor at 1, volume keys sync. Alarm behaviour (M3 onward) **not yet verified** - see below |

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

There is no visual block editor yet (milestone M5). Until there is, author the
schedule as JSON and pull it in with **Import** on the main screen. Start from
`sample-schedule.json`.

Import is strict: unknown keys, dangling preset or profile names, levels above
the device maximum, two events touching the same stream in the same minute, and
ring or notification levels below 1 are all rejected with a message rather than
half-applied.

Ring and notification have a floor of 1 because level 0 is how Android enters
vibrate/silent, and that transition needs `ACCESS_NOTIFICATION_POLICY`. Holding
the floor at 1 is what lets this app ship with zero runtime permissions.

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

M1-M4, M6 and M7 are implemented but unverified on device. Still to do:

- **M5** - visual schedule editor (preset manager, profile builder, weekly block
  grid with overlap warnings). JSON import covers authoring until then.
- Quick Settings tile, if you ever want the toggle from the notification shade
  as well as the home screen.
