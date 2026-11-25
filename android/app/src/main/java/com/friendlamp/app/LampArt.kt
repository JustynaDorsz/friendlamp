package com.friendlamp.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** Draws the lamp in any color, for the widget and the app screen. */
object LampArt {
    private val cache = HashMap<String, Bitmap>()

    /** A square picture of the lamp. `color` null means switched off (grey). */
    @Synchronized
    fun bitmap(color: Int?, size: Int): Bitmap {
        val key = "$color/$size"
        cache[key]?.let { return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size * 0.33f

        if (color != null) {
            // Glow around the lamp.
            paint.shader = RadialGradient(
                cx, cy, size / 2f,
                intArrayOf(alpha(color, 0x99), alpha(color, 0x99), alpha(color, 0x38), alpha(color, 0)),
                floatArrayOf(0f, 0.62f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, size / 2f, paint)
            // The lamp: bright in the middle, the chosen color towards the edge.
            paint.shader = RadialGradient(
                cx, cy - radius * 0.18f, radius * 1.25f,
                intArrayOf(mix(color, Color.WHITE, 0.82f), mix(color, Color.WHITE, 0.3f), color, mix(color, Color.BLACK, 0.14f)),
                floatArrayOf(0f, 0.42f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        } else {
            paint.shader = RadialGradient(
                cx, cy - radius * 0.18f, radius * 1.25f,
                intArrayOf(0xFFA6A6A6.toInt(), 0xFF7A7A7A.toInt(), 0xFF565656.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.01f
            paint.color = 0x99C8C8C8.toInt()
            canvas.drawCircle(cx, cy, radius, paint)
        }

        cache[key] = bitmap
        return bitmap
    }

    private fun alpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Blends `color` towards `other` by `amount` (0..1). */
    private fun mix(color: Int, other: Int, amount: Float): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * amount).toInt()
        return Color.rgb(
            channel(Color.red(color), Color.red(other)),
            channel(Color.green(color), Color.green(other)),
            channel(Color.blue(color), Color.blue(other))
        )
    }
}
