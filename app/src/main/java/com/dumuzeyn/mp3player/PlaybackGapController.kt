package com.dumuzeyn.mp3player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer

/** Pauses only automatic item boundaries; a bounded wake lock keeps screen-off gaps reliable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackGapController(context: Context, private val player: ExoPlayer,
    private val changed: (Boolean) -> Unit) : Player.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener, AutoCloseable {
    private val preferences = context.getSharedPreferences(InterTrackDelayPolicy.PREFS, 0)
    private val handler = Handler(player.applicationLooper)
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voltunizator:track-gap")
    private var preview = false
    private var closed = false
    private var startedAt = 0L
    private var itemId = ""
    private var itemIndex = -1
    var waiting = false
        private set
    private val resume = Runnable { resumeNow() }

    init {
        wakeLock.setReferenceCounted(false)
        preferences.registerOnSharedPreferenceChangeListener(this)
        player.addListener(this)
        refresh()
    }

    private fun seconds() = preferences.getInt(InterTrackDelayPolicy.SECONDS, 0)
    private fun hasContinuation() = player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE
    private fun sameItem() = player.currentMediaItem?.mediaId == itemId && player.currentMediaItemIndex == itemIndex

    fun setPreviewMode(active: Boolean) { preview = active; cancel(); refresh() }

    fun beforeCommand(command: Int) {
        if (command in setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM)) cancel()
    }

    fun cancel() {
        handler.removeCallbacks(resume)
        if (wakeLock.isHeld) wakeLock.release()
        if (waiting) { waiting = false; changed(false) }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
            !preview && InterTrackDelayPolicy.milliseconds(seconds()) > 0 && hasContinuation()) {
            cancel()
            startedAt = SystemClock.elapsedRealtime()
            itemId = player.currentMediaItem?.mediaId.orEmpty()
            itemIndex = player.currentMediaItemIndex
            waiting = true
            changed(true)
            schedule(InterTrackDelayPolicy.milliseconds(seconds()))
        } else cancel()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(resume)
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(delayMs + 30_000L)
        handler.postDelayed(resume, delayMs)
    }

    private fun resumeNow() {
        val canResume = waiting && sameItem() && hasContinuation() && !preview &&
            !player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playerError == null
        cancel()
        if (canResume) player.play()
    }

    private fun refresh() {
        if (closed) return
        player.pauseAtEndOfMediaItems = !preview && InterTrackDelayPolicy.milliseconds(seconds()) > 0L
        if (waiting) {
            val remaining = InterTrackDelayPolicy.remaining(seconds(), SystemClock.elapsedRealtime() - startedAt)
            if (remaining == 0L) resumeNow() else schedule(remaining)
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key == InterTrackDelayPolicy.SECONDS) handler.post { refresh() }
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (waiting) cancel()
    }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (waiting && !sameItem()) cancel() }
    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED && !hasContinuation()) cancel()
    }
    override fun onPlayerError(error: PlaybackException) = cancel()

    override fun close() {
        closed = true
        cancel()
        handler.removeCallbacksAndMessages(null)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        player.removeListener(this)
    }
}
