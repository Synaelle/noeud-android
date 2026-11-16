// SPDX-License-Identifier: GPL-3.0-or-later

// The times this person uses, so the quick picks and the snooze choices offer theirs first.

package com.synaelle.noeud.lib

import java.time.Instant
import java.time.ZoneId

/** One reusable time (see normalizeWhen), how often it was chosen and when last. */
data class TimeUse(val `when`: String, var count: Int, var last: Long)

const val KEEP_TIMES = 50
const val SHOWN_TIMES = 5

val addDefaults = listOf("10m", "1h", "18:00", "tomorrow 9:00", "monday 9:00")
val snoozeDefaults = listOf("10m", "1h", "1d", "tomorrow 9:00", "1w")

/** Counts a time chosen for a new reminder or a snooze. A one-off date counts for nothing. */
fun noteTimeUse(state: State, text: String?, now: Long) {
    val normalized = text?.let(::normalizeWhen) ?: return
    val used = state.times.find { it.`when` == normalized }
    if (used != null) {
        used.count += 1
        used.last = now
    } else {
        state.times += TimeUse(normalized, 1, now)
    }
    if (state.times.size > KEEP_TIMES) state.times = byUse(state.times).take(KEEP_TIMES).toMutableList()
}

private fun byUse(times: List<TimeUse>): List<TimeUse> =
    times.sortedWith(compareByDescending<TimeUse> { it.count }.thenByDescending { it.last })

private val TIME_OF_DAY =
    Regex("""^(?:(tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday) )?(\d{1,2}):(\d{2})$""")

/** A time of day ("18:00") that has already gone by today, so it is not worth offering. */
private fun passed(time: String, now: Long, zone: ZoneId): Boolean {
    val found = TIME_OF_DAY.find(time) ?: return false
    if (found.groups[1] != null) return false
    val today = Instant.ofEpochMilli(now).atZone(zone)
    val at = today.withHour(found.groupValues[2].toInt())
        .withMinute(found.groupValues[3].toInt())
        .withSecond(0)
        .withNano(0)
    return at.toInstant().toEpochMilli() <= now
}

/** The most used times first, ties to the most recent, then the defaults in their order. */
fun rankTimes(times: List<TimeUse>, defaults: List<String>, now: Long, zone: ZoneId): List<String> {
    val shown = mutableListOf<String>()
    for (time in byUse(times).map { it.`when` } + defaults) {
        if (shown.size == SHOWN_TIMES) break
        if (time !in shown && !passed(time, now, zone)) shown += time
    }
    return shown
}

/** Before any history, the add form starts here. */
const val FIRST_ADD_TIME = "1h"

/** Where the add form's When starts: the most used time still ahead, else in an hour. */
fun startingTime(times: List<TimeUse>, now: Long, zone: ZoneId): String =
    rankTimes(times, emptyList(), now, zone).firstOrNull() ?: FIRST_ADD_TIME

private fun plural(amount: Long, word: String) = "$amount $word${if (amount == 1L) "" else "s"}"

/** "10 min", "2 hours", "3 days", "1 week", or "8 h 52 min" when it does not fit one unit. */
fun durationLabel(time: String): String {
    val ms = (parseDuration(time) as? Duration.Ms)?.ms ?: return time
    val hour = 3_600_000L
    val day = 86_400_000L
    if (ms < hour && ms % 60_000L == 0L) return "${ms / 60_000L} min"
    if (ms % hour == 0L && ms / hour < 48) return plural(ms / hour, "hour")
    if (ms % day == 0L && (ms / day) % 7 == 0L) return plural(ms / day / 7, "week")
    if (ms % day == 0L) return plural(ms / day, "day")
    return durationWords(ms)
}

/** The button text for a normalized time: "2 hours", "Today 18:00", "Friday 9:00". */
fun timeLabel(time: String): String {
    if (parseDuration(time) != null) return durationLabel(time)
    val found = TIME_OF_DAY.find(time) ?: return time
    val hour = found.groupValues[2].toInt()
    val text = formatHour(hour, found.groupValues[3].toInt())
    val day = found.groups[1]?.value ?: return "${if (hour >= 20) "Tonight" else "Today"} $text"
    return "${day.replaceFirstChar { it.uppercase() }} $text"
}
