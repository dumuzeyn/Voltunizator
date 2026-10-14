package com.dumuzeyn.mp3player

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.dumuzeyn.mp3player.data.playback.PlaybackStateManager

/** Sends UI commands to Media3 and publishes one read-only playback projection. */
class PlaybackController(private val host: MainActivityCore) : Player.Listener {
    private val mapper = MediaItemMapper { host.appearanceState.showArtistName }
    private val connection = MediaControllerConnection(host, this, ::onControllerConnected)
    private var discardExpiredSession = false

    fun connect() = connection.connect()

    fun restorePersistedUiState() {
        val stateManager = PlaybackStateManager(host)
        val state = stateManager.load()
        if (!hasSavedSession(state)) {
            host.getSharedPreferences("player_tool_session", 0).edit().remove("collection").apply()
            return
        }
        val resumeWindowMs = host.appearanceState.resumeWindowMinutes.coerceAtLeast(0).toLong() * 60_000L
        if (
            MiniPlayerRetentionPolicy.isExpired(
                state.playing,
                state.inactiveSince,
                state.savedAt,
                System.currentTimeMillis(),
                resumeWindowMs,
            )
        ) {
            discardExpiredSession = true
            stateManager.clear()
            clearProjectedSession()
            return
        }
        var current = host.findTrack(state.uri)
        val restoredQueue = PlaybackQueueResolver.restore(
            host.libraryState.tracks,
            state.queueUris,
            current,
        )
        if (restoredQueue.isEmpty()) return
        var index = state.index.coerceIn(0, restoredQueue.lastIndex)
        if (current == null) {
            current = restoredQueue[index]
        } else {
            restoredQueue.indexOf(current).takeIf { it >= 0 }?.let { index = it }
        }
        val mediaIds = restoredQueue.mapTo(ArrayList(), mapper::mediaId)
        host.playbackUiState.queue.apply {
            clear()
            addAll(restoredQueue)
        }
        host.updatePlaybackSnapshot(
            PlaybackSnapshot(
                mediaIds,
                mapper.mediaId(current),
                index,
                state.position.toLong(),
                maxOf(current.durationMs, state.duration).toLong(),
                state.playing,
                Player.STATE_READY,
                RepeatModeMapper.toMedia3(state.loopMode),
                state.shuffle,
                PlaybackPhase.READY,
                if (state.playing) PauseReason.NONE else PauseReason.USER,
                StopReason.NONE,
                null,
                state.savedAt,
            ),
        )
    }

    fun enforceMiniPlayerRetention() {
        val stateManager = PlaybackStateManager(host)
        val state = stateManager.load()
        if (!hasSavedSession(state)) return
        val retentionMs = host.appearanceState.resumeWindowMinutes.coerceAtLeast(0).toLong() * 60_000L
        if (
            !MiniPlayerRetentionPolicy.isExpired(
                state.playing,
                state.inactiveSince,
                state.savedAt,
                System.currentTimeMillis(),
                retentionMs,
            )
        ) return
        discardExpiredSession = true
        stateManager.clear()
        discardExpiredControllerSession()
        clearProjectedSession()
    }

    fun release() = connection.close()

    internal fun editorPreviewCommand(args: Bundle, valid: () -> Boolean = { true },
        done: (SessionResult?) -> Unit) = connection.execute { controller ->
        if (!valid()) return@execute
        val future = controller.sendCustomCommand(Media3Commands.EDITOR_PREVIEW_COMMAND, args)
        future.addListener({ host.uiHandler.post { if (valid()) done(runCatching { future.get() }.getOrNull()) } },
            java.util.concurrent.Executor(Runnable::run))
    }

    fun submitQueue(
        source: List<Track>,
        index: Int,
        positionMs: Int,
        repeatMode: Int,
        playWhenReady: Boolean,
    ) {
        val queue = ArrayList(source)
        connection.execute { controller ->
            if (queue.isEmpty()) return@execute
            val items = queue.mapTo(ArrayList(), mapper::toMediaItem)
            controller.setMediaItems(
                items,
                index.coerceIn(0, items.lastIndex),
                positionMs.coerceAtLeast(0).toLong(),
            )
            controller.shuffleModeEnabled = false
            controller.repeatMode = RepeatModeMapper.toMedia3(repeatMode)
            controller.prepare()
            if (playWhenReady) controller.play() else controller.pause()
        }
    }

    fun toggle() = connection.execute { controller ->
        if (controller.mediaItemCount == 0) return@execute
        if (controller.playbackState != Player.STATE_IDLE && (controller.isPlaying || controller.playWhenReady)) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() = connection.execute { controller ->
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.play()
        }
    }

    fun previous() = connection.execute { controller ->
        controller.seekToPreviousMediaItem()
        controller.play()
    }

