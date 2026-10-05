package com.dumuzeyn.mp3player

import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lazy, cached lyrics page with a ticker active only while visible. */
internal class LyricsPageController(
    private val host: MainActivityCore,
    private val state: PlaybackStateProvider,
) : AutoCloseable {
    private val lines = ArrayList<TextView>()
    private val ticker = Runnable(::tick)
    private var root: FrameLayout? = null
    private var scroll: ScrollView? = null
    private var content: LinearLayout? = null
    private var document: LrcDocument? = null
    private var loadedUri = ""
    private var activeLine = -1
    private var manualScrollUntil = 0L
    private var active = false

    fun createView(): View {
        root?.let { return it }
        val createdRoot = FrameLayout(host)
        val createdScroll = object : ScrollView(host) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Claim the gesture before a clickable synchronized lyric line receives it.
                    var ancestor = parent
                    while (ancestor != null) {
                        if (ancestor is FullPlayerSheet) {
                            ancestor.retainContentGesture()
                            break
                        }
                        ancestor = ancestor.parent
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE
                ) {
                    manualScrollUntil = System.currentTimeMillis() + MANUAL_SCROLL_GRACE_MS
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        val createdContent = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(24))
        }
        createdScroll.addView(createdContent, FrameLayout.LayoutParams(-1, -2))
        createdRoot.addView(createdScroll, FrameLayout.LayoutParams(-1, -1))
        root = createdRoot
        scroll = createdScroll
        content = createdContent
        showLoading()
        if (active) refresh()
        return createdRoot
    }

    fun setActive(value: Boolean) {
        active = value
        host.uiHandler.removeCallbacks(ticker)
        if (value) {
            refresh()
            scheduleTicker()
        }
    }

    fun refresh() {
        val track = state.currentTrack()
        if (!active || track == null || content == null) return
        if (track.uri == loadedUri && document != null) {
            scheduleTicker()
            return
        }
        loadedUri = track.uri
        document = null
        activeLine = -1
        showLoading()
        val requestedUri = track.uri
        host.lyricsRepository.load(track) { value ->
            if (root != null && requestedUri == loadedUri) render(value)
        }
    }

    private fun showLoading() {
        val target = content ?: return
        target.removeAllViews()
        val loading = host.uiFactory.text(
            host.tr("Looking for local lyrics…", "Ищем локальный текст…"),
            17,
            true,
        ).apply { gravity = Gravity.CENTER }
        target.addView(loading, LinearLayout.LayoutParams(-1, host.dp(110)))
    }

    private fun render(value: LrcDocument) {
        val target = content ?: return
        document = value
        target.removeAllViews()
        lines.clear()
        when {
            value.synchronizedLyrics -> {
                value.lines.forEach { line ->
                    val text = host.uiFactory.text(line.text, 18, false).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(10), host.dp(12), host.dp(10), host.dp(12))
                        setOnClickListener {
                            host.playbackActions.seekTo(
                                line.timeMs.coerceAtMost(Int.MAX_VALUE.toLong()),
                            )
                        }
                    }
                    target.addView(text, LinearLayout.LayoutParams(-1, -2))
                    lines.add(text)
                }
                scheduleTicker()
            }

            value.plainText.isNotEmpty() -> {
                val text = host.uiFactory.text(value.plainText, 18, false).apply {
                    setPadding(host.dp(10), host.dp(12), host.dp(10), host.dp(24))
                }
                target.addView(text, LinearLayout.LayoutParams(-1, -2))
            }

            else -> {
                val empty = host.uiFactory.panelCard()
                val title = host.uiFactory.text(
                    host.tr("Lyrics not available", "Текст не определён"),
                    20,
                    true,
                ).apply { gravity = Gravity.CENTER }
                empty.addView(title, LinearLayout.LayoutParams(-1, host.dp(110)))
                target.addView(
                    empty,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, host.dp(28), 0, 0)
                    },
                )
            }
        }
    }

    private fun scheduleTicker() {
        host.uiHandler.removeCallbacks(ticker)
        val currentDocument = document
        if (active && currentDocument != null && currentDocument.synchronizedLyrics) {
            host.uiHandler.post(ticker)
        }
    }

    private fun tick() {
        val currentDocument = document
        if (!active || currentDocument == null || !currentDocument.synchronizedLyrics) return
        val next = currentDocument.lineAt(host.playbackPosition().toLong())
        if (next != activeLine && next >= 0 && next < lines.size) {
            if (activeLine >= 0) styleLine(lines[activeLine], false)
            activeLine = next
            styleLine(lines[activeLine], true)
            if (System.currentTimeMillis() >= manualScrollUntil) {
                val current = lines[activeLine]
                scroll?.smoothScrollTo(0, (current.top - (scroll?.height ?: 0) / 3).coerceAtLeast(0))
            }
        }
        host.uiHandler.postDelayed(ticker, 250L)
    }

    private fun styleLine(line: TextView, selected: Boolean) {
        line.setTextColor(if (selected) host.purple else host.secondaryText)
        line.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    override fun close() {
        active = false
        host.uiHandler.removeCallbacks(ticker)
        lines.clear()
        root = null
        scroll = null
        content = null
        document = null
        loadedUri = ""
    }

    private companion object {
        const val MANUAL_SCROLL_GRACE_MS = 4_000L
    }
}
