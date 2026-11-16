// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// average lengths, only used to guess how many occurrences have passed
private val approximateMs = mapOf(
    RepeatUnit.DAY to 86_400_000L,
    RepeatUnit.WEEK to 604_800_000L,
    RepeatUnit.MONTH to 2_629_746_000L,
    RepeatUnit.YEAR to 31_556_952_000L,
)

/**
 * Occurrence n in milliseconds, counted from the start on the calendar of the time zone. The 31st
 * falls on the last day of shorter months, and a time inside a clock change moves forward by the
 * change, as the API asks.
 */
fun occurrence(repeat: Repeat, zone: ZoneId, n: Long): Long {
    val start = Instant.ofEpochMilli(parseTime(repeat.start)).atZone(zone)
    val amount = n * repeat.interval
    val local = start.toLocalDateTime().let {
        when (repeat.every) {
            RepeatUnit.DAY -> it.plusDays(amount)
            RepeatUnit.WEEK -> it.plusWeeks(amount)
            RepeatUnit.MONTH -> it.plusMonths(amount)
            RepeatUnit.YEAR -> it.plusYears(amount)
        }
    }
    // `of` takes the earlier offset in an overlap and moves forward in a gap, as Temporal does
    return ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
}

/** The first occurrence strictly after `after`. */
fun nextOccurrence(repeat: Repeat, zone: ZoneId, after: Long): Long {
    val elapsed = after - parseTime(repeat.start)
    var n = maxOf(0L, Math.floorDiv(elapsed, approximateMs.getValue(repeat.every) * repeat.interval) - 1)
    while (n > 0 && occurrence(repeat, zone, n - 1) > after) n--
    while (occurrence(repeat, zone, n) <= after) n++
    return occurrence(repeat, zone, n)
}
