package com.dumuzeyn.mp3player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

internal class AudioEditorController(private val host: MainActivityCore) : AutoCloseable {
    private val store by lazy { AudioEditStore(host) }
    private val exporter by lazy { AudioEditExporter(host) }
    private val waveformRepository = lazy { AudioWaveformRepository(host) }
    val waveforms get() = waveformRepository.value
    private val previewController = lazy { AudioEditorPreviewController(host, ::render) }
    val preview get() = previewController.value
    private val processingController = lazy { AudioEditorProcessing(host, ::render) }
    val processing get() = processingController.value
    private val files = Executors.newSingleThreadExecutor()
    private val undo = ArrayDeque<AudioEditProject>()
    private val redo = ArrayDeque<AudioEditProject>()
    var project: AudioEditProject = AudioEditProject()
        private set
    var selectedClipId: String? = null
        private set
    private val previewMutedLanes = linkedSetOf<Int>()
    val mutedPreviewLanes: Set<Int> get() = previewMutedLanes
    var editingMode = false
        private set
    private var loaded = false
    private var closed = false
    private var working = false
    private var exportGeneration = 0
    val busy get() = working || StemSeparationJob.snapshot(host).active ||
        (previewController.isInitialized() && preview.active) ||
        (processingController.isInitialized() && processing.active)
    var exporting = false
        private set
    var status = ""
        private set
    var progress = -1
        private set
    private var readyFile: File? = null
    private var readyFormat = AudioExportFormat.M4A
    var onProgress: (() -> Unit)? = null
    val canUndo get() = undo.isNotEmpty() && !busy
    val canRedo get() = redo.isNotEmpty() && !busy
    val canSave get() = readyFile?.isFile == true && !busy

    fun load() {
        if (loaded) return
        loaded = true
        project = store.load()
        StemSeparationJob.resume(host)
        selectedClipId = project.clips.firstOrNull()?.id
        preview.maintainCache(project)
        preview.prepareCache(project)
        val saved = host.getSharedPreferences("audio_editor", 0).getString("export", null)
        readyFile = saved?.let { File(host.cacheDir, it) }?.takeIf {
            it.parentFile?.canonicalFile == host.cacheDir.canonicalFile && it.isFile
        }
        readyFile?.let { AudioExportFormat.fromFile(it) }?.let { readyFormat = it }
    }

    fun syncFromStore() {
        if (closed || !loaded) return
        val saved = store.load()
        if (saved == project) return
        undo.addLast(project)
        while (undo.size > 32) undo.removeFirst()
        redo.clear()
        project = saved
        preview.maintainCache(saved)
        preview.prepareCache(saved)
        previewMutedLanes.retainAll(saved.clips.mapTo(HashSet(), AudioEditClip::lane))
        selectedClipId = selectedClipId?.takeIf { id -> saved.clips.any { it.id == id } }
            ?: saved.clips.lastOrNull()?.id
        render()
    }

    fun change(operation: (AudioEditProject) -> AudioEditProject): Boolean {
        load()
        if (busy) return false
        return try {
            val next = operation(project)
            if (next == project) return true
            undo.addLast(project)
            while (undo.size > 32) undo.removeFirst()
            redo.clear()
            project = next
            preview.maintainCache(next)
            preview.prepareCache(next)
            previewMutedLanes.retainAll(next.clips.mapTo(HashSet(), AudioEditClip::lane))
            selectedClipId = selectedClipId?.takeIf { selected ->
                next.clips.any { it.id == selected }
            } ?: next.clips.lastOrNull()?.id
            status = ""
            store.save(project)
            render()
            true
        } catch (_: IllegalArgumentException) {
            message(host.tr("Check the range and overlapping clips on this lane",
                "Проверьте границы и пересечения фрагментов на дорожке"))
            false
        }
    }

    fun add(track: Track, lane: Int) {
        if (track.durationMs <= 0) {
            message(host.tr("Track duration is unavailable", "Длительность трека неизвестна"))
            return
        }
        val clip = AudioEditClip(uri = track.uri, title = track.title,
            sourceDurationMs = track.durationMs.toLong())
        val previousSelection = selectedClipId
        selectedClipId = clip.id
        if (!change { it.append(clip, lane) }) selectedClipId = previousSelection
    }

