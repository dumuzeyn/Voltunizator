package com.dumuzeyn.mp3player

import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/** Swipe-to-dismiss surface used by the full player. */
internal class FullPlayerSheet(
    private val host: MainActivityCore,
    private val closeListener: CloseListener,
) : FrameLayout(host) {
    fun interface CloseListener {
        fun close(sheet: FrameLayout)
    }

    private var draggingDown = false
    private var closingDown = false
    private var contentGesture = false
    private var startX = 0f
    private var startY = 0f
    private var startTranslationY = 0f

    fun retainContentGesture() {
        contentGesture = true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return when (val action = event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event)
                super.dispatchTouchEvent(event)
                true
            }

            MotionEvent.ACTION_MOVE -> moveGesture(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture(event, action)
            else -> {
                super.dispatchTouchEvent(event)
                true
            }
        }
    }

    private fun beginGesture(event: MotionEvent) {
        draggingDown = false
        closingDown = false
        contentGesture = false
        startX = event.rawX
        startY = event.rawY
        startTranslationY = translationY
        animate().cancel()
        alpha = 1f
    }

    private fun moveGesture(event: MotionEvent): Boolean {
        if (closingDown) return true
        val dx = event.rawX - startX
        val dy = event.rawY - startY
        if (!contentGesture && !draggingDown && dy > host.dp(8) && dy > abs(dx) * 0.75f) {
            draggingDown = true
            MotionEvent.obtain(event).also { cancelEvent ->
                cancelEvent.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancelEvent)
                cancelEvent.recycle()
            }
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (draggingDown) {
            val drag = max(0f, startTranslationY + dy)
            translationY = drag
            alpha = max(0.55f, 1f - drag / max(1, height))
        } else {
            super.dispatchTouchEvent(event)
        }
        return true
    }

    private fun finishGesture(event: MotionEvent, action: Int): Boolean {
        if (closingDown) return true
        if (!draggingDown) {
            super.dispatchTouchEvent(event)
            return true
        }
        draggingDown = false
        val drag = max(0f, startTranslationY + event.rawY - startY)
        if (action == MotionEvent.ACTION_UP && drag > host.dp(56)) {
            closingDown = true
            closeListener.close(this)
        } else if (host.appearanceState.animations) {
            animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(120L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            translationY = 0f
            alpha = 1f
        }
        return true
    }
}
