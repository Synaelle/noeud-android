// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

enum class SyncTone { OK, OFFLINE, ERROR, IDLE }

private fun count(amount: Long, word: String) = "$amount $word${if (amount == 1L) "" else "s"}"

/** "just now", "5 minutes ago", "yesterday". */
fun ago(at: String, now: Long): String {
    val seconds = Math.round((now - parseTime(at)) / 1000.0)
    if (seconds < 60) return "just now"
    val minutes = Math.round(seconds / 60.0)
    if (minutes < 60) return "${count(minutes, "minute")} ago"
    val hours = Math.round(minutes / 60.0)
    if (hours < 24) return "${count(hours, "hour")} ago"
    val days = Math.round(hours / 24.0)
    return if (days == 1L) "yesterday" else "$days days ago"
}

/** One sentence on where the sync stands, for the main screen and the settings. */
fun syncSummary(state: State, now: Long): Pair<SyncTone, String> {
    val pending = state.pending.size
    val waiting = when (pending) {
        0 -> ""
        1 -> " 1 change waits to be sent."
        else -> " $pending changes wait to be sent."
    }
    val sync = state.sync
    val last = sync.okAt?.let { " Last synced ${ago(it, now)}." } ?: ""
    return when (sync.state) {
        SyncState.OK -> SyncTone.OK to "Synced ${ago(sync.okAt ?: sync.at ?: timestamp(now), now)}.$waiting"
        SyncState.OFFLINE -> SyncTone.OFFLINE to "The server is out of reach.$last$waiting"
        SyncState.ERROR -> SyncTone.ERROR to "${sync.message ?: "The last sync failed."}$waiting"
        else -> SyncTone.IDLE to "Not synced yet.$waiting"
    }
}
