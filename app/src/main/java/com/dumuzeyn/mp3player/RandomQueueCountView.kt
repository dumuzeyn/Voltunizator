package com.dumuzeyn.mp3player

import android.graphics.Typeface
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/** Compact vertical wheel used to choose how many tracks enter a generated queue. */
internal class RandomQueueCountView(
    private val host: MainActivityCore,
    maximum: Int,
) : FrameLayout(host) {
    private val maximum = maximum.coerceAtLeast(1)
    private val stepDistance = host.dp(12).toFloat()
    private val label = TextView(host).apply {
        gravity = android.view.Gravity.CENTER
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(host.primaryText)
    }
    private var lastY = 0f
    private var accumulatedDrag = 0f
    private var dragged = false

    var value: Int = minOf(DEFAULT_VALUE, this.maximum)
        private set

    init {
        id = R.id.random_queue_count
        background = host.uiFactory.cardBackground(host.appearanceState.cardOpacity)
        TextOutlinePolicy.markCardSurface(this, true)
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        updateLabel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                accumulatedDrag = 0f
                dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = lastY - event.y
                lastY = event.y
                accumulatedDrag += distance
                if (abs(accumulatedDrag) >= stepDistance) dragged = true
                while (accumulatedDrag >= stepDistance) {
                    changeBy(1)
                    accumulatedDrag -= stepDistance
                }
                while (accumulatedDrag <= -stepDistance) {
                    changeBy(-1)
                    accumulatedDrag += stepDistance
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                changeBy(if (scroll > 0f) 1 else -1)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        changeBy(if (value < maximum) 1 else 1 - maximum)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.NumberPicker::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            1f,
            maximum.toFloat(),
            value.toFloat(),
        )
        if (value < maximum) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (value > 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> changeBy(1)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> changeBy(-1)
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun changeBy(delta: Int): Boolean {
        val next = (value + delta).coerceIn(1, maximum)
        if (next == value) return false
        val direction = if (next > value) 1f else -1f
        value = next
        updateLabel()
        animateLabel(direction)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private fun updateLabel() {
        label.text = value.toString()
        contentDescription = host.tr("Queue size", "Размер очереди") + ": $value / $maximum"
    }

    private fun animateLabel(direction: Float) {
        if (!host.appearanceState.animations) return
        label.animate().cancel()
        label.translationY = direction * host.dp(10)
        label.alpha = 0.25f
        label.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(140L)
            .start()
    }

    private companion object {
        const val DEFAULT_VALUE = 10
    }
}
