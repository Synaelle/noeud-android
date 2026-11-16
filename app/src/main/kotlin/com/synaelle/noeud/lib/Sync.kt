// SPDX-License-Identifier: GPL-3.0-or-later

// Keeping the reminders the same here and on a Noeud server: local changes wait in an outbox and
// are sent with the version they were made on, then the changes made elsewhere are pulled.

package com.synaelle.noeud.lib

import org.json.JSONObject

/** The server could not be reached. Normal for a phone on a train, not an error. */
class Offline : Exception()

class ServerError(val status: Int, message: String) : Exception(message)

class Answer(val status: Int, val data: JSONObject?)

/** Sends one request to the server. Throws Offline when it cannot be reached. */
fun interface Transport {
    fun request(server: Server, method: String, path: String, body: JSONObject?): Answer
}

private const val PAGE = 500

private fun messageOf(answer: Answer): String =
    answer.data?.optString("message")?.takeIf { it.isNotEmpty() } ?: "The server answered ${answer.status}."

private sealed interface Outcome {
    data class FromServer(val reminder: Reminder, val conflict: Boolean) : Outcome
    data object Gone : Outcome
    data class Rejected(val message: String) : Outcome
}

/** The body of a successful answer, which a page in front of the server would not have. */
private fun body(answer: Answer): JSONObject =
    answer.data ?: throw ServerError(answer.status, "The address does not answer like a Noeud server.")

private fun outcome(answer: Answer): Outcome = when (answer.status) {
    200, 201 -> Outcome.FromServer(reminderOf(body(answer)), false)
    409 -> Outcome.FromServer(reminderOf(body(answer).getJSONObject("reminder")), true)
    400, 413 -> Outcome.Rejected(messageOf(answer))
    else -> throw ServerError(answer.status, messageOf(answer))
}

private fun changes(reminder: Reminder): JSONObject = JSONObject()
    .put("title", reminder.title)
    .put("notes", reminder.notes ?: JSONObject.NULL)
    .put("due_at", reminder.dueAt)
    .put("time_zone", reminder.timeZone)
    .put("repeat", repeatJson(reminder.repeat))
    .put("snoozed_until", reminder.snoozedUntil ?: JSONObject.NULL)
    .put("done_at", reminder.doneAt ?: JSONObject.NULL)

/** The fields both sides can change, compared and merged one by one. */
private val fields: List<Pair<(Reminder) -> Any?, (Reminder, Reminder) -> Unit>> = listOf(
    Pair({ r -> r.title }, { to, from -> to.title = from.title }),
    Pair({ r -> r.notes }, { to, from -> to.notes = from.notes }),
    Pair({ r -> r.dueAt }, { to, from -> to.dueAt = from.dueAt }),
    Pair({ r -> r.timeZone }, { to, from -> to.timeZone = from.timeZone }),
    Pair({ r -> r.repeat }, { to, from -> to.repeat = from.repeat }),
    Pair({ r -> r.snoozedUntil }, { to, from -> to.snoozedUntil = from.snoozedUntil }),
    Pair({ r -> r.doneAt }, { to, from -> to.doneAt = from.doneAt }),
    Pair({ r -> r.deleted }, { to, from -> to.deleted = from.deleted }),
)

/**
 * Both sides changed the reminder: each field takes the side that changed it, and the local side
 * when both did. `before` is the reminder as the server had it when the local change began.
 */
fun merge(before: Reminder, local: Reminder, server: Reminder): Reminder {
    val merged = server.copy()
    for ((get, set) in fields) {
        if (get(local) != get(before)) set(merged, local)
    }
    return merged
}

private fun sameFields(a: Reminder, b: Reminder): Boolean = fields.all { (get) -> get(a) == get(b) }

class Sync(private val store: Store, private val transport: Transport, private val clock: () -> Long) {
    private fun request(server: Server, method: String, path: String, body: JSONObject? = null) =
        transport.request(server, method, path, body)

    /** Sends one local change. The base version lets the server refuse it when it changed since. */
    private fun pushOne(server: Server, local: Reminder, base: Int): Outcome {
        val path = "/v1/reminders/${local.id}"
        if (local.deleted) {
            if (base == 0) return Outcome.Gone
            val answer = request(server, "DELETE", "$path?version=$base")
            return if (answer.status == 204 || answer.status == 404) Outcome.Gone else outcome(answer)
        }
        if (base > 0) {
            val answer = request(server, "PATCH", path, changes(local).put("version", base))
            // unknown there, as after moving to another server: create it
            if (answer.status != 404) return outcome(answer)
        }
        val created = outcome(
            request(
                server,
                "POST",
                "/v1/reminders",
                JSONObject()
                    .put("id", local.id)
                    .put("title", local.title)
                    .put("notes", local.notes ?: JSONObject.NULL)
                    .put("due_at", local.dueAt)
                    .put("time_zone", local.timeZone)
                    .put("repeat", repeatJson(local.repeat)),
            ),
        )
        // a create carries no snooze or done, and a retried one answers with what the server kept
        if (created !is Outcome.FromServer || created.reminder.deleted || sameContent(created.reminder, local)) {
            return created
        }
        return outcome(request(server, "PATCH", path, changes(local).put("version", created.reminder.version)))
    }

