// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.synaelle.noeud.lib.Clock
import com.synaelle.noeud.lib.INTERVAL_MAX
import com.synaelle.noeud.lib.RepeatUnit
import com.synaelle.noeud.lib.When
import com.synaelle.noeud.lib.clock
import com.synaelle.noeud.lib.formatPreview
import com.synaelle.noeud.lib.parseWhen
import com.synaelle.noeud.lib.pickerText
import java.time.Instant

fun EditText.onText(change: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = change(s.toString())
})

/**
 * "When": typed text (2h30m, 8h52, 18:00, tomorrow 9:00) or a date from the calendar, with exactly
 * when it will notify you, or the reason it is wrong, underneath.
 */
class WhenField(private val activity: Activity, private val ui: Ui, label: String, hint: String) {
    val input: EditText = ui.field(hint).apply {
        // no autocorrect on "2h", which the visible password variation turns off, in the usual font
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        typeface = Type.regular
        contentDescription = label
        background = null
        setPadding(0, 0, 0, 0)
    }
    val preview: TextView = ui.text("", 13, ui.palette.accentText).apply {
        setPadding(0, ui.dp(6), 0, 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val labelView = ui.text(label, 12, ui.palette.textMuted).apply {
        setPadding(0, 0, ui.dp(8), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val calendar = ui.iconButton(R.drawable.ic_calendar, "Pick a date").apply {
        setOnClickListener { pickDate() }
    }
    private val line = ui.row(Gravity.CENTER_VERTICAL, labelView, input, calendar).apply {
        input.layoutParams = weighted()
        setPadding(ui.dp(12), 0, ui.dp(4), 0)
        minimumHeight = ui.dp(48)
        input.setOnFocusChangeListener { _, focused -> refreshFrame(focused) }
    }
    val view: LinearLayout = ui.column(line, preview)

    /** What the field means now, for the preview and for the moment it is used. */
    var parsed: When = When.Error("")
        private set

    /** The text that counts: typed by hand, or the starting time until it is. */
    var fallback = ""

    /** The text the field opened with and the time it stands for, kept until the text changes. */
    var kept: Pair<String, Long>? = null
    var onChange: () -> Unit = {}

    init {
        refreshFrame(false)
        input.onText { onChange() }
    }

    private fun refreshFrame(focused: Boolean) {
        line.background = ui.box(ui.palette.bg, if (focused) ui.palette.accent else ui.palette.border)
    }

    val text: String get() = input.text.toString().ifBlank { fallback }

    fun refresh(now: Long) {
        val unchanged = kept?.takeIf { it.first == text }
        parsed = if (unchanged != null) When.Ok(unchanged.second) else parseWhen(text, now, Noeud.zone())
        when (val at = parsed) {
            is When.Ok -> {
                preview.setTextColor(ui.palette.accentText)
                preview.text = formatPreview(at.at, at.delay, now, Noeud.zone())
            }
            is When.Error -> {
                preview.setTextColor(ui.palette.danger)
                preview.text = if (text.isBlank()) "" else at.error
            }
        }
    }

    /** The calendar, then the clock, both Android's own; picking writes "2026-11-13 14:30". */
    private fun pickDate() {
        val now = System.currentTimeMillis()
        val start = Instant.ofEpochMilli((parsed as? When.Ok)?.at ?: (now + 3_600_000))
            .atZone(Noeud.zone()).toLocalDateTime()
        DatePickerDialog(activity, { _, year, month, day ->
            TimePickerDialog(activity, { _, hour, minute ->
                input.setText(pickerText(java.time.LocalDateTime.of(year, month + 1, day, hour, minute)))
                input.setSelection(input.length())
            }, start.hour, start.minute, clock == Clock.H24).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            datePicker.minDate = now - 60_000
        }.show()
    }
}

/** A row of quick times that scrolls sideways, the one in use marked. */
class Picks(private val ui: Ui, private val label: String) {
    private val row = ui.row(Gravity.CENTER_VERTICAL)
    val view = HorizontalScrollView(ui.context).apply {
        isHorizontalScrollBarEnabled = false
        contentDescription = label
        addView(row)
    }

    fun show(picks: List<Pair<String, String>>, chosen: String?, pick: (String) -> Unit) {
        row.removeAllViews()
        for ((time, text) in picks) {
            val kind = if (time == chosen) Ui.Kind.CHIP_ON else Ui.Kind.CHIP
            row.addView(ui.button(text, kind) { pick(time) }, wrap().margins(ui, end = 8))
        }
    }
}

/** Repeat: never, or every n days, weeks, months or years. */
class RepeatField(private val ui: Ui) {
    var every: RepeatUnit? = null
        private set
    private val interval = ui.field("1").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText("1")
        contentDescription = "Repeat every"
        gravity = Gravity.CENTER
    }
    private val unit = ui.text("", 14, ui.palette.textMuted)
    private val choices = ui.row(Gravity.CENTER_VERTICAL)
    private val intervalRow = ui.row(Gravity.CENTER_VERTICAL, ui.text("every", 13, ui.palette.textMuted), interval, unit).apply {
        interval.layoutParams = LinearLayout.LayoutParams(ui.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).margins(ui, 8, 0, 8, 0)
        setPadding(0, ui.dp(8), 0, 0)
    }
    val view = ui.column(
        HorizontalScrollView(ui.context).apply {
            isHorizontalScrollBarEnabled = false
            addView(choices)
        },
        intervalRow,
    )

    private val labels = listOf(null to "Never", RepeatUnit.DAY to "Daily", RepeatUnit.WEEK to "Weekly", RepeatUnit.MONTH to "Monthly", RepeatUnit.YEAR to "Yearly")

    init {
        interval.onText { showUnit() }
        set(null, 1)
    }

    fun set(every: RepeatUnit?, count: Int) {
        this.every = every
        interval.setText(count.toString())
        choices.removeAllViews()
        for ((value, text) in labels) {
            val kind = if (value == every) Ui.Kind.CHIP_ON else Ui.Kind.CHIP
            choices.addView(ui.button(text, kind) { set(value, intervalValue() ?: 1) }, wrap().margins(ui, end = 8))
        }
        intervalRow.visibility = if (every == null) View.GONE else View.VISIBLE
        showUnit()
    }

    private fun showUnit() {
        val word = every?.word ?: return
        unit.text = if (intervalValue() == 1) word else "${word}s"
    }

    fun intervalValue(): Int? = interval.text.toString().trim().toIntOrNull()

    /** The repeat chosen, or why it cannot be one. */
    fun value(): Result<Pair<RepeatUnit, Int>?> {
        val chosen = every ?: return Result.success(null)
        val count = intervalValue()
        if (count == null || count < 1 || count > INTERVAL_MAX) {
            return Result.failure(IllegalArgumentException("Repeat every 1 to $INTERVAL_MAX"))
        }
        return Result.success(chosen to count)
    }
}

/** A panel that rises from the bottom of the screen, over a dimmed page. */
fun sheet(activity: Activity, ui: Ui, title: String, content: View): Dialog {
    val dialog = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
    val close = ui.iconButton(R.drawable.ic_x, "Close").apply { setOnClickListener { dialog.dismiss() } }
    val heading = ui.text(title, 18, ui.palette.text, Type.bold).apply {
        heading()
    }
    ui.ellipsize(heading, 2)
    val body = ui.column(
        ui.row(Gravity.CENTER_VERTICAL, heading, close).apply { heading.layoutParams = weighted() },
        content,
    ).apply {
        setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(16))
        background = ui.box(ui.palette.surface, ui.palette.border, 6)
    }
    content.layoutParams = wide().margins(ui, end = 8)
    dialog.setContentView(ScrollView(activity).apply { addView(body) })
    dialog.window?.apply {
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setBackgroundDrawableResource(android.R.color.transparent)
        // below Android 11 the window itself must make room for the keyboard
        @Suppress("DEPRECATION")
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    return dialog
}

/** Enter in a single-line field runs the form's main action. */
fun EditText.onDone(action: () -> Unit) {
    imeOptions = EditorInfo.IME_ACTION_DONE
    setOnEditorActionListener { _, id, _ ->
        if (id == EditorInfo.IME_ACTION_DONE) {
            action()
            true
        } else {
            false
        }
    }
}
