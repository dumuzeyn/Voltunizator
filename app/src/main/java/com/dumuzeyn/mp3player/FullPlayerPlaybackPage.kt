package com.dumuzeyn.mp3player

import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Builds playback controls once and binds them to the shared Media3 state. */
internal class FullPlayerPlaybackPage(
    private val host: MainActivityCore,
    private val actions: PlaybackActions,
    private val state: PlaybackStateProvider,
) : AutoCloseable {
    private val tools = PlayerToolActions(host)
    private val progress = FullPlayerProgressController(
        host,
        state,
        { refresh(true) },
        { timer?.let { host.uiFactory.setLabeledIcon(it, StrictIcon.TIMER, host.timerButtonText(), true) } },
    )
    private var root: ScrollView? = null
    private var cover: ImageView? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var timer: Button? = null
    private var save: Button? = null
    private var repeat: Button? = null
    private var play: Button? = null
    private var speed: Button? = null
    private var visualizer: FullPlayerVisualizerView? = null
    private var boundTrack: Track? = null
    private var active = false

    fun createView(): View {
        root?.let { return it }
        val createdRoot = ScrollView(host).apply { isFillViewport = true }
        root = createdRoot
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(6), 0, host.dp(6), host.dp(8))
        }
        createdRoot.addView(content, FrameLayout.LayoutParams(-1, -2))
        val track = state.currentTrack() ?: return createdRoot
        addCoverAndTitle(content, track)
        visualizer = FullPlayerVisualizerView(host, state::isPlaying).also {
            content.addView(it, LinearLayout.LayoutParams(-1, host.dp(80)).apply {
                setMargins(host.dp(8), 0, host.dp(8), host.dp(4))
            })
        }
        addActionRow(content, track)
        addAudioTools(content)
        addSeek(content, track)
        boundTrack = track
        refresh(false)
        progress.setActive(active)
        return createdRoot
    }

    fun setActive(value: Boolean) {
        active = value
        progress.setActive(value && root != null)
        if (value) refresh(true)
        visualizer?.setActive(value)
        rotatingCover()?.setUiActive(value)
    }

    fun refresh(allowTrackChange: Boolean) {
        val track = state.currentTrack() ?: return
        if (root == null) return
        val changed = boundTrack?.uri != track.uri
        val currentCover = cover ?: return
        if (allowTrackChange && changed) {
            boundTrack = track
            host.artworkUi.loadCover(
                currentCover,
                track,
                coverFallback(),
                MainActivityCore.COVER_FULL_SIZE,
            )
        }
        title?.text = track.title
        val queuePosition = "${state.queueIndex(track) + 1} " +
            host.tr3("of", "из", "/") + " ${state.activeQueue().size}"
        subtitle?.text = if (host.appearanceState.showArtistName) {
            track.artist + " · " + queuePosition
        } else queuePosition
        timer?.let {
            host.uiFactory.setLabeledIcon(it, StrictIcon.TIMER, host.timerButtonText(), true)
            host.uiFactory.applyPlayerToolStyle(it, host.playbackUiState.sleepTimerEndsAt > 0)
        }
        save?.let {
            host.uiFactory.setLabeledIcon(
                it,
                if (tools.isSaved(track)) StrictIcon.HEART else StrictIcon.HEART_OUTLINE,
                saveText(track),
                true,
            )
            host.uiFactory.applyPlayerToolStyle(it, tools.isSaved(track))
        }
        repeat?.let {
            host.uiFactory.setLabeledIcon(it, repeatIcon(), host.loopLabel(), true)
            host.uiFactory.applyPlayerToolStyle(it, state.repeatMode() != 0)
        }
        play?.let {
            host.uiFactory.setIcon(it, if (state.isPlaying()) StrictIcon.PAUSE else StrictIcon.PLAY)
            host.uiFactory.applyPlainIconStyle(
                it,
                if (state.isPlaying()) host.yellow else host.primaryText,
            )
        }
        speed?.let {
            host.uiFactory.setLabeledIcon(it, StrictIcon.SPEED, tools.speedText(), true)
            host.uiFactory.applyPlayerToolStyle(it, host.playbackController.playbackSpeed() != 1f)
        }
        rotatingCover()?.updatePlaybackState()
    }

    private fun addCoverAndTitle(content: LinearLayout, track: Track) {
        val createdCover = host.uiFactory.coverView()
        cover = createdCover
        (createdCover as? RotatingCoverImageView)?.setRotationSpeedPercent(
            host.appearanceState.fullPlayerRotationSpeed,
        )
        host.artworkUi.loadCover(
            createdCover,
            track,
            coverFallback(),
            MainActivityCore.COVER_FULL_SIZE,
        )
        val metrics = host.resources.displayMetrics
        val screenDp = (metrics.heightPixels / metrics.density).roundToInt()
        val sizeDp = host.responsiveLayoutController.fullPlayerCoverSizeDp(screenDp)
        content.addView(
            createdCover,
            LinearLayout.LayoutParams(host.dp(sizeDp), host.dp(sizeDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        title = host.uiFactory.text(track.title, 24, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        subtitle = host.uiFactory.text("", 15, false).apply {
            gravity = Gravity.CENTER
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, host.dp(34))) }
    }

    private fun addActionRow(content: LinearLayout, track: Track) {
        val row = host.uiFactory.row()
        timer = host.uiFactory.button(host.timerButtonText()).apply {
            host.uiFactory.setLabeledIcon(this, StrictIcon.TIMER, host.timerButtonText(), true)
            setOnClickListener { tools.toggleTimer(); refresh(false) }
            setOnLongClickListener { host.sleepTimerController.openDialog(); true }
        }.also { row.addView(it, toolParams()) }
        save = host.uiFactory.button(saveText(track)).apply {
            host.uiFactory.setLabeledIcon(
                this,
                if (tools.isSaved(track)) StrictIcon.HEART else StrictIcon.HEART_OUTLINE,
                saveText(track),
                true,
            )
            setOnClickListener {
                state.currentTrack()?.let(tools::toggleSaved)
                refresh(false)
            }
            setOnLongClickListener {
                state.currentTrack()?.let {
                    tools.chooseCollection { refresh(false) }
                }
                true
            }
        }.also { row.addView(it, toolParams()) }
        repeat = host.uiFactory.button(host.loopLabel()).apply {
            host.uiFactory.setLabeledIcon(this, repeatIcon(), host.loopLabel(), true)
            setOnClickListener {
                actions.cycleRepeatMode()
                refresh(false)
            }
            setOnLongClickListener { tools.chooseRepeat(); true }
        }.also { row.addView(it, toolParams()) }
        content.addView(row)
    }

    private fun addAudioTools(content: LinearLayout) {
        val row = host.uiFactory.row()
        row.addView(host.equalizerController.createPlayerButton(), toolParams())
        row.addView(host.volumeLevelingController.createPlayerButton(), toolParams())
        speed = host.uiFactory.button(tools.speedText()).apply {
            host.uiFactory.setLabeledIcon(this, StrictIcon.SPEED, tools.speedText(), true)
            contentDescription = host.tr("Playback speed", "Скорость воспроизведения")
            setOnClickListener { tools.toggleSpeed(); refresh(false) }
            setOnLongClickListener { tools.chooseSpeed(); true }
        }.also { row.addView(it, toolParams()) }
        content.addView(row)
    }

    private fun addSeek(content: LinearLayout, track: Track) {
        val seek = SeekBar(host)
        host.uiFactory.applySeekBarColors(seek)
        val elapsed = host.uiFactory.text("0:00", 13, false)
        val remaining = host.uiFactory.text("-0:00", 13, false)
        var dragged = false
        var startX = 0f
        seek.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    dragged = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - startX) > host.dp(8)) dragged = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        seek.setOnSeekBarChangeListener(seekListener(elapsed, remaining) { dragged })
        content.addView(seek, LinearLayout.LayoutParams(-1, host.dp(42)))
        val times = host.uiFactory.row().apply { gravity = Gravity.CENTER_VERTICAL }
        elapsed.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        remaining.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        times.addView(elapsed, LinearLayout.LayoutParams(0, host.dp(64), 1f))
        addTransport(times)
        times.addView(remaining, LinearLayout.LayoutParams(0, host.dp(64), 1f))
        content.addView(times, LinearLayout.LayoutParams(-1, host.dp(72)))
        progress.bind(root!!, track, seek, elapsed, remaining)
    }

    private fun seekListener(
        elapsed: TextView,
        remaining: TextView,
        dragged: () -> Boolean,
    ): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (!fromUser) return
            if (dragged()) rotatingCover()?.updateSeekSpin(value)
            elapsed.text = host.formatMs(value)
            val duration = state.currentTrack()?.let(host::playbackDurationFor) ?: bar.max
            remaining.text = "-" + host.formatMs(max(0, duration - value))
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            progress.setSeekTracking(true)
            rotatingCover()?.beginSeekSpin(host.playbackPosition())
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            rotatingCover()?.endSeekSpin(bar.progress, !dragged())
            actions.seekTo(bar.progress.toLong())
            progress.setSeekTracking(false)
        }
    }

    private fun addTransport(row: LinearLayout) {
        val previous = host.uiFactory.icon(StrictIcon.PREVIOUS).apply {
            setOnClickListener {
                actions.previous()
                refresh(true)
            }
        }
        row.addView(previous, host.uiFactory.square(52))
        play = host.uiFactory.icon(if (state.isPlaying()) StrictIcon.PAUSE else StrictIcon.PLAY).apply {
            host.uiFactory.applyPlainIconStyle(
                this,
                if (state.isPlaying()) host.yellow else host.primaryText,
            )
            setOnClickListener {
                actions.togglePlayPause()
                refresh(false)
            }
        }.also { row.addView(it, host.uiFactory.square(64)) }
        val next = host.uiFactory.icon(StrictIcon.NEXT).apply {
            setOnClickListener {
                actions.next()
                refresh(true)
            }
        }
        row.addView(next, host.uiFactory.square(52))
    }

    private fun toolParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, host.dp(52), 1f).apply {
            setMargins(host.dp(3), host.dp(3), host.dp(3), host.dp(3))
        }

    private fun coverFallback(): Int = if (host.appearanceState.dark) {
        Color.rgb(28, 28, 28)
    } else {
        Color.rgb(235, 235, 235)
    }

    private fun saveText(track: Track): String = if (tools.isSaved(track)) {
        host.tr("Saved", "Добавлено")
    } else {
        host.tr("Save", "Добавить")
    }

    private fun repeatIcon(): StrictIcon = when (state.repeatMode()) {
        1 -> StrictIcon.REPEAT_ONE
        2 -> StrictIcon.REPEAT_LIST
        else -> StrictIcon.REPEAT
    }

    private fun rotatingCover(): RotatingCoverImageView? = cover as? RotatingCoverImageView

    override fun close() {
        progress.close()
        visualizer?.close()
        visualizer = null
        cover?.let { host.artworkUi.clearCover(it, coverFallback()) }
        root = null
        cover = null
        title = null
        subtitle = null
        timer = null
        save = null
        repeat = null
        play = null
        speed = null
        boundTrack = null
    }
}
