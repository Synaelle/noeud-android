// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.json.JSONArray
import org.json.JSONObject

data class Server(
    /** The origin and optional base path, without a trailing slash. */
    val url: String,
    val token: String,
    /** The token's name, as the server reported it. */
    val name: String,
)

enum class SyncState(val stored: String) {
    OFF("off"),
    SYNCING("syncing"),
    OK("ok"),
    OFFLINE("offline"),
    ERROR("error"),
}

data class SyncStatus(
    val state: SyncState = SyncState.OFF,
    /** The last attempt. */
    val at: String? = null,
    /** The last attempt that reached the server and went through. */
    val okAt: String? = null,
    val message: String? = null,
)

/** A local change the server has not seen yet. */
data class Pending(
    /** The server version the change was made on, 0 for a reminder the server does not have. */
    var base: Int,
    /** Bumped on every local change, so a push can tell whether more came while it ran. */
    var rev: Int,
    /** The reminder as the server last had it, to keep both sides' changes after a conflict. */
    var before: Reminder? = null,
    /** Why the server refused this change. It is not sent again until the reminder changes. */
    var refused: String? = null,
)

enum class Theme(val stored: String) {
    DARK("dark"),
    LIGHT("light"),
}

class State {
    val reminders = LinkedHashMap<String, Reminder>()
    /** The due time (notifyAt) last notified for each reminder, one record per occurrence. */
    val fired = LinkedHashMap<String, Long>()
    /** The outbox. */
    val pending = LinkedHashMap<String, Pending>()
    var cursor = 0L
    var server: Server? = null
    var sync = SyncStatus()
    /** The times chosen when adding and snoozing, for the quick picks. */
    var times = mutableListOf<TimeUse>()
    /** The reminders in the summary notification, which its button acts on. */
    var summary = listOf<String>()
    var theme = Theme.DARK
    var clock = Clock.H24
    var delay = DEFAULT_DELAY
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key)

private fun Pending.toJson(): JSONObject = JSONObject()
    .put("base", base)
    .put("rev", rev)
    .put("before", before?.toJson() ?: JSONObject.NULL)
    .put("refused", refused ?: JSONObject.NULL)

fun State.toJson(): JSONObject = JSONObject()
    .put("reminders", JSONObject().also { all -> reminders.forEach { (id, r) -> all.put(id, r.toJson()) } })
    .put("fired", JSONObject().also { all -> fired.forEach { (id, at) -> all.put(id, at) } })
    .put("pending", JSONObject().also { all -> pending.forEach { (id, p) -> all.put(id, p.toJson()) } })
    .put("cursor", cursor)
    .put(
        "server",
        server?.let { JSONObject().put("url", it.url).put("token", it.token).put("name", it.name) }
            ?: JSONObject.NULL,
    )
    .put(
        "sync",
        JSONObject()
            .put("state", sync.state.stored)
            .put("at", sync.at ?: JSONObject.NULL)
            .put("ok_at", sync.okAt ?: JSONObject.NULL)
            .put("message", sync.message ?: JSONObject.NULL),
    )
    .put(
        "times",
        JSONArray().also { all ->
            times.forEach { all.put(JSONObject().put("when", it.`when`).put("count", it.count).put("last", it.last)) }
        },
    )
    .put("summary", JSONArray(summary))
    .put("theme", theme.stored)
    .put("clock", clock.stored)
    .put("delay", delay)

private inline fun JSONObject.eachObject(key: String, action: (String, JSONObject) -> Unit) {
    val all = optJSONObject(key) ?: return
    for (id in all.keys()) action(id, all.getJSONObject(id))
}

fun stateOf(json: JSONObject): State = State().apply {
    json.eachObject("reminders") { id, reminder -> reminders[id] = reminderOf(reminder) }
    json.optJSONObject("fired")?.let { all -> for (id in all.keys()) fired[id] = all.getLong(id) }
    json.eachObject("pending") { id, p ->
        pending[id] = Pending(
            p.getInt("base"),
            p.getInt("rev"),
            p.optJSONObject("before")?.let(::reminderOf),
            p.text("refused"),
        )
    }
    // optLong would read a string such as "3" as a number
    cursor = (json.opt("cursor") as? Number)?.toLong() ?: 0
    server = json.optJSONObject("server")?.let { Server(it.getString("url"), it.getString("token"), it.getString("name")) }
    json.optJSONObject("sync")?.let { s ->
        sync = SyncStatus(
            SyncState.entries.firstOrNull { it.stored == s.optString("state") } ?: SyncState.OFF,
            s.text("at"),
            s.text("ok_at"),
            s.text("message"),
        )
    }
    json.optJSONArray("times")?.let { all ->
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            times += TimeUse(t.getString("when"), t.getInt("count"), t.getLong("last"))
        }
    }
    json.optJSONArray("summary")?.let { all -> summary = List(all.length()) { all.getString(it) } }
    theme = if (json.optString("theme") == Theme.LIGHT.stored) Theme.LIGHT else Theme.DARK
    clock = Clock.of(json.optString("clock"))
    delay = json.optString("delay").let { stored ->
        val read = readDelay(stored)
        if (read is DelayChoice.Ok) read.delay else DEFAULT_DELAY
    }
}

/** Where the state is kept: a file on the phone, or memory in the tests. */
interface Storage {
    fun read(): String?
    fun write(text: String)
}

/**
 * The state, read once and written whole after each change. Changes run one after the other, so
 * an alarm, a sync and the screen never overwrite each other's changes.
 */
class Store(private val storage: Storage) {
    private var state: State? = null

    private fun current(): State =
        state ?: (storage.read()?.let { stateOf(JSONObject(it)) } ?: State()).also { state = it }

    /** A copy to read from, safe to keep while other changes go on. */
    @Synchronized
    fun read(): State = stateOf(current().toJson())

    @Synchronized
    fun <T> update(change: (State) -> T): T {
        val working = stateOf(current().toJson())
        val before = working.toJson().toString()
        val result = change(working)
        val after = working.toJson().toString()
        if (after != before) {
            storage.write(after)
            state = working
        }
        return result
    }
}