    /** What the push did to the outbox: true when a merged change waits to be sent again. */
    private fun applyPushed(state: State, id: String, sent: Pending, result: Outcome): Boolean {
        val pending = state.pending[id]
        val newer = pending != null && pending.rev != sent.rev
        when (result) {
            is Outcome.Gone -> {
                val local = state.reminders[id]
                forget(state, id)
                if (newer && local != null && !local.deleted) {
                    // restored while its deletion was on the way: the server keeps the id deleted
                    val restored = local.copy(id = uuidv7(), version = 0)
                    state.reminders[restored.id] = restored
                    state.pending[restored.id] = Pending(base = 0, rev = 1)
                }
            }
            is Outcome.Rejected -> {
                // kept, and said, until the reminder changes again
                if (pending != null && !newer) pending.refused = result.message
            }
            is Outcome.FromServer -> {
                val server = result.reminder
                if (result.conflict) {
                    val local = state.reminders[id]
                    val before = pending?.before
                    if (local == null || before == null || server.deleted) {
                        state.pending.remove(id)
                        applyServer(state, server)
                        return false
                    }
                    // changed here and elsewhere: keep both, then send the result on the new version
                    val merged = merge(before, local, server)
                    if (sameFields(merged, server)) {
                        state.pending.remove(id)
                        applyServer(state, server)
                        return false
                    }
                    state.reminders[id] = merged.copy(version = server.version)
                    state.pending[id] = Pending(base = server.version, rev = pending.rev + 1, before = server.copy())
                    return true
                } else if (newer && !server.deleted) {
                    // changed again here while this push ran: keep that, on top of the new version
                    val local = state.reminders[id]
                    if (local != null) {
                        local.version = server.version
                        pending.base = server.version
                        pending.before = server.copy()
                    }
                } else {
                    state.pending.remove(id)
                    applyServer(state, server)
                }
            }
        }
        return false
    }

    /** Sends the outbox. True when a change was merged after a conflict and waits to go again. */
    private fun push(server: Server): Boolean {
        var again = false
        for (id in store.read().pending.keys) {
            val (local, pending) = store.update { it.reminders[id]?.copy() to it.pending[id]?.copy() }
            if (pending == null || pending.refused != null) continue
            if (local == null) {
                store.update { it.pending.remove(id) }
                continue
            }
            val result = pushOne(server, local, pending.base)
            if (store.update { applyPushed(it, id, pending, result) }) again = true
        }
        return again
    }

    private fun pull(server: Server) {
        var more = true
        while (more) {
            val cursor = store.read().cursor
            val answer = request(server, "GET", "/v1/reminders?since=$cursor&limit=$PAGE")
            if (answer.status != 200) throw ServerError(answer.status, messageOf(answer))
            val page = body(answer)
            val reminders = page.getJSONArray("reminders")
            store.update { state ->
                for (i in 0 until reminders.length()) {
                    val reminder = reminderOf(reminders.getJSONObject(i))
                    if (state.pending[reminder.id] == null) applyServer(state, reminder)
                }
                state.cursor = page.getLong("cursor")
            }
            more = page.optBoolean("has_more", false)
        }
    }

    private fun setStatus(state: SyncState, message: String? = null) = store.update { current ->
        val at = timestamp(clock())
        current.sync = SyncStatus(state, at, if (state == SyncState.OK) at else current.sync.okAt, message)
    }

    /** One push and pull. True when a merged change waits to be sent again. */
    private fun syncOnce(): Boolean {
        val server = store.read().server ?: return false
        try {
            val again = push(server)
            pull(server)
            val refused = refusal(store.read())
            if (refused != null) setStatus(SyncState.ERROR, "The server refused a change: $refused")
            else setStatus(SyncState.OK)
            return again
        } catch (_: Offline) {
            setStatus(SyncState.OFFLINE)
        } catch (error: ServerError) {
            setStatus(SyncState.ERROR, if (error.status == 401) "The server refused the token." else error.message)
        }
        return false
    }

    private var running = false
    private var again = false

    /** Pushes the outbox, then pulls what changed on the server. Calls during a run add one more run. */
    fun sync() {
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        try {
            // a few passes at most, should a server keep answering with conflicts
            var pass = 0
            var more = true
            while (more && pass < 5) {
                synchronized(this) { again = false }
                val merged = syncOnce()
                pass++
                more = merged || synchronized(this) { again }
            }
        } finally {
            synchronized(this) { running = false }
        }
    }
}

fun forget(state: State, id: String) {
    state.reminders.remove(id)
    state.pending.remove(id)
    state.fired.remove(id)
}

/** Takes the server's copy, unless a local change waits to be pushed. */
private fun applyServer(state: State, reminder: Reminder) {
    if (reminder.deleted) forget(state, reminder.id) else state.reminders[reminder.id] = reminder
}

/** The first change the server refused and that still waits, if any. */
private fun refusal(state: State): String? = state.pending.values.firstNotNullOfOrNull { it.refused }

private val idle = SyncStatus()

/** Starts syncing with a server, sending every local reminder it does not have. */
fun connect(state: State, server: Server) {
    state.server = server
    state.cursor = 0
    state.sync = idle
    for (reminder in state.reminders.values) {
        if (reminder.version == 0 && state.pending[reminder.id] == null) {
            state.pending[reminder.id] = Pending(base = 0, rev = 1)
        }
    }
}

/** A new token for the same server, as after `token regenerate`: what waits is kept. */
fun replaceToken(state: State, server: Server) {
    state.server = server
    state.sync = idle
}

/** Stops syncing. The reminders stay, as if they had never been on a server. */
fun disconnect(state: State) {
    state.server = null
    state.cursor = 0
    state.pending.clear()
    state.sync = idle
    for (reminder in state.reminders.values.toList()) {
        if (reminder.deleted) forget(state, reminder.id) else reminder.version = 0
    }
}
