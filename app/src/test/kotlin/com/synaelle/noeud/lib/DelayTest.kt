// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 3_600_000L

/** What choosing a length in the settings stores. */
private fun chooseDelay(store: Store, typed: String) {
    when (val read = readDelay(typed)) {
        is DelayChoice.Error -> throw ActionError(read.error)
        is DelayChoice.Ok -> store.update { it.delay = read.delay }
    }
}

class DelayTest {
    @Test
    fun `is 24 hours until chosen`() {
        assertEquals("1d", DEFAULT_DELAY)
        val store = Store(MemoryStorage())
        assertEquals("1d", store.read().delay)
        assertEquals("Delay 24 hours", delayTitle(store.read().delay))
    }

    @Test
    fun `offers 1 hour, 3 hours, 24 hours and 1 week`() {
        assertEquals(
            listOf("Delay 1 hour", "Delay 3 hours", "Delay 24 hours", "Delay 1 week"),
            delayChoices.map(::delayTitle),
        )
    }

    @Test
    fun `stores a custom length in its shortest form`() {
        val cases = listOf(
            Triple("3h", "3h", "Delay 3 hours"),
            Triple("36h", "1d12h", "Delay 36 hours"),
            Triple("1d12h", "1d12h", "Delay 36 hours"),
            Triple(" 2 D ", "2d", "Delay 2 days"),
            Triple("90m", "1h30m", "Delay 1 h 30 min"),
            Triple("1d6", "1d6h", "Delay 30 hours"),
            Triple("in 45m", "45m", "Delay 45 min"),
        )
        for ((typed, stored, title) in cases) {
            val storage = MemoryStorage()
            chooseDelay(Store(storage), typed)
            assertEquals(typed, stored, JSONObject(storage.text!!).getString("delay"))
            assertEquals(typed, stored, Store(storage).read().delay)
            assertEquals(typed, title, delayTitle(stored))
        }
    }

    @Test
    fun `refuses what is not a length`() {
        val cases = listOf(
            "soon" to "Enter a length like 2h or 1d12h",
            "" to "Enter a length like 2h or 1d12h",
            "18:00" to "Enter a length like 2h or 1d12h",
            "0m" to "Enter a time after now, like 10m",
            "1h1h" to "Use each unit once, like 1h30m",
            "600w" to "Keep it under 10 years",
        )
        for ((typed, error) in cases) {
            assertEquals(typed, DelayChoice.Error(error), readDelay(typed))
            val storage = MemoryStorage()
            val thrown = runCatching { chooseDelay(Store(storage), typed) }.exceptionOrNull()
            assertEquals(typed, error, thrown?.message)
            assertNull(typed, storage.text)
        }
    }

    @Test
    fun `falls back to 24 hours when what is stored cannot be read`() {
        assertEquals("1d", Store(MemoryStorage("""{"delay":"soon"}""")).read().delay)
        assertEquals("1d", Store(MemoryStorage("""{"delay":3}""")).read().delay)
    }

    @Test
    fun `puts the reminder off by the length set when the button is pressed`() {
        val reminder = makeReminder(dueAt = timestamp(T0))
        val store = Store(seed(listOf(reminder)))
        notifyDue(store, T0)
        chooseDelay(store, "3h")
        store.update { snooze(it, reminder.id, delayedUntil(it.delay, T0), T0) }
        assertEquals(timestamp(T0 + 3 * HOUR), store.read().reminders[reminder.id]?.snoozedUntil)
        assertFalse(stillDue(store.read(), reminder.id, T0))
    }

    @Test
    fun `puts off every reminder of the summary, except those done since`() {
        val reminders = (1..5).map { n -> makeReminder(title = "Reminder $n", dueAt = timestamp(T0 - n * 60_000L)) }
        val store = Store(seed(reminders))
        val due = notifyDue(store, T0)
        assertTrue(due.size > SUMMARY_ABOVE)
        assertEquals("Delay 24 hours", delayTitle(store.read().delay))
        store.update { it.summary = due.map(Reminder::id) }
        val first = reminders.first()
        val rest = reminders.drop(1)
        store.update { markDone(it, first.id, T0) }
        store.update { state ->
            // some may have been done or deleted since
            val ids = state.summary.filter { id ->
                state.reminders[id]?.let { !it.deleted && it.doneAt == null } == true
            }
            val until = delayedUntil(state.delay, T0)
            for (id in ids) snooze(state, id, until, T0)
        }
        val state = store.read()
        for (reminder in rest) assertEquals(timestamp(T0 + 24 * HOUR), state.reminders[reminder.id]?.snoozedUntil)
        assertNull(state.reminders[first.id]?.snoozedUntil)
        assertFalse(state.summary.any { stillDue(state, it, T0) })
    }

    @Test
    fun `is the same from the app`() {
        val reminder = makeReminder(dueAt = timestamp(T0 - HOUR))
        val store = Store(seed(listOf(reminder)))
        store.update { snooze(it, reminder.id, delayedUntil(it.delay, T0), T0) }
        assertEquals(timestamp(T0 + 24 * HOUR), store.read().reminders[reminder.id]?.snoozedUntil)
        assertEquals(T0 + 24 * HOUR, nextAlarm(store.read(), T0))
    }
}
