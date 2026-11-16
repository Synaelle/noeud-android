// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

private class Case(
    val name: String,
    val start: String,
    val timeZone: String,
    val every: RepeatUnit,
    val interval: Int,
    val after: String,
    val next: String,
)

// the same cases as the Noeud server's own repeat tests, so both agree
private val cases = listOf(
    Case(
        "daily, strictly after an occurrence",
        "2026-01-01T09:00:00Z", "UTC", RepeatUnit.DAY, 1,
        "2026-01-05T09:00:00Z", "2026-01-06T09:00:00Z",
    ),
    Case(
        "daily, between two occurrences",
        "2026-01-01T09:00:00Z", "UTC", RepeatUnit.DAY, 1,
        "2026-01-05T10:00:00Z", "2026-01-06T09:00:00Z",
    ),
    Case(
        "before the start gives the start",
        "2026-01-01T09:00:00Z", "UTC", RepeatUnit.DAY, 1,
        "2025-12-01T00:00:00Z", "2026-01-01T09:00:00Z",
    ),
    Case(
        "ten years later",
        "2026-01-01T09:00:00Z", "UTC", RepeatUnit.DAY, 1,
        "2036-01-01T09:00:00Z", "2036-01-02T09:00:00Z",
    ),
    Case(
        "every three days in Tokyo",
        "2026-11-01T00:00:00Z", "Asia/Tokyo", RepeatUnit.DAY, 3,
        "2026-11-05T00:00:00Z", "2026-11-07T00:00:00Z",
    ),
    Case(
        "daily across the spring change in Paris",
        "2026-03-27T08:00:00Z", "Europe/Paris", RepeatUnit.DAY, 1,
        "2026-03-28T08:00:00Z", "2026-03-29T07:00:00Z",
    ),
    Case(
        "daily across the autumn change in Paris",
        "2026-10-23T07:00:00Z", "Europe/Paris", RepeatUnit.DAY, 1,
        "2026-10-24T07:00:00Z", "2026-10-25T08:00:00Z",
    ),
    Case(
        "weekly across the spring change in Paris",
        "2026-03-23T08:00:00Z", "Europe/Paris", RepeatUnit.WEEK, 1,
        "2026-03-23T08:00:00Z", "2026-03-30T07:00:00Z",
    ),
    Case(
        "a time inside the spring gap moves forward",
        "2026-03-07T07:30:00Z", "America/New_York", RepeatUnit.DAY, 1,
        "2026-03-07T08:00:00Z", "2026-03-08T07:30:00Z",
    ),
    Case(
        "the day after the spring gap is back to the same time",
        "2026-03-07T07:30:00Z", "America/New_York", RepeatUnit.DAY, 1,
        "2026-03-08T07:30:00Z", "2026-03-09T06:30:00Z",
    ),
    Case(
        "a repeated hour in autumn takes the first one",
        "2026-10-31T05:30:00Z", "America/New_York", RepeatUnit.DAY, 1,
        "2026-10-31T06:00:00Z", "2026-11-01T05:30:00Z",
    ),
    Case(
        "every two weeks",
        "2026-11-02T18:30:00Z", "UTC", RepeatUnit.WEEK, 2,
        "2026-11-10T00:00:00Z", "2026-11-16T18:30:00Z",
    ),
    Case(
        "monthly on the 31st, February",
        "2026-01-31T12:00:00Z", "UTC", RepeatUnit.MONTH, 1,
        "2026-02-01T00:00:00Z", "2026-02-28T12:00:00Z",
    ),
    Case(
        "monthly on the 31st, back to the 31st",
        "2026-01-31T12:00:00Z", "UTC", RepeatUnit.MONTH, 1,
        "2026-02-28T12:00:00Z", "2026-03-31T12:00:00Z",
    ),
    Case(
        "monthly on the 31st, April",
        "2026-01-31T12:00:00Z", "UTC", RepeatUnit.MONTH, 1,
        "2026-03-31T12:00:00Z", "2026-04-30T12:00:00Z",
    ),
    Case(
        "every three months",
        "2026-01-15T08:00:00Z", "UTC", RepeatUnit.MONTH, 3,
        "2026-05-01T00:00:00Z", "2026-07-15T08:00:00Z",
    ),
    Case(
        "yearly on the 29th of February, common year",
        "2024-02-29T10:00:00Z", "UTC", RepeatUnit.YEAR, 1,
        "2024-03-01T00:00:00Z", "2025-02-28T10:00:00Z",
    ),
    Case(
        "yearly on the 29th of February, leap year",
        "2024-02-29T10:00:00Z", "UTC", RepeatUnit.YEAR, 1,
        "2027-03-01T00:00:00Z", "2028-02-29T10:00:00Z",
    ),
)

private fun next(repeat: Repeat, timeZone: String, after: String): String =
    Instant.ofEpochMilli(nextOccurrence(repeat, ZoneId.of(timeZone), parseTime(after))).toString()

class RepeatTest {
    @Test
    fun repeats() {
        assertEquals(
            cases.map { "${it.name}: ${it.next}" },
            cases.map { "${it.name}: ${next(Repeat(it.every, it.interval, it.start), it.timeZone, it.after)}" },
        )
    }

    @Test
    fun `finds the occurrence after a thousand of them without drifting`() {
        val repeat = Repeat(RepeatUnit.MONTH, 1, "2026-01-31T12:00:00Z")
        assertEquals("2109-02-28T12:00:00Z", next(repeat, "UTC", "2109-02-01T00:00:00Z"))
    }
}
