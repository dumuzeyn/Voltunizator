package com.dumuzeyn.mp3player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle-independent UI projection of the authoritative Media3 state. */
class PlaybackUiState {
    @JvmField val queue = ArrayList<Track>()
    @JvmField var sleepTimerEndsAt: Long = 0L

    private val mutableState = MutableStateFlow(PlaybackSnapshot.empty())
    private var cachedMediaId = ""
    private var cachedTrackIndex = -1

    fun state(): StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()

    fun snapshot(): PlaybackSnapshot = mutableState.value

    fun updateSnapshot(value: PlaybackSnapshot?) {
        val next = value ?: PlaybackSnapshot.empty()
        if (mutableState.value.currentMediaId != next.currentMediaId) {
            cachedMediaId = ""
            cachedTrackIndex = -1
        }
        mutableState.value = next
    }

    fun currentTrackIndex(library: LibraryState): Int {
        val currentMediaId = mutableState.value.currentMediaId
        if (currentMediaId.isEmpty()) return -1
        if (
            cachedMediaId == currentMediaId &&
            cachedTrackIndex in library.tracks.indices &&
            MediaItemMapper.matchesMediaId(library.tracks[cachedTrackIndex], currentMediaId)
        ) {
            return cachedTrackIndex
        }
        val index = library.tracks.indexOfFirst {
            MediaItemMapper.matchesMediaId(it, currentMediaId)
        }
        cachedMediaId = currentMediaId
        cachedTrackIndex = index
        return index
    }

    fun isPlaying(): Boolean {
        val snapshot = mutableState.value
        return snapshot.playWhenReady &&
            snapshot.queueMediaIds.isNotEmpty() &&
            (snapshot.phase == PlaybackPhase.READY || snapshot.phase == PlaybackPhase.BUFFERING)
    }

    fun repeatMode(): Int = RepeatModeMapper.fromMedia3(mutableState.value.repeatMode)

    fun shuffleEnabled(): Boolean = mutableState.value.shuffleEnabled
}
