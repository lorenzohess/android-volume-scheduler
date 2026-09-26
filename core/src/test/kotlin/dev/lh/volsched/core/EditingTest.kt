package dev.lh.volsched.core

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditingTest {

    private val quietRing = LevelSpec.PresetRef("Quiet")

    /** RING and MEDIA both have a preset called "Quiet", to prove presets are per stream. */
    private val base = Schedule(
        presets = mapOf(
            AudioStream.RING to listOf(Preset("Quiet", 2), Preset("Loud", 7)),
            AudioStream.MEDIA to listOf(Preset("Quiet", 4)),
        ),
        profiles = listOf(
            Profile("Work", mapOf(AudioStream.RING to quietRing, AudioStream.MEDIA to LevelSpec.PresetRef("Quiet"))),
            Profile("Evening", mapOf(AudioStream.RING to LevelSpec.PresetRef("Loud"))),
        ),
        blocks = listOf(
            block(day = MONDAY, start = at(9), target = Target.ProfileRef("Work")),
            block(day = MONDAY, start = at(12), target = presetRef(AudioStream.RING, "Quiet")),
            block(day = MONDAY, start = at(13), target = presetRef(AudioStream.MEDIA, "Quiet")),
            block(day = TUESDAY, start = at(18), target = Target.ProfileRef("Evening")),
        ),
    )

    @Test
    fun `the fixture is valid, so every test below starts clean`() {
        assertEquals(emptyList(), base.validate(TestMaxLevels))
    }

    @Test
    fun `adding a preset appends it to that stream only`() {
        val edited = base.putPreset(AudioStream.RING, Preset("Silentish", 1))

        assertEquals(listOf("Quiet", "Loud", "Silentish"), edited.presets.getValue(AudioStream.RING).map { it.name })
        assertEquals(base.presets[AudioStream.MEDIA], edited.presets[AudioStream.MEDIA])
    }

    @Test
    fun `adding the first preset for a stream creates its list`() {
        val edited = base.putPreset(AudioStream.ALARM, Preset("Normal", 5))

        assertEquals(listOf(Preset("Normal", 5)), edited.presets[AudioStream.ALARM])
    }

    @Test
    fun `changing a preset's level replaces it in place`() {
        val edited = base.putPreset(AudioStream.RING, Preset("Quiet", 3), replacing = "Quiet")

        assertEquals(listOf(Preset("Quiet", 3), Preset("Loud", 7)), edited.presets[AudioStream.RING])
        assertEquals(base.profiles, edited.profiles)
        assertEquals(base.blocks, edited.blocks)
    }

    @Test
    fun `renaming a preset follows it into profiles and single-stream blocks of that stream only`() {
        val edited = base.putPreset(AudioStream.RING, Preset("Hushed", 2), replacing = "Quiet")

        val work = edited.profiles.first { it.name == "Work" }
        assertEquals(LevelSpec.PresetRef("Hushed"), work.slots[AudioStream.RING])
        // MEDIA's own "Quiet" is a different preset and must not be renamed.
        assertEquals(LevelSpec.PresetRef("Quiet"), work.slots[AudioStream.MEDIA])
        assertEquals(presetRef(AudioStream.RING, "Hushed"), edited.blocks[1].target)
        assertEquals(presetRef(AudioStream.MEDIA, "Quiet"), edited.blocks[2].target)
        assertEquals(emptyList(), edited.validate(TestMaxLevels))
    }

    @Test
    fun `preset usages list the profiles and blocks that would dangle`() {
        val usages = base.presetUsages(AudioStream.RING, "Quiet")

        assertEquals(
            listOf(PresetUsage.InProfile("Work"), PresetUsage.InBlock(base.blocks[1])),
            usages,
        )
        assertEquals(emptyList(), base.presetUsages(AudioStream.ALARM, "Quiet"))
    }

    @Test
    fun `removing a stream's last preset drops the stream's entry`() {
        val edited = base.removePreset(AudioStream.MEDIA, "Quiet")

        assertTrue(AudioStream.MEDIA !in edited.presets)
        assertEquals(base.presets[AudioStream.RING], edited.presets[AudioStream.RING])
    }

    @Test
    fun `renaming a profile follows it into blocks`() {
        val renamed = Profile("Office", base.profiles[0].slots)
        val edited = base.putProfile(renamed, replacing = "Work")

        assertEquals(listOf("Office", "Evening"), edited.profiles.map { it.name })
        assertEquals(Target.ProfileRef("Office"), edited.blocks[0].target)
        assertEquals(emptyList(), edited.validate(TestMaxLevels))
    }

    @Test
    fun `profile usages are the blocks that reference it`() {
        assertEquals(listOf(base.blocks[3]), base.profileUsages("Evening"))
        assertEquals(emptyList(), base.profileUsages("Nobody"))
    }

    @Test
    fun `a new profile is appended and removing it leaves the rest`() {
        val sleep = Profile("Sleep", mapOf(AudioStream.MEDIA to LevelSpec.Raw(0)))

        val added = base.putProfile(sleep)
        assertEquals(listOf("Work", "Evening", "Sleep"), added.profiles.map { it.name })
        assertEquals(base.profiles, added.removeProfile("Sleep").profiles)
    }

    @Test
    fun `adding a block to several days creates one per day, kept in week order`() {
        val edited = base.addBlocks(listOf(WEDNESDAY, MONDAY), at(7), 60, single(AudioStream.ALARM, 5))

        assertEquals(
            listOf(MONDAY to at(7), MONDAY to at(9), MONDAY to at(12), MONDAY to at(13), TUESDAY to at(18), WEDNESDAY to at(7)),
            edited.blocks.map { it.day to it.start },
        )
    }

    @Test
    fun `blocks on a day come earliest first with their index into the list`() {
        val shuffled = base.copy(blocks = base.blocks.reversed())

        val monday = shuffled.blocksOn(MONDAY)

        assertEquals(listOf(at(9), at(12), at(13)), monday.map { it.value.start })
        monday.forEach { assertEquals(shuffled.blocks[it.index], it.value) }
    }

    @Test
    fun `removing a block by index removes only that one`() {
        val edited = base.removeBlockAt(1)

        assertEquals(base.blocks - base.blocks[1], edited.blocks)
    }

    @Test
    fun `copying a day replaces the target days and leaves the source alone`() {
        val edited = base.copyDay(MONDAY, listOf(TUESDAY, FRIDAY, MONDAY))

        assertEquals(base.blocks.filter { it.day == MONDAY }, edited.blocks.filter { it.day == MONDAY })
        // Tuesday's own Evening block is gone, replaced by Monday's three.
        assertEquals(listOf(at(9), at(12), at(13)), edited.blocksOn(TUESDAY).map { it.value.start })
        assertEquals(listOf(at(9), at(12), at(13)), edited.blocksOn(FRIDAY).map { it.value.start })
        assertEquals(emptyList(), edited.blocksOn(SATURDAY))
        assertEquals(emptyList(), edited.validate(TestMaxLevels))
    }
}
