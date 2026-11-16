// SPDX-License-Identifier: GPL-3.0-or-later

// Which reminders notify you and what the notifications say. Showing them and the alarm itself are
// the phone's part.

package com.synaelle.noeud.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L

class CatchupTest {
    @Test
    fun `notifies about a reminder that came due while the phone was off, marked missed`() {
        val missed = makeReminder(title = "Water the plants", dueAt = timestamp(T0 - 40 * 60_000))
        val store = Store(seed(listOf(missed)))
        val due = notifyDue(store, T0)
        assertEquals(listOf(missed.id), due.map(Reminder::id))
        assertEquals("Water the plants", due[0].title)
        assertTrue(notificationText(due[0], T0, PARIS).startsWith("Missed, due at "))
        assertEquals("Delay 24 hours", delayTitle(store.read().delay))
    }

    @Test
    fun `notifies each occurrence once, whatever wakes the app`() {
        val store = Store(seed(listOf(makeReminder(dueAt = timestamp(T0 - 60_000)))))
        assertEquals(1, notifyDue(store, T0).size)
        assertEquals(0, notifyDue(store, T0).size)
        assertEquals(0, notifyDue(store, T0).size)
        assertEquals(0, notifyDue(store, T0 + 5 * 60_000).size)
    }

    @Test
    fun `says Due at when the alarm is on time`() {
        val reminder = makeReminder(dueAt = timestamp(T0 + 60_000))
        val store = Store(seed(listOf(reminder)))
        assertEquals(0, notifyDue(store, T0).size)
        assertEquals(T0 + 60_000, nextAlarm(store.read(), T0))
        val now = T0 + 60_000 + 300
        val due = notifyDue(store, now)
        assertEquals(listOf(reminder.id), due.map(Reminder::id))
        assertTrue(notificationText(due[0], now, PARIS).startsWith("Due at "))
    }

    @Test
    fun `makes more than three at once into one summary`() {
        val titles = listOf("One", "Two", "Three", "Four", "Five")
        val store = Store(
            seed(titles.mapIndexed { i, title -> makeReminder(title = title, dueAt = timestamp(T0 - (i + 2) * 3600_000L)) }),
        )
        val due = notifyDue(store, T0)
        assertTrue(due.size > SUMMARY_ABOVE)
        assertEquals("5 missed reminders", summaryTitle(due, T0))
        assertEquals("Five, Four, Three, and 2 more", summaryText(due))
        assertEquals(0, notifyDue(store, T0).size)
    }

    @Test
    fun `leaves done, deleted and future reminders quiet`() {
        val store = Store(
            seed(
                listOf(
                    makeReminder(dueAt = timestamp(T0 - 60_000), doneAt = timestamp(T0 - 30_000)),
                    makeReminder(dueAt = timestamp(T0 - 60_000), deleted = true),
                    makeReminder(dueAt = timestamp(T0 + 3600_000)),
                    makeReminder(dueAt = timestamp(T0 - 60_000), snoozedUntil = timestamp(T0 + 60_000)),
                ),
            ),
        )
        assertEquals(0, notifyDue(store, T0).size)
    }

    @Test
    fun `delays from the button and notifies again 24 hours later`() {
        val reminder = makeReminder(dueAt = timestamp(T0))
        val store = Store(seed(listOf(reminder)))
        notifyDue(store, T0)
        store.update { snooze(it, reminder.id, delayedUntil(it.delay, T0), T0) }
        assertEquals(timestamp(T0 + DAY), store.read().reminders[reminder.id]?.snoozedUntil)
        assertFalse(stillDue(store.read(), reminder.id, T0))
        assertEquals(T0 + DAY, nextAlarm(store.read(), T0))
        val due = notifyDue(store, T0 + DAY)
        assertEquals(listOf(reminder.id), due.map(Reminder::id))
        assertTrue(notificationText(due[0], T0 + DAY, PARIS).startsWith("Snoozed, due yesterday at "))
    }

    @Test
    fun `marks done from the button`() {
        val reminder = makeReminder(dueAt = timestamp(T0))
        val store = Store(seed(listOf(reminder)))
        notifyDue(store, T0)
        store.update { markDone(it, reminder.id, T0) }
        assertEquals(timestamp(T0), store.read().reminders[reminder.id]?.doneAt)
        assertFalse(stillDue(store.read(), reminder.id, T0))
        assertNull(nextAlarm(store.read(), T0))
    }

    @Test
    fun `moves a repeating reminder to its next occurrence when done`() {
        // every day at 09:00 in Paris, two days late
        val start = "2026-11-10T08:00:00Z"
        val reminder = makeReminder(dueAt = start, repeat = Repeat(RepeatUnit.DAY, 1, start))
        val store = Store(seed(listOf(reminder)))
        store.update { markDone(it, reminder.id, T0) }
        val moved = store.read().reminders[reminder.id]
        assertEquals("2026-11-13T08:00:00Z", moved?.dueAt)
        assertNull(moved?.doneAt)
    }

    @Test
    fun `notifies at once about a reminder added in the past`() {
        val store = Store(MemoryStorage())
        val id = store.update {
            addReminder(it, NewReminder("Already late", null, timestamp(T0 - 1000), "UTC", null), T0).id
        }
        assertEquals(listOf(id), notifyDue(store, T0).map(Reminder::id))
    }

    @Test
    fun `refuses a reminder without a title`() {
        val store = Store(MemoryStorage())
        val error = assertThrows(ActionError::class.java) {
            store.update { addReminder(it, NewReminder("  ", null, timestamp(T0), "UTC", null), T0) }
        }
        assertEquals("Enter what to remember", error.message)
    }

    @Test
    fun `deletes a local reminder outright when there is no server`() {
        val reminder = makeReminder()
        val store = Store(seed(listOf(reminder)))
        store.update { remove(it, reminder.id, T0) }
        val state = store.read()
        assertTrue(state.reminders.isEmpty())
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `the next alarm is the soonest time still ahead`() {
        val soon = makeReminder(dueAt = timestamp(T0 + 60_000))
        val snoozed = makeReminder(dueAt = timestamp(T0 - 60_000), snoozedUntil = timestamp(T0 + 30_000))
        val done = makeReminder(dueAt = timestamp(T0 + 10_000), doneAt = timestamp(T0))
        val deleted = makeReminder(dueAt = timestamp(T0 + 10_000), deleted = true)
        val past = makeReminder(dueAt = timestamp(T0 - 60_000))
        val state = State()
        for (reminder in listOf(soon, snoozed, done, deleted, past)) state.reminders[reminder.id] = reminder
        assertEquals(T0 + 30_000, nextAlarm(state, T0))
        state.reminders.remove(snoozed.id)
        assertEquals(T0 + 60_000, nextAlarm(state, T0))
    }

    @Test
    fun `there is no alarm when nothing is ahead`() {
        val state = State()
        assertNull(nextAlarm(state, T0))
        val past = makeReminder(dueAt = timestamp(T0 - 60_000))
        state.reminders[past.id] = past
        assertNull(nextAlarm(state, T0))
        // a reminder due right now is notified now, not by a later alarm
        val now = makeReminder(dueAt = timestamp(T0))
        state.reminders[now.id] = now
        assertNull(nextAlarm(state, T0))
    }
}
