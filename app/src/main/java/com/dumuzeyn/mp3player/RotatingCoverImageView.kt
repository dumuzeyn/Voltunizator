package com.dumuzeyn.mp3player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.roundToLong

internal class RotatingCoverImageView(host: MainActivityCore) : ShapedCoverImageView(host) {
    private val trackUris = HashSet<String>()
    private var sourceTracks = ArrayList<Track>()
    private var requireActiveQueue = false
    private var rotationAnimator: ValueAnimator? = null
    private var boundTrackUri = ""
    private var lastObservedTrackUri = ""
    private var seeking = false
    private var seekStartPosition = 0
    private var seekStartRotation = 0f
    private var rotationDurationMs = DEFAULT_ROTATION_DURATION_MS
    private var uiActive = true

    init { setRotationSpeedPercent(host.appearanceState.fullPlayerRotationSpeed) }

    fun bindTrack(track: Track?) {
        val nextTrackUri = track?.uri.orEmpty()
        if (nextTrackUri != boundTrackUri) {
            seeking = false
            stopRotation(true)
            boundTrackUri = nextTrackUri
        }
        trackUris.clear()
        sourceTracks.clear()
        requireActiveQueue = false
        if (nextTrackUri.isNotEmpty()) trackUris.add(nextTrackUri)
        updatePlaybackState()
    }

    fun setRotationSpeedPercent(speedPercent: Int) {
        val boundedSpeed = speedPercent.coerceIn(25, 200)
        val nextDuration = (DEFAULT_ROTATION_DURATION_MS * (100.0 / boundedSpeed)).roundToLong()
        if (nextDuration == rotationDurationMs) return
        val restart = rotationAnimator?.isRunning == true && !seeking
        rotationDurationMs = nextDuration
        if (restart) {
            stopRotation(false)
            startRotation()
        }
    }

    fun setUiActive(active: Boolean) {
        uiActive = active
        updatePlaybackState()
    }

    fun bindTracks(tracks: ArrayList<Track>?) {
        bindSourceTracks(tracks, false)
    }

    fun bindPlaylistTracks(tracks: ArrayList<Track>?) {
        bindSourceTracks(tracks, true)
    }

    private fun bindSourceTracks(tracks: ArrayList<Track>?, requireActiveQueue: Boolean) {
        trackUris.clear()
        sourceTracks = if (tracks == null) ArrayList() else ArrayList(tracks)
        this.requireActiveQueue = requireActiveQueue
        if (tracks != null) {
            for (track in tracks) trackUris.add(track.uri)
        }
        updatePlaybackState()
    }

    fun updatePlaybackState() {
        updatePlaybackState(host.playbackStateProvider.currentTrack(), host.isPlaybackPlaying())
    }

    fun updatePlaybackState(currentTrack: Track?, playing: Boolean) {
        setRotationSpeedPercent(host.appearanceState.fullPlayerRotationSpeed)
        invalidateOutline()
        val currentUri = currentTrack?.uri.orEmpty()
        if (currentUri.isNotEmpty() && currentUri != lastObservedTrackUri) {
            if (lastObservedTrackUri.isNotEmpty()) stopRotation(true)
            lastObservedTrackUri = currentUri
        }
        val shouldRotate = uiActive && host.appearanceState.rotateCovers && playing &&
            trackUris.contains(currentUri) &&
            (!requireActiveQueue || host.playbackQueueController.isCurrentCollection(sourceTracks))
        if (seeking) return
        if (shouldRotate && isAttachedToWindow) {
            startRotation()
        } else {
            stopRotation(!host.appearanceState.rotateCovers)
        }
    }

    fun beginSeekSpin(positionMs: Int) {
        if (!host.appearanceState.rotateCovers) return
        seeking = true
        seekStartPosition = positionMs
        stopRotation(false)
        seekStartRotation = rotation
    }

    fun updateSeekSpin(positionMs: Int) {
        if (!seeking || !host.appearanceState.rotateCovers) return
        val deltaMs = positionMs - seekStartPosition
        if (deltaMs == 0) {
            setArtworkRotation(seekStartRotation)
            return
        }
        setArtworkRotation(seekStartRotation + degreesForSeekDelta(deltaMs))
    }

    fun endSeekSpin(positionMs: Int, animateTap: Boolean) {
        if (!seeking) return
        seeking = false
        if (!host.appearanceState.rotateCovers) {
            updatePlaybackState()
            return
        }
        val totalDeltaMs = positionMs - seekStartPosition
        if (totalDeltaMs == 0) {
            setArtworkRotation(seekStartRotation)
            updatePlaybackState()
            return
        }
        val target = seekStartRotation + degreesForSeekDelta(totalDeltaMs)
        if (!animateTap || !host.appearanceState.animations) {
            setArtworkRotation(target % 360f)
            updatePlaybackState()
            return
        }
        setArtworkRotation(seekStartRotation)
        val animator = ValueAnimator.ofFloat(seekStartRotation, target)
        rotationAnimator = animator
        val turns = abs(target - seekStartRotation) / 360f
        animator.duration = (180f + turns * 115f).coerceIn(220f, 850f).toLong()
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { setArtworkRotation(it.animatedValue as Float) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (rotationAnimator !== animator) return
                rotationAnimator = null
                setArtworkRotation(rotation % 360f)
                updatePlaybackState()
            }
        })
        animator.start()
    }

    private fun degreesForSeekDelta(deltaMs: Int): Float =
        deltaMs * (360f / rotationDurationMs)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePlaybackState()
    }

    override fun onDetachedFromWindow() {
        seeking = false
        stopRotation(true)
        super.onDetachedFromWindow()
    }

    private fun startRotation() {
        if (rotationAnimator?.isRunning == true) return
        val start = rotation % 360f
        rotationAnimator = ValueAnimator.ofFloat(start, start + 360f).apply {
            duration = rotationDurationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { setArtworkRotation(it.animatedValue as Float) }
            start()
        }
    }

    private fun stopRotation(reset: Boolean) {
        val animator = rotationAnimator
        rotationAnimator = null
        animator?.cancel()
        if (reset) setArtworkRotation(0f)
    }

    fun refreshCoverTransform() = setArtworkRotation(rotation)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshCoverTransform()
    }

    private fun setArtworkRotation(degrees: Float) {
        rotation = degrees
        val fit = if (host.appearanceState.rotateCovers) {
            CoverShapeGeometry.rotationFitScale(host.appearanceState.coverShape, width.toFloat(), height.toFloat())
        } else 1f
        scaleX = fit
        scaleY = fit
    }

    companion object {
        private const val DEFAULT_ROTATION_DURATION_MS = 18_000L
    }
}
