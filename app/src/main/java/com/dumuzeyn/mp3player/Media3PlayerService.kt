package com.dumuzeyn.mp3player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.Trace
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dumuzeyn.mp3player.data.playback.PlaybackStateManager
import com.dumuzeyn.mp3player.playback.service.PlaybackErrorRecovery
import com.dumuzeyn.mp3player.playback.service.PlaybackSleepTimer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@SuppressLint("UnsafeOptInUsageError")
class Media3PlayerService : MediaLibraryService() {
    private val mapper = MediaItemMapper { UiPreferencesStore.showArtistName(this) }
    private val transitionPolicy = PlaybackTransitionPolicy()
    private val errorRecovery = PlaybackErrorRecovery()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var artworkProvider: MediaArtworkProvider
    private lateinit var stateManager: PlaybackStateManager
    private lateinit var sleepTimer: PlaybackSleepTimer
    private lateinit var audioEffects: AudioEffectsManager
    private lateinit var loudnessNormalizer: TrackLoudnessNormalizer
    private lateinit var fadeController: PlaybackFadeController
    private lateinit var gapController: PlaybackGapController
    private lateinit var commandHandler: Media3SessionCommandHandler
    private lateinit var eventLogger: PlaybackEventLogger
    private lateinit var historyRecorder: PlaybackHistoryRecorder
    private lateinit var sessionRestorer: PlaybackSessionRestorer
    private lateinit var libraryCallback: VoltuneMediaLibraryCallback
    private lateinit var playbackState: PlaybackServiceState
    private lateinit var editorPreview: EditorPreviewSession
    private var positionSaveJob: Job? = null
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var audioFocusState = "managed"
    private var uninterruptedPlayback = false
    private var noVolumeDucking = false