    val selectedClip: AudioEditClip?
        get() = selectedClipId?.let { selected -> project.clips.firstOrNull { it.id == selected } }

    fun select(clip: AudioEditClip) {
        if (project.clips.none { it.id == clip.id } || selectedClipId == clip.id) return
        selectedClipId = clip.id
        render()
    }

    fun moveClip(clip: AudioEditClip, target: AudioEditorDropTarget, nearMs: Long): Boolean {
        val previousMutes = previewMutedLanes.toSet()
        if (target !is AudioEditorDropTarget.Existing) previewMutedLanes.clear()
        val moved = change { project ->
            when (target) {
                AudioEditorDropTarget.Above -> project.moveToNewEdgeLane(clip.id, true, nearMs)
                AudioEditorDropTarget.Below -> project.moveToNewEdgeLane(clip.id, false, nearMs)
                is AudioEditorDropTarget.Existing -> {
                    val offset = project.nearestFreeOffset(clip.id, target.lane, nearMs)
                    project.replace(clip.copy(lane = target.lane, offsetMs = offset))
                }
            }
        }
        if (!moved && target !is AudioEditorDropTarget.Existing) {
            previewMutedLanes.addAll(previousMutes)
        }
        return moved
    }

    fun previewProject(): AudioEditProject {
        if (previewMutedLanes.isEmpty()) return project
        val audible = project.clips.filterNot { it.lane in previewMutedLanes }
        return project.copy(clips = audible)
    }

    fun togglePreviewLane(lane: Int) {
        load()
        if (project.clips.none { it.lane == lane }) return
        val resume = if (previewController.isInitialized() && preview.active) preview.positionMs else null
        if (resume != null) preview.stop()
        if (!previewMutedLanes.add(lane)) previewMutedLanes.remove(lane)
        render()
        if (resume != null && previewProject().clips.isNotEmpty()) preview.start(previewProject(), resume)
    }

    fun toggleEditingMode() {
        if (host.navigationState.tabIndex != LibraryTabs.EDITOR) return
        editingMode = !editingMode
        host.tabsController.refreshEditorModeIndicator()
        render()
    }

    fun openTrack(track: Track) {
        load()
        if (busy) {
            message(host.tr("Finish the current editor operation first",
                "Сначала завершите текущую операцию редактора"))
            return
        }
        val existing = project.clips.firstOrNull { it.uri == track.uri }
        if (existing != null) {
            selectedClipId = existing.id
        } else {
            add(track, 0)
        }
        if (!host.menuConfigurationController.isVisible(LibraryTabs.EDITOR)) {
            host.menuConfigurationController.setEnabled(LibraryTabs.EDITOR, true)
            host.refreshMenuConfiguration()
        }
        if (host.navigationState.tabIndex == LibraryTabs.EDITOR) {
            render()
        } else {
            host.switchTabAnimated(
                LibraryTabs.EDITOR,
                host.tabsController.directionTo(LibraryTabs.EDITOR),
            )
        }
    }

    fun undo() = restore(undo, redo)
    fun redo() = restore(redo, undo)

    private fun restore(from: ArrayDeque<AudioEditProject>, to: ArrayDeque<AudioEditProject>) {
        if (busy || from.isEmpty()) return
        to.addLast(project)
        project = from.removeLast()
        preview.maintainCache(project)
        preview.prepareCache(project)
        previewMutedLanes.retainAll(project.clips.mapTo(HashSet(), AudioEditClip::lane))
        selectedClipId = selectedClipId?.takeIf { selected ->
            project.clips.any { it.id == selected }
        } ?: project.clips.lastOrNull()?.id
        store.save(project)
        render()
    }

