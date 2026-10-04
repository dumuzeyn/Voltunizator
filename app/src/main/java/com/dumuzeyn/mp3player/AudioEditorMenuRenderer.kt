package com.dumuzeyn.mp3player

import android.os.Build
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar

internal class AudioEditorMenuRenderer(private val host: MainActivityCore) : MenuRenderer {
    override fun needsMiniSpacer() = true

    override fun render() {
        val controller = host.audioEditorController
        controller.load()
        val dialogs = AudioEditorDialogs(host)
        val toolbar = host.uiFactory.row().apply { gravity = Gravity.CENTER }
        toolbar.addView(tool(StrictIcon.ADD, host.tr("Add audio", "Добавить аудио"), !controller.busy) {
            dialogs.chooseTrack(0)
        })
        toolbar.addView(tool(StrictIcon.UNDO, host.tr("Undo", "Отменить"), controller.canUndo, controller::undo))
        toolbar.addView(tool(StrictIcon.REDO, host.tr("Redo", "Повторить"), controller.canRedo, controller::redo))
        toolbar.addView(tool(StrictIcon.DELETE, host.tr("Clear project", "Очистить проект"),
            !controller.busy && controller.project.clips.isNotEmpty()) {
            host.showConfirmPanel(host.tr("Clear project?", "Очистить проект?"),
                host.tr("Remove all clips from the draft?", "Удалить все фрагменты из черновика?"),
                Runnable { controller.change { AudioEditProject() } })
        })
        toolbar.addView(editorModeTool(controller.editingMode) { controller.toggleEditingMode() })
        host.list.addView(toolbar)
        if (controller.project.clips.isEmpty()) {
            host.list.addView(host.uiFactory.text(host.tr("No audio clips", "Нет аудиофрагментов"), 17, false))
        } else {
            host.list.addView(
                AudioEditorWorkspaceView(
                    host,
                    controller.project,
                    controller.selectedClipId,
                    controller.mutedPreviewLanes,
                    controller::select,
                    controller::togglePreviewLane,
                    controller::moveClip,
                ),
                LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, host.dp(4), 0, host.dp(8))
                },
            )
            controller.selectedClip?.let { selected ->
                renderClipTools(controller, dialogs, selected)
            }
            host.list.addView(View(host), LinearLayout.LayoutParams(-1, host.dp(6)))
            controller.project.clips.groupBy { it.lane }.toSortedMap().forEach { (lane, clips) ->
                val header = host.uiFactory.row()
                header.addView(host.uiFactory.text("${host.tr("Lane", "Дорожка")} ${lane + 1}", 16, true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(tool(StrictIcon.ADD, host.tr("Append audio", "Добавить аудио в конец"), !controller.busy) {
                    dialogs.chooseTrack(lane)
                })
                host.list.addView(header)
                clips.sortedBy { it.offsetMs }.forEach { clip ->
                    val row = host.uiFactory.button(
                        "${clip.title}\n${host.formatSeconds(clip.offsetMs / 1000)} + " +
                            host.formatSeconds(clip.durationMs / 1000),
                    ).apply {
                        minHeight = 0
                        maxHeight = host.dp(44)
                        maxLines = 2
                        setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3))
                        host.uiFactory.applyPlayerToolStyle(this, clip.id == controller.selectedClipId)
                        setOnClickListener { controller.select(clip) }
                        contentDescription = if (clip.id == controller.selectedClipId) {
                            "${host.tr("Selected clip", "Выбранный фрагмент")}: ${clip.title}"
                        } else {
                            "${host.tr("Select clip", "Выбрать фрагмент")}: ${clip.title}"
                        }
                    }
                    host.list.addView(row, LinearLayout.LayoutParams(-1, host.dp(44)).apply {
                        setMargins(0, host.dp(1), 0, host.dp(1))
                    })
                }
            }
            host.list.addView(View(host), LinearLayout.LayoutParams(-1, host.dp(12)))
            val nextLane = (0 until AudioEditClip.MAX_LANES).firstOrNull { lane ->
                controller.project.clips.none { it.lane == lane }
            }
            host.list.addView(command(host.tr("Add lane", "Добавить дорожку"),
                !controller.busy && nextLane != null) { dialogs.chooseTrack(nextLane ?: 0) })
            host.list.addView(View(host), LinearLayout.LayoutParams(-1, host.dp(6)))
            host.list.addView(command(host.tr("Join in clip order", "Соединить по порядку фрагментов"),
                !controller.busy) { controller.change { it.concatenate() } })
        }
        val status = host.uiFactory.text(controller.status, 14, false)
        status.visibility = if (controller.status.isEmpty()) View.GONE else View.VISIBLE
        host.list.addView(status)
        val processing = controller.processing
        val processingStatus = host.uiFactory.text(processing.status, 14, false)
        processingStatus.visibility = if (processing.status.isEmpty()) View.GONE else View.VISIBLE
        host.list.addView(processingStatus)
        val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = if (controller.busy && !controller.preview.active) View.VISIBLE else View.GONE
            isIndeterminate = !processing.active && controller.progress < 0
            this.progress = if (processing.active) processing.progress else controller.progress.coerceAtLeast(0)
        }
        host.list.addView(progress, LinearLayout.LayoutParams(-1, host.dp(12)))
        if (!host.navigationState.renderingTabPreview) controller.onProgress = {
            progress.isIndeterminate = controller.progress < 0
            progress.progress = controller.progress.coerceAtLeast(0)
        }
        if (!host.navigationState.renderingTabPreview) processing.onProgress = {
            progress.isIndeterminate = false
            progress.progress = processing.progress
        }
        if (processing.active) host.list.addView(command(host.tr("Cancel processing", "Отменить обработку"),
            true, processing::cancel))
        host.list.addView(View(host), LinearLayout.LayoutParams(-1, host.dp(12)))
        host.list.addView(command(host.tr("Export", "Экспортировать"),
            !controller.busy && controller.project.clips.isNotEmpty(), dialogs::chooseExport).apply {
            host.uiFactory.applyPrimaryButtonStyle(this)
        })
        if (controller.exporting) host.list.addView(command(host.tr("Cancel export", "Отменить экспорт"), true,
            controller::cancelExport))
        if (controller.canSave) host.list.addView(command(host.tr("Save exported audio", "Сохранить готовое аудио"),
            true, controller::saveExport))
    }

    private fun renderClipTools(
        controller: AudioEditorController,
        dialogs: AudioEditorDialogs,
        clip: AudioEditClip,
    ) {
        host.list.addView(host.uiFactory.text(
            "${host.tr("Selected", "Выбрано")}: ${clip.title}",
            16,
            true,
        ))
        val enabled = !controller.busy
        val actions = listOf(
            EditorAction(host.tr("Cut", "Обрезать"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.CUT)
            },
            EditorAction(host.tr("Position", "Позиция"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.POSITION)
            },
            EditorAction(host.tr("Change volume", "Изменить громкость"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.VOLUME)
            },
            EditorAction(host.tr("Remove noise", "Убрать шумы"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.CLEAN_SPEECH)
            },
            EditorAction(host.tr("Separate stems", "Разделить на дорожки"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.SEPARATE_STEMS)
            },
            EditorAction(host.tr("Vocal work", "Работа с вокалом"), enabled) {
                dialogs.edit(clip, AudioEditorDialogs.Focus.REMOVE_VOCALS)
            },
            EditorAction(host.tr("Delete", "Удалить"), enabled) {
                host.showConfirmPanel(
                    host.tr("Remove selected clip?", "Удалить выбранный фрагмент?"),
                    clip.title,
                    Runnable { controller.change { it.remove(clip.id) } },
                )
            },
        )
        actions.chunked(2).forEach { pair ->
            val row = host.uiFactory.row()
            pair.forEach { action ->
                row.addView(command(action.label, action.enabled, action.run),
                    LinearLayout.LayoutParams(0, host.dp(48), 1f).apply {
                        setMargins(host.dp(2), host.dp(2), host.dp(2), host.dp(2))
                    })
            }
            if (pair.size == 1) row.addView(View(host),
                LinearLayout.LayoutParams(0, host.dp(48), 1f).apply {
                    setMargins(host.dp(2), host.dp(2), host.dp(2), host.dp(2))
                })
            host.list.addView(row, LinearLayout.LayoutParams(-1, host.dp(52)))
        }
    }

    private fun tool(icon: StrictIcon, label: String, enabled: Boolean, run: () -> Unit): Button =
        host.uiFactory.icon(icon).apply {
            contentDescription = label
            if (Build.VERSION.SDK_INT >= 26) tooltipText = label
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            layoutParams = host.uiFactory.square(44)
            setOnClickListener { run() }
        }

    private fun editorModeTool(active: Boolean, run: () -> Unit): Button =
        tool(
            StrictIcon.LOCK,
            if (active) host.tr("Exit editing mode", "Выйти из режима редактирования")
            else host.tr("Lock editing mode", "Зафиксировать режим редактирования"),
            true,
            run,
        ).apply {
            id = R.id.editor_mode_lock
            if (active) {
                val blackContrast = ThemeContrastPolicy.contrastRatio(Color.BLACK, host.yellow)
                val whiteContrast = ThemeContrastPolicy.contrastRatio(Color.WHITE, host.yellow)
                setTextColor(if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE)
                val activeBackground = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(host.yellow)
                }
                host.uiFactory.setIconOnBackground(this, StrictIcon.LOCK, activeBackground)
                elevation = host.dp(2).toFloat()
            } else {
                host.uiFactory.applyPlainIconStyle(this)
            }
        }

    private fun command(label: String, enabled: Boolean, run: () -> Unit) = host.uiFactory.button(label).apply {
        layoutParams = LinearLayout.LayoutParams(-1, host.dp(52))
        host.uiFactory.applySecondaryButtonStyle(this)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
        setOnClickListener { run() }
    }

    private data class EditorAction(
        val label: String,
        val enabled: Boolean,
        val run: () -> Unit,
    )
}
