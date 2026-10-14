package com.example.wdoverlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/** Black look shared by the main screen and the overlay, with a choosable accent color. */
object Mono {
    val BLACK: Int = Color.BLACK
    val WHITE: Int = Color.WHITE
    val GRAY: Int = Color.parseColor("#B0B0B0")
    val RED: Int = Color.parseColor("#FF2D2D")
    val BLUE: Int = Color.parseColor("#2D7BFF")
    val PURPLE: Int = Color.parseColor("#A64DFF")
    private val PANEL: Int = Color.parseColor("#F2000000")
    private val BUTTON_FILL: Int = Color.parseColor("#111111")

    /** The saved accent color (white, red, blue or purple). */
    fun accent(ctx: Context): Int =
        ctx.getSharedPreferences("wd", Context.MODE_PRIVATE).getInt("accent", WHITE)

    fun saveAccent(ctx: Context, color: Int) {
        ctx.getSharedPreferences("wd", Context.MODE_PRIVATE).edit().putInt("accent", color).apply()
    }

    private fun shape(fill: Int, stroke: Int, radius: Float, strokePx: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        setStroke(strokePx, stroke)
    }

    private fun strokeWidth(density: Float): Int = (1.5f * density).toInt().coerceAtLeast(1)

    fun button(b: Button, density: Float) {
        val accent = accent(b.context)
        val radius = 8f * density
        val s = strokeWidth(density)
        val pressedText = if (accent == WHITE) BLACK else WHITE
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(accent, accent, radius, s))
            addState(intArrayOf(), shape(BUTTON_FILL, accent, radius, s))
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(pressedText, WHITE)
            )
        )
        b.isAllCaps = false
        // taller buttons are easier to hit with a controller
        b.minHeight = (52 * density).toInt()
        b.minimumHeight = (52 * density).toInt()
        val gap = (3 * density).toInt()
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, gap, 0, gap) }
    }

    /** Tab look: the selected tab is filled with the accent color. */
    fun tab(b: Button, density: Float, selected: Boolean) {
        val accent = accent(b.context)
        val radius = 8f * density
        val s = strokeWidth(density)
        if (selected) {
            b.background = shape(accent, accent, radius, s)
            b.setTextColor(if (accent == WHITE) BLACK else WHITE)
        } else {
            b.background = shape(BUTTON_FILL, accent, radius, s)
            b.setTextColor(WHITE)
        }
        b.isAllCaps = false
        b.minHeight = (44 * density).toInt()
        b.minimumHeight = (44 * density).toInt()
    }

    fun edit(e: EditText) {
        e.setTextColor(WHITE)
        e.setHintTextColor(GRAY)
        e.backgroundTintList = ColorStateList.valueOf(accent(e.context))
    }

    /** Overlay panel: dark rounded box with a faint logo behind the buttons. */
    fun panelBackground(v: View, density: Float) {
        v.background = LayerDrawable(
            arrayOf<Drawable>(
                shape(PANEL, accent(v.context), 10f * density, strokeWidth(density)),
                LogoBackground(false, 70)
            )
        )
    }

    /** Main screen: black with the whole logo centered and dimmed. */
    fun screenBackground(): Drawable = LayerDrawable(
        arrayOf<Drawable>(ColorDrawable(BLACK), LogoBackground(true, 90))
    )
}
