package com.dumuzeyn.mp3player

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import java.util.Locale
import kotlin.math.roundToLong

internal class AudioEditorCutDialog(
    private val host: MainActivityCore,
    private val clip: AudioEditClip,
) {
    private val controller get() = host.audioEditorController
    private lateinit var shade: android.view.ViewGroup
    private lateinit var body: LinearLayout
    private lateinit var apply: Button
    private var applyAction: () -> Unit = {}

    fun show() {
        if (controller.busy) return
        if (controller.preview.active) controller.preview.stop()
        shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.centeredDialogTitle(host.tr("Cut audio", "Обрезать"), 18))
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        content.addView(host.uiFactory.text(clip.title, 15, true))
        val mode = Spinner(host).apply {
            adapter = ArrayAdapter(host, android.R.layout.simple_spinner_dropdown_item, listOf(
                host.tr("Trim start and end", "Обрезать начало и конец"),
                host.tr("Split into two parts", "Разделить на две части"),
                host.tr("Remove a range", "Удалить отрезок"),
            ))
        }
        content.addView(mode, LinearLayout.LayoutParams(-1, host.dp(50)))
        body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)
        panel.addView(ScrollView(host).apply { addView(content) },
            LinearLayout.LayoutParams(-1, bodyHeight()))
        apply = host.uiFactory.button("").apply {
            layoutParams = LinearLayout.LayoutParams(-1, host.dp(48))
            host.uiFactory.applyPrimaryButtonStyle(this)
            setOnClickListener { applyAction() }
        }
        panel.addView(apply)
        panel.addView(host.uiFactory.button(host.tr("Cancel", "Отменить")).apply {
            layoutParams = LinearLayout.LayoutParams(-1, host.dp(48))
            setOnClickListener { close() }
        })
        shade.addView(panel, host.centerParams(host.dp(360), -2))
        host.overlayHost.addView(shade)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (controller.preview.active) controller.preview.stop()
                renderMode(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        mode.setSelection(MODE_REMOVE)
        renderMode(MODE_REMOVE)
    }

    private fun renderMode(mode: Int) {
        body.removeAllViews()
        when (mode) {
            MODE_TRIM -> renderTrim()
            MODE_SPLIT -> renderSplit()
            else -> renderRemove()
        }
    }

    private fun renderTrim() {
        val waveform = waveform(cursorOnly = false)
        val fields = range(waveform)
        body.addView(AudioEditorPreviewControls(host, {
            val selected = absoluteRange(fields)
            AudioEditProject(listOf(clip.copy(startMs = selected.first, endMs = selected.second,
                offsetMs = 0, lane = 0)))
        }, stopOnDetach = true))
        apply.text = host.tr("Apply trimming", "Обрезать")
        applyAction = {
            runCatching { absoluteRange(fields) }.onSuccess { selected ->
                if (controller.change { it.replace(clip.copy(
                        startMs = selected.first, endMs = selected.second)) }) close()
            }.onFailure { fields.first.error = host.tr("Check the range", "Проверьте границы") }
        }
    }

    private fun renderSplit() {
        val waveform = waveform(cursorOnly = true)
        val label = host.tr("Split time (m:ss.mmm)", "Время разделения (м:сс.мс)")
        body.addView(host.uiFactory.text(label, 14, false))
        val timecode = EditText(host).apply {
            setText(AudioEditorTimecode.format(waveform.cursorMs - clip.startMs))
            contentDescription = label
            setTextColor(host.primaryText)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        body.addView(timecode, LinearLayout.LayoutParams(-1, host.dp(48)))
        waveform.onCursor = { value ->
            timecode.setText(AudioEditorTimecode.format(value - clip.startMs))
            timecode.setSelection(timecode.length())
        }
        timecode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                AudioEditorTimecode.parse(s.toString())?.let { relative ->
                    if (relative in 1 until clip.durationMs) waveform.setCursor(clip.startMs + relative)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        fun splitPosition(): Long {
            val relative = AudioEditorTimecode.parse(timecode.text.toString())
            require(relative != null && relative in 1 until clip.durationMs)
            return clip.startMs + relative
        }
        body.addView(AudioEditorPreviewControls(host, { AudioEditProject(listOf(
            clip.copy(offsetMs = 0, lane = 0),
        )) }, startPositionMs = { splitPosition() - clip.startMs },
            stopOnDetach = true))
        apply.text = host.tr("Split into two parts", "Разделить")
        applyAction = {
            runCatching { splitPosition() }.onSuccess { position ->
                if (controller.change { it.split(clip.id, position) }) close()
            }.onFailure { timecode.error = host.tr("Check the split time", "Проверьте время разделения") }
        }
    }

    private fun renderRemove() {
        body.addView(AudioEditorProjectBoundaryView(host, controller.project, clip.id),
            LinearLayout.LayoutParams(-1, host.dp(42)))
        val waveform = waveform(cursorOnly = false)
        val fields = range(waveform)
        val closeGap = toggle(host.tr("Join remaining parts", "Соединить оставшиеся части"), true)
        val smoothJoin = toggle(host.tr("Smooth join", "Сделать плавное соединение"), true)
        body.addView(closeGap, LinearLayout.LayoutParams(-1, host.dp(52)))
        body.addView(smoothJoin, LinearLayout.LayoutParams(-1, host.dp(52)))
        body.addView(AudioEditorPreviewControls(host, {
            val selected = absoluteRange(fields)
            AudioEditProject(listOf(clip.copy(offsetMs = 0, lane = 0))).removeRange(
                clip.id, selected.first, selected.second, closeGap.isChecked, smoothJoin.isChecked)
        }, startPositionMs = {
            (millis(fields.first) - 1500).coerceAtLeast(0)
        }, stopOnDetach = true))
        apply.text = host.tr("Remove selected range", "Удалить отрезок")
        applyAction = {
            runCatching { absoluteRange(fields) }.onSuccess { selected ->
                if (controller.change { it.removeRange(clip.id, selected.first, selected.second,
                        closeGap.isChecked, smoothJoin.isChecked) }) close()
            }.onFailure { fields.first.error = host.tr("Check the range", "Проверьте границы") }
        }
    }

    private fun waveform(cursorOnly: Boolean) = AudioEditorWaveformView(host, clip, cursorOnly).also {
        body.addView(it, LinearLayout.LayoutParams(-1, minOf(host.dp(120), bodyHeight())))
    }

    private fun range(waveform: AudioEditorWaveformView): Pair<EditText, EditText> {
        val from = secondsField(host.tr("Start, s", "Начало, с"), 0)
        val to = secondsField(host.tr("End, s", "Конец, с"), clip.durationMs)
        var updating = false
        waveform.onSelection = { start, end ->
            updating = true
            from.setText(seconds(start - clip.startMs))
            to.setText(seconds(end - clip.startMs))
            updating = false
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updating) runCatching {
                    waveform.setSelection(clip.startMs + millis(from), clip.startMs + millis(to))
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        from.addTextChangedListener(watcher)
        to.addTextChangedListener(watcher)
        return from to to
    }

    private fun secondsField(label: String, value: Long): EditText {
        body.addView(host.uiFactory.text(label, 14, false))
        return EditText(host).apply {
            setText(seconds(value))
            contentDescription = label
            setTextColor(host.primaryText)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            body.addView(this, LinearLayout.LayoutParams(-1, host.dp(48)))
        }
    }

    private fun absoluteRange(fields: Pair<EditText, EditText>): Pair<Long, Long> {
        val start = clip.startMs + millis(fields.first)
        val end = clip.startMs + millis(fields.second)
        require(start >= clip.startMs && end > start && end <= clip.endMs)
        return start to end
    }

    private fun millis(input: EditText): Long {
        val value = input.text.toString().replace(',', '.').toDoubleOrNull()
        require(value != null && value.isFinite() && value >= 0 && value <= clip.durationMs / 1000.0)
        return (value * 1000).roundToLong()
    }

    private fun seconds(value: Long) = String.format(Locale.ROOT, "%.3f", value / 1000.0)
    private fun close() = host.overlayHost.removeView(shade)
    private fun bodyHeight() = minOf(host.dp(430),
        (host.overlayHost.height - host.dp(190)).coerceAtLeast(host.dp(160)))

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(label: String, checked: Boolean) = Switch(host).apply {
        text = label
        setTextColor(host.primaryText)
        isChecked = checked
    }

    companion object {
        private const val MODE_TRIM = 0
        private const val MODE_SPLIT = 1
        private const val MODE_REMOVE = 2
    }
}
