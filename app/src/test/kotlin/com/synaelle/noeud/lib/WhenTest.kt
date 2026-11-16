// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Thursday 12 November 2026, 15:00 in Paris
private val now = parseTime("2026-11-12T14:00:00Z")
private val zone = PARIS

private fun at(text: String, from: Long = now): String = when (val parsed = parseWhen(text, from, zone)) {
    is When.Ok -> Instant.ofEpochMilli(parsed.at).toString()
    is When.Error -> parsed.error
}

/** Every case at once, so one failure does not hide the others. */
private fun assertCases(cases: List<Pair<String, String>>, read: (String) -> String) {
    assertEquals(cases.map { "${it.first} -> ${it.second}" }, cases.map { "${it.first} -> ${read(it.first)}" })
}

private fun ok(text: String, from: Long = now): When.Ok = when (val parsed = parseWhen(text, from, zone)) {
    is When.Ok -> parsed
    is When.Error -> throw AssertionError(parsed.error)
}

private fun preview(text: String): String {
    val parsed = ok(text)
    return formatPreview(parsed.at, parsed.delay, now, zone)
}

private val second = Regex("""\d:\d{2}:\d{2}""")

class WhenTest {
    @Test
    fun durations() = assertCases(
        listOf(
            "30s" to "2026-11-12T14:00:30Z",
            "5m" to "2026-11-12T14:05:00Z",
            "2h" to "2026-11-12T16:00:00Z",
            "1d" to "2026-11-13T14:00:00Z",
            "2w" to "2026-11-26T14:00:00Z",
            "1h30m" to "2026-11-12T15:30:00Z",
            "1h 30m" to "2026-11-12T15:30:00Z",
            "7w1h3s" to "2026-12-31T15:00:03Z",
            "1d12h" to "2026-11-14T02:00:00Z",
            "3s7w1h" to "2026-12-31T15:00:03Z",
            "8h52" to "2026-11-12T22:52:00Z",
            "1h30" to "2026-11-12T15:30:00Z",
            "5m30" to "2026-11-12T14:05:30Z",
            "1d6" to "2026-11-13T20:00:00Z",
            "2w3" to "2026-11-29T14:00:00Z",
            "1h 30" to "2026-11-12T15:30:00Z",
            "8H52" to "2026-11-12T22:52:00Z",
            "7W1H3S" to "2026-12-31T15:00:03Z",
            "in 2h" to "2026-11-12T16:00:00Z",
            "in2h30m" to "2026-11-12T16:30:00Z",
            "520w" to "2036-10-30T14:00:00Z",
            "1h30m30" to "2026-11-12T15:30:30Z",
        ),
    ) { at(it) }

    @Test
    fun `refuses durations that cannot be`() = assertCases(
        listOf(
            "0m" to "Enter a time after now, like 10m",
            "0h0" to "Enter a time after now, like 10m",
            "600w" to "Keep it under 10 years",
            "1h1h" to "Use each unit once, like 1h30m",
            "1h30m2h" to "Use each unit once, like 1h30m",
            "5s3" to "Add a unit to the last number",
            "1h2s30m4" to "Add a unit to the last number",
            "2y" to ENTER_A_TIME,
            "2h-" to ENTER_A_TIME,
            "h2" to ENTER_A_TIME,
            "" to ENTER_A_TIME,
            "soon" to ENTER_A_TIME,
        ),
    ) { at(it) }

    @Test
    fun `says how long the duration is`() {
        assertEquals(Duration.Ms((8 * 60 + 52) * 60_000L), parseDuration("8h52"))
        assertEquals(When.Ok(now + 31_920_000, 31_920_000), parseWhen("8h52", now, zone))
        assertNull(parseDuration("18:00"))
    }

    @Test
    fun `is due on the minute shown when the time is shown to the minute`() {
        val later = now + 21_000
        val long = ok("8h52", later)
        assertEquals(0L, long.at % 60_000)
        assertEquals(Math.floorDiv(later + 31_920_000, 60_000L) * 60_000, long.at)
        assertTrue(formatPreview(long.at, long.delay, later, zone).endsWith(" · in 8 h 52 min"))
        // under an hour or with seconds, it is shown to the second and kept to the second
        assertEquals(later + 300_000, ok("5m", later).at)
        assertEquals(later + 7_203_000, ok("2h3s", later).at)
    }

    @Test
    fun `the preview shows the seconds for a duration with seconds or under an hour`() {
        assertTrue(second.containsMatchIn(preview("8h52m3s")))
        assertTrue(preview("8h52m3s").endsWith(" · in 8 h 52 min 3 s"))
        assertTrue(second.containsMatchIn(preview("30m")))
        assertTrue(preview("30m").endsWith(" · in 30 min"))
        assertTrue(preview("7w1h3s").endsWith(" · in 7 wk 1 h 3 s"))
    }

    @Test
    fun `the preview leaves them out for longer round durations and for times`() {
        assertFalse(second.containsMatchIn(preview("8h52")))
        assertTrue(preview("8h52").endsWith(" · in 8 h 52 min"))
        assertFalse(second.containsMatchIn(preview("tomorrow 9:00")))
        assertTrue(preview("tomorrow 9:00").endsWith(" · in 18 h"))
    }

