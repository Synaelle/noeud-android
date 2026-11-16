// SPDX-License-Identifier: GPL-3.0-or-later

// What to notify and what the notifications say. Showing them is the phone's part.

package com.synaelle.noeud.lib

import java.time.ZoneId

/** A notification shown this much after its time says it was missed. */
const val MISSED_AFTER_MS = 60_000L

/** More reminders than this at once become one summary notification. */
const val SUMMARY_ABOVE = 3

// alarms can fire a moment before their time
const val EARLY_MS = 1000L

private fun isMissed(reminder: Reminder, now: Long): Boolean =
    now - (notifyAt(reminder) ?: now) > MISSED_AFTER_MS

/** "Due at 14:00", "Missed, due at 14:00" or "Snoozed, due at 14:00", then the notes. */
fun notificationText(reminder: Reminder, now: Long, zone: ZoneId): String {
    val at = formatAt(parseTime(reminder.dueAt), now, zone)
    val line = when {
        isMissed(reminder, now) -> "Missed, due $at"
        reminder.snoozedUntil != null -> "Snoozed, due $at"
        else -> "Due $at"
    }
    val notes = reminder.notes ?: return line
    return "$line\n${notes.take(200)}"
}

fun summaryTitle(due: List<Reminder>, now: Long): String =
    if (due.all { isMissed(it, now) }) "${due.size} missed reminders" else "${due.size} reminders are due"

fun summaryText(due: List<Reminder>): String {
    val titles = due.take(SUMMARY_ABOVE).map { it.title }
    val rest = due.size - titles.size
    return titles.joinToString(", ") + if (rest > 0) ", and $rest more" else ""
}

/**
 * Every reminder whose time has come and that was not notified for this occurrence, soonest
 * first, whether its alarm fired on time or the phone was off when it came due.
 */
fun dueReminders(state: State, now: Long): List<Reminder> = state.reminders.values
    .filter { reminder ->
        val at = notifyAt(reminder)
        at != null && at <= now + EARLY_MS && state.fired[reminder.id] != at
    }
    .sortedBy { notifyAt(it) ?: 0 }

/** Records these as notified, so the same occurrence never notifies twice. */
fun markFired(state: State, due: List<Reminder>, now: Long) {
    for (reminder in due) state.fired[reminder.id] = notifyAt(reminder) ?: now
}

/** Whether a reminder's notification still has a reason to be shown. */
fun stillDue(state: State, id: String, now: Long): Boolean {
    val at = state.reminders[id]?.let(::notifyAt)
    return at != null && at <= now + EARLY_MS
}

/** The next time an alarm is needed, or null when nothing is ahead. */
fun nextAlarm(state: State, now: Long): Long? =
    state.reminders.values.mapNotNull(::notifyAt).filter { it > now }.minOrNull()
