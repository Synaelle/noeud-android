// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.synaelle.noeud.lib.ActionError
import com.synaelle.noeud.lib.Change
import com.synaelle.noeud.lib.Changes
import com.synaelle.noeud.lib.NewReminder
import com.synaelle.noeud.lib.Reminder
import com.synaelle.noeud.lib.RepeatChange
import com.synaelle.noeud.lib.State
import com.synaelle.noeud.lib.SyncTone
import com.synaelle.noeud.lib.Theme
import com.synaelle.noeud.lib.When
import com.synaelle.noeud.lib.addDefaults
import com.synaelle.noeud.lib.addReminder
import com.synaelle.noeud.lib.delayTitle
import com.synaelle.noeud.lib.delayedUntil
import com.synaelle.noeud.lib.editReminder
import com.synaelle.noeud.lib.fieldsProblem
import com.synaelle.noeud.lib.formatWhen
import com.synaelle.noeud.lib.markDone
import com.synaelle.noeud.lib.normalizeWhen
import com.synaelle.noeud.lib.noteTimeUse
import com.synaelle.noeud.lib.notifyAt
import com.synaelle.noeud.lib.parseTime
import com.synaelle.noeud.lib.rankTimes
import com.synaelle.noeud.lib.remove
import com.synaelle.noeud.lib.reopen
import com.synaelle.noeud.lib.restoreReminder
import com.synaelle.noeud.lib.snooze
import com.synaelle.noeud.lib.startingTime
import com.synaelle.noeud.lib.syncSummary
import com.synaelle.noeud.lib.timeLabel
import com.synaelle.noeud.lib.timestamp
import java.time.LocalDate

private const val UNDO_MS = 6000L
private const val ASK_NOTIFICATIONS = 1

/** Applies the chosen room to the window: its background and the status bar's icons. */
fun Activity.applyTheme(theme: Theme): Palette {
    setTheme(if (theme == Theme.LIGHT) R.style.Noeud_Light else R.style.Noeud_Dark)
    val palette = Palette.of(theme)
    window.decorView.setBackgroundColor(palette.bg)
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = if (theme == Theme.LIGHT) {
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        0
    }
    return palette
}

