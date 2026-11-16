// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.synaelle.noeud.lib.Clock
import com.synaelle.noeud.lib.DelayChoice
import com.synaelle.noeud.lib.Offline
import com.synaelle.noeud.lib.Server
import com.synaelle.noeud.lib.State
import com.synaelle.noeud.lib.SyncTone
import com.synaelle.noeud.lib.Theme
import com.synaelle.noeud.lib.connect
import com.synaelle.noeud.lib.delayChoices
import com.synaelle.noeud.lib.disconnect
import com.synaelle.noeud.lib.durationLabel
import com.synaelle.noeud.lib.readDelay
import com.synaelle.noeud.lib.replaceToken
import com.synaelle.noeud.lib.syncSummary
import java.net.URI
import java.net.URISyntaxException

/** Settings: the room, the clock, how long Delay is, and the server to sync with. */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var theme: Theme
    private lateinit var page: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var changingToken = false
    private var error: String? = null
    private var customDelay = ""
    private val render: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Noeud.read(this).theme
        ui = Ui(this, applyTheme(theme))
        page = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
        val back = ui.iconButton(R.drawable.ic_x, "Close").apply { setOnClickListener { finish() } }
        val heading = ui.text("Settings", 20, ui.palette.text, Type.bold).apply { heading() }
        val header = ui.row(Gravity.CENTER_VERTICAL, heading, back).apply {
            heading.layoutParams = weighted()
            setPadding(ui.dp(16), ui.dp(8), ui.dp(4), 0)
            minimumHeight = ui.dp(56)
        }
        val root = ui.column(header, ScrollView(this).apply { addView(page) }).apply {
            getChildAt(1).layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(ui.palette.bg)
            padForBars()
        }
        setContentView(root)
        val delay = Noeud.read(this).delay
        if (delay !in delayChoices) customDelay = delay
    }

    override fun onResume() {
        super.onResume()
        Noeud.listen(render)
        render()
    }

    override fun onPause() {
        super.onPause()
        Noeud.unlisten(render)
    }

    private fun section(title: String) {
        page.addView(ui.line(), wide(ui.dp(1)).margins(ui, top = 16))
        page.addView(ui.caption(title).apply { heading() })
    }

    private fun paragraph(text: String, color: Int = ui.palette.textMuted, size: Number = 13): TextView =
        ui.text(text, size, color).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }.also { page.addView(it) }

    private fun <T> choices(options: List<Pair<T, String>>, chosen: T, pick: (T) -> Unit) {
        val row = ui.row(Gravity.CENTER_VERTICAL).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        for ((value, label) in options) {
            val kind = if (value == chosen) Ui.Kind.CHIP_ON else Ui.Kind.CHIP
            val button = ui.button(label, kind) { pick(value) }.apply {
                minimumHeight = ui.dp(48)
                isSingleLine = true
            }
            row.addView(button, wrap().margins(ui, end = 8))
        }
        page.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            },
        )
    }

    private fun change(change: (State) -> Unit) {
        Noeud.change(this, syncAfter = false, change = change)
    }

    private fun render() {
        val state = Noeud.read(this)
        page.removeAllViews()
        renderServer(state)

        section("Appearance")
        choices(listOf(Theme.DARK to "Dark", Theme.LIGHT to "Light"), state.theme) { chosen ->
            change { it.theme = chosen }
            if (chosen != theme) recreate()
        }

        section("Time")
        choices(listOf(Clock.H24 to "24-hour  18:30", Clock.H12 to "12-hour  6:30 PM"), state.clock) { chosen ->
            change { it.clock = chosen }
        }

        section("Delay")
        paragraph("How long Delay puts a reminder off, from a notification or the app.")
        val custom = state.delay !in delayChoices || customDelay.isNotBlank()
        choices(delayChoices.map { it to durationLabel(it) } + ("custom" to "Custom"), if (custom) "custom" else state.delay) { chosen ->
            if (chosen == "custom") {
                customDelay = customDelay.ifBlank { state.delay }
                render()
            } else {
                customDelay = ""
                change { it.delay = chosen }
            }
        }
        if (custom) customDelayField(state)

        section("About")
        paragraph(
            "Noeud ${BuildConfig.VERSION_NAME}. Reminders that came due while the phone was off notify you when it " +
                "starts again. Hanken Grotesk is under the SIL Open Font License, Lucide icons under the ISC license.",
        )
    }

    private fun customDelayField(state: State) {
        val field = ui.field("1d12h").apply {
            setText(customDelay)
            contentDescription = "Custom delay"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Type.regular
        }
        val hint = ui.text("", 13, ui.palette.textMuted)
        fun show(text: String) {
            when (val read = readDelay(text)) {
                is DelayChoice.Ok -> {
                    hint.setTextColor(ui.palette.textMuted)
                    hint.text = durationLabel(read.delay)
                }
                is DelayChoice.Error -> {
                    hint.setTextColor(ui.palette.danger)
                    hint.text = if (text.isBlank()) "" else read.error
                }
            }
        }
        // used once it reads as a length; until then the last good one stays
        field.onText { text ->
            customDelay = text
            show(text)
            (readDelay(text) as? DelayChoice.Ok)?.let { read ->
                if (read.delay != state.delay) Noeud.store(this).update { it.delay = read.delay }
            }
        }
        show(customDelay)
        page.addView(ui.column(field, hint).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0) })
    }

    /** The address as typed, or null when it is not an http(s) address. */
    private fun normalized(address: String): String? = try {
        val uri = URI(address.trim())
        if (uri.scheme != "https" && uri.scheme != "http" || uri.host == null) {
            null
        } else {
            val port = if (uri.port == -1) "" else ":${uri.port}"
            // the API's own /v1 is added by Noeud, so an address copied with it still works
            "${uri.scheme}://${uri.host}$port${uri.rawPath ?: ""}".trimEnd('/').removeSuffix("/v1")
        }
    } catch (_: URISyntaxException) {
        null
    }

    /** Asks the server whose token this is, on a background thread. */
    private fun checkToken(server: Server, done: (String?, Server?) -> Unit) {
        busy = true
        error = null
        render()
        Noeud.inBackground {
            val (problem, named) = try {
                val answer = HttpTransport().request(server, "GET", "/v1/token", null)
                val name = answer.data?.optString("name")?.takeIf { it.isNotEmpty() }
                when {
                    answer.status == 401 -> "The server refused this token." to null
                    answer.status != 200 || name == null ->
                        "This does not look like a Noeud server (it answered ${answer.status})." to null
                    else -> null to server.copy(name = name)
                }
            } catch (_: Offline) {
                "The server could not be reached. Check the address." to null
            }
            main.post {
                busy = false
                error = problem
                done(problem, named)
                render()
            }
        }
    }

    private fun renderServer(state: State) {
        section("Server")
        val server = state.server
        if (server == null) {
            paragraph(
                "Noeud works on its own and keeps your reminders on this phone. Add your Noeud server to share them " +
                    "with your other devices and apps.",
            )
            val address = ui.field("https://example.com/noeud").apply {
                contentDescription = "Address"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            val token = tokenField("Token")
            val connectButton = ui.button(if (busy) "Connecting…" else "Connect", Ui.Kind.PRIMARY) {
                val url = normalized(address.text.toString())
                when {
                    url == null -> {
                        error = "Enter the server address, like https://example.com/noeud."
                        render()
                    }
                    token.text.isBlank() -> {
                        error = "Paste the token made with noeud-server token create."
                        render()
                    }
                    else -> checkToken(Server(url, token.text.toString().trim(), "")) { _, named ->
                        if (named != null) {
                            change { connect(it, named) }
                            SyncJob.schedule(this)
                            Noeud.sync(this)
                        }
                    }
                }
            }.apply { isEnabled = !busy }
            page.addView(labelled("Address", address))
            page.addView(labelled("Token", token))
            paragraph("Make one on the server with noeud-server token create android.")
            page.addView(ui.row(Gravity.START, connectButton).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        } else {
            paragraph("Syncing with ${server.url} as ${server.name}.", ui.palette.text, 14)
            val (tone, text) = syncSummary(state, System.currentTimeMillis())
            val color = when (tone) {
                SyncTone.ERROR -> ui.palette.danger
                SyncTone.OK -> ui.palette.success
                else -> ui.palette.textMuted
            }
            paragraph(text, color).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val syncNow = ui.button(if (busy) "Syncing…" else "Sync now", Ui.Kind.PRIMARY) {
                busy = true
                render()
                Noeud.sync(this) {
                    main.post {
                        busy = false
                        render()
                    }
                }
            }.apply { isEnabled = !busy }
            val changeToken = ui.button("Change token", Ui.Kind.OUTLINE) {
                changingToken = !changingToken
                render()
            }
            val disconnectButton = ui.button("Disconnect", Ui.Kind.OUTLINE) {
                change { disconnect(it) }
                SyncJob.schedule(this)
            }
            val buttons = ui.row(Gravity.CENTER_VERTICAL, syncNow, changeToken, disconnectButton).apply {
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
                syncNow.layoutParams = wrap().margins(ui, end = 8)
                changeToken.layoutParams = wrap().margins(ui, end = 8)
            }
            page.addView(buttons)
            if (changingToken) {
                val token = tokenField("New token")
                page.addView(labelled("New token", token))
                paragraph("Made with noeud-server token regenerate ${server.name}.")
                val use = ui.button("Use this token", Ui.Kind.PRIMARY) {
                    if (token.text.isBlank()) {
                        error = "Paste the new token."
                        render()
                    } else {
                        checkToken(server.copy(token = token.text.toString().trim())) { _, named ->
                            if (named != null) {
                                changingToken = false
                                change { replaceToken(it, named) }
                                Noeud.sync(this)
                            }
                        }
                    }
                }.apply { isEnabled = !busy }
                page.addView(ui.row(Gravity.START, use).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
            }
            paragraph("Disconnecting keeps every reminder on this phone. Connecting again sends them back.")
        }
        error?.let { paragraph(it, ui.palette.danger, 14).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE }
    }

    private fun tokenField(label: String): EditText = ui.field("noeud_…").apply {
        contentDescription = label
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        typeface = Type.regular
    }

    private fun labelled(label: String, field: View): View =
        ui.column(ui.text(label, 13, ui.palette.textMuted), field).apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
            field.layoutParams = wide().margins(ui, top = 4)
            getChildAt(0).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
}
