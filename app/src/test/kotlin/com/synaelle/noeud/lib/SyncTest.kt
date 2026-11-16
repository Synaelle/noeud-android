// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun later(minutes: Int) = timestamp(T0 + minutes * 60_000L)

class SyncTest {
    private val session = Session()
    private val server = session.server
    private val store = session.store

    private fun sync() = session.sync.sync()

    private fun connect() = session.connectServer()

    private fun add(title: String, dueAt: String = later(60)): String = session.handle {
        addReminder(it, NewReminder(title, null, dueAt, "Europe/Paris", null), T0).id
    }

    @Test
    fun `sends the reminders made before connecting, under their own ids`() {
        val first = add("Buy bread")
        val second = add("Call mum")
        connect()
        assertEquals(listOf(first, second).sorted(), server.reminders.keys.sorted())
        val state = store.read()
        assertTrue(state.pending.isEmpty())
        assertEquals(1, state.reminders[first]?.version)
        assertEquals("android", state.reminders[first]?.createdBy)
        assertEquals(SyncState.OK, state.sync.state)
    }

    @Test
    fun `creates once when an answer is lost and the create is sent again`() {
        connect()
        server.loseNextAnswer = true
        // the sync after the change loses its answer, the one asked meanwhile sends the create again
        val id = add("Buy bread")
        sync()
        assertEquals(1, server.reminders.size)
        assertEquals(2, server.requests.count { it == "POST /v1/reminders" })
        assertEquals(1, store.read().reminders[id]?.version)
    }

    @Test
    fun `keeps changes made offline and sends them when the server is back`() {
        connect()
        server.offline = true
        val id = add("Buy bread")
        session.handle { snooze(it, id, T0 + 90 * 60_000, T0) }
        sync()
        var state = store.read()
        assertEquals(SyncState.OFFLINE, state.sync.state)
        assertEquals(listOf(id), state.pending.keys.toList())
        assertEquals("Buy bread", state.reminders[id]?.title)

        server.offline = false
        sync()
        state = store.read()
        assertEquals(SyncState.OK, state.sync.state)
        assertTrue(state.pending.isEmpty())
        assertEquals(later(90), server.reminders[id]?.snoozedUntil)
    }

    @Test
    fun `keeps both sides of a conflict, each field from the side that changed it`() {
        connect()
        val id = add("Buy bread")
        sync()
        server.edit(id) { it.title = "Buy bread and milk" }
        server.offline = true
        session.handle { snooze(it, id, T0 + 30 * 60_000, T0) }
        server.offline = false
        sync()
        val local = store.read().reminders[id]
        assertEquals("Buy bread and milk", local?.title)
        assertEquals(later(30), local?.snoozedUntil)
        assertEquals(later(30), server.reminders[id]?.snoozedUntil)
        assertEquals("Buy bread and milk", server.reminders[id]?.title)
        assertEquals(server.reminders[id]?.version, local?.version)
        assertTrue(store.read().pending.isEmpty())
    }

    @Test
    fun `keeps a Done made here when another app renamed the reminder meanwhile`() {
        connect()
        val id = add("Buy bread")
        sync()
        server.edit(id) { it.title = "Buy bread and milk" }
        server.offline = true
        session.handle { markDone(it, id, T0) }
        server.offline = false
        sync()
        assertNotNull(server.reminders[id]?.doneAt)
        assertNotNull(store.read().reminders[id]?.doneAt)
    }

    @Test
    fun `lets the local side win a field both sides changed`() {
        connect()
        val id = add("Buy bread")
        sync()
        server.edit(id) { it.title = "From the phone" }
        server.offline = true
        session.handle { editReminder(it, id, Changes(title = "From here"), T0) }
        server.offline = false
        sync()
        assertEquals("From here", server.reminders[id]?.title)
    }

    @Test
    fun `pulls what other apps created, changed and deleted`() {
        connect()
        val made = server.create("From the phone", later(10))
        sync()
        assertEquals("From the phone", store.read().reminders[made.id]?.title)
        server.edit(made.id) { it.doneAt = later(0) }
        sync()
        assertEquals(later(0), store.read().reminders[made.id]?.doneAt)
        server.edit(made.id) {
            it.deleted = true
            it.title = ""
        }
        sync()
        assertNull(store.read().reminders[made.id])
    }

