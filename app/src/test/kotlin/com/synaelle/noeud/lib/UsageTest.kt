// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T0 is 14:00 UTC, 15:00 in Paris
private val zone = PARIS

private fun state(times: List<TimeUse> = emptyList()): State = State().also { it.times = times.toMutableList() }

class UsageTest {
    @After
    fun resetClock() {
        clock = Clock.H24
    }

    @Test
    fun normalizing() {
        val cases = listOf(
            "2h" to "2h",
            "in 2h" to "2h",
            "120m" to "2h",
            "1h60m" to "2h",
            " 90 m " to "1h30m",
            "8h52" to "8h52m",
            "24h" to "1d",
            "7d" to "1w",
            "7D2H30S" to "1w2h30s",
            "3s7w1h" to "7w1h3s",
            "18:00" to "18:00",
            "at 6pm" to "18:00",
            "today 6:00 pm" to "18:00",
            "tomorrow" to "tomorrow 9:00",
            "Tomorrow at 7:30am" to "tomorrow 7:30",
            "next monday 9:00" to "monday 9:00",
            "friday 10am" to "friday 10:00",
        )
        assertEquals(cases.map { "${it.first} -> ${it.second}" }, cases.map { "${it.first} -> ${normalizeWhen(it.first)}" })
    }

    @Test
    fun `does not count a one-off date or what is not a time`() {
        val texts = listOf("2026-11-13 14:30", "2026-11-13T14:30", "today", "0m", "600w", "1h1h", "5s3", "soon", "9", "")
        assertEquals(texts.map { "$it -> null" }, texts.map { "$it -> ${normalizeWhen(it)}" })
    }

    @Test
    fun labels() {
        val cases = listOf(
            "10m" to "10 min",
            "30m" to "30 min",
            "1h" to "1 hour",
            "2h" to "2 hours",
            "1d" to "24 hours",
            "3d" to "3 days",
            "1w" to "1 week",
            "2w" to "2 weeks",
            "1h30m" to "1 h 30 min",
            "8h52m" to "8 h 52 min",
            "7w1h3s" to "7 wk 1 h 3 s",
            "30s" to "30 s",
        )
        assertEquals(cases.map { "${it.first} -> ${it.second}" }, cases.map { "${it.first} -> ${timeLabel(it.first)}" })
    }

    @Test
    fun `says the time the way the app writes it`() {
        assertEquals("Today ${formatHour(18)}", timeLabel("18:00"))
        assertEquals("Tonight ${formatHour(21)}", timeLabel("21:00"))
        assertEquals("Tomorrow ${formatHour(9)}", timeLabel("tomorrow 9:00"))
        assertEquals("Friday ${formatHour(9, 30)}", timeLabel("friday 9:30"))
    }

    @Test
    fun `counts the same choice however it was written`() {
        val s = state()
        noteTimeUse(s, "2h", T0)
        noteTimeUse(s, "120m", T0 + 1000)
        noteTimeUse(s, "tomorrow", T0 + 2000)
        noteTimeUse(s, "2026-11-13 14:30", T0 + 3000)
        noteTimeUse(s, null, T0 + 4000)
        assertEquals(listOf(TimeUse("2h", 2, T0 + 1000), TimeUse("tomorrow 9:00", 1, T0 + 2000)), s.times)
    }

    @Test
    fun `keeps the most used`() {
        val s = state()
        noteTimeUse(s, "1m", T0)
        noteTimeUse(s, "1m", T0 + 100)
        for (i in 2..KEEP_TIMES + 10) noteTimeUse(s, "${i}m", T0 + 100 + i)
        assertEquals(KEEP_TIMES, s.times.size)
        assertEquals(TimeUse("1m", 2, T0 + 100), s.times.find { it.`when` == "1m" })
        // the oldest of the single uses went first
        assertFalse(s.times.any { it.`when` == "2m" })
        assertTrue(s.times.any { it.`when` == "1h" })
    }

