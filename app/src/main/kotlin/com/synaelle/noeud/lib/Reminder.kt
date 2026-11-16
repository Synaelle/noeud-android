// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONObject

enum class RepeatUnit(val word: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year");

    companion object {
        fun of(word: String): RepeatUnit = entries.first { it.word == word }
    }
}

data class Repeat(val every: RepeatUnit, val interval: Int, val start: String)

/** A reminder as the server sends it, see the API reference in the main Noeud repository. */
data class Reminder(
    val id: String,
    var title: String,
    var notes: String?,
    var dueAt: String,
    var timeZone: String,
    var repeat: Repeat?,
    var snoozedUntil: String?,
    var doneAt: String?,
    var deleted: Boolean,
    /** 0 until the server has seen the reminder. */
    var version: Int,
    val createdAt: String,
    var updatedAt: String,
    val createdBy: String?,
)

const val TITLE_MAX = 500
const val NOTES_MAX = 10_000
const val INTERVAL_MAX = 1000

/** Length as the server counts it, one per character, so an emoji counts once. */
fun textLength(text: String): Int = text.codePointCount(0, text.length)

/** Why the server would refuse these fields, or null when they are fine. */
fun fieldsProblem(title: String? = null, notes: String? = null, interval: Int? = null): String? {
    if (title != null) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return "Enter what to remember"
        if (textLength(trimmed) > TITLE_MAX) return "Keep the title under $TITLE_MAX characters"
    }
    if (notes != null && textLength(notes.trim()) > NOTES_MAX) {
        return "Keep the notes under 10,000 characters"
    }
    if (interval != null && interval !in 1..INTERVAL_MAX) return "Repeat every 1 to $INTERVAL_MAX"
    return null
}

/** A time as the server writes it, or any RFC 3339 time, in milliseconds. */
fun parseTime(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

/** When the reminder is next due, in milliseconds, or null when it never will. */
fun notifyAt(reminder: Reminder): Long? {
    if (reminder.deleted || reminder.doneAt != null) return null
    return parseTime(reminder.snoozedUntil ?: reminder.dueAt)
}

/** A time the way the server writes it, UTC to the second. */
fun timestamp(ms: Long): String = Instant.ofEpochMilli(Math.floorDiv(ms, 1000L) * 1000L).toString()

private fun time(value: String?): Long? = value?.let(::parseTime)

/** The fields a client can change, compared to tell whether the server has the local state. */
fun sameContent(a: Reminder, b: Reminder): Boolean =
    a.title == b.title &&
        a.notes == b.notes &&
        time(a.dueAt) == time(b.dueAt) &&
        a.timeZone == b.timeZone &&
        time(a.snoozedUntil) == time(b.snoozedUntil) &&
        time(a.doneAt) == time(b.doneAt) &&
        if (a.repeat == null || b.repeat == null) {
            a.repeat == b.repeat
        } else {
            a.repeat!!.every == b.repeat!!.every &&
                a.repeat!!.interval == b.repeat!!.interval &&
                time(a.repeat!!.start) == time(b.repeat!!.start)
        }

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

fun repeatJson(repeat: Repeat?): Any = repeat?.let {
    JSONObject().put("every", it.every.word).put("interval", it.interval).put("start", it.start)
} ?: JSONObject.NULL

fun repeatOf(json: JSONObject, key: String): Repeat? {
    if (json.isNull(key)) return null
    val repeat = json.getJSONObject(key)
    return Repeat(
        RepeatUnit.of(repeat.getString("every")),
        repeat.optInt("interval", 1),
        repeat.getString("start"),
    )
}

fun Reminder.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("notes", notes ?: JSONObject.NULL)
    .put("due_at", dueAt)
    .put("time_zone", timeZone)
    .put("repeat", repeatJson(repeat))
    .put("snoozed_until", snoozedUntil ?: JSONObject.NULL)
    .put("done_at", doneAt ?: JSONObject.NULL)
    .put("deleted", deleted)
    .put("version", version)
    .put("created_at", createdAt)
    .put("updated_at", updatedAt)
    .put("created_by", createdBy ?: JSONObject.NULL)

fun reminderOf(json: JSONObject): Reminder = Reminder(
    id = json.getString("id"),
    title = json.getString("title"),
    notes = json.text("notes"),
    dueAt = json.getString("due_at"),
    timeZone = json.getString("time_zone"),
    repeat = repeatOf(json, "repeat"),
    snoozedUntil = json.text("snoozed_until"),
    doneAt = json.text("done_at"),
    deleted = json.optBoolean("deleted", false),
    version = json.getInt("version"),
    createdAt = json.getString("created_at"),
    updatedAt = json.getString("updated_at"),
    createdBy = json.text("created_by"),
)
