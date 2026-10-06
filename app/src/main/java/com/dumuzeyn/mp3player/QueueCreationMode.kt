package com.dumuzeyn.mp3player

internal enum class QueueCreationMode(val english: String, val russian: String) {
    RANDOM("Random queue", "Случайная очередь"),
    SIMILAR("Similar queue", "Похожая очередь"),
    RECENT("Recent listens", "Недавно слушали"),
    OLDEST("Long unplayed", "Давно не слушали");

    companion object {
        const val PREFERENCE = "queueCreationMode"

        fun fromStored(value: String?): QueueCreationMode =
            entries.firstOrNull { it.name == value } ?: RANDOM
    }
}
