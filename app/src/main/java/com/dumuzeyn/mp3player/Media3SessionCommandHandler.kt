package com.dumuzeyn.mp3player

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.dumuzeyn.mp3player.data.playback.PlaybackStateManager
import com.dumuzeyn.mp3player.playback.service.PlaybackSleepTimer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Handles Voltune-specific commands which are not part of the standard Player API. */
@SuppressLint("UnsafeOptInUsageError")
internal class Media3SessionCommandHandler(
    private val player: Player,
    private val sleepTimer: PlaybackSleepTimer,
    private val stateManager: PlaybackStateManager,
    private val applyAudioEffects: () -> Unit,
    private val onQueueCleared: () -> Unit,
    private val snapshotProvider: () -> Bundle,
    private val controllerAccess: Media3ControllerAccess,
) {
    fun handle(
        controller: MediaSession.ControllerInfo,
        command: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (command.customAction !in Media3Commands.internalActions) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        if (!controllerAccess.canUseInternalCommand(controller, command.customAction)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        }
        return when (command.customAction) {
            Media3Commands.TIMER_START -> {
                sleepTimer.start(args.getLong(Media3Commands.ARG_TIMER_MS, 0L))
                success()
            }
            Media3Commands.TIMER_CANCEL -> {
                sleepTimer.cancel()
                success()
            }
            Media3Commands.AUDIO_EFFECTS -> {
                applyAudioEffects()
                success()
            }
            Media3Commands.CLEAR_QUEUE -> {
                player.pause()
                player.stop()
                player.clearMediaItems()
                stateManager.clear()
                onQueueCleared()
                success()
            }
            Media3Commands.DIAGNOSTIC_SNAPSHOT -> Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS, snapshotProvider()),
            )
            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    private fun success(): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
}
