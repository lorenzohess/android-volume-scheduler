package dev.lh.volsched.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineTest {

    private fun event(day: DayOfWeek, hour: Int, minute: Int, stream: AudioStream, level: Int) =
        Event(day, at(hour, minute), stream, level)

    @Test
    fun `next firing picks the soonest later event on the same day`() {
        val events = listOf(
            event(DayOfWeek.MONDAY, 7, 0, AudioStream.RING, 6),
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 4),
            event(DayOfWeek.MONDAY, 22, 0, AudioStream.RING, 1),
        )

        val firing = nextFiring(events, monday(9, 30))!!

        assertEquals(monday(17, 0), firing.at)
        assertEquals(4, firing.events.single().level)
    }

    @Test
    fun `next firing wraps into the following week`() {
        val events = listOf(event(DayOfWeek.MONDAY, 7, 0, AudioStream.RING, 6))

        val firing = nextFiring(events, monday(9, 0))!!

        assertEquals(LocalDateTime.of(2026, 9, 28, 7, 0), firing.at)
    }

    @Test
    fun `next firing groups every event due at the same instant`() {
        val events = listOf(
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.MEDIA, 10),
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 5),
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.NOTIFICATION, 5),
            event(DayOfWeek.MONDAY, 18, 0, AudioStream.ALARM, 6),
        )

        val firing = nextFiring(events, monday(9, 0))!!

        assertEquals(monday(17, 0), firing.at)
        assertEquals(3, firing.events.size)
        assertEquals(
            listOf(AudioStream.MEDIA, AudioStream.RING, AudioStream.NOTIFICATION),
            firing.events.map { it.stream },
        )
    }

    @Test
    fun `an event in the current minute is pushed a full week, so re-arming terminates`() {
        val events = listOf(event(DayOfWeek.MONDAY, 9, 0, AudioStream.RING, 5))

        val firing = nextFiring(events, monday(9, 0))!!

        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), firing.at)
    }

    @Test
    fun `seconds within the current minute do not resurrect an event that just fired`() {
        val events = listOf(event(DayOfWeek.MONDAY, 9, 0, AudioStream.RING, 5))

        val firing = nextFiring(events, monday(9, 0).withSecond(30))!!

        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), firing.at)
    }

    @Test
    fun `next firing is null when there are no events`() {
        assertNull(nextFiring(emptyList(), monday(9, 0)))
    }

    @Test
    fun `events at an instant returns every stream due then and nothing else`() {
        val events = listOf(
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.MEDIA, 10),
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 5),
            event(DayOfWeek.MONDAY, 17, 1, AudioStream.ALARM, 6),
        )

        assertEquals(
            listOf(AudioStream.MEDIA, AudioStream.RING),
            eventsAt(events, monday(17, 0)).map { it.stream },
        )
    }

    @Test
    fun `events at an instant ignores seconds`() {
        val events = listOf(event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 5))

        assertEquals(1, eventsAt(events, monday(17, 0).withSecond(42)).size)
    }

    @Test
    fun `events at an instant is empty when nothing is due`() {
        val events = listOf(event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 5))

        assertTrue(eventsAt(events, monday(16, 0)).isEmpty())
    }

    @Test
    fun `current levels find the most recent event earlier the same day`() {
        val events = listOf(
            event(DayOfWeek.MONDAY, 7, 0, AudioStream.RING, 6),
            event(DayOfWeek.MONDAY, 17, 0, AudioStream.RING, 4),
        )

        assertEquals(mapOf(AudioStream.RING to 6), currentLevels(events, monday(9, 0)))
    }

    @Test
    fun `current levels wrap backwards into the previous week`() {
        // Sunday 22:00 is the most recent ring event when it's Monday 03:00.
        val events = listOf(
            event(DayOfWeek.SUNDAY, 22, 0, AudioStream.RING, 1),
            event(DayOfWeek.MONDAY, 7, 0, AudioStream.RING, 6),
        )

        assertEquals(mapOf(AudioStream.RING to 1), currentLevels(events, monday(3, 0)))
    }

    @Test
    fun `each stream resolves independently`() {
        val events = listOf(
            event(DayOfWeek.MONDAY, 6, 0, AudioStream.MEDIA, 12),
            event(DayOfWeek.MONDAY, 8, 0, AudioStream.RING, 5),
            event(DayOfWeek.SUNDAY, 23, 0, AudioStream.NOTIFICATION, 2),
        )

        assertEquals(
            mapOf(
                AudioStream.MEDIA to 12,
                AudioStream.RING to 5,
                AudioStream.NOTIFICATION to 2,
            ),
            currentLevels(events, monday(9, 0)),
        )
    }

    @Test
    fun `streams with no events are absent and must be left alone`() {
        val events = listOf(event(DayOfWeek.MONDAY, 6, 0, AudioStream.MEDIA, 12))

        val levels = currentLevels(events, monday(9, 0))

        assertEquals(setOf(AudioStream.MEDIA), levels.keys)
        assertTrue(AudioStream.ALARM !in levels)
    }

    @Test
    fun `an event exactly now counts as the current level`() {
        val events = listOf(event(DayOfWeek.MONDAY, 9, 0, AudioStream.RING, 5))

        assertEquals(mapOf(AudioStream.RING to 5), currentLevels(events, monday(9, 0)))
    }

    @Test
    fun `a midnight-crossing block stays active past midnight`() {
        // Sleep starts Sunday 22:00 and runs to 07:00 Monday. At Monday 03:00
        // the ring level is still the one Sunday's edge set.
        val schedule = Schedule(
            presets = mapOf(AudioStream.RING to listOf(Preset("Off", 1))),
            blocks = listOf(
                block(
                    day = DayOfWeek.SUNDAY,
                    start = at(22),
                    durationMin = 9 * 60,
                    target = presetRef(AudioStream.RING, "Off"),
                ),
                block(
                    day = DayOfWeek.MONDAY,
                    start = at(7),
                    durationMin = 15 * 60,
                    target = single(AudioStream.RING, 6),
                ),
            ),
        )
        val events = schedule.compile()

        assertEquals(mapOf(AudioStream.RING to 1), currentLevels(events, monday(3, 0)))
        assertEquals(mapOf(AudioStream.RING to 6), currentLevels(events, monday(9, 0)))
        assertEquals(mapOf(AudioStream.RING to 1), currentLevels(events, sunday(23, 0)))
    }

    @Test
    fun `whole week of events resolves at every checkpoint`() {
        val events = DayOfWeek.entries.map { event(it, 8, 0, AudioStream.RING, it.value) }

        assertEquals(mapOf(AudioStream.RING to 1), currentLevels(events, monday(12, 0)))
        // Before Monday 08:00 the most recent edge is Sunday's.
        assertEquals(mapOf(AudioStream.RING to 7), currentLevels(events, monday(6, 0)))
    }
}
