// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** One exact alarm, for the next reminder due. It is set again after every change and every fire. */
object Alarms {
    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Whether Android lets Noeud notify on the minute rather than some minutes late. */
    fun exact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = intent(context)
        when {
            at == null -> alarms.cancel(pending)
            exact(context) -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}
