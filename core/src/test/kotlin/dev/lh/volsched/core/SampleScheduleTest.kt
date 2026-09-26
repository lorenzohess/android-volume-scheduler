package dev.lh.volsched.core

import java.io.File
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guards the example file shipped in the repo root.
 *
 * A sample schedule that doesn't actually load is worse than no sample, and
 * this is the one document a new user will copy from.
 */
class SampleScheduleTest {

    // Gradle runs tests with the module directory as the working directory.
    private val sampleFile = File("../sample-schedule.json")

    @Test
    fun `the shipped sample schedule parses and validates`() {
        assertTrue(sampleFile.exists(), "missing ${sampleFile.absolutePath}")

        val result = importSchedule(sampleFile.readText(), TestMaxLevels)

        assertIs<ImportResult.Ok>(result)
    }

    @Test
    fun `the sample compiles to events on every day of the week`() {
        val schedule = (importSchedule(sampleFile.readText(), TestMaxLevels) as ImportResult.Ok).schedule

        val days = schedule.compile().map { it.day }.toSet()

        assertEquals(DayOfWeek.entries.toSet(), days)
    }

    @Test
    fun `the sample leaves no stream unset at an arbitrary moment`() {
        val schedule = (importSchedule(sampleFile.readText(), TestMaxLevels) as ImportResult.Ok).schedule

        val levels = currentLevels(schedule.compile(), monday(14, 37))

        assertEquals(AudioStream.entries.toSet(), levels.keys)
    }
}
