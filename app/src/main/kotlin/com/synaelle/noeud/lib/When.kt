// SPDX-License-Identifier: GPL-3.0-or-later

// What people type for "when": a duration ("2h", "8h52", "7w1h3s"), a time ("18:00", "6pm"),
// a day ("tomorrow 9:00", "next monday"), or the date picker's "2026-11-13 14:30". English only.

package com.synaelle.noeud.lib

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** `delay` is set when the time was given as a duration from now. */
sealed interface When {
    data class Ok(val at: Long, val delay: Long? = null) : When
    data class Error(val error: String) : When
}

/** A duration read from text, or why it cannot be one. */
sealed interface Duration {
    data class Ms(val ms: Long) : Duration
    data class Error(val error: String) : Duration
}

private val unitMs = mapOf(
    's' to 1_000L,
    'm' to 60_000L,
    'h' to 3_600_000L,
    'd' to 86_400_000L,
    'w' to 604_800_000L,
)
const val MAX_AHEAD_MS = 10 * 365 * 86_400_000L

private val weekdays =
    listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

// a leading "in" is the Discord bot's way and is let through quietly
private val DURATION = Regex("""^(?:in\s*)?((?:\d+\s*[wdhms]\s*)+)(\d+)?$""")
private val DURATION_PART = Regex("""(\d+)\s*([wdhms])""")
private val smaller = mapOf('w' to 'd', 'd' to 'h', 'h' to 'm', 'm' to 's')
private val DATE_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[t ](\d{1,2}):(\d{2})$""")
private val DAY_TIME = Regex(
    """^(?:(today|tonight|tomorrow|(next\s+)?(${weekdays.joinToString("|")}))\s*)?(?:at\s*)?""" +
        """(?:(\d{1,2})(?::(\d{2}))?\s*(am|pm)?)?$""",
)

// unit words as the app writes them ("10 min", "2 hours", "1 wk") and as people type them
private val UNIT_WORDS = Regex(
    """(\d)\s*(weeks?|wks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])""",
)
private val SPACES = Regex("""\s+""")

/** "8 h 52 min" and "2 hours" written the short way, "8h52m" and "2h". */
private fun shortUnits(input: String): String =
    UNIT_WORDS.replace(input) { "${it.groupValues[1]}${it.groupValues[2][0]}" }

private fun tidy(text: String): String = text.trim().lowercase().replace(SPACES, " ")

const val ENTER_A_TIME = "Enter a time, like 2h, 18:00 or tomorrow 9:00"

private fun ahead(at: Long, now: Long): When = when {
    at <= now -> When.Error("That time has passed")
    at - now > MAX_AHEAD_MS -> When.Error("Keep it under 10 years")
    else -> When.Ok(at)
}

/**
 * A duration in any unit order, each unit once: "2h", "1h 30m", "7w1h3s". A bare number at the
 * end takes the unit below the one before it, so "8h52" is 8 h 52 min and "1d6" is 1 day 6 h.
 * Null when the text is not written as a duration at all.
 */
fun parseDuration(text: String): Duration? {
    val found = DURATION.find(shortUnits(tidy(text))) ?: return null
    val seen = mutableSetOf<Char>()
    var total = 0L
    var last = 's'
    for (part in DURATION_PART.findAll(found.groupValues[1])) {
        val unit = part.groupValues[2][0]
        if (!seen.add(unit)) return Duration.Error("Use each unit once, like 1h30m")
        total += part.groupValues[1].toLong() * unitMs.getValue(unit)
        last = unit
    }
    if (found.groups[2] != null) {
        val unit = smaller[last]
        if (unit == null || unit in seen) return Duration.Error("Add a unit to the last number")
        total += found.groupValues[2].toLong() * unitMs.getValue(unit)
    }
    if (total == 0L) return Duration.Error("Enter a time after now, like 10m")
    if (total > MAX_AHEAD_MS) return Duration.Error("Keep it under 10 years")
    return Duration.Ms(total)
}

private fun clock(hourText: String, minuteText: String?, half: String?): LocalTime? {
    // a bare number is too ambiguous: "9" could be a count
    if (minuteText == null && half == null) return null
    var hour = hourText.toInt()
    val minute = minuteText?.toInt() ?: 0
    if (half != null) {
        if (hour < 1 || hour > 12) return null
        hour = hour % 12 + if (half == "pm") 12 else 0
    }
    if (hour > 23 || minute > 59) return null
    return LocalTime.of(hour, minute)
}

/** A duration is shown to the second when it has seconds or is under an hour, else to the minute. */
private fun showsSeconds(delay: Long): Boolean =
    delay % unitMs.getValue('m') != 0L || delay < unitMs.getValue('h')

private fun MatchResult.group(index: Int): String? = groups[index]?.value

private fun ZonedDateTime.ms(): Long = toInstant().toEpochMilli()

/** Resolves what was typed to a time in milliseconds, or says what is wrong in a few words. */
fun parseWhen(text: String, now: Long, zone: ZoneId): When {
    val input = tidy(text)
    if (input.isEmpty()) return When.Error(ENTER_A_TIME)

    when (val duration = parseDuration(input)) {
        is Duration.Error -> return When.Error(duration.error)
        is Duration.Ms -> {
            val minute = unitMs.getValue('m')
            // shown to the minute, it is due on that minute, not seconds after it
            val at = if (showsSeconds(duration.ms)) {
                now + duration.ms
            } else {
                Math.floorDiv(now + duration.ms, minute) * minute
            }
            return When.Ok(at, duration.ms)
        }
        null -> {}
    }

    DATE_TIME.find(input)?.let { found ->
        val (year, month, day, hour, minute) = found.destructured.toList().map { it.toInt() }
        return try {
            ahead(LocalDateTime.of(year, month, day, hour, minute).atZone(zone).ms(), now)
        } catch (_: DateTimeException) {
            When.Error("That date does not exist")
        }
    }

    val asDay = DAY_TIME.find(input) ?: return When.Error(ENTER_A_TIME)
    val dayText = asDay.group(1)
    val next = asDay.group(2)
    val weekday = asDay.group(3)
    val hourText = asDay.group(4)
    // "tonight 21:00" is how the quick picks write a late time today
    val day = if (dayText == "tonight") "today" else dayText
    if (day == null && hourText == null) return When.Error(ENTER_A_TIME)
    val time = if (hourText == null) {
        LocalTime.of(9, 0)
    } else {
        clock(hourText, asDay.group(5), asDay.group(6))
    } ?: return When.Error("Enter the time like 18:00 or 6pm")
    if (day == "today" && hourText == null) return When.Error("Add a time, like today 18:00")

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun on(date: LocalDate): Long = date.atTime(time).atZone(zone).ms()
    // the night the clocks go back has some times twice: the second one counts once the first
    // has gone by
    fun onToday(): Long {
        val first = on(today)
        if (first > now) return first
        return today.atTime(time).atZone(zone).withLaterOffsetAtOverlap().ms()
    }
    if (day == null) {
        // a time alone is today, or tomorrow once it has passed
        val at = onToday()
        return ahead(if (at > now) at else on(today.plusDays(1)), now)
    }
    if (day == "today") return ahead(onToday(), now)
    if (day == "tomorrow") return ahead(on(today.plusDays(1)), now)
    // a weekday is today while its time is still ahead, else the coming one; "next" skips today
    val target = weekdays.indexOf(weekday) + 1
    val days = (target - today.dayOfWeek.value + 7) % 7
    if (days == 0 && next == null && onToday() > now) return ahead(onToday(), now)
    return ahead(on(today.plusDays(if (days == 0) 7L else days.toLong())), now)
}

private fun split(ms: Long): List<Pair<Char, Long>> {
    var rest = maxOf(0L, ms)
    val parts = mutableListOf<Pair<Char, Long>>()
    for (unit in "wdhms") {
        val amount = rest / unitMs.getValue(unit)
        if (amount > 0) parts += unit to amount
        rest -= amount * unitMs.getValue(unit)
    }
    return parts
}

/** A duration written from the largest unit down: 120m is "2h", 90m is "1h30m". */
fun durationText(ms: Long): String = split(ms).joinToString("") { (unit, amount) -> "$amount$unit" }

private val unitWords = mapOf('w' to "wk", 'd' to "d", 'h' to "h", 'm' to "min", 's' to "s")

/** "8 h 52 min 3 s", to the second or, without `seconds`, to the minute. */
fun durationWords(ms: Long, seconds: Boolean = true): String {
    val minute = unitMs.getValue('m')
    val parts = split(if (seconds) ms else ms / minute * minute)
    if (parts.isEmpty()) return if (seconds) "0 s" else "under a minute"
    return parts.joinToString(" ") { (unit, amount) -> "$amount ${unitWords.getValue(unit)}" }
}

/**
 * One way to write each time that can be used again: "2h", "18:00", "tomorrow 9:00" or
 * "monday 9:00", so that "2h", "1h60m" and "120m" count as the same choice. Null for a one-off
 * date from the picker and for anything that does not read as a time.
 */
fun normalizeWhen(text: String): String? {
    val input = tidy(text)
    when (val duration = parseDuration(input)) {
        is Duration.Ms -> return durationText(duration.ms)
        is Duration.Error -> return null
        null -> {}
    }
    val asDay = DAY_TIME.find(input) ?: return null
    val dayText = asDay.group(1)
    val hourText = asDay.group(4)
    val day = if (dayText == "tonight") "today" else dayText
    if (day == null && hourText == null) return null
    if (day == "today" && hourText == null) return null
    val time = if (hourText == null) {
        LocalTime.of(9, 0)
    } else {
        clock(hourText, asDay.group(5), asDay.group(6)) ?: return null
    }
    val hhmm = "${time.hour}:${time.minute.toString().padStart(2, '0')}"
    if (day == null || day == "today") return hhmm
    return "${if (day == "tomorrow") "tomorrow" else asDay.group(3)} $hhmm"
}

/**
 * Exactly when it notifies you, then how long until then: "Thursday 12 November at 22:33:12 ·
 * in 8 h 52 min 3 s". Seconds show for a duration that has them or is under an hour.
 */
fun formatPreview(at: Long, delay: Long?, now: Long, zone: ZoneId): String {
    val seconds = delay != null && showsSeconds(delay)
    val date = formatLongDate(at, now, zone, seconds)
    if (delay == null && at <= now) return "$date · ${durationWords(now - at, false)} ago"
    // a duration reads as typed, even when it is due on the minute just before
    return "$date · in ${durationWords(delay ?: (at - now), seconds)}"
}

/** The calendar's pick written as text the field understands, "2026-11-13 14:30". */
fun pickerText(at: LocalDateTime): String = String.format(
    Locale.ROOT,
    "%04d-%02d-%02d %02d:%02d",
    at.year,
    at.monthValue,
    at.dayOfMonth,
    at.hour,
    at.minute,
)
