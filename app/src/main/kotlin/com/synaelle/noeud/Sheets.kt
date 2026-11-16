// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import com.synaelle.noeud.lib.Change
import com.synaelle.noeud.lib.Changes
import com.synaelle.noeud.lib.Reminder
import com.synaelle.noeud.lib.RepeatChange
import com.synaelle.noeud.lib.When
import com.synaelle.noeud.lib.editReminder
import com.synaelle.noeud.lib.fieldsProblem
import com.synaelle.noeud.lib.formatAt
import com.synaelle.noeud.lib.noteTimeUse
import com.synaelle.noeud.lib.notifyAt
import com.synaelle.noeud.lib.parseTime
import com.synaelle.noeud.lib.parseWhen
import com.synaelle.noeud.lib.pickerText
import com.synaelle.noeud.lib.rankTimes
import com.synaelle.noeud.lib.snooze
import com.synaelle.noeud.lib.snoozeDefaults
import com.synaelle.noeud.lib.timeLabel
import com.synaelle.noeud.lib.timestamp
import java.time.Instant

/** Snooze: the times this person snoozes to most, or another one typed in. */
fun snoozeSheet(activity: MainActivity, ui: Ui, reminder: Reminder) {
    val state = Noeud.read(activity)
    val now = System.currentTimeMillis()
    val zone = Noeud.zone()
    lateinit var close: () -> Unit
    fun snoozeTo(text: String) {
        val until = parseWhen(text, System.currentTimeMillis(), zone) as? When.Ok ?: return
        val done = activity.act { s, time ->
            snooze(s, reminder.id, until.at, time)
            noteTimeUse(s, text, time)
        }
        if (done) close()
    }
    val choices = ui.column()
    for (time in rankTimes(state.times, snoozeDefaults, now, zone)) {
        val at = (parseWhen(time, now, zone) as? When.Ok)?.at
        val label = ui.text(timeLabel(time), 15, ui.palette.text, Type.medium)
        val hint = ui.text(at?.let { formatAt(it, now, zone) } ?: "", 13, ui.palette.textMuted)
        val choice = ui.row(Gravity.CENTER_VERTICAL, label, hint).apply {
            label.layoutParams = weighted()
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            minimumHeight = ui.dp(52)
            background = ui.pressable(ui.palette.bg, ui.palette.border)
            isFocusable = true
            isClickable = true
            contentDescription = "${timeLabel(time)}, ${hint.text}"
            setOnClickListener { snoozeTo(time) }
        }
        choices.addView(choice, wide().margins(ui, bottom = 8))
    }
    val other = WhenField(activity, ui, "Other", "Other: 3h, 18:00, friday")
    val button = ui.button("Snooze", Ui.Kind.PRIMARY) { snoozeTo(other.text) }
    other.input.onDone { snoozeTo(other.text) }
    val ticker = Handler(Looper.getMainLooper())
    val tick = object : Runnable {
        override fun run() {
            other.refresh(System.currentTimeMillis())
            button.isEnabled = other.parsed is When.Ok
            button.alpha = if (button.isEnabled) 1f else 0.5f
            ticker.postDelayed(this, 1000)
        }
    }
    other.onChange = {
        ticker.removeCallbacks(tick)
        tick.run()
    }
    val content = ui.column(choices, other.view, button).apply {
        choices.layoutParams = wide().margins(ui, top = 8)
        other.view.layoutParams = wide().margins(ui, top = 4)
        button.layoutParams = wide().margins(ui, top = 12)
    }
    val dialog = sheet(activity, ui, "Snooze ${reminder.title}", content)
    close = { dialog.dismiss() }
    dialog.setOnDismissListener { ticker.removeCallbacks(tick) }
    tick.run()
    dialog.show()
}

/** Edit: the title, when, the repeat and the notes, saved the way a PATCH would change them. */
fun editSheet(activity: MainActivity, ui: Ui, reminder: Reminder) {
    val zone = Noeud.zone()
    val p = ui.palette
    val title = ui.field("What should I remember?").apply {
        setText(reminder.title)
        contentDescription = "Reminder"
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }
    // until When changes, the reminder keeps its time, even one a sync moves meanwhile; a snoozed
    // reminder shows its snooze
    val at = notifyAt(reminder) ?: parseTime(reminder.dueAt)
    val opened = pickerText(Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime())
    val whenField = WhenField(activity, ui, "When", "2h, 18:00, tomorrow 9:00")
    whenField.kept = opened to at
    whenField.input.setText(opened)
    val repeat = RepeatField(ui)
    repeat.set(reminder.repeat?.every, reminder.repeat?.interval ?: 1)
    val notes = ui.field("Notes", 15, multiline = true).apply {
        setText(reminder.notes ?: "")
        contentDescription = "Notes"
        maxLines = 6
    }
    val problem = ui.text("", 13, p.danger).apply { visibility = View.GONE }
    lateinit var close: () -> Unit
    fun refresh() = whenField.refresh(System.currentTimeMillis())
    fun save() {
        val now = System.currentTimeMillis()
        whenField.refresh(now)
        val moved = whenField.input.text.toString() != opened
        val reason = fieldsProblem(title.text.toString(), notes.text.toString())
            ?: repeat.value().exceptionOrNull()?.message
            ?: if (moved && whenField.parsed !is When.Ok) (whenField.parsed as When.Error).error else null
        if (reason != null) {
            problem.text = reason
            problem.visibility = View.VISIBLE
            return
        }
        val chosen = repeat.value().getOrNull()
        val sameRepeat = chosen?.first == reminder.repeat?.every && chosen?.second == reminder.repeat?.interval
        val changes = Changes(
            title = title.text.toString(),
            notes = Change(notes.text.toString().trim().ifEmpty { null }),
            dueAt = if (moved) timestamp((whenField.parsed as When.Ok).at) else null,
            repeat = if (sameRepeat) null else Change(chosen?.let { RepeatChange(it.first, it.second) }),
        )
        if (activity.act { s, time -> editReminder(s, reminder.id, changes, time) }) close()
    }
    whenField.onChange = { refresh() }
    val cancel = ui.button("Cancel", Ui.Kind.OUTLINE) { close() }
    val saveButton = ui.button("Save", Ui.Kind.PRIMARY) { save() }
    val buttons = ui.row(Gravity.CENTER_VERTICAL or Gravity.END, cancel, saveButton).apply {
        cancel.layoutParams = wrap().margins(ui, end = 8)
    }
    val content = ui.column(
        title,
        whenField.view,
        ui.text("Repeat", 13, p.textMuted),
        repeat.view,
        notes,
        problem,
        buttons,
    ).apply {
        for (i in 0 until childCount) getChildAt(i).layoutParams = wide().margins(ui, top = if (i == 0) 8 else 12)
        buttons.layoutParams = wide().margins(ui, top = 16)
    }
    val dialog = sheet(activity, ui, "Edit", content)
    close = { dialog.dismiss() }
    val ticker = Handler(Looper.getMainLooper())
    val tick = object : Runnable {
        override fun run() {
            refresh()
            ticker.postDelayed(this, 1000)
        }
    }
    dialog.setOnDismissListener { ticker.removeCallbacks(tick) }
    tick.run()
    dialog.show()
}
