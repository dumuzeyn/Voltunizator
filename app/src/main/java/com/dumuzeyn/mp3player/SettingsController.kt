package com.dumuzeyn.mp3player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal class SettingsController(private val host: MainActivityCore) {
    private val musicFolders = MusicFoldersController(host)

    fun openMusicFolders() {
        musicFolders.open()
    }

    fun resumeWindowText(): String {
        val minutes = host.appearanceState.resumeWindowMinutes
        if (minutes <= 0) return host.tr("off", "выкл")
        if (minutes % 60 == 0) {
            val hours = minutes / 60
            return "$hours ${host.tr(if (hours == 1) "hour" else "hours", "ч")}"
        }
        return "$minutes ${host.tr("min", "мин")}"
    }

    fun openLanguageDialog() {
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16))
        panel.addView(
            host.uiFactory.dialogTitle(host.tr("Language", "Язык")),
            host.uiFactory.dialogTitleParams(),
        )
        val choices = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        AppLanguages.all.forEach { language ->
            addChoice(choices, language.nativeName, host.appearanceState.language == language.code) {
                applyLanguage(language.code, shade)
            }
        }
        panel.addView(
            ScrollView(host).apply {
                isVerticalScrollBarEnabled = false
                addView(choices, LinearLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, host.dp(470)),
        )
        addDoneButton(panel, shade)
        shade.addView(panel, host.centerParams(host.dp(330), -2))
        host.overlayHost.addView(shade)
        host.playerUiController.updateMini()
    }

    fun openResumeWindowDialog() {
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16))
        panel.addView(
            host.uiFactory.dialogTitle(host.tr("Mini-player memory", "Память мини-плеера")),
            host.uiFactory.dialogTitleParams(),
        )
        for (value in intArrayOf(30, 60, 120, 240, 480, 0)) {
            val label = when {
                value == 0 -> host.tr("Off", "Отключено")
                value % 60 == 0 ->
                    "${value / 60} ${host.tr(if (value == 60) "hour" else "hours", "ч")}"
                else -> "$value ${host.tr("minutes", "мин")}"
            }
            addChoice(panel, label, host.appearanceState.resumeWindowMinutes == value) {
                host.appearanceState.resumeWindowMinutes = value
                host.saveState()
                host.playbackController.enforceMiniPlayerRetention()
                host.overlayHost.removeView(shade)
                host.refreshSettingsLabels()
                openResumeWindowDialog()
            }
        }
        addDoneButton(panel, shade)
        shade.addView(panel, host.centerParams(host.dp(330), -2))
        host.overlayHost.addView(shade)
        host.playerUiController.updateMini()
    }

    fun openGithub() {
        try {
            host.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/dumuzeyn/Voltunizator"),
                ).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        } catch (_: RuntimeException) {
            host.showConfirmPanel(
                host.tr("GitHub is unavailable", "GitHub недоступен"),
                host.tr(
                    "No application can open the project link.",
                    "Нет приложения, которое может открыть ссылку проекта.",
                ),
            ) {}
        }
    }

    fun openAuthorSupport() {
        host.showActionPanel(
            host.tr("Support the author", "Поддержка автора"),
            host.tr(
                "You will be taken to an external CloudTips page.\n\n" +
                    "Support is voluntary and non-refundable. It does not unlock " +
                    "additional features, a subscription, or other benefits. " +
                    "All application features remain free.",
                "Вы перейдёте на внешнюю страницу CloudTips.\n\n" +
                    "Поддержка является добровольной и безвозмездной. Она не открывает " +
                    "дополнительные функции, подписку или другие преимущества. " +
                    "Все возможности приложения остаются бесплатными.",
            ),
            host.tr("Cancel", "Отмена"),
            host.tr("Support", "Поддержать"),
            true,
            ::openSupportPage,
        )
    }

    private fun openSupportPage() {
        try {
            host.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        } catch (_: RuntimeException) {
            host.showConfirmPanel(
                host.tr("CloudTips is unavailable", "CloudTips недоступен"),
                host.tr(
                    "No application can open the support page.",
                    "Нет приложения, которое может открыть страницу поддержки.",
                ),
            ) {}
        }
    }

    fun openCrashReports() {
        val count = CrashReportStore.count(host)
        if (count == 0) {
            host.showConfirmPanel(
                host.tr("Crash reports", "Отчёты о сбоях"),
                host.tr(
                    "No local crash reports have been recorded.",
                    "Локальных отчётов о сбоях нет.",
                ),
            ) {}
            return
        }
        val message = host.tr("Saved reports: ", "Сохранено отчётов: ") + count +
            "\n" + host.tr("Latest: ", "Последний: ") + CrashReportStore.latestSummary(host) +
            "\n\n" + host.tr(
                "Reports stay only on this device and do not contain music URIs. Clear them?",
                "Отчёты хранятся только на устройстве и не содержат URI музыки. Очистить их?",
            )
        host.showConfirmPanel(host.tr("Crash reports", "Отчёты о сбоях"), message) {
            CrashReportStore.clear(host)
            host.render()
        }
    }

    fun confirmDeleteAllSongs() {
        host.showConfirmPanel(
            host.tr("Delete all songs?", "Удалить все песни?"),
            host.tr(
                "Songs and imported folder links will disappear from Voltune. " +
                    "Files on the phone will stay untouched.",
                "Песни и связи с импортированными папками исчезнут из Voltune. " +
                    "Файлы на телефоне останутся без изменений.",
            ),
        ) { host.playbackQueueController.clearLibrary() }
    }

    fun exportLibraryBackup() {
        launchDocumentIntent(Intent.ACTION_CREATE_DOCUMENT, "application/json") {
            putExtra(Intent.EXTRA_TITLE, "Voltune-backup.json")
        }.also { host.startActivityForResult(it, EXPORT_BACKUP) }
    }

    fun importLibraryBackup() {
        launchDocumentIntent(Intent.ACTION_OPEN_DOCUMENT, "application/json") {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.also { host.startActivityForResult(it, IMPORT_BACKUP) }
    }

    fun exportTheme() {
        launchDocumentIntent(Intent.ACTION_CREATE_DOCUMENT, "application/json") {
            putExtra(Intent.EXTRA_TITLE, "Voltune-theme.json")
        }.also { host.startActivityForResult(it, EXPORT_THEME) }
    }

    fun importTheme() {
        launchDocumentIntent(Intent.ACTION_OPEN_DOCUMENT, "application/json") {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.also { host.startActivityForResult(it, IMPORT_THEME) }
    }

    fun confirmExportPlaybackDiagnostics() {
        host.showActionPanel(
            host.tr("Export playback diagnostics", "Экспорт диагностики воспроизведения"),
            host.tr(
                "The report contains the app and Android versions, device model, " +
                    "current playback state and up to 200 recent playback events. " +
                    "Music paths, URIs, file names and tags are not included. " +
                    "The file is created only after you confirm.",
                "Отчёт содержит версии приложения и Android, модель устройства, " +
                    "текущее состояние и до 200 последних событий воспроизведения. " +
                    "Пути, URI, имена файлов и теги музыки не добавляются. " +
                    "Файл будет создан только после вашего подтверждения.",
            ),
            host.tr("Cancel", "Отмена"),
            host.tr("Export", "Экспортировать"),
            false,
            ::exportPlaybackDiagnostics,
        )
    }

    private fun exportPlaybackDiagnostics() {
        launchDocumentIntent(Intent.ACTION_CREATE_DOCUMENT, "text/plain") {
            putExtra(Intent.EXTRA_TITLE, "Voltune-playback-diagnostics.txt")
        }.also { host.startActivityForResult(it, EXPORT_PLAYBACK_DIAGNOSTICS) }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode !in HANDLED_REQUESTS) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        try {
            if (requestCode in EXPORT_REQUESTS) {
                writeExport(requestCode, uri)
                Toast.makeText(host, host.tr("File exported", "Файл сохранён"), Toast.LENGTH_SHORT)
                    .show()
            } else {
                restoreImport(requestCode, uri)
                Toast.makeText(host, host.tr("File restored", "Файл восстановлен"), Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (_: Exception) {
            host.showConfirmPanel(
                host.tr("Backup error", "Ошибка резервной копии"),
                host.tr(
                    "The selected file is damaged or unsupported.",
                    "Выбранный файл повреждён или не поддерживается.",
                ),
            ) {}
        }
        return true
    }

    private fun writeExport(requestCode: Int, uri: Uri) {
        val content = when (requestCode) {
            EXPORT_BACKUP -> LibraryBackupManager.exportBackup(
                host,
                host.libraryState.tracks,
                host.libraryState.playlists,
            )
            EXPORT_THEME -> ThemePresetCodec.encode(host.getSharedPreferences(UI_PREFS, 0))
            else -> PlaybackEventLogger.buildReport(host)
        }
        val output = host.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Output file is unavailable")
        output.use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun restoreImport(requestCode: Int, uri: Uri) {
        val input = host.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Input file is unavailable")
        val maxBytes = if (requestCode == IMPORT_BACKUP) {
            LibraryBackupManager.MAX_BACKUP_BYTES
        } else {
            ThemePresetCodec.MAX_BYTES
        }
        val bytes = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IllegalArgumentException("Backup is too large")
                bytes.write(buffer, 0, count)
            }
        }
        val encoded = bytes.toString(StandardCharsets.UTF_8.name())
        if (requestCode == IMPORT_BACKUP) {
            val imported = LibraryBackupManager.importBackup(host, encoded, host.libraryState.tracks)
            host.libraryState.playlists.clear()
            host.libraryState.playlists.addAll(imported.playlists)
            host.saveState()
            host.librarySnapshotApplier.rebuildDerivedAndRender()
            host.rebuildUi()
        } else {
            ThemePresetCodec.decodeInto(encoded, host.getSharedPreferences(UI_PREFS, 0))
            host.reloadUiPreferences()
        }
    }

    fun confirmRemoveUnavailableSongs() {
        host.libraryMaintenanceController.inspectUnavailable(
            host.libraryState.tracks,
            ::showUnavailableResult,
        )
    }

    private fun showUnavailableResult(unavailable: List<Track>) {
        if (unavailable.isEmpty()) {
            host.showConfirmPanel(
                host.tr("File access", "Доступ к файлам"),
                host.tr("All library files are available.", "Все файлы медиатеки доступны."),
            ) {}
            return
        }
        host.showConfirmPanel(
            host.tr("Remove unavailable songs?", "Удалить недоступные песни?"),
            host.tr("Unavailable records: ", "Недоступных записей: ") + unavailable.size +
                "\n\n" + host.tr(
                    "The audio files themselves will not be deleted.",
                    "Сами аудиофайлы удалены не будут.",
                ),
        ) {
            host.libraryMaintenanceController.removeUnavailable(unavailable) {
                host.librarySnapshotApplier.applyRemovedRecords(unavailable)
            }
        }
    }

    fun confirmDeleteAllPlaylists() {
        host.showConfirmPanel(
            host.tr("Delete all playlists?", "Удалить все плейлисты?"),
            host.tr("Songs will stay in the app.", "Песни останутся в приложении."),
        ) {
            host.libraryState.playlists.clear()
            host.saveState()
            host.librarySnapshotApplier.rebuildDerivedAndRender()
        }
    }

    private fun applyLanguage(language: String, shade: FrameLayout) {
        host.appearanceState.language = language
        host.saveState()
        host.overlayHost.removeView(shade)
        host.rebuildUi()
        openLanguageDialog()
    }

    private fun addDoneButton(parent: LinearLayout, shade: FrameLayout) {
        val done = host.uiFactory.button(host.tr("Done", "Готово"))
        host.uiFactory.applyPrimaryButtonStyle(done)
        done.setOnClickListener {
            if (shade.parent != null) host.overlayHost.removeView(shade)
            host.playerUiController.updateMini()
        }
        parent.addView(
            done,
            LinearLayout.LayoutParams(-1, host.dp(50)).apply {
                setMargins(0, host.dp(8), 0, 0)
            },
        )
    }

    private fun addChoice(
        parent: LinearLayout,
        label: String,
        selected: Boolean,
        action: () -> Unit,
    ) {
        val button: Button = host.uiFactory.button(label)
        button.textSize = 17f
        button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        button.setPadding(host.dp(18), 0, host.dp(12), 0)
        if (selected) {
            host.uiFactory.applyPrimaryButtonStyle(button)
        } else {
            host.uiFactory.applySecondaryButtonStyle(button)
        }
        button.setOnClickListener { action() }
        parent.addView(
            button,
            LinearLayout.LayoutParams(-1, host.dp(54)).apply {
                setMargins(0, host.dp(5), 0, host.dp(5))
            },
        )
    }

    private fun launchDocumentIntent(
        action: String,
        type: String,
        configure: Intent.() -> Unit,
    ): Intent = Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        setType(type)
        configure()
    }

    companion object {
        private const val EXPORT_BACKUP = 5201
        private const val IMPORT_BACKUP = 5202
        private const val EXPORT_THEME = 5203
        private const val IMPORT_THEME = 5204
        private const val EXPORT_PLAYBACK_DIAGNOSTICS = 5205
        private const val SUPPORT_URL = "https://pay.cloudtips.ru/p/54e5a4f9"
        private const val UI_PREFS = "mp3_player_ui"

        private val HANDLED_REQUESTS = setOf(
            EXPORT_BACKUP,
            IMPORT_BACKUP,
            EXPORT_THEME,
            IMPORT_THEME,
            EXPORT_PLAYBACK_DIAGNOSTICS,
        )
        private val EXPORT_REQUESTS = setOf(
            EXPORT_BACKUP,
            EXPORT_THEME,
            EXPORT_PLAYBACK_DIAGNOSTICS,
        )
    }
}
