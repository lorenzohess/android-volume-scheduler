package dev.lh.volsched.core

import java.io.File
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlapsTest {

    private val profiles = listOf(
        Profile("Work", mapOf(AudioStream.RING to LevelSpec.Raw(2), AudioStream.NOTIFICATION to LevelSpec.Raw(1))),
        Profile("Music", mapOf(AudioStream.MEDIA to LevelSpec.Raw(15))),
    )

    private fun overlapsOf(vararg blocks: Block) =
        Schedule(profiles = profiles, blocks = blocks.toList()).overlaps()

    @Test
    fun `blocks overlapping on a shared stream are reported, earlier block first`() {
        val work = block(start = at(9), durationMin = 480, target = Target.ProfileRef("Work"))
        val ring = block(start = at(12), durationMin = 60, target = single(AudioStream.RING, 5))

        val overlap = overlapsOf(ring, work).single()

        assertEquals(work, overlap.first)
        assertEquals(ring, overlap.second)
        assertEquals(setOf(AudioStream.RING), overlap.streams)
        assertEquals("Monday 09:00–17:00 (Work) overlaps Monday 12:00–13:00 (RING 5) on RING", overlap.message)
    }

    @Test
    fun `overlapping blocks on different streams are the normal case and not reported`() {
        val work = block(start = at(9), durationMin = 480, target = Target.ProfileRef("Work"))
        val music = block(start = at(12), durationMin = 60, target = Target.ProfileRef("Music"))

        assertEquals(emptyList(), overlapsOf(work, music))
    }

    @Test
    fun `a block ending exactly when the next starts does not overlap`() {
        val first = block(start = at(7), durationMin = 120, target = single(AudioStream.RING, 3))
        val second = block(start = at(9), durationMin = 60, target = single(AudioStream.RING, 5))

        assertEquals(emptyList(), overlapsOf(first, second))
    }

    @Test
    fun `a block running past midnight overlaps the next morning's block`() {
        val night = block(day = MONDAY, start = at(22), durationMin = 600, target = single(AudioStream.RING, 1))
        val morning = block(day = TUESDAY, start = at(7), durationMin = 60, target = single(AudioStream.RING, 5))

        val overlap = overlapsOf(morning, night).single()

        assertEquals(night, overlap.first)
        assertEquals(morning, overlap.second)
    }

    @Test
    fun `a Sunday night block wraps into Monday morning`() {
        val sunday = block(day = SUNDAY, start = at(23), durationMin = 120, target = single(AudioStream.MEDIA, 0))
        val monday = block(day = MONDAY, start = at(0, 30), durationMin = 60, target = single(AudioStream.MEDIA, 5))

        val overlap = overlapsOf(monday, sunday).single()

        assertEquals(sunday, overlap.first)
        assertEquals(monday, overlap.second)
    }

    @Test
    fun `two blocks in the same minute are left to validation's conflict error`() {
        val a = block(start = at(9), target = single(AudioStream.RING, 2))
        val b = block(start = at(9), target = single(AudioStream.RING, 5))
        val schedule = Schedule(blocks = listOf(a, b))

        assertEquals(emptyList(), schedule.overlaps())
        assertTrue(schedule.validate(TestMaxLevels).any { it is ValidationError.Conflict })
    }

    @Test
    fun `a block pointing at a missing profile is skipped rather than crashing`() {
        val ghost = block(start = at(9), durationMin = 120, target = Target.ProfileRef("Ghost"))
        val ring = block(start = at(10), target = single(AudioStream.RING, 5))

        assertEquals(emptyList(), overlapsOf(ghost, ring))
    }

    /**
     * The sample's Saturday one-off (MEDIA 20, 13:00-16:00) sits inside the
     * Evening block. It reads as "louder for three hours", but block ends do
     * nothing, so media stays at 20 until Sleep at 23:30. Exactly the surprise
     * the warning exists for, and the only one in the sample.
     */
    @Test
    fun `the shipped sample's only overlap is Saturday's one-off media block`() {
        val sample = (importSchedule(File("../sample-schedule.json").readText(), TestMaxLevels) as ImportResult.Ok).schedule

        val overlap = sample.overlaps().single()

        assertEquals("Saturday 09:00–23:30 (Evening) overlaps Saturday 13:00–16:00 (MEDIA 20) on MEDIA", overlap.message)
    }

    @Test
    fun `span labels mark blocks that end on a later day`() {
        assertEquals("07:00–09:00", block(start = at(7), durationMin = 120, target = single(AudioStream.RING, 3)).spanLabel())
        assertEquals("22:00–07:00 (next day)", block(start = at(22), durationMin = 540, target = single(AudioStream.RING, 1)).spanLabel())
        assertEquals("22:00–22:00 (+2 days)", block(start = at(22), durationMin = 2 * 1440, target = single(AudioStream.RING, 1)).spanLabel())
    }

    @Test
    fun `target labels name the profile or the stream and level`() {
        assertEquals("Work", Target.ProfileRef("Work").label())
        assertEquals("RING 3", single(AudioStream.RING, 3).label())
        assertEquals("MEDIA Podcast", presetRef(AudioStream.MEDIA, "Podcast").label())
    }
}
