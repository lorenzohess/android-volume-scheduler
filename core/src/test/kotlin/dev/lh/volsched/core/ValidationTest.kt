package dev.lh.volsched.core

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidationTest {

    private fun Schedule.errors() = validate(TestMaxLevels)

    @Test
    fun `a well-formed schedule has no errors`() {
        val schedule = Schedule(
            presets = mapOf(AudioStream.RING to listOf(Preset("Quiet", 2), Preset("Loud", 7))),
            profiles = listOf(
                Profile("Work", mapOf(AudioStream.RING to LevelSpec.PresetRef("Quiet"))),
            ),
            blocks = listOf(block(target = Target.ProfileRef("Work"))),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `ring, notification and media may be zero`() {
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(9), target = single(AudioStream.RING, 0)),
                block(start = at(10), target = single(AudioStream.NOTIFICATION, 0)),
                block(start = at(11), target = single(AudioStream.MEDIA, 0)),
            ),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `alarm zero is rejected because Android won't silence the alarm stream`() {
        val schedule = Schedule(blocks = listOf(block(target = single(AudioStream.ALARM, 0))))

        val error = schedule.errors().single()
        assertIs<ValidationError.LevelTooLow>(error)
        assertEquals(AudioStream.ALARM, error.stream)
        assertTrue(error.message.contains("alarm"))
    }

    @Test
    fun `a profile muting ring while setting notification is rejected once, not per block`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile("Quiet", mapOf(AudioStream.RING to LevelSpec.Raw(0), AudioStream.NOTIFICATION to LevelSpec.Raw(2))),
            ),
            blocks = listOf(
                block(day = DayOfWeek.MONDAY, target = Target.ProfileRef("Quiet")),
                block(day = DayOfWeek.TUESDAY, target = Target.ProfileRef("Quiet")),
            ),
        )

        val error = schedule.errors().single()
        assertIs<ValidationError.NotificationWhileRingMuted>(error)
        assertEquals("Profile 'Quiet'", error.where)
        assertEquals(2, error.level)
    }

    @Test
    fun `ring muted through a preset counts too`() {
        val schedule = Schedule(
            presets = mapOf(AudioStream.RING to listOf(Preset("Muted", 0))),
            profiles = listOf(
                Profile("Quiet", mapOf(AudioStream.RING to LevelSpec.PresetRef("Muted"), AudioStream.NOTIFICATION to LevelSpec.Raw(3))),
            ),
        )

        assertIs<ValidationError.NotificationWhileRingMuted>(schedule.errors().single())
    }

    @Test
    fun `muting ring with notification at zero or left unset is fine`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile("BothMuted", mapOf(AudioStream.RING to LevelSpec.Raw(0), AudioStream.NOTIFICATION to LevelSpec.Raw(0))),
                Profile("RingMuted", mapOf(AudioStream.RING to LevelSpec.Raw(0))),
            ),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `separate blocks muting ring and setting notification in the same minute are rejected`() {
        val schedule = Schedule(
            blocks = listOf(
                block(day = DayOfWeek.MONDAY, start = at(22), target = single(AudioStream.RING, 0)),
                block(day = DayOfWeek.MONDAY, start = at(22), target = single(AudioStream.NOTIFICATION, 3)),
            ),
        )

        val error = schedule.errors().single()
        assertIs<ValidationError.NotificationWhileRingMuted>(error)
        assertEquals("Monday 22:00", error.where)
    }

    @Test
    fun `notification set later while ring is still muted is allowed`() {
        // Android keeps the notification level and applies it once ring is
        // unmuted; the runtime logs it as held rather than failed.
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(22), target = single(AudioStream.RING, 0)),
                block(start = at(23), target = single(AudioStream.NOTIFICATION, 3)),
            ),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `a level above the device maximum is rejected`() {
        val schedule = Schedule(blocks = listOf(block(target = single(AudioStream.RING, 9))))

        val error = schedule.errors().single()
        assertIs<ValidationError.LevelTooHigh>(error)
        assertEquals(7, error.max)
    }

    @Test
    fun `preset levels are checked where they are declared`() {
        val schedule = Schedule(
            presets = mapOf(AudioStream.ALARM to listOf(Preset("Silent", 0))),
            blocks = listOf(block(target = presetRef(AudioStream.ALARM, "Silent"))),
        )

        assertIs<ValidationError.LevelTooLow>(schedule.errors().single())
    }

    @Test
    fun `duplicate preset names within a stream are rejected`() {
        val schedule = Schedule(
            presets = mapOf(AudioStream.RING to listOf(Preset("Quiet", 2), Preset("Quiet", 3))),
        )

        assertIs<ValidationError.DuplicatePreset>(schedule.errors().single())
    }

    @Test
    fun `duplicate profile names are rejected`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile("Work", mapOf(AudioStream.RING to LevelSpec.Raw(2))),
                Profile("Work", mapOf(AudioStream.RING to LevelSpec.Raw(3))),
            ),
        )

        assertIs<ValidationError.DuplicateProfile>(schedule.errors().single())
    }

    @Test
    fun `unknown preset reference is reported, not thrown`() {
        val schedule = Schedule(blocks = listOf(block(target = presetRef(AudioStream.RING, "Nope"))))

        val error = schedule.errors().single()
        assertIs<ValidationError.UnknownPreset>(error)
        assertEquals("Nope", error.name)
    }

    @Test
    fun `unknown profile reference is reported, not thrown`() {
        val schedule = Schedule(blocks = listOf(block(target = Target.ProfileRef("Ghost"))))

        assertIs<ValidationError.UnknownProfile>(schedule.errors().single())
    }

    @Test
    fun `two blocks setting the same stream at the same minute conflict`() {
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(9), target = single(AudioStream.RING, 5)),
                block(start = at(9), target = single(AudioStream.RING, 3)),
            ),
        )

        val error = schedule.errors().single()
        assertIs<ValidationError.Conflict>(error)
        assertEquals(AudioStream.RING, error.stream)
    }

    @Test
    fun `different streams at the same minute do not conflict`() {
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(9), target = single(AudioStream.RING, 5)),
                block(start = at(9), target = single(AudioStream.MEDIA, 10)),
            ),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `a profile block and a single block clashing on one stream conflict`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile(
                    "Work",
                    mapOf(
                        AudioStream.RING to LevelSpec.Raw(2),
                        AudioStream.MEDIA to LevelSpec.Raw(10),
                    ),
                ),
            ),
            blocks = listOf(
                block(start = at(9), target = Target.ProfileRef("Work")),
                block(start = at(9), target = single(AudioStream.RING, 6)),
            ),
        )

        val conflicts = schedule.errors().filterIsInstance<ValidationError.Conflict>()
        assertEquals(1, conflicts.size)
        assertEquals(AudioStream.RING, conflicts.single().stream)
    }

    @Test
    fun `overlapping blocks on the same stream are fine when edges differ`() {
        // Overlap is not a runtime concern: only leading edges matter.
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(9), durationMin = 480, target = single(AudioStream.RING, 5)),
                block(start = at(12), durationMin = 60, target = single(AudioStream.RING, 2)),
            ),
        )

        assertEquals(emptyList(), schedule.errors())
    }

    @Test
    fun `non-positive duration is rejected`() {
        val schedule = Schedule(
            blocks = listOf(block(durationMin = 0, target = single(AudioStream.RING, 5))),
        )

        assertIs<ValidationError.InvalidDuration>(schedule.errors().single())
    }

    @Test
    fun `a profile that sets nothing is rejected`() {
        val schedule = Schedule(profiles = listOf(Profile("Empty", emptyMap())))

        assertIs<ValidationError.EmptyProfile>(schedule.errors().single())
    }

    @Test
    fun `every problem is reported at once, not just the first`() {
        val schedule = Schedule(
            blocks = listOf(
                block(day = DayOfWeek.MONDAY, start = at(9), target = single(AudioStream.ALARM, 0)),
                block(day = DayOfWeek.TUESDAY, start = at(9), target = single(AudioStream.RING, 99)),
                block(day = DayOfWeek.WEDNESDAY, start = at(9), target = Target.ProfileRef("Ghost")),
            ),
        )

        assertEquals(3, schedule.errors().size)
    }
}
