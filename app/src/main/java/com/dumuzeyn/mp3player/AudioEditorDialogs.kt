package com.dumuzeyn.mp3player

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal class AudioEditorDialogs(private val host: MainActivityCore) {
    enum class Focus {
        CUT,
        POSITION,
        VOLUME,
        CLEAN_SPEECH,
        SEPARATE_STEMS,
        REMOVE_VOCALS,
    }

    private val controller get() = host.audioEditorController

    fun chooseExport() {
        if (controller.busy || controller.project.clips.isEmpty()) return
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.centeredDialogTitle(host.tr("Export", "Экспортировать"), 18))
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        content.addView(host.uiFactory.text(host.tr("Format", "Формат"), 14, false))
        val format = Spinner(host).apply {
            adapter = ArrayAdapter(host, android.R.layout.simple_spinner_dropdown_item,
                AudioExportFormat.entries.map(AudioExportFormat::name))
        }
        content.addView(format, LinearLayout.LayoutParams(-1, host.dp(52)))
        panel.addView(content)
        panel.addView(action(host.tr("Choose save location", "Выбрать место сохранения")) {
            host.overlayHost.removeView(shade)
            controller.export(AudioExportFormat.entries[format.selectedItemPosition])
        }.apply { host.uiFactory.applyPrimaryButtonStyle(this) })
        panel.addView(action(host.tr("Cancel", "Отмена")) { host.overlayHost.removeView(shade) })
        shade.addView(panel, host.centerParams(host.dp(340), -2))
        host.overlayHost.addView(shade)
    }

    fun chooseTrack(lane: Int) {
        if (controller.busy) return
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.dialogTitle(host.tr("Add audio", "Добавить аудио")))
        val search = EditText(host).apply {
            hint = host.tr("Search songs", "Поиск песен")
            setTextColor(host.primaryText)
            setHintTextColor(host.secondaryText)
            isSingleLine = true
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, host.dp(48)))
        val tracks = host.libraryState.tracks.toList()
        val adapter = object : RecyclerView.Adapter<TrackHolder>() {
            var visible = tracks
            override fun getItemCount() = visible.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackHolder =
                TrackHolder(host.uiFactory.text("", 15, false).apply {
                    setPadding(host.dp(8), host.dp(6), host.dp(8), host.dp(6))
                    minHeight = host.dp(56)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                })
            override fun onBindViewHolder(holder: TrackHolder, position: Int) {
                val track = visible[position]
                holder.label.text = "${track.title}\n${host.formatTrackDuration(track)}"
                holder.label.setOnClickListener {
                    host.overlayHost.removeView(shade)
                    controller.add(track, lane)
                }
            }
        }
        val list = RecyclerView(host).apply {
            layoutManager = LinearLayoutManager(host)
            this.adapter = adapter
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, bodyHeight(280, 240)))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.visible = tracks.filter { it.title.contains(s.toString(), ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        panel.addView(action(host.tr("Import files", "Импорт файлов")) {
            host.overlayHost.removeView(shade)
            host.audioImportController.openFiles()
        })
        panel.addView(action(host.tr("Close", "Закрыть")) { host.overlayHost.removeView(shade) })
        shade.addView(panel, host.centerParams(host.dp(360), -2))
        host.overlayHost.addView(shade)
    }

    @JvmOverloads
    fun edit(clip: AudioEditClip, focus: Focus = Focus.CUT) {
        if (controller.busy) return
        if (focus == Focus.CUT) {
            AudioEditorCutDialog(host, clip).show()
            return
        }
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.centeredDialogTitle(focusTitle(focus), 18))
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(host).apply { addView(content) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, bodyHeight(390, 210)))
        content.addView(host.uiFactory.text(clip.title, 15, true))

        fun close() = host.overlayHost.removeView(shade)
        fun previewProject(value: () -> AudioEditProject) {
            content.addView(AudioEditorPreviewControls(host, value, stopOnDetach = true))
        }
        fun preview(selected: () -> AudioEditClip) {
            previewProject { AudioEditProject(listOf(selected().copy(offsetMs = 0, lane = 0))) }
        }
        fun primary(label: String, run: () -> Unit) {
            panel.addView(action(label, run).apply { host.uiFactory.applyPrimaryButtonStyle(this) })
        }
        fun lanePicker(): Spinner {
            content.addView(host.uiFactory.text(host.tr("Lane", "Дорожка"), 14, false))
            return Spinner(host).apply {
                adapter = ArrayAdapter(host, android.R.layout.simple_spinner_dropdown_item,
                    (1..AudioEditClip.MAX_LANES).map(Int::toString))
                setSelection(clip.lane)
                content.addView(this, LinearLayout.LayoutParams(-1, host.dp(48)))
            }
        }
        when (focus) {
            Focus.CUT -> Unit
            Focus.POSITION -> {
                val label = host.tr("Position on lane (m:ss.mmm)",
                    "Позиция на дорожке (м:сс.мс)")
                content.addView(host.uiFactory.text(label, 14, false))
                val timecode = EditText(host).apply {
                    setText(AudioEditorTimecode.format(clip.offsetMs))
                    contentDescription = label
                    setTextColor(host.primaryText)
                    inputType = InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                }
                content.addView(timecode, LinearLayout.LayoutParams(-1, host.dp(48)))
                primary(host.tr("Move clip", "Переместить")) {
                    val position = AudioEditorTimecode.parse(timecode.text.toString())
                    if (position == null || position + clip.durationMs > AudioEditClip.MAX_TIME_MS) {
                        timecode.error = host.tr("Check the position", "Проверьте позицию")
                    } else if (controller.change { it.replace(clip.copy(offsetMs = position)) }) {
                        close()
                    } else {
                        timecode.error = host.tr("This position overlaps another clip",
                            "Эта позиция пересекается с другим фрагментом")
                    }
                }
            }
            Focus.VOLUME -> {
                val level = host.uiFactory.text(
                    "${host.tr("Volume", "Громкость")}: ${(clip.gain * 100).toInt()}%",
                    14,
                    false,
                )
                content.addView(level)
                val gain = SeekBar(host).apply {
                    max = 200
                    progress = (clip.gain * 100).toInt()
                    contentDescription = host.tr("Clip volume", "Громкость фрагмента")
                    setOnSeekBarChangeListener(listener { value ->
                        level.text = "${host.tr("Volume", "Громкость")}: $value%"
                    })
                }
                host.uiFactory.applySeekBarColors(gain)
                content.addView(gain, LinearLayout.LayoutParams(-1, host.dp(48)))
                preview { clip.copy(gain = gain.progress / 100f) }
                primary(host.tr("Apply volume", "Применить громкость")) {
                    if (controller.change { it.replace(clip.copy(gain = gain.progress / 100f)) }) close()
                }
            }
            Focus.CLEAN_SPEECH -> {
                preview { clip }
                primary(host.tr("Remove noise", "Убрать шумы")) {
                    if (controller.processing.cleanSpeech(clip)) close()
                }
            }
            Focus.SEPARATE_STEMS -> {
                val lane = lanePicker()
                preview { clip }
                primary(host.tr("Separate into four stems", "Разделить на четыре дорожки")) {
                    runCatching { clip.copy(lane = lane.selectedItemPosition) }
                        .onSuccess { if (controller.processing.separate(it, false)) close() }
                }
            }
            Focus.REMOVE_VOCALS -> {
                content.addView(host.uiFactory.text(host.tr(
                    "The selected clip provides the vocal. In mix mode, all other clips provide the music.",
                    "Выбранный фрагмент используется как вокал. В режиме совмещения музыка берётся из остальных фрагментов.",
                ), 13, false))
                val mode = Spinner(host).apply {
                    adapter = ArrayAdapter(host, android.R.layout.simple_spinner_dropdown_item, listOf(
                        host.tr("Remove vocals", "Удалить вокал"),
                        host.tr("Combine vocal with other music", "Совместить вокал с остальной музыкой"),
                    ))
                    content.addView(this, LinearLayout.LayoutParams(-1, host.dp(52)))
                }
                preview { clip }
                primary(host.tr("Process vocals", "Обработать вокал")) {
                    val started = if (mode.selectedItemPosition == 0)
                        controller.processing.separate(clip, true)
                    else controller.processing.combineVocals(clip)
                    if (started) close()
                }
            }
        }
        panel.addView(action(host.tr("Cancel", "Отмена")) { host.overlayHost.removeView(shade) })
        shade.addView(panel, host.centerParams(host.dp(360), -2))
        host.overlayHost.addView(shade)
    }

    private fun focusTitle(focus: Focus): String = when (focus) {
        Focus.POSITION -> host.tr("Clip position", "Позиция фрагмента")
        Focus.VOLUME -> host.tr("Clip volume", "Громкость фрагмента")
        Focus.CLEAN_SPEECH -> host.tr("Remove noise", "Удаление шумов")
        Focus.SEPARATE_STEMS -> host.tr("Separate stems", "Разделение дорожек")
        Focus.REMOVE_VOCALS -> host.tr("Vocal work", "Работа с вокалом")
        Focus.CUT -> host.tr("Cut audio", "Обрезать")
    }

    private fun bodyHeight(preferredDp: Int, chromeDp: Int): Int = minOf(host.dp(preferredDp),
        (host.overlayHost.height - host.dp(chromeDp)).coerceAtLeast(host.dp(64)))
    private fun action(label: String, run: () -> Unit) = host.uiFactory.button(label).apply {
        layoutParams = LinearLayout.LayoutParams(-1, host.dp(48))
        setOnClickListener { run() }
    }
    private fun listener(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = change(progress)
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
    private class TrackHolder(val label: TextView) : RecyclerView.ViewHolder(label)
}
