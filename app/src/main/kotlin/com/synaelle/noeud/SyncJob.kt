// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Syncs every 15 minutes while a server is set and the network is up, so reminders added
 * elsewhere notify you here even when the app is not opened. 15 minutes is Android's shortest.
 */
class SyncJob : JobService() {
    companion object {
        private const val ID = 1
        private const val PERIOD_MS = 15 * 60_000L

        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (Noeud.read(context).server == null) {
                jobs.cancel(ID)
                return
            }
            if (jobs.getPendingJob(ID) != null) return
            jobs.schedule(
                JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Noeud.sync(applicationContext) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
