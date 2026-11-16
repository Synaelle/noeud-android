// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.synaelle.noeud.lib.State
import com.synaelle.noeud.lib.Storage
import com.synaelle.noeud.lib.Store
import com.synaelle.noeud.lib.Sync
import com.synaelle.noeud.lib.clock
import com.synaelle.noeud.lib.dueReminders
import com.synaelle.noeud.lib.markFired
import com.synaelle.noeud.lib.nextAlarm
import com.synaelle.noeud.lib.SUMMARY_ABOVE
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneId
import java.util.concurrent.Executors

/** The reminders kept in one file in the app's own storage, replaced whole on each write. */
private class FileStorage(context: Context) : Storage {
    private val file = AtomicFile(File(context.filesDir, "noeud.json"))

    override fun read(): String? = try {
        file.readFully().decodeToString()
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(text: String) {
        val out = file.startWrite()
        try {
            out.write(text.encodeToByteArray())
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }
}

/** The app's one store, and what happens after every change: notify, schedule, sync. */
object Noeud {
    private lateinit var shared: Store
    private lateinit var syncer: Sync
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun store(context: Context): Store {
        if (!::shared.isInitialized) {
            shared = Store(FileStorage(context.applicationContext))
            syncer = Sync(shared, HttpTransport(), System::currentTimeMillis)
            clock = shared.read().clock
        }
        return shared
    }

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun read(context: Context): State = store(context).read()

    /** The screen listens while it is shown, to redraw after a sync or a notification action. */
    fun listen(listener: () -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /**
     * Notifies whatever came due and was not notified yet, then sets the alarm for the next one
     * and takes down notifications that no longer have a reason to show.
     */
    fun catchUp(context: Context) {
        val now = System.currentTimeMillis()
        val due = store(context).update { state ->
            val due = dueReminders(state, now)
            if (due.size > SUMMARY_ABOVE) state.summary = due.map { it.id }
            markFired(state, due, now)
            due
        }
        val state = read(context)
        Notifier.show(context, state, due, now)
        Alarms.schedule(context, nextAlarm(state, now))
        Notifier.clearStale(context, state, now)
    }

    /**
     * Applies a change from the screen or a notification, then notifies and schedules. The sync
     * follows in the background, unless the caller runs it itself.
     */
    fun <T> change(context: Context, syncAfter: Boolean = true, change: (State) -> T): T {
        val result = store(context).update(change)
        clock = read(context).clock
        catchUp(context)
        changed()
        if (syncAfter) sync(context)
        return result
    }

    /** Syncs now, on the calling thread, then catches up with what it brought. */
    fun syncNow(context: Context) {
        if (read(context).server == null) return
        store(context)
        syncer.sync()
        catchUp(context)
        changed()
    }

    /** Syncs in the background when there is a server. */
    fun sync(context: Context, done: (() -> Unit)? = null) {
        val app = context.applicationContext
        background.execute {
            try {
                syncNow(app)
            } finally {
                done?.invoke()
            }
        }
    }

    /** Runs work off the main thread, one piece after the other. */
    fun inBackground(work: () -> Unit) = background.execute(work)
}
