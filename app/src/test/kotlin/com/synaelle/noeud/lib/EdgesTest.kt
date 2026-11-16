// SPDX-License-Identifier: GPL-3.0-or-later

// What a day of trying to break the extension found.

package com.synaelle.noeud.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgesTest {
    @Test
    fun `undo of a delete does not notify an overdue reminder a second time`() {
        val reminder = makeReminder(dueAt = timestamp(T0 - 60_000))
        val store = Store(seed(listOf(reminder)))
        assertEquals(1, notifyDue(store, T0).size)
        val snapshot = store.read().reminders.getValue(reminder.id)
        store.update { remove(it, reminder.id, T0) }
        store.update { restoreReminder(it, snapshot, T0) }
        assertEquals(0, notifyDue(store, T0).size)
    }

    @Test
    fun `the summary notification goes once every reminder in it is done`() {
        val reminders = (1..4).map { n -> makeReminder(title = "Reminder $n", dueAt = timestamp(T0 - n * 60_000L)) }
        val store = Store(seed(reminders))
        val due = notifyDue(store, T0)
        assertTrue(due.size > SUMMARY_ABOVE)
        val summary = due.map(Reminder::id)
        fun shown() = store.read().let { state -> summary.any { stillDue(state, it, T0) } }
        assertTrue(shown())
        val last = reminders.reversed().first()
        val others = reminders.reversed().drop(1)
        for (reminder in others) store.update { markDone(it, reminder.id, T0) }
        assertTrue(shown())
        store.update { markDone(it, last.id, T0) }
        assertFalse(shown())
    }

    @Test
    fun `stored data of the wrong shape reads as empty, and adding works again`() {
        val store = Store(MemoryStorage("""{"reminders":null,"fired":[],"times":"x","cursor":"3"}"""))
        val state = store.read()
        assertEquals(emptyMap<String, Reminder>(), state.reminders)
        assertEquals(emptyMap<String, Long>(), state.fired)
        assertEquals(emptyList<TimeUse>(), state.times)
        assertEquals(0L, state.cursor)
        val id = store.update {
            addReminder(it, NewReminder("Pet the cat", null, timestamp(T0 + 3600_000), "Europe/Paris", null), T0).id
        }
        assertEquals("Pet the cat", store.read().reminders[id]?.title)
    }

    @Test
    fun `counts an emoji as one character, as the server does`() {
        assertNull(fieldsProblem(title = "🐈".repeat(500)))
        assertTrue(fieldsProblem(title = "🐈".repeat(501))!!.contains("under 500"))
    }

    @Test
    fun `refuses long notes and repeats past 1000 before the server does`() {
        assertTrue(fieldsProblem(notes = "a".repeat(10_001))!!.contains("notes under 10,000"))
        assertTrue(fieldsProblem(interval = 5000)!!.contains("1 to 1000"))
        assertNull(fieldsProblem(interval = 1000))
    }

    @Test
    fun `says so when adding`() {
        val store = Store(MemoryStorage())
        val error = assertThrows(ActionError::class.java) {
            store.update {
                addReminder(
                    it,
                    NewReminder("Pet the cat", "a".repeat(10_001), timestamp(T0 + 3600_000), "Europe/Paris", null),
                    T0,
                )
            }
        }
        assertTrue(error.message!!.contains("notes under"))
    }
}
