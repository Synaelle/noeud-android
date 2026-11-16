// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.synaelle.noeud.lib.delayedUntil
import com.synaelle.noeud.lib.markDone
import com.synaelle.noeud.lib.snooze

/** Runs the work off the main thread, keeping the phone awake until it is done. */
private fun BroadcastReceiver.work(context: Context, work: (Context) -> Unit) {
    val pending = goAsync()
    val app = context.applicationContext
    Noeud.inBackground {
        try {
            work(app)
        } finally {
            pending.finish()
        }
    }
}

/** The alarm for the next reminder. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = work(context) { Noeud.catchUp(it) }
}

/**
 * The phone started, Noeud was updated, or the clock or time zone changed: alarms are gone or
 * wrong, and whatever came due meanwhile notifies you now.
 */
class SystemReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) catchUp(context)
    }

    private fun catchUp(context: Context) = work(context) { app ->
        Noeud.catchUp(app)
        SyncJob.schedule(app)
        Noeud.syncNow(app)
    }
}

/** Done and Delay on a notification. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "com.synaelle.noeud.DONE"
        const val DELAY = "com.synaelle.noeud.DELAY"
        const val IDS = "ids"
        const val TAG = "tag"
    }

    override fun onReceive(context: Context, intent: Intent) = work(context) { app ->
        val ids = intent.getStringArrayExtra(IDS)?.toList().orEmpty()
        intent.getStringExtra(TAG)?.let { Notifier.cancel(app, it) }
        val now = System.currentTimeMillis()
        Noeud.change(app, syncAfter = false) { state ->
            // some may have been done or deleted since, here or on another device
            val live = ids.filter { id -> state.reminders[id]?.let { !it.deleted && it.doneAt == null } == true }
            when (intent.action) {
                DONE -> live.forEach { markDone(state, it, now) }
                DELAY -> {
                    val until = delayedUntil(state.delay, now)
                    live.forEach { snooze(state, it, until, now) }
                }
            }
        }
        Noeud.syncNow(app)
    }
}
