// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.ZoneId

val T0: Long = parseTime("2026-11-12T14:00:00Z")

val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** The state kept in memory instead of a file. */
class MemoryStorage(var text: String? = null) : Storage {
    override fun read(): String? = text

    override fun write(text: String) {
        this.text = text
    }
}

fun makeReminder(
    now: Long = T0,
    title: String = "Call the dentist",
    notes: String? = null,
    dueAt: String = timestamp(now),
    timeZone: String = "Europe/Paris",
    repeat: Repeat? = null,
    snoozedUntil: String? = null,
    doneAt: String? = null,
    deleted: Boolean = false,
    version: Int = 0,
): Reminder = Reminder(
    id = uuidv7(now),
    title = title,
    notes = notes,
    dueAt = dueAt,
    timeZone = timeZone,
    repeat = repeat,
    snoozedUntil = snoozedUntil,
    doneAt = doneAt,
    deleted = deleted,
    version = version,
    createdAt = timestamp(now),
    updatedAt = timestamp(now),
    createdBy = null,
)

/** Storage holding these reminders, as an earlier run of the app would have left it. */
fun seed(reminders: List<Reminder>, extra: (State) -> Unit = {}): MemoryStorage {
    val state = State()
    for (reminder in reminders) state.reminders[reminder.id] = reminder
    extra(state)
    return MemoryStorage(state.toJson().toString())
}

/** What a wake or an alarm does with the state: the reminders to notify, recorded as notified. */
fun notifyDue(store: Store, now: Long): List<Reminder> = store.update { state ->
    dueReminders(state, now).also { markFired(state, it, now) }
}

/** The app's state, a server and the sync between them, on a clock the test sets. */
class Session(val storage: MemoryStorage = MemoryStorage()) {
    var now = T0
    val store = Store(storage)
    val server = FakeServer(clock = { now })
    val sync = Sync(store, server) { now }

    /** A change made in the app, then the sync that follows it. */
    fun <T> handle(change: (State) -> T): T = store.update(change).also { sync.sync() }

    fun connectServer() = handle { connect(it, Server(server.origin, server.token, "android")) }
}
