# Development Plan: Scheduled Volume Controller

Companion to `notes.md`, which holds the requirements discussion. This
document is the build plan.

## 1. Core concept model

Four layers, three of which are authoring conveniences that disappear
before the scheduler ever sees them.

**Preset** — a named volume level, scoped to one stream.
Scoped because each stream has its own maximum, so `Loud` means a
different number for ring than for media.

    ring:  Loud = 7, Quiet = 2, Off = 1
    media: Music = 9, Podcast = 4

**Profile** — a named bundle covering some or all of the four streams.
Each slot is a preset reference, a raw level, or unset. Unset means
"don't touch this stream", which is what makes partial profiles useful.

    Work  = { media: Podcast, ring: Quiet, notif: raw 3, alarm: unset }
    Sleep = { media: raw 0,   ring: Off,   notif: Off,    alarm: raw 6 }

**Block** — a day, a start time, a duration, and a target. The target is
either a profile reference or a single inline `(stream, level)` pair, so
you can drop a one-off "ring only, 6" without inventing a profile.

**Event** — the compiled output: `(dayOfWeek, time, stream, level)`.
This is the only thing the runtime knows about.

### Why the compile step matters

Block *end* times do nothing at runtime (settled in `notes.md` Q1/Q4: a
manual change sticks, so nothing enforces a block while it runs). A block
is therefore only its leading edge. Compiling collapses presets,
profiles, and blocks into a flat sorted event list, which means:

- Per-stream independence and all-four-stream profiles coexist for free.
- Overlapping blocks are not a runtime concern. The editor still warns
  about them for sanity, but the scheduler cannot be confused by them.
- The single true conflict is two events for the same stream at the same
  minute. Detect that at compile time and refuse to save.

`durationMin` exists only so the editor can draw bars and detect
overlaps. The runtime never reads it.

## 2. Data model

```kotlin
@Serializable
data class Schedule(
    val version: Int = 1,
    val enabled: Boolean = true,
    val presets: Map<Stream, List<Preset>>,
    val profiles: List<Profile>,
    val blocks: List<Block>,
)

@Serializable enum class Stream { MEDIA, RING, NOTIFICATION, ALARM }

@Serializable data class Preset(val name: String, val level: Int)

@Serializable data class Profile(
    val name: String,
    val slots: Map<Stream, LevelSpec>,   // absent key == unset
)

@Serializable sealed interface LevelSpec {
    @Serializable data class PresetRef(val name: String) : LevelSpec
    @Serializable data class Raw(val level: Int) : LevelSpec
}

@Serializable data class Block(
    val day: DayOfWeek,
    val start: LocalTime,
    val durationMin: Int,
    val target: Target,
)

@Serializable sealed interface Target {
    @Serializable data class ProfileRef(val name: String) : Target
    @Serializable data class Single(val stream: Stream, val spec: LevelSpec) : Target
}

// compiled, never persisted
data class Event(val day: DayOfWeek, val time: LocalTime,
                 val stream: Stream, val level: Int)
```

### Storage

A single JSON file, kotlinx-serialization, written atomically (temp file
+ rename). Not Room: the data is a few dozen blocks, is never queried,
and is loaded whole on every use. Room would add a DAO, migrations, and
an async boundary for zero benefit.

The file lives in **device-protected storage** (see §4) and doubles as
the export format, so export/import is a file copy plus validation.

Keep an in-memory `StateFlow<Schedule>` as the single source of truth for
UI, widget, and scheduler.

### Validation on save

- Ring and notification levels clamp to a **minimum of 1**. Enforced when
  a preset or raw level is created, so the scheduler never encounters the
  case. Level 0 on those streams is how Android enters vibrate/silent,
  which needs `ACCESS_NOTIFICATION_POLICY` — this rule is what lets the
  app ship with no runtime permissions at all.
- Levels clamp to `getStreamMaxVolume(stream)`, read at validation time.
- Reject unknown preset/profile names (dangling refs after a rename).
- Reject two events for the same stream at the same minute.

