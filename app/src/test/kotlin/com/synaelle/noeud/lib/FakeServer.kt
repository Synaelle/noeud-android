// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.json.JSONArray
import org.json.JSONObject

private val editable = listOf("title", "notes", "due_at", "time_zone", "repeat", "snoozed_until", "done_at")

/** A Noeud server in memory that answers as the API reference says. */
class FakeServer(
    val origin: String = "https://noeud.test",
    var token: String = "noeud_test",
    private val clock: () -> Long,
) : Transport {
    val reminders = LinkedHashMap<String, Reminder>()
    private val changedAt = LinkedHashMap<String, Long>()
    private var counter = 0L
    var offline = false
    /** The next request reaches the server but its answer is lost on the way back. */
    var loseNextAnswer = false
    /** Creates and changes are refused with this message, as the server does invalid ones. */
    var refuse: String? = null
    /** Runs once, when the next request is about to reach the server. */
    var gate: (() -> Unit)? = null
    val requests = mutableListOf<String>()

    /** A reminder made by another app. */
    fun create(title: String, dueAt: String): Reminder {
        val answer = handle("POST", "/v1/reminders", emptyMap(), JSONObject().put("title", title).put("due_at", dueAt))
        return reminderOf(answer.data!!)
    }

    /** A change made by another app. */
    fun edit(id: String, change: (Reminder) -> Unit) {
        val reminder = reminders[id] ?: error("no $id")
        change(reminder)
        reminder.version += 1
        changedAt[id] = ++counter
    }

    private fun json(status: Int, body: JSONObject) = Answer(status, body)

    private fun problem(status: Int, error: String, message: String, reminder: Reminder? = null): Answer {
        val body = JSONObject().put("error", error).put("message", message)
        if (reminder != null) body.put("reminder", reminder.toJson())
        return json(status, body)
    }

    private fun now(): String = timestamp(clock())

    private fun iso(value: String): String = timestamp(parseTime(value))

    private fun save(reminder: Reminder) {
        reminders[reminder.id] = reminder
        changedAt[reminder.id] = ++counter
    }

    private fun handle(method: String, path: String, query: Map<String, String>, body: JSONObject?): Answer {
        if (path == "/v1/token" && method == "GET") return json(200, JSONObject().put("name", "android"))
        if (path == "/v1/reminders" && method == "GET") {
            val since = query["since"]?.toLong() ?: 0
            val limit = query["limit"]?.toInt() ?: 500
            val changed = changedAt.entries.filter { it.value > since }.sortedBy { it.value }
            val page = changed.take(limit)
            return json(
                200,
                JSONObject()
                    .put("reminders", JSONArray(page.map { reminders.getValue(it.key).toJson() }))
                    .put("cursor", page.lastOrNull()?.value ?: since)
                    .put("has_more", changed.size > limit),
            )
        }
        if (path == "/v1/reminders" && method == "POST") {
            val title = body?.opt("title") as? String
            if (title == null || title.isEmpty() || title.length > 500) {
                return problem(400, "invalid_request", "title must be 1 to 500 characters")
            }
            val id = body.opt("id") as? String ?: uuidv7(clock())
            reminders[id]?.let { return json(200, it.toJson()) }
            val dueAt = iso(body.getString("due_at"))
            val repeat = if (body.isNull("repeat")) {
                null
            } else {
                val asked = body.getJSONObject("repeat")
                Repeat(
                    RepeatUnit.of(asked.getString("every")),
                    asked.optInt("interval", 1),
                    iso(if (asked.isNull("start")) dueAt else asked.getString("start")),
                )
            }
            val reminder = Reminder(
                id = id,
                title = title,
                notes = if (body.isNull("notes")) null else body.getString("notes"),
                dueAt = dueAt,
                timeZone = body.optString("time_zone", "UTC"),
                repeat = repeat,
                snoozedUntil = null,
                doneAt = null,
                deleted = false,
                version = 1,
                createdAt = now(),
                updatedAt = now(),
                createdBy = "android",
            )
            save(reminder)
            return json(201, reminder.toJson())
        }
        val match = Regex("""^/v1/reminders/([^/]+)$""").find(path)
        val reminder = match?.let { reminders[it.groupValues[1]] }
            ?: return problem(404, "not_found", "no such reminder")
        if (method == "PATCH") {
            if (reminder.deleted) return problem(404, "not_found", "no such reminder")
            if (body?.optInt("version", -1) != reminder.version) {
                return problem(409, "conflict", "the reminder was changed in the meantime", reminder)
            }
            val fields = reminder.toJson()
            for (field in editable) if (body.has(field)) fields.put(field, body.get(field))
            if (body.has("due_at") && !body.has("snoozed_until")) fields.put("snoozed_until", JSONObject.NULL)
            val changed = reminderOf(fields)
            changed.version += 1
            changed.updatedAt = now()
            save(changed)
            return json(200, changed.toJson())
        }
        if (method == "DELETE") {
            val version = query["version"]
            if (version != null && version.toInt() != reminder.version) {
                return problem(409, "conflict", "the reminder was changed in the meantime", reminder)
            }
            reminder.deleted = true
            reminder.title = ""
            reminder.notes = null
            reminder.version += 1
            save(reminder)
            return Answer(204, null)
        }
        return problem(400, "invalid_request", "unknown route")
    }

    override fun request(server: Server, method: String, path: String, body: JSONObject?): Answer {
        requests += "$method $path"
        if (offline || server.url != origin) throw Offline()
        if (server.token != token) return problem(401, "unauthorized", "bad token")
        gate?.let {
            gate = null
            it()
        }
        if (refuse != null && (method == "POST" || method == "PATCH")) {
            return problem(400, "invalid_request", refuse!!)
        }
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        // the server keeps its own copy, as JSON would
        val answer = handle(method, route, query, body?.let { JSONObject(it.toString()) })
        if (loseNextAnswer) {
            loseNextAnswer = false
            throw Offline()
        }
        return answer
    }
}
