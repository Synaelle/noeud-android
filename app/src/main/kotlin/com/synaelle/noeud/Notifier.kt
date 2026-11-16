// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.synaelle.noeud.lib.Reminder
import com.synaelle.noeud.lib.State
import com.synaelle.noeud.lib.SUMMARY_ABOVE
import com.synaelle.noeud.lib.delayTitle
import com.synaelle.noeud.lib.notificationText
import com.synaelle.noeud.lib.notifyAt
import com.synaelle.noeud.lib.stillDue
import com.synaelle.noeud.lib.summaryText
import com.synaelle.noeud.lib.summaryTitle

/** The notifications: one per reminder, or one summary when many come due at once. */
object Notifier {
    private const val CHANNEL = "reminders"
    const val SUMMARY = "summary"
    // every notification is told apart by its tag, the reminder's id
    private const val ID = 1
    private const val ACCENT = 0xFFE44C3E.toInt()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Reminders when they are due"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Whether notifications can show at all: allowed for the app and for the channel. */
    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun broadcast(context: Context, action: String, tag: String, ids: List<String>): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(action)
            // the data keeps each notification's intents apart
            .setData(Uri.parse("noeud://notification/$tag"))
            .putExtra(ActionReceiver.IDS, ids.toTypedArray())
            .putExtra(ActionReceiver.TAG, tag)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun open(context: Context, reminderId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("noeud://reminder/${reminderId ?: ""}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun builder(context: Context): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ACCENT)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)

    fun show(context: Context, state: State, due: List<Reminder>, now: Long) {
        if (due.isEmpty()) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val delay = delayTitle(state.delay)
        if (due.size > SUMMARY_ABOVE) {
            val ids = due.map { it.id }
            val notification = builder(context)
                .setContentTitle(summaryTitle(due, now))
                .setContentText(summaryText(due))
                .setStyle(Notification.BigTextStyle().bigText(summaryText(due)))
                .setContentIntent(open(context, null))
                // the one action delays every reminder in the summary
                .addAction(Notification.Action.Builder(null, delay, broadcast(context, ActionReceiver.DELAY, SUMMARY, ids)).build())
                .build()
            manager.notify(SUMMARY, ID, notification)
            return
        }
        val zone = Noeud.zone()
        for (reminder in due) {
            val text = notificationText(reminder, now, zone)
            val ids = listOf(reminder.id)
            val notification = builder(context)
                .setContentTitle(reminder.title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setWhen(notifyAt(reminder) ?: now)
                .setShowWhen(true)
                .setContentIntent(open(context, reminder.id))
                .addAction(Notification.Action.Builder(null, "Done", broadcast(context, ActionReceiver.DONE, reminder.id, ids)).build())
                .addAction(Notification.Action.Builder(null, delay, broadcast(context, ActionReceiver.DELAY, reminder.id, ids)).build())
                .build()
            manager.notify(reminder.id, ID, notification)
        }
    }

    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, ID)
    }

    /** Takes down notifications of reminders that were done, snoozed or deleted since. */
    fun clearStale(context: Context, state: State, now: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (shown in manager.activeNotifications) {
            val tag = shown.tag ?: continue
            // the summary stays while any of its reminders is still due
            val stale = if (tag == SUMMARY) state.summary.none { stillDue(state, it, now) } else !stillDue(state, tag, now)
            if (stale) manager.cancel(tag, shown.id)
        }
    }
}