## 3. Runtime

Two functions do all the work.

**`nextEvent(now): Event?`** — compile, then find the earliest event
strictly after `now`, wrapping across the week. Arm **one** alarm for it.
Not one alarm per event: a single pending alarm, re-armed each time it
fires, keeps the whole scheduler at roughly fifty lines and sidesteps
pending-intent bookkeeping entirely.

**`reconcile(now)`** — for each stream independently, walk *backwards* to
the most recent event at or before `now` (searching back up to 7 days)
and apply it. This is what makes the app self-healing rather than purely
event-driven.

### Trigger table

| Trigger | Action |
|---|---|
| `LOCKED_BOOT_COMPLETED` | `reconcile()` + arm |
| `BOOT_COMPLETED` | arm (defensive; may be the only one that fires) |
| `MY_PACKAGE_REPLACED` | arm — every sideload wipes pending alarms |
| `TIMEZONE_CHANGED`, `TIME_SET` | recompute + arm |
| Alarm fires | apply event, log, arm next |
| Widget toggle → enabled | `reconcile()` + arm |
| Widget toggle → disabled | cancel alarm, touch nothing else |
| Schedule edited | arm (no reconcile — don't clobber a manual change) |

Applying a volume is `setStreamVolume(stream, level, 0)`. Flags must be
`0`: no `FLAG_SHOW_UI`, no `FLAG_PLAY_SOUND`.

~~A `BroadcastReceiver` is sufficient for alarm delivery. No foreground
service.~~ Disproved on the device at M3: **Android 17 silently ignores
`setStreamVolume` from an app with no visible activity and no foreground
service** ([background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)).
The call returns normally and the level doesn't move. So every background
volume change (alarm, boot/timezone reconcile, widget toggle) goes through
`VolumeChangeService`, a `specialUse` foreground service that the receiver
starts from `onReceive` and that stops itself within seconds. Its
notification is deferred, so it is normally never shown. The alarm and boot
broadcasts exempt the app from the background start restriction on
foreground services. The in-app buttons need none of this: the activity is
visible.

This depends on `targetSdk` < 37. Apps targeting 37 also need the service to
hold while-in-use capability, which a service started from a receiver does
not get.

### Known behavioural trade

Reconcile-on-boot means a manual override survives *within* a session but
not across a reboot. GrapheneOS auto-reboot (~18h idle, on by default)
will silently reset to schedule overnight. Accepted deliberately; the
alternative is volumes drifting out of sync after every reboot.

## 4. Platform specifics

**Direct boot.** `BOOT_COMPLETED` does not arrive until first unlock, and
credential-encrypted storage is unreadable before then. A 04:00 reboot
would otherwise swallow the 07:00 event until the phone is picked up. So:
mark the boot receiver `android:directBootAware="true"`, register for
`LOCKED_BOOT_COMPLETED`, and keep the schedule file in
`context.createDeviceProtectedStorageContext()`.

**Exact alarms.** Use `USE_EXACT_ALARM` (API 33+), granted at install
with no prompt. Play Store policy restricts it to alarm/calendar apps,
but this is sideloaded and personal, so that restriction does not apply.
Skips the entire "Alarms & reminders" onboarding flow.

**Alarm API.** `setExactAndAllowWhileIdle`. `setAlarmClock` is more
Doze-resistant but posts a persistent status-bar alarm icon, which is
unacceptable for something firing a dozen times a day. If misfires show
up in the event log under Doze, the escalation path is a battery
optimisation exemption before reaching for `setAlarmClock`.

**minSdk = compileSdk = the device's shipping API.** Personal use, one
device, no compatibility branches.

**Permissions, in full:** `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (the last two for
Android 17, see §3). All install-time: no runtime permission requests, no
onboarding screen.

## 5. Module layout

```
core/       Schedule model, serialization, validation, compile()
scheduler/  nextEvent, reconcile, AlarmManager wrapper, receivers
audio/      AudioManager wrapper (max/min lookup, apply, clamp)
storage/    Atomic JSON file I/O in device-protected storage, StateFlow
ui/         Compose: streams, presets, profiles, schedule editor, debug
widget/     Glance widget + toggle callback
```

`core` is pure Kotlin with no Android dependencies, so `compile()`,
`nextEvent()`, `reconcile()`, and validation are all unit-testable on the
JVM with no device and no emulator. That is where the real logic lives
and where the tests should concentrate.

## 6. Milestones

The boot receiver moves early: it is the most likely thing to be silently
broken, and every sideload wipes alarms anyway, so the plumbing should be
exercised from the start rather than bolted on at the end.

**M1 — Hello world on device.**
Empty Compose app, USB sideload working, a repeatable install command.
*Done when:* an edit shows up on the phone in one command.

**M2 — Four sliders, immediate apply.**
Read `getStreamMinVolume`/`getStreamMaxVolume` per stream, show `5/7`
style labels, apply on drag.
*Done when:* all four streams move independently, and ring/notification
refuse to go below 1. Confirms the device's independent-notification
behaviour first-hand.

**M3 — The risky core, on fake data.**
Hardcoded event list. Single-alarm arm/fire/re-arm loop, all receivers
from the trigger table, append-only event log with timestamps, debug
screen showing the log and the next scheduled event.
*Done when:* an event fires correctly after (a) a reboot, (b) a
reinstall, (c) two hours of screen-off Doze, with the log proving it.
This is the milestone that decides whether the whole approach works.

**M4 — Real schedule.**
Presets, profiles, blocks, `compile()`, validation, the JSON file,
`nextEvent()` + `reconcile()` replacing the hardcoded list. JVM unit
tests for compile/validation/reconcile including midnight-crossing and
week-wrap cases.
*Done when:* a hand-written JSON file drives real volume changes.

**M5 — Editor UI.**
Preset manager, profile builder, weekly block editor with a day view,
overlap warnings, same-minute conflict errors.
*Done when:* the M4 JSON can be produced entirely from the phone.

**M6 — Glance widget.**
Current per-stream levels, active profile, next change, and a master
enable/disable toggle button wired to the trigger table.
*Done when:* toggling from the home screen disables scheduling and
re-enabling reconciles immediately.

**M7 — Export / import.**
Share-sheet export, file-picker import, version field checked, full
validation on import followed by reconcile.

M1–M3 hold all the unknowns. M4–M7 are ordinary app work.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Doze delays or drops alarms | Event log with intended-vs-actual timestamps from M3; escalate to battery exemption, then `setAlarmClock` |
| Overnight reboot never reconciles | Direct-boot-aware receiver + device-protected storage; test by rebooting and leaving locked overnight |
| Notification/ring linked despite expectations | Verified directly at M2, before any scheduling exists |
| Ring 0 throws `SecurityException` | Clamp to 1 at validation; the case never reaches the API |
| Midnight-crossing and week-wrap logic | Store as start + duration, allow spill into next day; JVM unit tests at M4 |
| Sideload wipes alarms mid-development | `MY_PACKAGE_REPLACED` receiver from M3 |

## 8. Testing

**JVM unit tests** (the bulk): `compile()` expansion, preset/profile
resolution, dangling refs, conflict detection, clamping, `nextEvent()`
across midnight and week boundaries, `reconcile()` backward search.
Inject a clock — never call `LocalTime.now()` inside `core`.

**On-device manual checks**, per `notes.md`:
- Debug mode compressing the schedule to minute-scale for fast cycles.
- "Fire next event now" button.
- Reboot, then leave locked past the scheduled time.
- Manual volume change mid-block, confirm it sticks until the next edge.
- DND active during an event.

Every applied change logs intended time, actual time, stream, and level,
so any misfire can be diagnosed after the fact rather than reproduced.
