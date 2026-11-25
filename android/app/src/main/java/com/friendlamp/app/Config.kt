package com.friendlamp.app

object Config {
    /** The FriendLamp server (the Cloudflare Worker). */
    const val SERVER = "https://friendlamp.justyna-dorsz.workers.dev"

    // Defaults for the settings screen.
    const val DEFAULT_MY_COLOR = 0xFF4A90FF.toInt() // blue
    const val DEFAULT_FRIEND_COLOR = 0xFFFF9A3D.toInt() // orange
    const val DEFAULT_LIT_MINUTES = 30

    /** Colors offered in settings. */
    val PALETTE = intArrayOf(
        0xFF4A90FF.toInt(), // blue
        0xFFFF9A3D.toInt(), // orange
        0xFFFF5C9A.toInt(), // pink
        0xFF4CD471.toInt(), // green
        0xFFA06BFF.toInt(), // purple
        0xFFFFD23D.toInt(), // yellow
        0xFFFF5247.toInt(), // red
        0xFF2ED3C6.toInt(), // teal
    )

    /** "Stay lit for" choices, in minutes. */
    val LIT_CHOICES = intArrayOf(2, 10, 30, 60, 120)
}