    override fun onCreate() {
        super.onCreate()
        stateManager = PlaybackStateManager(this)
        sleepTimer = PlaybackSleepTimer(this, ::onSleepTimerExpired)
        audioEffects = AudioEffectsManager(this)
        loudnessNormalizer = TrackLoudnessNormalizer(this)
        artworkProvider = MediaArtworkProvider(this)
        eventLogger = PlaybackEventLogger(this)
        historyRecorder = PlaybackHistoryRecorder(this)
        val controllerAccess = Media3ControllerAccess(Process.myUid(), packageName)

        val playbackPreferences = getSharedPreferences(UninterruptedPlaybackController.PREFS, MODE_PRIVATE)
        val uninterrupted = playbackPreferences.getBoolean(UninterruptedPlaybackController.ENABLED, false)
        val stableVolume = playbackPreferences.getBoolean(StableVolumeController.ENABLED, false)
        uninterruptedPlayback = uninterrupted
        noVolumeDucking = stableVolume
        audioFocusState = if (uninterrupted) "ignored_by_setting" else "managed"
        val attributes = playbackAudioAttributes(stableVolume)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(PlaybackVisualizerBuffer.shared)))
                .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(attributes, !uninterrupted)
            .setHandleAudioBecomingNoisy(!uninterrupted)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        playbackState = PlaybackServiceState(player, mapper, stateManager)
        fadeController = PlaybackFadeController(this, player)
        gapController = PlaybackGapController(this, player) { waiting ->
            playbackState.pauseReason = if (waiting) PauseReason.TRACK_GAP else PauseReason.USER
            playbackState.persist(true)
            if (waiting && ::mediaSession.isInitialized) onUpdateNotification(mediaSession, true)
        }
        editorPreview = EditorPreviewSession(this, player, controllerAccess) { active ->
            playbackState.persistenceSuspended = active
            stopPositionSaver()
            historyRecorder.playing(false)
            fadeController.setPreviewMode(active)
            gapController.setPreviewMode(active)
            if (active) audioEffects.release() else {
                applyAudioEffects()
                playbackState.persist(true)
                PlayerWidgetProvider.updateFromPlayer(this, player)
                if (player.isPlaying) startPositionSaver()
            }
        }
        player.addListener(PlayerEvents())

        commandHandler = Media3SessionCommandHandler(
            player,
            sleepTimer,
            stateManager,
            ::applyAudioEffects,
            { playbackState.stopReason = StopReason.USER },
            playbackState::snapshotBundle,
            controllerAccess,
        )
        libraryCallback = VoltuneMediaLibraryCallback(
            LibraryDatabase(this),
            mapper,
            object : VoltuneMediaLibraryCallback.CommandDelegate {
                override fun handle(
                    controller: MediaSession.ControllerInfo,
                    command: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (command.customAction == Media3Commands.CLEAR_QUEUE) {
                        gapController.cancel()
                        editorPreview.stop(false)
                    }
                    return commandHandler.handle(controller, command, args)
                }

                override fun preview(controller: MediaSession.ControllerInfo, args: Bundle) =
                    editorPreview.command(controller, args)

                override fun beforePlayerCommand(command: Int) {
                    gapController.beforeCommand(command)
                    editorPreview.stop()
                }
                override fun disconnected(controller: MediaSession.ControllerInfo) = editorPreview.disconnected(controller)

                override fun onCommand(action: String) {
                    val separator = action.lastIndexOf('.')
                    logEvent("command_${action.substring(separator + 1).lowercase()}", "none")
                }
            },
            controllerAccess,
            "$packageName.artwork",
        )
        mediaSession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setBitmapLoader(artworkProvider)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        val notificationProvider = object : DefaultMediaNotificationProvider(
            this,
            { NOTIFICATION_ID },
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID,
        ) {
            override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
                if (UiPreferencesStore.showArtistName(this@Media3PlayerService)) {
                    super.getNotificationContentText(metadata)
                } else null
        }
        notificationProvider.setSmallIcon(R.drawable.ic_notification_music)
        setMediaNotificationProvider(notificationProvider)

        sessionRestorer = PlaybackSessionRestorer(this, stateManager, mapper)
        sessionRestorer.restore(player)
        sleepTimer.restore()
        PlayerWidgetProvider.updateFromPlayer(this, player)
        logEvent("service_created", "none")
        VoltuneLog.info("service_created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired ||
            (::gapController.isInitialized && gapController.waiting))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        editorPreview.stop(false)
        logEvent("task_removed", "none")
        if (!player.isPlaying && !gapController.waiting && player.playbackState != Player.STATE_BUFFERING) stopSelf()
    }

    override fun onDestroy() {
        editorPreview.close()
        gapController.close()
        stopPositionSaver()
        sleepTimer.close()
        if (playbackState.stopReason == StopReason.NONE) {
            playbackState.stopReason = StopReason.SERVICE_DESTROYED
        }
        playbackState.persist(true)
        logEvent("service_destroyed", "none", false)
        mediaSession.release()
        fadeController.close()
        player.release()
        audioEffects.release()
        loudnessNormalizer.release()
        artworkProvider.close()
        historyRecorder.close()
        sessionRestorer.close()
        libraryCallback.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onSleepTimerExpired() {
        gapController.cancel()
        editorPreview.stop(false)
        playbackState.pauseReason = PauseReason.SLEEP_TIMER
        playbackState.stopReason = StopReason.SLEEP_TIMER
        player.pause()
        player.stop()
        playbackState.persist(true)
        logEvent("sleep_timer_expired", "none")
    }

    private fun applyAudioEffects() {
        if (editorPreview.active) return
        applyPlaybackBehavior()
        if (loudnessNormalizer.isEnabled) prefetchLoudness()
        val analyzedGain = if (loudnessNormalizer.isEnabled) {
            loudnessNormalizer.cachedGainDb(playbackState.currentTrack())
        } else {
            0.0f
        }
        val appliedGain = audioEffects.adjustedNormalizationGainDb(analyzedGain)
        fadeController.setBaseVolume(AudioEffectsManager.playerVolumeForGainDb(appliedGain))
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            audioEffects.apply(audioSessionId, appliedGain.coerceAtLeast(0.0f))
        }
    }

    private fun applyPlaybackBehavior() {
        val preferences = getSharedPreferences(UninterruptedPlaybackController.PREFS, MODE_PRIVATE)
        val uninterrupted = preferences.getBoolean(UninterruptedPlaybackController.ENABLED, false)
        val stableVolume = preferences.getBoolean(StableVolumeController.ENABLED, false)
        if (uninterrupted == uninterruptedPlayback && stableVolume == noVolumeDucking) return
        uninterruptedPlayback = uninterrupted
        noVolumeDucking = stableVolume
        player.setAudioAttributes(playbackAudioAttributes(stableVolume), !uninterrupted)
        player.setHandleAudioBecomingNoisy(!uninterrupted)
        audioFocusState = if (uninterrupted) "ignored_by_setting" else "managed"
    }

    private fun playbackAudioAttributes(stableVolume: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(
                if (stableVolume) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC,
            )
            .build()

    private fun prefetchLoudness() {
        if (!loudnessNormalizer.isEnabled || player.mediaItemCount == 0) return
        val upcoming = ArrayList<Track>(3)
        val start = player.currentMediaItemIndex.coerceAtLeast(0)
        repeat(minOf(3, player.mediaItemCount)) { offset ->
            mapper.fromMediaItem(player.getMediaItemAt((start + offset) % player.mediaItemCount))
                ?.let(upcoming::add)
        }
        loudnessNormalizer.prefetch(upcoming, 0) { analyzed ->
            serviceScope.launch {
                if (playbackState.currentTrack()?.trackId == analyzed.trackId) {
                    applyAudioEffects()
                }
            }
        }
    }

    private fun recoverFromError(error: PlaybackException) {
        val failures = errorRecovery.recordError()
        val queueSize = player.mediaItemCount
        val recoverable = queueSize > 1
        playbackState.lastError = PlaybackErrorInfo(
            error.errorCode,
            error.errorCodeName,
            recoverable,
            error.message,
            playbackState.currentMediaId(),
        )
        logEvent("player_error", playbackState.lastError?.category ?: "unknown")
        if (transitionPolicy.shouldSkipError(failures, queueSize, recoverable)) {
            val next = (player.currentMediaItemIndex.coerceAtLeast(0) + 1) % queueSize
            player.seekToDefaultPosition(next)
            player.prepare()
            player.play()
            return
        }
        playbackState.stopReason = transitionPolicy.stopReasonForError(
            failures,
            queueSize,
            recoverable,
        )
        player.stop()
        playbackState.persist(true)
    }

    private fun updateDurationAsync(uri: String, durationMs: Int) {
        if (uri.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            TrackStore.updateDuration(this@Media3PlayerService, uri, durationMs)
        }
    }

    private fun startPositionSaver() {
        stopPositionSaver()
        positionSaveJob = serviceScope.launch {
            delay(POSITION_SAVE_INTERVAL_MS)
            while (isActive && player.isPlaying) {
                historyRecorder.sample(player.duration)
                playbackState.persist(false)
                delay(POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionSaver() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private fun logEvent(type: String, errorCategory: String) {
        val foreground = player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        logEvent(type, errorCategory, foreground)
    }

    private fun logEvent(type: String, errorCategory: String, foreground: Boolean) {
        eventLogger.record(
            type,
            playbackState.snapshot(),
            errorCategory,
            audioFocusState,
            true,
            foreground,
        )
    }

    private inline fun traced(section: String, action: () -> Unit) {
        Trace.beginSection(section)
        try {
            action()
        } finally {
            Trace.endSection()
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) =
            traced("Voltune/Playback.isPlayingChanged") {
                if (editorPreview.active) return@traced
                historyRecorder.playing(isPlaying)
                stopPositionSaver()
                if (isPlaying) {
                    playbackState.pauseReason = PauseReason.NONE
                    playbackState.stopReason = StopReason.NONE
                    playbackState.lastError = null
                    errorRecovery.resetConsecutiveErrors()
                    transitionPolicy.onUserPlay()
                    prefetchLoudness()
                    startPositionSaver()
                }
                playbackState.persist(true)
                PlayerWidgetProvider.updateFromPlayer(this@Media3PlayerService, player)
                logEvent(if (isPlaying) "playback_started" else "playback_paused", "none")
            }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (editorPreview.active) return
            if (!playWhenReady) {
                when {
                    gapController.waiting -> playbackState.pauseReason = PauseReason.TRACK_GAP
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                        playbackState.pauseReason = transitionPolicy.onTemporaryAudioFocusLoss(true)
                        audioFocusState = "lost"
                    }
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                        playbackState.pauseReason = transitionPolicy.onAudioBecomingNoisy()
                        audioFocusState = "audio_becoming_noisy"
                    }
                    playbackState.pauseReason == PauseReason.NONE -> {
                        playbackState.pauseReason = PauseReason.USER
                        transitionPolicy.onUserPause()
                        audioFocusState = "inactive"
                    }
                }
            } else {
                audioFocusState = "active_or_not_required"
            }
            playbackState.persist(false)
            logEvent("play_when_ready_changed", "none")
        }

        override fun onPlaybackStateChanged(state: Int) =
            traced("Voltune/Playback.stateChanged") {
                if (editorPreview.active) return@traced
                if (state == Player.STATE_READY) {
                    errorRecovery.resetConsecutiveErrors()
                    historyRecorder.sample(player.duration)
                    updateDurationAsync(
                        playbackState.currentUri(),
                        PlaybackServiceState.safeInt(player.duration),
                    )
                } else if (state == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
                    playbackState.stopReason = StopReason.QUEUE_ENDED
                    historyRecorder.ended(player.duration)
                }
                playbackState.persist(true)
                PlayerWidgetProvider.updateFromPlayer(this@Media3PlayerService, player)
                logEvent("playback_state_changed", "none")
            }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            traced("Voltune/Playback.mediaItemTransition") {
                if (editorPreview.active || EditorPreviewSession.isPreview(mediaItem)) return@traced
                historyRecorder.transition(mediaItem?.mediaId.orEmpty(), player.duration, reason)
                audioEffects.release()
                errorRecovery.resetConsecutiveErrors()
                playbackState.lastError = null
                prefetchLoudness()
                applyAudioEffects()
                playbackState.persist(true)
                PlayerWidgetProvider.updateFromPlayer(this@Media3PlayerService, player)
                logEvent("media_item_transition", "none")
            }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (!editorPreview.active) PlayerWidgetProvider.updateFromPlayer(this@Media3PlayerService, player)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!editorPreview.active) recoverFromError(error)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            this@Media3PlayerService.audioSessionId = audioSessionId
            applyAudioEffects()
            logEvent("audio_session_changed", "none")
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            logEvent("repeat_mode_changed", "none")
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            logEvent("shuffle_mode_changed", "none")
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 7
        const val POSITION_SAVE_INTERVAL_MS = 7_000L
    }
}
