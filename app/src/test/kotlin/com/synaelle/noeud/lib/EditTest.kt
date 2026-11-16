// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun later(minutes: Int) = timestamp(T0 + minutes * 60_000L)

class EditTest {
    @Test
    fun `moves repeat start with due_at, as the server does`() {
        val start = later(-24 * 60)
        val reminder = makeReminder(dueAt = start, repeat = Repeat(RepeatUnit.DAY, 1, start))
        val store = Store(seed(listOf(reminder)))
        store.update { editReminder(it, reminder.id, Changes(dueAt = later(90)), T0) }
        val edited = store.read().reminders[reminder.id]
        assertEquals(later(90), edited?.dueAt)
        assertEquals(Repeat(RepeatUnit.DAY, 1, later(90)), edited?.repeat)
    }

    @Test
    fun `keeps repeat start when the same change sets the repeat`() {
        val start = later(-24 * 60)
        val reminder = makeReminder(dueAt = start, repeat = Repeat(RepeatUnit.DAY, 1, start))
        val store = Store(seed(listOf(reminder)))
        store.update {
            editReminder(
                it,
                reminder.id,
                Changes(dueAt = later(90), repeat = Change(RepeatChange(RepeatUnit.DAY, 1, start))),
                T0,
            )
        }
        assertEquals(start, store.read().reminders[reminder.id]?.repeat?.start)
    }

    @Test
    fun `clears the snooze when the time moves, and keeps it when only the title changes`() {
        val reminder = makeReminder(dueAt = later(-10), snoozedUntil = later(10))
        val store = Store(seed(listOf(reminder)))
        store.update { editReminder(it, reminder.id, Changes(title = "Call the dentist back"), T0) }
        var edited = store.read().reminders[reminder.id]
        assertEquals(later(10), edited?.snoozedUntil)
        store.update { editReminder(it, reminder.id, Changes(dueAt = later(60)), T0) }
        edited = store.read().reminders[reminder.id]
        assertEquals("Call the dentist back", edited?.title)
        assertNull(edited?.snoozedUntil)
        assertEquals(T0 + 60 * 60_000L, nextAlarm(store.read(), T0))
    }

    @Test
    fun `starts a new repeat at the due time`() {
        val reminder = makeReminder(dueAt = later(30))
        val store = Store(seed(listOf(reminder)))
        store.update {
            editReminder(it, reminder.id, Changes(repeat = Change(RepeatChange(RepeatUnit.WEEK, 2))), T0)
        }
        assertEquals(Repeat(RepeatUnit.WEEK, 2, later(30)), store.read().reminders[reminder.id]?.repeat)
    }

    @Test
    fun `refuses an empty title`() {
        val reminder = makeReminder()
        val store = Store(seed(listOf(reminder)))
        val error = assertThrows(ActionError::class.java) {
            store.update { editReminder(it, reminder.id, Changes(title = " "), T0) }
        }
        assertEquals("Enter what to remember", error.message)
    }

    @Test
    fun `undoes done on a repeating reminder by putting its time back`() {
        val start = later(-60)
        val repeat = Repeat(RepeatUnit.DAY, 1, start)
        val reminder = makeReminder(dueAt = start, repeat = repeat)
        val store = Store(seed(listOf(reminder)))
        store.update { markDone(it, reminder.id, T0) }
        store.update {
            editReminder(
                it,
                reminder.id,
                Changes(
                    dueAt = reminder.dueAt,
                    repeat = Change(RepeatChange(repeat.every, repeat.interval, repeat.start)),
                    snoozedUntil = Change(reminder.snoozedUntil),
                    doneAt = Change(reminder.doneAt),
                ),
                T0,
            )
        }
        val undone = store.read().reminders[reminder.id]
        assertEquals(start, undone?.dueAt)
        assertEquals(repeat, undone?.repeat)
    }

    @Test
    fun `brings back a deleted reminder under its id without a server`() {
        val reminder = makeReminder(dueAt = later(30))
        val store = Store(seed(listOf(reminder)))
        store.update { remove(it, reminder.id, T0) }
        val id = store.update { restoreReminder(it, reminder, T0) }
        assertEquals(reminder.id, id)
        assertEquals(false, store.read().reminders[reminder.id]?.deleted)
    }

    @Test
    fun `brings back a reminder whose deletion reached the server as a new one`() {
        val session = Session()
        val server = session.server
        session.connectServer()
        val id = session.handle {
            addReminder(it, NewReminder("Buy bread", null, later(30), "UTC", null), T0).id
        }
        session.sync.sync()
        val snapshot = session.store.read().reminders.getValue(id)
        session.handle { remove(it, id, T0) }
        session.sync.sync()
        assertTrue(server.reminders[id]?.deleted == true)
        val back = session.handle { restoreReminder(it, snapshot, T0) }
        session.sync.sync()
        assertNotEquals(id, back)
        assertEquals("Buy bread", server.reminders[back]?.title)
        assertEquals(listOf(back), session.store.read().reminders.keys.toList())
        assertFalse(session.store.read().reminders.getValue(back).deleted)
    }
}
