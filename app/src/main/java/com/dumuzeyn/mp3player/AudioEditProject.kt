package com.dumuzeyn.mp3player

import java.util.UUID

internal data class AudioEditClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val title: String,
    val sourceDurationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = sourceDurationMs,
    val lane: Int = 0,
    val offsetMs: Long = 0,
    val gain: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
) {
    val durationMs: Long get() = endMs - startMs
    val finishMs: Long get() = offsetMs + durationMs

    init {
        require(uri.isNotBlank() && sourceDurationMs in 1..MAX_TIME_MS)
        require(startMs >= 0 && endMs > startMs && endMs <= sourceDurationMs)
        require(lane in 0 until MAX_LANES && offsetMs >= 0 && finishMs <= MAX_TIME_MS)
        require(gain.isFinite() && gain in 0f..MAX_GAIN)
        require(fadeInMs in 0..MAX_FADE_MS && fadeOutMs in 0..MAX_FADE_MS)
    }

    companion object {
        const val MAX_LANES = 8
        const val MAX_TIME_MS = 86_400_000L
        const val MAX_GAIN = 2f
        const val MAX_FADE_MS = 1_000L
        const val SMOOTH_JOIN_MS = 24L
    }
}

internal data class AudioEditProject(val clips: List<AudioEditClip> = emptyList()) {
    val durationMs: Long get() = clips.maxOfOrNull(AudioEditClip::finishMs) ?: 0

    init {
        require(clips.size <= 200 && clips.map { it.id }.distinct().size == clips.size)
        clips.groupBy { it.lane }.values.forEach { lane ->
            lane.sortedBy { it.offsetMs }.zipWithNext().forEach { (left, right) ->
                require(left.finishMs <= right.offsetMs) { "Clips overlap on the same lane" }
            }
        }
    }

    fun replace(clip: AudioEditClip): AudioEditProject {
        require(clips.any { it.id == clip.id })
        return copy(clips = clips.map { if (it.id == clip.id) clip else it })
    }

    fun append(clip: AudioEditClip, lane: Int): AudioEditProject = copy(clips = clips + clip.copy(
        lane = lane, offsetMs = clips.filter { it.lane == lane }.maxOfOrNull { it.finishMs } ?: 0,
    ))

    fun remove(id: String): AudioEditProject = copy(clips = clips.filterNot { it.id == id })

    fun split(id: String, sourcePositionMs: Long): AudioEditProject {
        val clip = clips.first { it.id == id }
        require(sourcePositionMs > clip.startMs && sourcePositionMs < clip.endMs)
        val left = clip.copy(title = "${clip.title} (1)", endMs = sourcePositionMs, fadeOutMs = 0)
        val right = clip.copy(id = UUID.randomUUID().toString(), startMs = sourcePositionMs,
            title = "${clip.title} (2)", offsetMs = clip.offsetMs + left.durationMs, fadeInMs = 0)
        return copy(clips = clips.flatMap { if (it.id == id) listOf(left, right) else listOf(it) })
    }

    fun removeRange(id: String, fromMs: Long, toMs: Long, closeGap: Boolean = true,
        smoothJoin: Boolean = false): AudioEditProject {
        val clip = clips.first { it.id == id }
        require(fromMs >= clip.startMs && toMs <= clip.endMs && fromMs < toMs)
        val hasLeft = fromMs > clip.startMs
        val hasRight = toMs < clip.endMs
        val joinFade = if (smoothJoin && hasLeft && hasRight) AudioEditClip.SMOOTH_JOIN_MS else 0L
        val rightOffset = clip.offsetMs + if (closeGap) fromMs - clip.startMs else toMs - clip.startMs
        val parts = buildList {
            if (hasLeft) add(clip.copy(endMs = fromMs, fadeOutMs = joinFade))
            if (hasRight) add(clip.copy(id = UUID.randomUUID().toString(), startMs = toMs,
                offsetMs = rightOffset, fadeInMs = joinFade))
        }
        val removed = if (closeGap) toMs - fromMs else 0
        return copy(clips = clips.flatMap {
            when {
                it.id == id -> parts
                it.lane == clip.lane && it.offsetMs >= clip.finishMs ->
                    listOf(it.copy(offsetMs = it.offsetMs - removed))
                else -> listOf(it)
            }
        })
    }

    fun nearestFreeOffset(id: String, lane: Int, nearMs: Long): Long {
        val moving = clips.first { it.id == id }
        require(lane in 0 until AudioEditClip.MAX_LANES && nearMs >= 0)
        val occupied = clips.filter { it.id != id && it.lane == lane }.sortedBy { it.offsetMs }
        val latest = AudioEditClip.MAX_TIME_MS - moving.durationMs
        var gapStart = 0L
        var best: Long? = null
        fun consider(gapEnd: Long) {
            if (gapStart > gapEnd) return
            val candidate = nearMs.coerceIn(gapStart, gapEnd)
            if (best == null || kotlin.math.abs(candidate - nearMs) <
                kotlin.math.abs(best!! - nearMs)) best = candidate
        }
        occupied.forEach { other ->
            consider(minOf(latest, other.offsetMs - moving.durationMs))
            gapStart = maxOf(gapStart, other.finishMs)
        }
        consider(latest)
        return best ?: throw IllegalArgumentException("No free space on lane")
    }

    fun moveToNewEdgeLane(id: String, above: Boolean, nearMs: Long): AudioEditProject {
        val moving = clips.first { it.id == id }
        val others = clips.filterNot { it.id == id }
        val used = others.map(AudioEditClip::lane).distinct().sorted()
        require(used.size < AudioEditClip.MAX_LANES)
        val laneMap = used.mapIndexed { index, lane ->
            lane to if (above) index + 1 else index
        }.toMap()
        val targetLane = if (above) 0 else used.size
        val remapped = others.map { it.copy(lane = laneMap.getValue(it.lane)) }
        val maxOffset = AudioEditClip.MAX_TIME_MS - moving.durationMs
        return AudioEditProject(remapped + moving.copy(
            lane = targetLane,
            offsetMs = nearMs.coerceIn(0, maxOffset),
        ))
    }

    fun concatenate(): AudioEditProject {
        var cursor = 0L
        return copy(clips = clips.sortedWith(compareBy<AudioEditClip> { it.lane }.thenBy { it.offsetMs }).map {
            it.copy(lane = 0, offsetMs = cursor).also { result -> cursor = result.finishMs }
        })
    }
}