    // the app writes dates in English whatever the phone's language, where the extension follows
    // the browser's
    @Test
    fun `the preview writes the full date, with the year when it is not this one`() {
        // 24-hour unless 12-hour is chosen
        assertEquals("Thursday 12 November at 23:52:03 · in 8 h 52 min 3 s", preview("8h52m3s"))
        clock = Clock.H12
        try {
            assertEquals("Thursday 12 November at 11:52:03 PM · in 8 h 52 min 3 s", preview("8h52m3s"))
        } finally {
            clock = Clock.H24
        }
        assertTrue(preview("520w").contains("2036"))
    }

    @Test
    fun `times and days, in the reminder zone`() = assertCases(
        listOf(
            "18:00" to "2026-11-12T17:00:00Z",
            "at 6pm" to "2026-11-12T17:00:00Z",
            "6:30 pm" to "2026-11-12T17:30:00Z",
            "14:00" to "2026-11-13T13:00:00Z",
            "12am" to "2026-11-12T23:00:00Z",
            "today 18:00" to "2026-11-12T17:00:00Z",
            "tomorrow" to "2026-11-13T08:00:00Z",
            "tomorrow 9:30" to "2026-11-13T08:30:00Z",
            "tomorrow at 7am" to "2026-11-13T06:00:00Z",
            "monday" to "2026-11-16T08:00:00Z",
            "next monday 9:00" to "2026-11-16T08:00:00Z",
            "thursday 10:00" to "2026-11-19T09:00:00Z",
        ),
    ) { at(it) }

    @Test
    fun `refuses times and days that cannot be`() = assertCases(
        listOf(
            "today 8:00" to "That time has passed",
            "today" to "Add a time, like today 18:00",
            "9" to "Enter the time like 18:00 or 6pm",
            "25:00" to "Enter the time like 18:00 or 6pm",
            "13pm" to "Enter the time like 18:00 or 6pm",
        ),
    ) { at(it) }

    @Test
    fun `the date picker`() = assertCases(
        listOf(
            "2026-11-13 14:30" to "2026-11-13T13:30:00Z",
            "2026-11-13T14:30" to "2026-11-13T13:30:00Z",
            // inside the spring change the time moves forward, as for repeats
            "2027-03-28 02:30" to "2027-03-28T01:30:00Z",
        ),
    ) { at(it) }

    @Test
    fun `the date picker refuses a date that does not exist or has passed`() = assertCases(
        listOf(
            "2026-02-30 10:00" to "That date does not exist",
            "2025-01-01 10:00" to "That time has passed",
        ),
    ) { at(it) }

    @Test
    fun `what the app itself shows`() = assertCases(
        listOf(
            "10 min" to "2026-11-12T14:10:00Z",
            "1 hour" to "2026-11-12T15:00:00Z",
            "2 hours" to "2026-11-12T16:00:00Z",
            "24 hours" to "2026-11-13T14:00:00Z",
            "2 days" to "2026-11-14T14:00:00Z",
            "1 week" to "2026-11-19T14:00:00Z",
            "1 wk" to "2026-11-19T14:00:00Z",
            "8 h 52 min" to "2026-11-12T22:52:00Z",
            "8 h 52 min 3 s" to "2026-11-12T22:52:03Z",
            "30min" to "2026-11-12T14:30:00Z",
            "1hr" to "2026-11-12T15:00:00Z",
            "90 secs" to "2026-11-12T14:01:30Z",
            "Tonight 21:00" to "2026-11-12T20:00:00Z",
            "tonight 9:00 PM" to "2026-11-12T20:00:00Z",
            "Today 18:00" to "2026-11-12T17:00:00Z",
        ),
    ) { at(it) }

    @Test
    fun `still tells words that are not units apart`() {
        assertEquals(ENTER_A_TIME, at("2 hamsters"))
    }

    // Monday 16 November 2026, 08:00 in Paris
    private val monday = parseTime("2026-11-16T07:00:00Z")

    @Test
    fun `a weekday on that same day is today while its time is ahead`() {
        assertEquals("2026-11-16T08:00:00Z", at("monday 9:00", monday))
    }

    @Test
    fun `a weekday on that same day is next week once its time has passed, or with next`() {
        assertEquals("2026-11-23T06:00:00Z", at("monday 7:00", monday))
        assertEquals("2026-11-23T08:00:00Z", at("next monday 9:00", monday))
    }

    // Paris, 25 October 2026: 03:00 summer time becomes 02:00 winter time at 01:00 UTC
    @Test
    fun `the night the clocks go back takes the second 2-50 once the first has gone by`() {
        val after = parseTime("2026-10-25T01:40:00Z") // 02:40 winter time
        assertEquals("2026-10-25T01:50:00Z", at("2:50", after))
    }

    @Test
    fun `the night the clocks go back takes the first 2-50 while it is ahead`() {
        val before = parseTime("2026-10-25T00:30:00Z") // 02:30 summer time
        assertEquals("2026-10-25T00:50:00Z", at("2:50", before))
    }

    @Test
    fun `a time that has passed says how long ago in the preview`() {
        clock = Clock.H24
        assertTrue(formatPreview(now - 3_600_000, null, now, zone).endsWith("· 1 h ago"))
    }
}