/** Keeps the content clear of the status bar, the navigation bar and the keyboard. */
fun View.padForBars() {
    setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }
        insets
    }
}

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var theme: Theme
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var syncButton: ImageButton
    private lateinit var notices: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private lateinit var error: TextView

    private lateinit var title: EditText
    private lateinit var addButton: View
    private lateinit var details: LinearLayout
    private lateinit var whenField: WhenField
    private lateinit var picks: Picks
    private lateinit var repeat: RepeatField
    private lateinit var notes: EditText
    private lateinit var titleError: TextView

    /** The reminder whose actions show. */
    private var opened: String? = null
    private var showDone = false
    private var undo: ((State, Long) -> Unit)? = null
    private var update: Updater.Release? = null

    private val render: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            refreshWhen()
            handler.postDelayed(this, 1000)
        }
    }
    private val minute = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }
    private val hideUndo = Runnable {
        undo = null
        undoBar.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Noeud.read(this).theme
        ui = Ui(this, applyTheme(theme))
        setContentView(build())
        opened = reminderFrom(intent)
        Updater.checkOnce(this) { release ->
            update = release
            render()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reminderFrom(intent)?.let {
            opened = it
            render()
        }
    }

    private fun reminderFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "noeud" && it.host == "reminder" }?.lastPathSegment

    override fun onResume() {
        super.onResume()
        if (Noeud.read(this).theme != theme) {
            recreate()
            return
        }
        Noeud.listen(render)
        render()
        handler.post(tick)
        handler.postDelayed(minute, 30_000)
        Noeud.inBackground { Noeud.catchUp(applicationContext) }
        Noeud.sync(this)
    }

    override fun onPause() {
        super.onPause()
        Noeud.unlisten(render)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(minute)
    }

    // Back folds the open composer away; from Android 13 through the back dispatcher
    private val foldOnBack: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OnBackInvokedCallback { collapse() }
    } else {
        null
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (details.visibility == View.VISIBLE) {
            collapse()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun build(): View {
        val p = ui.palette
        val name = ui.text("Noeud", 20, p.text, Type.bold).apply { heading() }
        syncButton = ui.iconButton(R.drawable.ic_cloud_check, "Sync now").apply {
            setOnClickListener { Noeud.sync(this@MainActivity) }
        }
        val settings = ui.iconButton(R.drawable.ic_settings, "Settings").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val bow = ui.icon(R.drawable.ic_bow, size = 26).apply { imageTintList = null }
        val header = ui.row(Gravity.CENTER_VERTICAL, bow, name, syncButton, settings).apply {
            name.layoutParams = weighted().margins(ui, start = 8)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(4), 0)
            minimumHeight = ui.dp(56)
        }

        notices = ui.column()
        list = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(16)) }
        scroll = ScrollView(this).apply {
            addView(list)
            isFillViewport = true
        }
        error = ui.text("", 13, p.danger).apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        undoText = ui.ellipsize(ui.text("", 14, p.text), 2)
        undoBar = ui.row(Gravity.CENTER_VERTICAL, undoText, ui.button("Undo", Ui.Kind.OUTLINE) { runUndo() }).apply {
            undoText.layoutParams = weighted()
            setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8))
            setBackgroundColor(p.surface2)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        return ui.column(header, notices, scroll, error, undoBar, composer()).apply {
            scroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(p.bg)
            padForBars()
        }
    }

    private fun composer(): View {
        val p = ui.palette
        titleError = ui.text("", 13, p.danger).apply {
            setPadding(0, ui.dp(6), 0, 0)
            visibility = View.GONE
        }
        title = ui.field("What should I remember?").apply {
            contentDescription = "What should I remember?"
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setOnFocusChangeListener { _, focused -> if (focused) expand() }
            onText { titleError.visibility = View.GONE }
        }
        addButton = ui.iconButton(R.drawable.ic_plus, "Add", p.accentOn).apply {
            background = ui.pressable(p.accent)
            setOnClickListener { if (title.text.isBlank()) showKeyboard(title) else add() }
        }
        whenField = WhenField(this, ui, "When", "2h, 18:00, tomorrow 9:00")
        whenField.onChange = { refreshWhen() }
        whenField.input.onDone { add() }
        picks = Picks(ui, "Quick times")
        repeat = RepeatField(ui)
        repeat.view.visibility = View.GONE
        notes = ui.field("Notes", 15, multiline = true).apply {
            contentDescription = "Notes"
            maxLines = 5
            visibility = View.GONE
        }
        val repeatToggle = ui.button("Repeat", Ui.Kind.PLAIN) {
            repeat.view.visibility = if (repeat.view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_repeat, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(p.textMuted)
            compoundDrawablePadding = ui.dp(6)
        }
        val notesToggle = ui.button("Notes", Ui.Kind.PLAIN) {
            notes.visibility = if (notes.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (notes.visibility == View.VISIBLE) showKeyboard(notes)
        }
        val add = ui.button("Add", Ui.Kind.PRIMARY) { add() }.apply { minWidth = ui.dp(88) }
        val space = View(this)
        val actions = ui.row(Gravity.CENTER_VERTICAL, repeatToggle, notesToggle, space, add).apply {
            space.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        details = ui.column(whenField.view, picks.view, repeat.view, notes, actions).apply {
            whenField.view.layoutParams = wide().margins(ui, top = 10)
            picks.view.layoutParams = wide().margins(ui, top = 10)
            repeat.view.layoutParams = wide().margins(ui, top = 10)
            notes.layoutParams = wide().margins(ui, top = 10)
            actions.layoutParams = wide().margins(ui, top = 6)
            visibility = View.GONE
        }
        val line = ui.row(Gravity.CENTER_VERTICAL, title, addButton).apply {
            title.layoutParams = weighted().margins(ui, end = 8)
        }
        return ui.column(line, titleError, details).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
            // the surface with a 1dp border along its top
            background = LayerDrawable(arrayOf(ui.box(p.border, null, 0), ui.box(p.surface, null, 0))).apply {
                setLayerInset(1, 0, ui.dp(1), 0, 0)
            }
        }
    }

    private fun showKeyboard(field: EditText) {
        field.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
    }

    private fun expand() {
        if (details.visibility == View.VISIBLE) return
        details.visibility = View.VISIBLE
        addButton.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                foldOnBack as OnBackInvokedCallback,
            )
        }
        (title.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        refreshWhen()
    }

    private fun collapse() {
        details.visibility = View.GONE
        addButton.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(foldOnBack as OnBackInvokedCallback)
        }
        (title.layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(8)
        title.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(title.windowToken, 0)
    }

    /** The preview and the quick picks, kept to the second. */
    private fun refreshWhen() {
        if (details.visibility != View.VISIBLE) return
        val state = Noeud.read(this)
        val now = System.currentTimeMillis()
        // until set by hand, When is the most used time still ahead
        whenField.fallback = startingTime(state.times, now, Noeud.zone())
        whenField.input.hint = timeLabel(whenField.fallback)
        whenField.refresh(now)
        val chosen = normalizeWhen(whenField.text)
        val shown = rankTimes(state.times, addDefaults, now, Noeud.zone()).map { it to timeLabel(it) }
        picks.show(shown, chosen) { time ->
            whenField.input.setText(time)
            whenField.input.setSelection(whenField.input.length())
        }
    }

    private fun add() {
        val text = title.text.toString().trim()
        val problem = fieldsProblem(text, notes.text.toString())
        if (problem != null) {
            titleError.text = problem
            titleError.visibility = View.VISIBLE
            showKeyboard(title)
            return
        }
        val now = System.currentTimeMillis()
        // the time the preview showed when Add was pressed
        whenField.refresh(now)
        val at = whenField.parsed as? When.Ok
        if (at == null) {
            showKeyboard(whenField.input)
            return
        }
        val chosenRepeat = repeat.value().getOrElse {
            titleError.text = it.message
            titleError.visibility = View.VISIBLE
            return
        }
        val whenText = whenField.text
        val added = act { state, time ->
            addReminder(
                state,
                NewReminder(text, notes.text.toString(), timestamp(at.at), Noeud.zone().id, chosenRepeat),
                time,
            )
            noteTimeUse(state, whenText, time)
        }
        if (!added) return
        askNotifications()
        title.setText("")
        whenField.input.setText("")
        notes.setText("")
        notes.visibility = View.GONE
        repeat.set(null, 1)
        repeat.view.visibility = View.GONE
        collapse()
    }

    /** Android 13 and later ask before an app may notify; asked when a reminder is first added. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFICATIONS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    /** Runs a change, showing why when it cannot be made. */
    fun act(change: (State, Long) -> Unit): Boolean {
        error.visibility = View.GONE
        return try {
            Noeud.change(this) { change(it, System.currentTimeMillis()) }
            true
        } catch (failure: ActionError) {
            error.text = failure.message
            error.visibility = View.VISIBLE
            false
        }
    }

    private fun announce(text: String, restore: (State, Long) -> Unit) {
        handler.removeCallbacks(hideUndo)
        undo = restore
        undoText.text = text
        undoBar.visibility = View.VISIBLE
        handler.postDelayed(hideUndo, UNDO_MS)
    }

    private fun runUndo() {
        val restore = undo ?: return
        handler.removeCallbacks(hideUndo)
        hideUndo.run()
        act(restore)
    }

    fun closeRow() {
        opened = null
        render()
    }

    private fun done(reminder: Reminder) {
        val before = reminder.copy()
        if (!act { state, now -> markDone(state, reminder.id, now) }) return
        announce(if (before.repeat != null) "Done until next time: ${before.title}" else "Done: ${before.title}") { state, now ->
            editReminder(
                state,
                before.id,
                Changes(
                    dueAt = before.dueAt,
                    repeat = Change(before.repeat?.let { RepeatChange(it.every, it.interval, it.start) }),
                    snoozedUntil = Change(before.snoozedUntil),
                    doneAt = Change(before.doneAt),
                ),
                now,
            )
        }
        closeRow()
    }

    private fun delete(reminder: Reminder) {
        val snapshot = reminder.copy()
        if (!act { state, now -> remove(state, reminder.id, now) }) return
        announce("Deleted: ${snapshot.title}") { state, now -> restoreReminder(state, snapshot, now) }
        closeRow()
    }

    private fun render() {
        val state = Noeud.read(this)
        val now = System.currentTimeMillis()
        renderNotices(state, now)
        renderSync(state, now)
        list.removeAllViews()
        val reminders = state.reminders.values.filter { !it.deleted }
        if (reminders.isEmpty()) {
            list.addView(emptyBow(), wide(LinearLayout.LayoutParams.MATCH_PARENT))
            return
        }
        val open = reminders.filter { it.doneAt == null }.sortedBy { notifyAt(it) ?: 0 }
        val zone = Noeud.zone()
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val groups = listOf(
            "Overdue" to open.filter { (notifyAt(it) ?: 0) <= now },
            "Today" to open.filter { (notifyAt(it) ?: 0) in (now + 1) until endOfToday },
            "Later" to open.filter { (notifyAt(it) ?: 0) >= endOfToday },
        )
        for ((label, items) in groups) {
            if (items.isEmpty()) continue
            list.addView(
                ui.caption("$label ${items.size}").apply {
                    heading()
                    if (label == "Overdue") setTextColor(ui.palette.accentText)
                },
            )
            items.forEach { list.addView(row(it, state, now)) }
        }
        if (open.isEmpty()) {
            list.addView(
                ui.text("Everything is done.", 14, ui.palette.textMuted).apply {
                    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(8))
                },
            )
        }
        val done = reminders.filter { it.doneAt != null }.sortedByDescending { parseTime(it.doneAt!!) }
        if (done.isNotEmpty()) list.addView(doneToggle(done.size), wrap().margins(ui, top = 8))
        if (showDone) done.forEach { list.addView(row(it, state, now)) }
        scrollToOpened()
    }

    private fun doneToggle(count: Int): View = ui.button("DONE $count", Ui.Kind.PLAIN) {
        showDone = !showDone
        render()
    }.apply {
        letterSpacing = 0.04f
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (showDone) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, 0)
        compoundDrawableTintList = ColorStateList.valueOf(ui.palette.textMuted)
        compoundDrawablePadding = ui.dp(4)
        contentDescription = "Done, $count, ${if (showDone) "shown" else "hidden"}"
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
    }

    private fun scrollToOpened() {
        val id = opened ?: return
        scroll.post {
            val target = list.findViewWithTag<View>(id) ?: return@post
            val top = target.top
            val bottom = target.bottom
            if (top < scroll.scrollY || bottom > scroll.scrollY + scroll.height) {
                scroll.smoothScrollTo(0, maxOf(0, top - scroll.height / 3))
            }
        }
    }

    private fun emptyBow(): View = ImageView(this).apply {
        setImageResource(R.drawable.bow_tie)
        scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        post { (drawable as? AnimatedVectorDrawable)?.start() }
    }

    private fun row(reminder: Reminder, state: State, now: Long): View {
        val p = ui.palette
        val isDone = reminder.doneAt != null
        val at = notifyAt(reminder)
        val overdue = at != null && at <= now
        val isOpen = opened == reminder.id

        val titleView = ui.ellipsize(ui.text(reminder.title, 15, if (isDone) p.textMuted else p.text, Type.medium), 2)
        val texts = ui.column(titleView)
        reminder.notes?.let { texts.addView(ui.ellipsize(ui.text(it, 13, p.textMuted), 2)) }
        val tags = ui.row(Gravity.CENTER_VERTICAL)
        if (reminder.repeat != null) {
            val repeats = ui.icon(R.drawable.ic_repeat, p.textMuted, 14).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Repeats"
            }
            tags.addView(repeats, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).margins(ui, end = 6))
        }
        if (reminder.snoozedUntil != null && !isDone) {
            tags.addView(ui.text("Snoozed", 12, p.textMuted), wrap().margins(ui, end = 6))
        }
        val time = formatWhen(if (isDone) parseTime(reminder.doneAt!!) else at ?: now, now, Noeud.zone())
        val timeColor = when {
            overdue && !isDone -> p.accentText
            isOpen -> p.text
            else -> p.textMuted
        }
        val timeView = ui.text(time, 13, timeColor).apply { fontFeatureSettings = "tnum" }
        val line = ui.row(Gravity.CENTER_VERTICAL, texts, tags, timeView).apply {
            texts.layoutParams = weighted().margins(ui, end = 12)
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            minimumHeight = ui.dp(56)
            background = ui.pressable(0x00000000, null, 0)
            contentDescription = "${reminder.title}, ${if (isDone) "done" else "due"} $time"
            setOnClickListener {
                opened = if (isOpen) null else reminder.id
                render()
            }
        }
        val holder = ui.column(line).apply {
            tag = reminder.id
            if (isOpen) setBackgroundColor(p.selection)
        }
        if (isOpen) holder.addView(actions(reminder, state, overdue))
        return holder
    }

    private fun actions(reminder: Reminder, state: State, overdue: Boolean): View {
        val p = ui.palette
        val strip = ui.row(Gravity.CENTER_VERTICAL).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(8)) }
        fun action(icon: Int, label: String, color: Int = p.text, run: () -> Unit) {
            val text = ui.text(label, 12, color).apply {
                // "Delay 24 hours" takes two lines beside the others
                maxLines = 2
                gravity = Gravity.CENTER
            }
            val button = ui.column(ui.icon(icon, color), text).apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(56)
                background = ui.pressable(0x00000000)
                isFocusable = true
                isClickable = true
                contentDescription = "$label: ${reminder.title}"
                setOnClickListener { run() }
            }
            strip.addView(button, weighted())
        }
        if (reminder.doneAt == null) {
            action(R.drawable.ic_check, "Done") { done(reminder) }
            action(R.drawable.ic_clock, "Snooze") { snoozeSheet(this, ui, reminder) }
            if (overdue) {
                // the same Delay as on the notification, for a reminder that came due
                action(R.drawable.ic_clock, delayTitle(state.delay)) {
                    if (act { s, now -> snooze(s, reminder.id, delayedUntil(s.delay, now), now) }) closeRow()
                }
            }
            action(R.drawable.ic_pencil, "Edit") { editSheet(this, ui, reminder) }
        } else {
            action(R.drawable.ic_rotate_ccw, "Open again") {
                if (act { s, now -> reopen(s, reminder.id, now) }) closeRow()
            }
        }
        action(R.drawable.ic_trash_2, "Delete", p.danger) { delete(reminder) }
        return strip
    }

    private fun notice(text: String, action: String, icon: Int = R.drawable.ic_circle_alert, run: () -> Unit): View {
        val p = ui.palette
        val message = ui.text(text, 13, p.text)
        val mark = ui.icon(icon, if (icon == R.drawable.ic_circle_alert) p.warning else p.textMuted, 18)
        return ui.row(Gravity.CENTER_VERTICAL, mark, message, ui.button(action, Ui.Kind.OUTLINE) { run() }).apply {
            message.layoutParams = weighted().margins(ui, 10, 0, 10, 0)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8))
            setBackgroundColor(p.surface2)
        }
    }

    private fun renderNotices(state: State, now: Long) {
        notices.removeAllViews()
        update?.let { release ->
            notices.addView(
                notice("Noeud ${release.version} is available.", "Install", R.drawable.ic_download) {
                    Updater.install(this, release) { message ->
                        error.text = message
                        error.visibility = View.VISIBLE
                    }
                },
            )
        }
        val waiting = state.reminders.values.any { notifyAt(it) != null }
        if (waiting && !Notifier.allowed(this)) {
            notices.addView(
                notice("Notifications are off, so Noeud cannot notify you.", "Turn on") {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                },
            )
        } else if (waiting && !Alarms.exact(this)) {
            notices.addView(
                notice("Reminders may notify you a few minutes late.", "Allow") {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                },
            )
        }
        if (state.server != null) {
            val (tone, text) = syncSummary(state, now)
            if (tone == SyncTone.ERROR) {
                notices.addView(
                    ui.text(text, 13, ui.palette.danger).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)) },
                )
            }
        }
    }

    private fun renderSync(state: State, now: Long) {
        if (state.server == null) {
            syncButton.visibility = View.GONE
            return
        }
        syncButton.visibility = View.VISIBLE
        val (tone, text) = syncSummary(state, now)
        syncButton.setImageResource(
            when (tone) {
                SyncTone.OFFLINE -> R.drawable.ic_cloud_off
                SyncTone.ERROR -> R.drawable.ic_circle_alert
                else -> R.drawable.ic_cloud_check
            },
        )
        syncButton.contentDescription = "Sync now. $text"
    }
}
