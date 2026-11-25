package com.friendlamp.app

object Config {
    /** The FriendLamp server (the Cloudflare Worker). */
    const val SERVER = "https://friendlamp.justyna-dorsz.workers.dev"

    /**
     * How long the lamp stays lit after the other person thinks of you.
     * TESTING VALUE: 2 minutes. For normal use change to 60 * 60 * 1000L.
     */
    const val LIT_MS = 2 * 60 * 1000L
}
