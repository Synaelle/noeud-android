// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.ZoneId

/** A field an edit sets, to a value or to null. A field left out of an edit is not a Change. */
data class Change<T>(val value: T)

data class RepeatChange(val every: RepeatUnit, val interval: Int, val start: String? = null)

/** What an edit may change, as in a PATCH. A repeat without a start starts at the due time. */
data class Changes(
    val title: String? = null,
    val notes: Change<String?>? = null,
    val dueAt: String? = null,
    val timeZone: String? = null,
    val snoozedUntil: Change<String?>? = null,
    val doneAt: Change<String?>? = null,
    val repeat: Change<RepeatChange?>? = null,
)

data class NewReminder(
    val title: String,
    val notes: String?,
    val dueAt: String,
    val timeZone: String,
    val repeat: Pair<RepeatUnit, Int>?,
)

/** A change that cannot be made, with the reason to show. */
class ActionError(message: String) : Exception(message)

/** Records a local change in the outbox, when there is a server to send it to. */
private fun changed(state: State, reminder: Reminder, now: Long) {
    reminder.updatedAt = timestamp(now)
    if (state.server == null) return
    val pending = state.pending[reminder.id]
    state.pending[reminder.id] = Pending(
        base = pending?.base ?: reminder.version,
        rev = (pending?.rev ?: 0) + 1,
        before = pending?.before,
    )
}

/** The reminder to change. Before its first unsent change, keeps it as the server has it. */
private fun live(state: State, id: String): Reminder {
    val reminder = state.reminders[id]
    if (reminder == null || reminder.deleted) throw ActionError("This reminder is gone")
    if (state.server != null && state.pending[id] == null && reminder.version > 0) {
        state.pending[id] = Pending(base = reminder.version, rev = 0, before = reminder.copy())
    }
    return reminder
}

private fun trimmedNotes(notes: String?): String? = notes?.trim()?.ifEmpty { null }

fun addReminder(state: State, input: NewReminder, now: Long): Reminder {
    fieldsProblem(input.title, input.notes, input.repeat?.second)?.let { throw ActionError(it) }
    val due = timestamp(parseTime(input.dueAt))
    val reminder = Reminder(
        id = uuidv7(now),
        title = input.title.trim(),
        notes = trimmedNotes(input.notes),
        dueAt = due,
        timeZone = input.timeZone,
        repeat = input.repeat?.let { (every, interval) -> Repeat(every, interval, due) },
        snoozedUntil = null,
        doneAt = null,
        deleted = false,
        version = 0,
        createdAt = timestamp(now),
        updatedAt = timestamp(now),
        createdBy = null,
    )
    state.reminders[reminder.id] = reminder
    changed(state, reminder, now)
    return reminder
}

/** Done, or for a repeating reminder the next occurrence after now, as the server does it. */
fun markDone(state: State, id: String, now: Long) {
    val reminder = live(state, id)
    val repeat = reminder.repeat
    if (repeat != null) {
        val after = maxOf(now, parseTime(reminder.dueAt))
        reminder.dueAt = timestamp(nextOccurrence(repeat, ZoneId.of(reminder.timeZone), after))
    } else {
        reminder.doneAt = timestamp(now)
    }
    reminder.snoozedUntil = null
    changed(state, reminder, now)
}

fun reopen(state: State, id: String, now: Long) {
    val reminder = live(state, id)
    reminder.doneAt = null
    reminder.snoozedUntil = null
    changed(state, reminder, now)
}

fun snooze(state: State, id: String, until: Long, now: Long) {
    val reminder = live(state, id)
    reminder.snoozedUntil = timestamp(until)
    changed(state, reminder, now)
}

/** Deletes a reminder, keeping a tombstone until the server has heard of it. */
fun remove(state: State, id: String, now: Long) {
    val reminder = live(state, id)
    state.fired.remove(id)
    if (state.server == null) {
        state.reminders.remove(id)
        state.pending.remove(id)
        return
    }
    reminder.deleted = true
    changed(state, reminder, now)
}

/**
 * Changes a reminder the way a PATCH does on the server: moving the due time clears the snooze
 * and moves the repeat's start along, unless the same change sets them.
 */
fun editReminder(state: State, id: String, changes: Changes, now: Long) {
    fieldsProblem(changes.title, changes.notes?.value, changes.repeat?.value?.interval)?.let {
        throw ActionError(it)
    }
    val reminder = live(state, id)
    changes.title?.let { reminder.title = it.trim() }
    changes.notes?.let { reminder.notes = trimmedNotes(it.value) }
    changes.timeZone?.let { reminder.timeZone = it }
    fun time(value: String?): String? = value?.let { timestamp(parseTime(it)) }
    changes.dueAt?.let { due ->
        reminder.dueAt = timestamp(parseTime(due))
        if (changes.snoozedUntil == null) reminder.snoozedUntil = null
        if (changes.repeat == null) reminder.repeat = reminder.repeat?.copy(start = reminder.dueAt)
    }
    changes.snoozedUntil?.let { reminder.snoozedUntil = time(it.value) }
    changes.doneAt?.let { reminder.doneAt = time(it.value) }
    changes.repeat?.let { change ->
        reminder.repeat = change.value?.let {
            Repeat(it.every, it.interval, time(it.start) ?: reminder.dueAt)
        }
    }
    changed(state, reminder, now)
}

/** A reminder brought back after it came due was notified already: it is not notified again. */
private fun noteNotified(state: State, reminder: Reminder, now: Long) {
    val at = notifyAt(reminder)
    if (at != null && at <= now) state.fired[reminder.id] = at
}

/**
 * Brings back a reminder deleted a moment ago. Once the server has its deletion, the same id
 * stays deleted there, so it comes back as a new reminder.
 */
fun restoreReminder(state: State, snapshot: Reminder, now: Long): String {
    val existing = state.reminders[snapshot.id]
    if (existing != null && !existing.deleted) return existing.id
    if (existing != null) {
        existing.deleted = false
        changed(state, existing, now)
        noteNotified(state, existing, now)
        return existing.id
    }
    val restored = snapshot.copy(
        id = if (state.server != null) uuidv7(now) else snapshot.id,
        deleted = false,
        version = 0,
        createdAt = timestamp(now),
        createdBy = null,
    )
    state.reminders[restored.id] = restored
    changed(state, restored, now)
    noteNotified(state, restored, now)
    return restored.id
}
