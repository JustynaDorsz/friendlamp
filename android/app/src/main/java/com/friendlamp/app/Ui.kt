package com.friendlamp.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helpers shared by the screens. */
object Ui {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** A rounded choice button for a horizontal row; the chosen one is filled. */
    fun chip(context: Context, label: String, selected: Boolean, onPick: () -> Unit): View {
        val view = TextView(context)
        view.text = label
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        view.maxLines = 1
        view.setTextColor(if (selected) 0xFF2A1A05.toInt() else context.getColor(R.color.ink))
        val shape = GradientDrawable()
        shape.cornerRadius = dp(context, 18).toFloat()
        if (selected) {
            shape.setColor(context.getColor(R.color.warm))
        } else {
            shape.setColor(context.getColor(R.color.bg))
            shape.setStroke(dp(context, 1), context.getColor(R.color.line))
        }
        view.background = shape
        view.setOnClickListener { onPick() }
        val params = LinearLayout.LayoutParams(0, dp(context, 38), 1f)
        params.marginEnd = dp(context, 6)
        view.layoutParams = params
        return view
    }
}
