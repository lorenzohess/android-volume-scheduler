package dev.lh.volsched.core

import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScheduleJsonTest {

    private val sample = Schedule(
        enabled = true,
        presets = mapOf(
            AudioStream.RING to listOf(Preset("Off", 1), Preset("Quiet", 2), Preset("Loud", 7)),
            AudioStream.MEDIA to listOf(Preset("Podcast", 4), Preset("Music", 20)),
        ),
        profiles = listOf(
            Profile(
                "Sleep",
                mapOf(
                    AudioStream.RING to LevelSpec.PresetRef("Off"),
                    AudioStream.MEDIA to LevelSpec.Raw(0),
                    AudioStream.ALARM to LevelSpec.Raw(6),
                ),
            ),
        ),
        blocks = listOf(
            block(day = DayOfWeek.SUNDAY, start = at(22, 30), durationMin = 510,
                target = Target.ProfileRef("Sleep")),
            block(day = DayOfWeek.MONDAY, start = at(7, 15), durationMin = 60,
                target = presetRef(AudioStream.RING, "Loud")),
        ),
    )

    @Test
    fun `round trip preserves the schedule exactly`() {
        assertEquals(sample, scheduleFromJson(sample.toJson()))
    }

    @Test
    fun `times serialize as HH mm and days as names`() {
        val json = sample.toJson()

        assertTrue(json.contains("\"22:30\""), json)
        assertTrue(json.contains("\"07:15\""), json)
        assertTrue(json.contains("\"SUNDAY\""), json)
    }

    @Test
    fun `level specs use readable discriminators`() {
        val json = sample.toJson()

        assertTrue(json.contains("\"preset\""), json)
        assertTrue(json.contains("\"raw\""), json)
    }

    @Test
    fun `import accepts a valid document`() {
        assertIs<ImportResult.Ok>(importSchedule(sample.toJson(), TestMaxLevels))
    }

    @Test
    fun `import reports validation errors rather than accepting them`() {
        val bad = Schedule(blocks = listOf(block(target = single(AudioStream.RING, 0))))

        val result = importSchedule(bad.toJson(), TestMaxLevels)

        assertIs<ImportResult.Invalid>(result)
        assertIs<ValidationError.LevelTooLow>(result.errors.single())
    }

    @Test
    fun `import rejects a newer schema version`() {
        val future = sample.copy(version = SCHEMA_VERSION + 1).toJson()

        val result = importSchedule(future, TestMaxLevels)

        assertIs<ImportResult.Unreadable>(result)
        assertTrue(result.reason.contains("schema version"))
    }

    @Test
    fun `import rejects malformed json instead of crashing`() {
        assertIs<ImportResult.Unreadable>(importSchedule("{ not json", TestMaxLevels))
    }

    @Test
    fun `a typo in a key is rejected rather than silently ignored`() {
        val typo = """
            {
              "version": 1,
              "enabled": true,
              "blocks": [
                {
                  "day": "MONDAY",
                  "start": "09:00",
                  "durationMinutes": 60,
                  "target": { "type": "single", "stream": "RING", "spec": { "type": "raw", "level": 5 } }
                }
              ]
            }
        """.trimIndent()

        assertIs<ImportResult.Unreadable>(importSchedule(typo, TestMaxLevels))
    }

    @Test
    fun `a hand-written minimal document loads`() {
        val handWritten = """
            {
              "version": 1,
              "enabled": true,
              "presets": { "RING": [ { "name": "Quiet", "level": 2 } ] },
              "profiles": [],
              "blocks": [
                {
                  "day": "MONDAY",
                  "start": "09:00",
                  "durationMin": 480,
                  "target": {
                    "type": "single",
                    "stream": "RING",
                    "spec": { "type": "preset", "name": "Quiet" }
                  }
                }
              ]
            }
        """.trimIndent()

        val result = importSchedule(handWritten, TestMaxLevels)

        assertIs<ImportResult.Ok>(result)
        assertEquals(2, result.schedule.compile().single().level)
    }
}
