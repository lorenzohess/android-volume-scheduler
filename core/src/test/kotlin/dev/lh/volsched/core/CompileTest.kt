package dev.lh.volsched.core

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompileTest {

    @Test
    fun `single target compiles to exactly one event`() {
        val schedule = Schedule(
            blocks = listOf(block(target = single(AudioStream.RING, 5))),
        )

        val events = schedule.compile()

        assertEquals(1, events.size)
        assertEquals(Event(DayOfWeek.MONDAY, at(9), AudioStream.RING, 5), events.single())
    }

    @Test
    fun `profile block expands to one event per slot, all at the same instant`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile(
                    "Work",
                    mapOf(
                        AudioStream.MEDIA to LevelSpec.Raw(4),
                        AudioStream.RING to LevelSpec.Raw(2),
                        AudioStream.NOTIFICATION to LevelSpec.Raw(3),
                        AudioStream.ALARM to LevelSpec.Raw(6),
                    ),
                ),
            ),
            blocks = listOf(block(target = Target.ProfileRef("Work"))),
        )

        val events = schedule.compile()

        assertEquals(4, events.size)
        assertTrue(events.all { it.day == DayOfWeek.MONDAY && it.time == at(9) })
        assertEquals(
            mapOf(
                AudioStream.MEDIA to 4,
                AudioStream.RING to 2,
                AudioStream.NOTIFICATION to 3,
                AudioStream.ALARM to 6,
            ),
            events.associate { it.stream to it.level },
        )
    }

    @Test
    fun `unset profile slot produces no event for that stream`() {
        val schedule = Schedule(
            profiles = listOf(
                Profile(
                    "Sleep",
                    mapOf(
                        AudioStream.RING to LevelSpec.Raw(1),
                        // alarm deliberately absent: don't touch it
                    ),
                ),
            ),
            blocks = listOf(block(target = Target.ProfileRef("Sleep"))),
        )

        val events = schedule.compile()

        assertEquals(listOf(AudioStream.RING), events.map { it.stream })
    }

    @Test
    fun `preset references resolve to their levels`() {
        val schedule = Schedule(
            presets = mapOf(AudioStream.RING to listOf(Preset("Quiet", 2))),
            blocks = listOf(block(target = presetRef(AudioStream.RING, "Quiet"))),
        )

        assertEquals(2, schedule.compile().single().level)
    }

    @Test
    fun `presets with the same name in different streams resolve independently`() {
        val schedule = Schedule(
            presets = mapOf(
                AudioStream.RING to listOf(Preset("Loud", 7)),
                AudioStream.MEDIA to listOf(Preset("Loud", 22)),
            ),
            blocks = listOf(
                block(start = at(9), target = presetRef(AudioStream.RING, "Loud")),
                block(start = at(10), target = presetRef(AudioStream.MEDIA, "Loud")),
            ),
        )

        assertEquals(
            mapOf(AudioStream.RING to 7, AudioStream.MEDIA to 22),
            schedule.compile().associate { it.stream to it.level },
        )
    }

    @Test
    fun `dangling preset reference throws`() {
        val schedule = Schedule(
            blocks = listOf(block(target = presetRef(AudioStream.RING, "Nope"))),
        )

        val error = assertFailsWith<ScheduleException> { schedule.compile() }
        assertTrue(error.message!!.contains("Nope"))
    }

    @Test
    fun `dangling profile reference throws`() {
        val schedule = Schedule(blocks = listOf(block(target = Target.ProfileRef("Ghost"))))

        val error = assertFailsWith<ScheduleException> { schedule.compile() }
        assertTrue(error.message!!.contains("Ghost"))
    }

    @Test
    fun `events are sorted chronologically across the week`() {
        val schedule = Schedule(
            blocks = listOf(
                block(day = DayOfWeek.FRIDAY, start = at(8), target = single(AudioStream.RING, 5)),
                block(day = DayOfWeek.MONDAY, start = at(22), target = single(AudioStream.RING, 1)),
                block(day = DayOfWeek.MONDAY, start = at(7), target = single(AudioStream.RING, 6)),
            ),
        )

        assertEquals(
            listOf(
                DayOfWeek.MONDAY to at(7),
                DayOfWeek.MONDAY to at(22),
                DayOfWeek.FRIDAY to at(8),
            ),
            schedule.compile().map { it.day to it.time },
        )
    }

    @Test
    fun `block duration does not generate a trailing event`() {
        // A block's end is decorative: nothing reverts when it finishes.
        val schedule = Schedule(
            blocks = listOf(
                block(start = at(22), durationMin = 9 * 60, target = single(AudioStream.RING, 1)),
            ),
        )

        assertEquals(1, schedule.compile().size)
    }

    @Test
    fun `clamp lets ring reach zero but holds alarm at one`() {
        assertEquals(0, AudioStream.RING.clamp(0, maxLevel = 7))
        assertEquals(1, AudioStream.ALARM.clamp(0, maxLevel = 7))
        assertEquals(0, AudioStream.MEDIA.clamp(-3, maxLevel = 25))
        assertEquals(7, AudioStream.RING.clamp(99, maxLevel = 7))
    }
}