    @Test
    fun `gives the defaults in their order without history`() {
        assertEquals(addDefaults, rankTimes(emptyList(), addDefaults, T0, zone))
        assertEquals(snoozeDefaults, rankTimes(emptyList(), snoozeDefaults, T0, zone))
    }

    @Test
    fun `puts the most used first, ties to the most recent, then fills with defaults`() {
        val times = listOf(
            TimeUse("2h", 3, T0 - 5000),
            TimeUse("friday 9:00", 5, T0 - 9000),
            TimeUse("30m", 3, T0 - 1000),
            TimeUse("10m", 1, T0 - 1000),
        )
        assertEquals(listOf("friday 9:00", "30m", "2h", "10m", "1h"), rankTimes(times, addDefaults, T0, zone))
    }

    @Test
    fun `hides a time of day that has gone by today`() {
        // 15:00 in Paris: 9:00 and 14:30 have passed, 18:00 has not
        val times = listOf(
            TimeUse("9:00", 9, T0),
            TimeUse("14:30", 8, T0),
            TimeUse("tomorrow 9:00", 7, T0),
        )
        assertEquals(
            listOf("tomorrow 9:00", "10m", "1h", "18:00", "monday 9:00"),
            rankTimes(times, addDefaults, T0, zone),
        )
        val evening = parseTime("2026-11-12T18:30:00Z")
        assertFalse("18:00" in rankTimes(emptyList(), addDefaults, evening, zone))
    }

    @Test
    fun `starts in an hour before any history`() {
        assertEquals("1h", startingTime(emptyList(), T0, zone))
    }

    @Test
    fun `starts at the most used time`() {
        val times = listOf(TimeUse("30m", 4, T0 - 1000), TimeUse("friday 9:00", 3, T0 - 500))
        assertEquals("30m", startingTime(times, T0, zone))
    }

    @Test
    fun `skips a most used time of day that has gone by today`() {
        // 15:00 in Paris
        val times = listOf(TimeUse("9:00", 9, T0), TimeUse("2h", 2, T0))
        assertEquals("2h", startingTime(times, T0, zone))
        assertEquals("1h", startingTime(listOf(TimeUse("9:00", 9, T0)), T0, zone))
    }

    @Test
    fun `counts the time of an added reminder and of a snooze, not a picked date`() {
        val reminder = makeReminder(dueAt = timestamp(T0 - 60_000))
        val store = Store(seed(listOf(reminder)))
        store.update {
            addReminder(it, NewReminder("Stretch", null, timestamp(T0 + 7_200_000), "Europe/Paris", null), T0)
            noteTimeUse(it, "2h", T0)
        }
        store.update {
            addReminder(it, NewReminder("Dentist", null, "2026-11-13T13:30:00Z", "Europe/Paris", null), T0)
            noteTimeUse(it, "2026-11-13 14:30", T0)
        }
        store.update {
            snooze(it, reminder.id, T0 + 7_200_000, T0)
            noteTimeUse(it, "120m", T0)
        }
        assertEquals(listOf(TimeUse("2h", 2, T0)), store.read().times)
    }

    // the app writes "6:30 PM" in English, where the extension follows the browser's language
    @Test
    fun `writes times in 24-hour by default and 12-hour once chosen, kept in storage`() {
        val storage = MemoryStorage()
        val store = Store(storage)
        clock = store.read().clock
        assertEquals(Clock.H24, clock)
        assertEquals("18:30", formatHour(18, 30))
        assertEquals("Tomorrow 9:00", timeLabel("tomorrow 9:00"))
        store.update { it.clock = Clock.H12 }
        clock = Clock.H12
        assertEquals("6:30 PM", formatHour(18, 30))
        clock = Clock.H24
        assertEquals("18:30", formatHour(18, 30))
        clock = Store(storage).read().clock
        assertEquals(Clock.H12, clock)
        assertEquals("6:30 PM", formatHour(18, 30))
    }
}
