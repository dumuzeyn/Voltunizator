package com.dumuzeyn.mp3player

enum class PauseReason {
    NONE,
    USER,
    AUDIO_FOCUS,
    AUDIO_BECOMING_NOISY,
    SYSTEM_INTERRUPTION,
    SLEEP_TIMER,
    TRACK_GAP,
}