    @Test
    fun `notifies about a reminder that came due on another device while the phone was off`() {
        connect()
        val made = server.create("From the bot", later(-20))
        sync()
        assertEquals(listOf(made.id), notifyDue(store, T0).map(Reminder::id))
    }

    @Test
    fun `pages through more changes than fit in one answer`() {
        connect()
        repeat(501) { server.create("Reminder $it", later(60)) }
        sync()
        assertEquals(501, store.read().reminders.size)
        assertEquals(3, server.requests.count { it.startsWith("GET /v1/reminders?since=") })
    }

    @Test
    fun `keeps a change made while its push was on the way`() {
        connect()
        // the change is made once the create has left, before the server has answered it
        server.gate = { session.handle { snooze(it, it.reminders.keys.single(), T0 + 45 * 60_000, T0) } }
        val id = add("Buy bread")
        sync()
        assertEquals(later(45), server.reminders[id]?.snoozedUntil)
        assertEquals(later(45), store.read().reminders[id]?.snoozedUntil)
        assertTrue(store.read().pending.isEmpty())
    }

    @Test
    fun `sends done on a repeating reminder as its next due time`() {
        connect()
        val start = "2026-11-12T08:00:00Z"
        val id = session.handle {
            addReminder(it, NewReminder("Stretch", null, start, "Europe/Paris", RepeatUnit.DAY to 1), T0).id
        }
        sync()
        session.handle { markDone(it, id, T0) }
        sync()
        assertEquals("2026-11-13T08:00:00Z", server.reminders[id]?.dueAt)
        assertEquals(start, server.reminders[id]?.repeat?.start)
        assertNull(server.reminders[id]?.doneAt)
    }

    @Test
    fun `deletes on the server and forgets the tombstone`() {
        connect()
        val id = add("Buy bread")
        sync()
        session.handle { remove(it, id, T0) }
        sync()
        assertEquals(true, server.reminders[id]?.deleted)
        assertNull(store.read().reminders[id])
    }

    @Test
    fun `reports a refused token`() {
        val seeded = Session(seed(emptyList()) { it.server = Server("https://noeud.test", "noeud_wrong", "android") })
        seeded.sync.sync()
        val status = seeded.store.read().sync
        assertEquals(SyncState.ERROR, status.state)
        assertEquals("The server refused the token.", status.message)
    }

    @Test
    fun `keeps every reminder after disconnecting, and sends them again on the next connect`() {
        connect()
        val id = add("Buy bread")
        sync()
        session.handle { disconnect(it) }
        var state = store.read()
        assertNull(state.server)
        assertEquals(0, state.reminders[id]?.version)
        connect()
        state = store.read()
        assertEquals(1, state.reminders[id]?.version)
        assertEquals(1, server.reminders.size)
    }

    @Test
    fun `stays quiet without a server`() {
        val seeded = Session(seed(listOf(makeReminder())))
        seeded.sync.sync()
        assertEquals(emptyList<String>(), seeded.server.requests)
    }

    @Test
    fun `keeps saying a change was refused until it changes, and sends it then`() {
        connect()
        server.refuse = "notes must be at most 10000 characters"
        val id = add("Buy bread")
        sync()
        sync()
        var state = store.read()
        assertEquals(SyncState.ERROR, state.sync.state)
        assertTrue(state.sync.message!!.contains("notes must be at most"))
        assertNotNull(state.pending[id]?.refused)
        server.refuse = null
        session.handle { editReminder(it, id, Changes(title = "Buy bread today"), T0) }
        sync()
        state = store.read()
        assertEquals(SyncState.OK, state.sync.state)
        assertEquals("Buy bread today", server.reminders[id]?.title)
    }

    @Test
    fun `keeps what waits when the token is changed`() {
        connect()
        val id = add("Buy bread")
        sync()
        server.token = "noeud_new"
        session.handle { remove(it, id, T0) }
        sync()
        assertEquals("The server refused the token.", store.read().sync.message)
        session.handle { replaceToken(it, Server(server.origin, "noeud_new", "android")) }
        assertEquals(true, server.reminders[id]?.deleted)
        assertNull(store.read().reminders[id])
        assertEquals(SyncState.OK, store.read().sync.state)
    }
}