    fun cycleRepeatMode() {
        val nextMode = (host.repeatMode() + 1) % 3
        connection.execute { it.repeatMode = RepeatModeMapper.toMedia3(nextMode) }
    }

    fun setRepeatMode(mode: Int) = connection.execute {
        it.repeatMode = RepeatModeMapper.toMedia3(mode.coerceIn(0, 2))
    }

    fun playbackSpeed(): Float = connection.controller?.playbackParameters?.speed ?: 1f

    fun setPlaybackSpeed(speed: Float) = connection.execute {
        it.setPlaybackSpeed(PlaybackSpeedPolicy.constrain(speed))
    }

    fun clearQueue() = connection.execute { controller ->
        controller.pause()
        controller.stop()
        controller.clearMediaItems()
        controller.sendCustomCommand(Media3Commands.CLEAR_QUEUE_COMMAND, Bundle.EMPTY)
    }

    fun addQueueItem(track: Track) = connection.execute {
        it.addMediaItem(mapper.toMediaItem(track))
    }

    fun addQueueItems(tracks: List<Track>) {
        val requested = ArrayList(tracks)
        connection.execute { controller ->
            val additions = requested
                .filter { indexOfMediaId(controller, mapper.mediaId(it)) < 0 }
                .mapTo(ArrayList(), mapper::toMediaItem)
            if (additions.isNotEmpty()) controller.addMediaItems(additions)
        }
    }

    fun playNext(track: Track) = connection.execute { controller ->
        val existing = indexOfMediaId(controller, mapper.mediaId(track))
        val insertion = minOf(controller.currentMediaItemIndex + 1, controller.mediaItemCount)
        if (existing >= 0) {
            val destination = if (existing < insertion) insertion - 1 else insertion
            if (existing != destination) controller.moveMediaItem(existing, destination)
        } else {
            controller.addMediaItem(insertion, mapper.toMediaItem(track))
        }
    }

    fun moveQueueItem(from: Int, to: Int) = connection.execute { controller ->
        if (from in 0..<controller.mediaItemCount && to in 0..<controller.mediaItemCount && from != to) {
            controller.moveMediaItem(from, to)
        }
    }

    fun seekQueueItem(index: Int) = connection.execute { controller ->
        if (index in 0..<controller.mediaItemCount) {
            controller.seekToDefaultPosition(index)
            controller.play()
        }
    }

    fun removeQueueItem(index: Int) = connection.execute { controller ->
        if (index in 0..<controller.mediaItemCount) controller.removeMediaItem(index)
    }

    fun removeQueueItems(mediaIds: Set<String>) {
        val removed = HashSet(mediaIds)
        connection.execute { controller ->
            for (index in controller.mediaItemCount - 1 downTo 0) {
                if (controller.getMediaItemAt(index).mediaId in removed) {
                    controller.removeMediaItem(index)
                }
            }
            if (controller.mediaItemCount == 0) {
                controller.stop()
                controller.sendCustomCommand(Media3Commands.CLEAR_QUEUE_COMMAND, Bundle.EMPTY)
            }
        }
    }

    fun seekTo(positionMs: Int) = connection.execute {
        it.seekTo(positionMs.coerceAtLeast(0).toLong())
    }

    fun startSleepTimer(delayMs: Long) {
        val args = Bundle().apply {
            putLong(Media3Commands.ARG_TIMER_MS, delayMs.coerceAtLeast(1_000L))
        }
        connection.execute {
            it.sendCustomCommand(Media3Commands.TIMER_START_COMMAND, args)
        }
    }

    fun cancelSleepTimer() = connection.execute {
        it.sendCustomCommand(Media3Commands.TIMER_CANCEL_COMMAND, Bundle.EMPTY)
    }

    fun refreshAudioEffects() = connection.execute {
        it.sendCustomCommand(Media3Commands.AUDIO_EFFECTS_COMMAND, Bundle.EMPTY)
    }