    fun export(format: AudioExportFormat = AudioExportFormat.M4A) {
        if (busy || project.clips.isEmpty()) return
        val token = ++exportGeneration
        working = true
        exporting = true
        status = host.tr("Preparing ${format.name}", "Подготовка ${format.name}")
        render()
        exporter.export(project, { value ->
            progress = value
            onProgress?.invoke()
        }) { result ->
            if (token != exportGeneration) { result.getOrNull()?.delete(); return@export }
            val m4a = result.getOrElse { error -> completeExport(token, format, Result.failure(error)); return@export }
            if (format == AudioExportFormat.M4A) completeExport(token, format, Result.success(m4a))
            else {
                status = host.tr("Creating ${format.name}", "Создание ${format.name}")
                render()
                files.execute {
                    val wave = File.createTempFile("voltune-edit-", ".wav", host.cacheDir)
                    var convertedFile: File? = null
                    val converted = runCatching {
                        WaveAudioConverter.convert(m4a, wave) { token != exportGeneration || closed }
                        if (format == AudioExportFormat.WAV) wave else {
                            File.createTempFile("voltune-edit-", ".mp3", host.cacheDir).also { mp3 ->
                                convertedFile = mp3
                                Mp3AudioConverter.convert(wave, mp3)
                                check(token == exportGeneration && !closed) { "Export cancelled" }
                            }
                        }
                    }
                    m4a.delete()
                    if (format != AudioExportFormat.WAV) wave.delete()
                    if (converted.isFailure) wave.delete()
                    host.uiHandler.post {
                        if (token == exportGeneration && !closed) completeExport(token, format, converted)
                        else converted.getOrNull()?.delete()
                        if (converted.isFailure) convertedFile?.delete()
                    }
                }
            }
        }
    }

    private fun completeExport(token: Int, format: AudioExportFormat, result: Result<File>) {
        if (token != exportGeneration || closed) { result.getOrNull()?.delete(); return }
        working = false
        exporting = false
        progress = -1
        result.fold(onSuccess = { file ->
            readyFile?.delete()
            readyFile = file
            readyFormat = format
            host.getSharedPreferences("audio_editor", 0).edit().putString("export", file.name).apply()
            status = host.tr("${format.name} is ready to save", "${format.name} готов к сохранению")
            render()
            saveExport()
        }, onFailure = {
            status = host.tr("Export failed. Check source files and free space.",
                "Не удалось экспортировать. Проверьте исходники и свободное место.")
            render()
        })
    }

    fun cancelExport() {
        if (!exporting) return
        exportGeneration++
        exporter.close()
        working = false
        exporting = false
        progress = -1
        status = host.tr("Export cancelled", "Экспорт отменён")
        render()
    }

    fun saveExport() {
        if (!canSave) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = readyFormat.mimeType
            putExtra(Intent.EXTRA_TITLE, "Voltune-${System.currentTimeMillis()}.${readyFormat.extension}")
        }
        try { host.startActivityForResult(intent, SAVE_AUDIO) } catch (_: Exception) {
            message(host.tr("File picker unavailable", "Выбор файла недоступен"))
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SAVE_AUDIO) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        val file = readyFile ?: return true
        if (busy) return true
        val sourceUris = project.clips.map { Uri.parse(it.uri) }.toSet()
        working = true
        status = host.tr("Saving", "Сохранение")
        render()
        val resolver = host.applicationContext.contentResolver
        files.execute {
            val result = runCatching {
                check(uri !in sourceUris) { "Source overwrite" }
                resolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output)
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
            host.uiHandler.post {
                if (closed) return@post
                working = false
                if (result.isSuccess) {
                    host.audioImportController.importExported(uri, data.flags)
                    preview.clearCache()
                    if (readyFile === file) readyFile = null
                    file.delete()
                    host.getSharedPreferences("audio_editor", 0).edit().remove("export").apply()
                }
                status = if (result.isSuccess) host.tr("Audio saved", "Аудио сохранено")
                    else host.tr("Saving failed; export is available to retry",
                        "Не удалось сохранить; экспорт доступен для повторной попытки")
                render()
            }
        }
        return true
    }

    private fun render() {
        if (!closed && host.navigationState.tabIndex == LibraryTabs.EDITOR &&
            !host.navigationState.tabAnimating) host.render()
    }

    private fun message(value: String) = Toast.makeText(host, value, Toast.LENGTH_LONG).show()

    override fun close() {
        closed = true
        editingMode = false
        if (previewController.isInitialized()) preview.close()
        if (processingController.isInitialized()) processing.close()
        onProgress = null
        exporter.close()
        files.shutdown()
        if (waveformRepository.isInitialized()) waveforms.close()
    }

    companion object { private const val SAVE_AUDIO = 6201 }
}
