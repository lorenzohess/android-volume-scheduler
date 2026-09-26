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
    fun `ring level zero is rejected because it would trigger silent mode`() {
        val schedule = Schedule(blocks = listOf(block(target = single(AudioStream.RING, 0))))

        val error = schedule.errors().single()
        assertIs<ValidationError.LevelTooLow>(error)
        assertEquals(AudioStream.RING, error.stream)
        assertTrue(error.message.contains("DND"))
    }

    @Test
    fun `notification level zero is rejected for the same reason`() {
        val schedule = Schedule(blocks = listOf(block(target = single(AudioStream.NOTIFICATION, 0))))

        assertIs<ValidationError.LevelTooLow>(schedule.errors().single())
    }

    @Test
    fun `media and alarm may legitimately be zero`() {
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(9), target = single(AudioStream.MEDIA, 0)),
                block(start = at(10), target = single(AudioStream.ALARM, 0)),
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
            presets = mapOf(AudioStream.RING to listOf(Preset("Silent", 0))),
            blocks = listOf(block(target = presetRef(AudioStream.RING, "Silent"))),
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
                block(day = DayOfWeek.MONDAY, start = at(9), target = single(AudioStream.RING, 0)),
                block(day = DayOfWeek.TUESDAY, start = at(9), target = single(AudioStream.RING, 99)),
                block(day = DayOfWeek.WEDNESDAY, start = at(9), target = Target.ProfileRef("Ghost")),
            ),
        )

        assertEquals(3, schedule.errors().size)
    }
}
