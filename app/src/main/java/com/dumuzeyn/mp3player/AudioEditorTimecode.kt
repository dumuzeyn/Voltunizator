package com.dumuzeyn.mp3player

import java.util.Locale
import kotlin.math.roundToLong

internal object AudioEditorTimecode {
    fun parse(input: String): Long? {
        val parts = input.trim().replace(',', '.').split(':')
        if (parts.size !in 1..3 || parts.any(String::isBlank)) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0 || parts.size > 1 && seconds >= 60) return null
        val minutes = if (parts.size > 1) parts[parts.lastIndex - 1].toLongOrNull() ?: return null else 0
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        if (minutes < 0 || hours < 0 || parts.size == 3 && minutes >= 60) return null
        val total = hours * 3_600_000.0 + minutes * 60_000.0 + seconds * 1000.0
        return if (total.isFinite() && total in 0.0..AudioEditClip.MAX_TIME_MS.toDouble())
            total.roundToLong() else null
    }

    fun format(valueMs: Long): String {
        val safe = valueMs.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d.%03d", safe / 60_000,
            safe / 1000 % 60, safe % 1000)
    }
}
