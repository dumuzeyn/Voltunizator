package com.dumuzeyn.mp3player

internal object InterTrackDelayPolicy {
    const val PREFS = "mp3_player_ui"
    const val SECONDS = "interTrackDelaySeconds"
    const val MAX_SECONDS = 300

    fun milliseconds(seconds: Int): Long = seconds.coerceIn(0, MAX_SECONDS) * 1000L
    fun remaining(seconds: Int, elapsedMs: Long): Long =
        (milliseconds(seconds) - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
}