    fun refreshArtistVisibility() = connection.execute { controller ->
        if (EditorPreviewSession.isPreview(controller.currentMediaItem)) return@execute
        val tracksById = host.libraryState.tracks.associateBy(Track::trackId)
        val items = (0 until controller.mediaItemCount).map { index ->
            val item = controller.getMediaItemAt(index)
            val artist = if (host.appearanceState.showArtistName) {
                tracksById[item.mediaId]?.artist ?: item.mediaMetadata.artist
            } else ""
            item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setArtist(artist).build(),
            ).build()
        }
        if (items.isNotEmpty()) controller.replaceMediaItems(0, items.size, items)
        PlayerWidgetProvider.updateFromPlayer(host, controller)
    }

    fun currentPosition(): Long = connection.controller
        ?.takeUnless { EditorPreviewSession.isPreview(it.currentMediaItem) }
        ?.currentPosition
        ?.coerceAtLeast(0L)
        ?: host.playbackSnapshot().positionMs

    fun duration(): Long {
        if (EditorPreviewSession.isPreview(connection.controller?.currentMediaItem)) return host.playbackSnapshot().durationMs
        val value = connection.controller?.duration ?: return host.playbackSnapshot().durationMs
        return if (value == C.TIME_UNSET) host.playbackSnapshot().durationMs else value.coerceAtLeast(0L)
    }

    fun hasPlaybackSession(): Boolean = connection.controller
        ?.let { it.mediaItemCount > 0 }
        ?: PlaybackStateManager(host).load().queueUris.isNotEmpty()

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
        val refreshRows = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
        synchronizeUi(connection.controller ?: return, refreshRows)
    }

    private fun onControllerConnected(controller: MediaController) {
        discardExpiredControllerSession()
        synchronizeUi(controller, true)
    }

    private fun discardExpiredControllerSession() {
        val controller = connection.controller ?: return
        if (!discardExpiredSession || controller.playWhenReady) return
        discardExpiredSession = false
        controller.stop()
        controller.clearMediaItems()
        controller.sendCustomCommand(Media3Commands.CLEAR_QUEUE_COMMAND, Bundle.EMPTY)
    }

    private fun clearProjectedSession() {
        host.getSharedPreferences("player_tool_session", 0).edit().remove("collection").apply()
        host.playbackUiState.queue.clear()
        host.updatePlaybackSnapshot(PlaybackSnapshot.empty())
        host.playerUiController.closeExpiredSession()
    }

    private fun synchronizeUi(controller: MediaController, refreshRows: Boolean) {
        if (EditorPreviewSession.isPreview(controller.currentMediaItem)) return
        val previous = host.libraryState.tracks.getOrNull(host.currentTrackIndex())
        val current = currentTrack(controller)
        host.updatePlaybackSnapshot(snapshotFromController(controller))
        synchronizeQueueProjection(controller)
        val trackChanged = previous?.uri != current?.uri
        host.playerUiController.updateMini()
        if (trackChanged || refreshRows) host.refreshAfterTrackChange()
        host.playerUiController.syncPlaybackUi()
    }

    private fun synchronizeQueueProjection(controller: MediaController) {
        val tracksById = HashMap<String, Track>(host.libraryState.tracks.size)
        val tracksByUri = HashMap<String, Track>(host.libraryState.tracks.size)
        host.libraryState.tracks.forEach { track ->
            tracksById[mapper.mediaId(track)] = track
            tracksByUri[track.uri] = track
        }
        host.playbackUiState.queue.apply {
            clear()
            repeat(controller.mediaItemCount) { itemIndex ->
                val item = controller.getMediaItemAt(itemIndex)
                val track = tracksById[item.mediaId]
                    ?: item.localConfiguration?.uri?.toString()?.let(tracksByUri::get)
                if (track != null) add(track)
            }
        }
    }

    private fun snapshotFromController(controller: MediaController): PlaybackSnapshot {
        val mediaIds = buildList(controller.mediaItemCount) {
            repeat(controller.mediaItemCount) { add(controller.getMediaItemAt(it).mediaId) }
        }
        val phase = phase(controller.playbackState)
        val duration = controller.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        return PlaybackSnapshot(
            mediaIds,
            controller.currentMediaItem?.mediaId.orEmpty(),
            controller.currentMediaItemIndex,
            controller.currentPosition,
            duration,
            controller.playWhenReady,
            controller.playbackState,
            controller.repeatMode,
            controller.shuffleModeEnabled,
            phase,
            if (controller.playWhenReady) PauseReason.NONE else PauseReason.USER,
            if (phase == PlaybackPhase.ENDED) StopReason.QUEUE_ENDED else StopReason.NONE,
            null,
            System.currentTimeMillis(),
        )
    }

    private fun currentTrack(controller: MediaController): Track? = controller.currentMediaItem
        ?.localConfiguration
        ?.uri
        ?.toString()
        ?.let(host::findTrack)

    private fun indexOfMediaId(controller: MediaController, mediaId: String): Int =
        (0..<controller.mediaItemCount).firstOrNull {
            controller.getMediaItemAt(it).mediaId == mediaId
        } ?: -1

    private fun hasSavedSession(state: PlaybackStateManager.State): Boolean =
        state.uri.isNotEmpty() || state.queueUris.isNotEmpty()

    private fun phase(playbackState: Int): PlaybackPhase = when (playbackState) {
        Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
        Player.STATE_READY -> PlaybackPhase.READY
        Player.STATE_ENDED -> PlaybackPhase.ENDED
        else -> PlaybackPhase.IDLE
    }
}
