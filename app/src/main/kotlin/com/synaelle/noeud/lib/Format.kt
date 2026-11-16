// SPDX-License-Identifier: GPL-3.0-or-later

// Dates and times as the app writes them, in English like the rest of it, 24-hour unless 12-hour
// is chosen in the settings.

package com.synaelle.noeud.lib

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class Clock(val stored: String) {
    H24("24h"),
    H12("12h");

    companion object {
        fun of(stored: String?): Clock = if (stored == H12.stored) H12 else H24
    }
}

/** The chosen clock, applied when the settings load and when it is changed. */
@Volatile
var clock: Clock = Clock.H24

private fun pattern(text: String): DateTimeFormatter = DateTimeFormatter.ofPattern(text, Locale.ENGLISH)

private val shortDay = pattern("EEE d MMM")
private val shortDayYear = pattern("EEE d MMM yyyy")
private val longDay = pattern("EEEE d MMMM")
private val longDayYear = pattern("EEEE d MMMM yyyy")
private val time24 = pattern("H:mm")
private val time24Seconds = pattern("H:mm:ss")
private val time12 = pattern("h:mm a")
private val time12Seconds = pattern("h:mm:ss a")

private fun zoned(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

private fun localDate(ms: Long, zone: ZoneId): LocalDate = zoned(ms, zone).toLocalDate()

/** "Today", "Tomorrow", "Yesterday" or a short date like "Wed 18 Nov". */
fun formatDay(ms: Long, now: Long, zone: ZoneId): String {
    val day = localDate(ms, zone)
    val today = localDate(now, zone)
    return when (ChronoUnit.DAYS.between(today, day)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> (if (day.year == today.year) shortDay else shortDayYear).format(day)
    }
}

/** "18:00" or "6:00 PM". */
fun formatTime(ms: Long, zone: ZoneId, seconds: Boolean = false): String {
    val format = when (clock) {
        Clock.H24 -> if (seconds) time24Seconds else time24
        Clock.H12 -> if (seconds) time12Seconds else time12
    }
    return format.format(zoned(ms, zone))
}

/** A time of day in the chosen clock, "18:00" or "6:00 PM", for button labels. */
fun formatHour(hour: Int, minute: Int = 0): String {
    val at = LocalDate.of(2000, 1, 1).atTime(hour, minute)
    return (if (clock == Clock.H24) time24 else time12).format(at)
}

/** "Today 14:00" for lists. */
fun formatWhen(ms: Long, now: Long, zone: ZoneId): String =
    "${formatDay(ms, now, zone)} ${formatTime(ms, zone)}"

/** "at 14:00" today, "yesterday at 14:00" or "Tue 10 Nov at 14:00" otherwise, for sentences. */
fun formatAt(ms: Long, now: Long, zone: ZoneId): String {
    val day = formatDay(ms, now, zone)
    if (day == "Today") return "at ${formatTime(ms, zone)}"
    val lower = if (day == "Tomorrow" || day == "Yesterday") day.lowercase() else day
    return "$lower at ${formatTime(ms, zone)}"
}

/** "Thursday 12 November at 22:33", with the year when it is not this one. */
fun formatLongDate(ms: Long, now: Long, zone: ZoneId, seconds: Boolean = false): String {
    val at = zoned(ms, zone)
    val day = if (at.year == zoned(now, zone).year) longDay else longDayYear
    return "${day.format(at)} at ${formatTime(ms, zone, seconds)}"
}

fun formatRepeat(repeat: Repeat): String {
    val unit = repeat.every.word
    return if (repeat.interval == 1) "Every $unit" else "Every ${repeat.interval} ${unit}s"
}
