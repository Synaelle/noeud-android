// SPDX-License-Identifier: GPL-3.0-or-later

// The Ficelle look in plain platform views: its colours, sharp 2dp corners, 1dp borders and one
// red accent. Built in code so both rooms, dark and light, come from one palette.

package com.synaelle.noeud

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.synaelle.noeud.lib.Theme

class Palette(
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val border: Int,
    val borderStrong: Int,
    val text: Int,
    val textMuted: Int,
    val textFaint: Int,
    val accent: Int,
    val accentText: Int,
    val accentPressed: Int,
    val accentOn: Int,
    val danger: Int,
    val success: Int,
    val warning: Int,
    val selection: Int,
) {
    companion object {
        private fun c(hex: Long) = hex.toInt()

        val dark = Palette(
            bg = c(0xFF0E0D0D), surface = c(0xFF161414), surface2 = c(0xFF1F1C1B),
            border = c(0xFF2E2A28), borderStrong = c(0xFF6B625C),
            text = c(0xFFE6DFD8), textMuted = c(0xFF9A918A), textFaint = c(0xFF5F5752),
            accent = c(0xFFE44C3E), accentText = c(0xFFEA5A4C), accentPressed = c(0xFFEF6E61),
            accentOn = c(0xFF140B09), danger = c(0xFFFF7A5C), success = c(0xFF8DB07A),
            warning = c(0xFFD9A441), selection = c(0xFF3A1915),
        )
        val light = Palette(
            bg = c(0xFFF1EFEA), surface = c(0xFFFBFAF7), surface2 = c(0xFFE5E2DB),
            border = c(0xFFCBC6BC), borderStrong = c(0xFF8F887F),
            text = c(0xFF161413), textMuted = c(0xFF5E5853), textFaint = c(0xFF9C958C),
            accent = c(0xFFC1271B), accentText = c(0xFFC1271B), accentPressed = c(0xFF9A1D13),
            accentOn = c(0xFFFFF6F0), danger = c(0xFFA3341F), success = c(0xFF3D6B35),
            warning = c(0xFF8A5A00), selection = c(0xFFF5D5CE),
        )

        fun of(theme: Theme) = if (theme == Theme.LIGHT) light else dark
    }
}

/** Hanken Grotesk, Ficelle's interface font, one variable file read at three weights. */
object Type {
    lateinit var regular: Typeface
        private set
    lateinit var medium: Typeface
        private set
    lateinit var bold: Typeface
        private set

    private fun weight(assets: AssetManager, wght: Int): Typeface =
        Typeface.Builder(assets, "fonts/HankenGrotesk.ttf").setFontVariationSettings("'wght' $wght").build()

    fun load(assets: AssetManager) {
        if (::regular.isInitialized) return
        regular = weight(assets, 400)
        medium = weight(assets, 600)
        bold = weight(assets, 700)
    }
}

/** One builder of views per screen, holding its palette. */
class Ui(val context: Context, val palette: Palette) {
    init {
        Type.load(context.assets)
    }

    fun dp(value: Number): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    fun box(fill: Int, stroke: Int? = null, radius: Number = 2): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** A pressable background: the fill, a ripple when pressed, a 2dp accent ring on keyboard focus. */
    fun pressable(fill: Int, stroke: Int? = null, radius: Number = 2): Drawable {
        val ring = box(fill, null, radius).apply { setStroke(dp(2), palette.accent) }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), box(fill, stroke, radius))
        }
        val ripple = ColorStateList.valueOf(palette.text and 0x00FFFFFF or 0x22000000)
        return RippleDrawable(ripple, states, box(0xFFFFFFFF.toInt(), null, radius))
    }

    fun text(
        value: CharSequence = "",
        size: Number = 14,
        color: Int = palette.text,
        face: Typeface = Type.regular,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        setTextColor(color)
        typeface = face
        setLineSpacing(0f, 1.1f)
    }

    /** A section caption: small, upper case, muted, like the edge print on film. */
    fun caption(value: String): TextView = text(value.uppercase(), 13, palette.textMuted).apply {
        letterSpacing = 0.04f
        setPadding(dp(16), dp(16), dp(16), dp(6))
    }

    fun icon(resource: Int, color: Int = palette.textMuted, size: Number = 20): ImageView =
        ImageView(context).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun iconButton(resource: Int, label: String, color: Int = palette.textMuted): ImageButton =
        ImageButton(context).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(color)
            contentDescription = label
            background = pressable(0x00000000)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    enum class Kind { PRIMARY, OUTLINE, PLAIN, CHIP, CHIP_ON }

    fun button(label: String, kind: Kind = Kind.OUTLINE, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isSingleLine = true
        isAllCaps = false
        stateListAnimator = null
        typeface = if (kind == Kind.CHIP || kind == Kind.CHIP_ON) Type.regular else Type.medium
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (kind == Kind.CHIP || kind == Kind.CHIP_ON) 13f else 14f)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = dp(if (kind == Kind.CHIP || kind == Kind.CHIP_ON) 40 else 48)
        setPadding(dp(14), 0, dp(14), 0)
        when (kind) {
            Kind.PRIMARY -> {
                setTextColor(palette.accentOn)
                background = pressable(palette.accent)
            }
            Kind.OUTLINE -> {
                setTextColor(palette.text)
                background = pressable(palette.surface, palette.borderStrong)
            }
            Kind.PLAIN -> {
                setTextColor(palette.textMuted)
                background = pressable(0x00000000)
            }
            Kind.CHIP -> {
                setTextColor(palette.text)
                background = pressable(palette.surface, palette.borderStrong)
            }
            Kind.CHIP_ON -> {
                setTextColor(palette.text)
                background = pressable(palette.selection, palette.accent)
            }
        }
        setOnClickListener { onClick() }
    }

    fun field(hint: String, size: Number = 16, multiline: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(palette.textFaint)
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        typeface = Type.regular
        highlightColor = palette.selection
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(palette.bg, palette.accent))
            addState(intArrayOf(), box(palette.bg, palette.border))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(48)
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (!multiline) setSingleLine()
        // a field's cursor colour can be set from Android 10
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(palette.accent)
                setSize(dp(2), dp(20))
            }
        }
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL, vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        children.forEach(::addView)
    }

    fun column(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach(::addView)
    }

    fun line(): View = View(context).apply {
        setBackgroundColor(palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun ellipsize(view: TextView, lines: Int): TextView = view.apply {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** Marks a title for screen readers, which can jump from one to the next (Android 9 and later). */
fun View.heading() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) isAccessibilityHeading = true
}

fun LinearLayout.LayoutParams.margins(ui: Ui, start: Number = 0, top: Number = 0, end: Number = 0, bottom: Number = 0) =
    apply { setMargins(ui.dp(start), ui.dp(top), ui.dp(end), ui.dp(bottom)) }

fun wide(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
