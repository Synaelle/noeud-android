// SPDX-License-Identifier: GPL-3.0-or-later

// How long "Delay" puts a reminder off, from a notification or the app. Chosen in the settings,
// written like a duration in When ("3h", "1d12h") and stored in its shortest form.

package com.synaelle.noeud.lib

const val DEFAULT_DELAY = "1d"

/** The lengths the settings offer before a custom one. */
val delayChoices = listOf("1h", "3h", "1d", "1w")

sealed interface DelayChoice {
    data class Ok(val delay: String, val ms: Long) : DelayChoice
    data class Error(val error: String) : DelayChoice
}

/** A typed length as it is stored ("36h" is "1d12h"), or why it cannot be one. */
fun readDelay(text: String): DelayChoice = when (val parsed = parseDuration(text)) {
    null -> DelayChoice.Error("Enter a length like 2h or 1d12h")
    is Duration.Error -> DelayChoice.Error(parsed.error)
    is Duration.Ms -> DelayChoice.Ok(durationText(parsed.ms), parsed.ms)
}

/** When a reminder delayed now comes back. */
fun delayedUntil(delay: String, now: Long): Long = now + ((readDelay(delay) as? DelayChoice.Ok)?.ms ?: 0)

/** "Delay 24 hours", "Delay 3 hours", "Delay 1 week", "Delay 36 hours". */
fun delayTitle(delay: String): String = "Delay ${durationLabel(delay)}"
