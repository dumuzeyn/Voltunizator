package com.dumuzeyn.mp3player

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal class SettingsRenderer(private val host: MainActivityCore) {
    private var memoryButton: Button? = null
    private var artistButton: Button? = null
    private var uninterruptedButton: Button? = null
    private var backgroundPlaybackButton: Button? = null
    private val interTrackDelay = InterTrackDelaySettingsController(host)
    private var interTrackDelayButton: Button? = null
    private var volumeButton: Button? = null
    private var equalizerButton: Button? = null
    private var stableVolumeButton: Button? = null
    private val fadeSettings = FadeSettingsController(host)
    private var fadeButton: Button? = null
    private var coverStyleButton: Button? = null
    private var coverMotionButton: Button? = null
    private var rotationButton: Button? = null
    private var animationsButton: Button? = null
    private var particlesButton: Button? = null
    private var soundAnalysisButton: Button? = null
    private var reanalyzeButton: Button? = null
    private var cachedContent: LinearLayout? = null
    private var cachedAppearanceKey = ""

    fun render() {
        val advanced = host.getSharedPreferences(PREFS, 0).getBoolean(ADVANCED, false)
        val appearanceKey = appearanceKey(advanced)
        if (cachedContent == null || appearanceKey != cachedAppearanceKey) {
            rebuildCachedContent(advanced, appearanceKey)
        }
        val content = checkNotNull(cachedContent)
        (content.parent as? ViewGroup)?.removeView(content)
        host.list.addView(content)
        refreshDynamicLabels()
    }

    fun refreshDynamicLabels() {
        artistButton?.text = artistLabel()
        memoryButton?.text = host.tr("Mini-player memory: ", "Память мини-плеера: ") +
            host.settingsController.resumeWindowText()
        uninterruptedButton?.text = host.uninterruptedPlaybackController.settingLabel()
        backgroundPlaybackButton?.text = host.backgroundPlaybackSettingsController.settingLabel()
        interTrackDelayButton?.text = interTrackDelay.label()
        volumeButton?.text = host.volumeLevelingController.settingLabel()
        equalizerButton?.text = host.equalizerController.settingLabel()
        stableVolumeButton?.text = host.stableVolumeController.settingLabel()
        fadeButton?.text = fadeSettings.label()
        rotationButton?.text = host.coverRotationSettingsController.settingLabel()
        coverStyleButton?.text = coverStyleLabel()
        coverMotionButton?.text = coverMotionLabel()
        animationsButton?.text = animationsLabel()
        particlesButton?.text = particlesLabel()
        soundAnalysisButton?.text = host.soundAnalysisController.settingLabel()
        reanalyzeButton?.let {
            it.text = reanalysisLabel()
            it.isEnabled = !host.soundAnalysisController.rebuildingGroups() &&
                !host.soundAnalysisController.fullReanalysis()
        }
    }

    private fun rebuildCachedContent(advanced: Boolean, appearanceKey: String) {
        val previousTarget = host.list
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        cachedContent = content
        host.list = content
        try {
            renderContent(advanced)
        } finally {
            host.list = previousTarget
        }
        cachedAppearanceKey = appearanceKey
    }

    private fun renderContent(advanced: Boolean) {
        section(host.tr("General", "Основные"))
        addButton(host.tr("Language: ", "Язык: ") + host.languageName()) {
            host.settingsController.openLanguageDialog()
        }
        artistButton = addButton(artistLabel()) {
            host.appearanceState.showArtistName = !host.appearanceState.showArtistName
            host.saveUiState()
            host.playbackController.refreshArtistVisibility()
            host.playerUiController.syncPlaybackUi()
            refreshDynamicLabels()
        }
        memoryButton = addButton(
            host.tr("Mini-player memory: ", "Память мини-плеера: ") +
                host.settingsController.resumeWindowText(),
        ) { host.settingsController.openResumeWindowDialog() }

        section(host.tr("Navigation", "Навигация"))
        addButton(host.tr("Menu sections and order", "Разделы и порядок меню")) {
            host.menuConfigurationDialog.open()
        }

        section(host.tr("Playback", "Воспроизведение"))
        uninterruptedButton = addButton(host.uninterruptedPlaybackController.settingLabel()) {
            host.uninterruptedPlaybackController.toggle()
        }
        backgroundPlaybackButton = addButton(
            host.backgroundPlaybackSettingsController.settingLabel(),
        ) { host.backgroundPlaybackSettingsController.openDialog() }
        interTrackDelayButton = addButton(interTrackDelay.label()) { interTrackDelay.openDialog() }

        section(host.tr("Sound", "Звук"))
        fadeButton = addButton(fadeSettings.label()) { fadeSettings.toggle() }.apply {
            setOnLongClickListener { fadeSettings.openDialog(); true }
        }
        volumeButton = addButton(host.volumeLevelingController.settingLabel()) {
            host.volumeLevelingController.toggle()
        }.apply {
            setOnLongClickListener { host.volumeLevelingController.openDialog(); true }
        }
        equalizerButton = addButton(host.equalizerController.settingLabel()) {
            host.equalizerController.toggle()
        }.apply {
            setOnLongClickListener { host.equalizerController.openDialog(); true }
        }
        stableVolumeButton = addButton(host.stableVolumeController.settingLabel()) {
            host.stableVolumeController.toggle()
        }

        section(host.tr("Appearance", "Внешний вид"))
        subsection(host.tr("Ready themes and accent colors", "Готовые темы и акцентные цвета"))
        addButton(host.tr("Theme: ", "Тема: ") + host.themeName()) { host.openThemeDialog() }
        subsection(host.tr("Text, outline, and background", "Текст, контур и фон"))
        addButton(host.tr("Background", "Фон")) { host.backgroundSettingsController.openDialog() }
        addButton(host.tr("Export theme", "Экспорт темы")) {
            host.settingsController.exportTheme()
        }
        addButton(host.tr("Import theme", "Импорт темы")) {
            host.settingsController.importTheme()
        }
        subsection(host.tr("Cards and artwork", "Карточки и обложки"))
        addButton(host.cardTransparencyController.settingLabel()) {
            host.cardTransparencyController.openDialog()
        }
        coverStyleButton = addButton(coverStyleLabel()) { openCoverStylePicker() }
        coverMotionButton = addButton(coverMotionLabel()) {
            host.appearanceState.rotateCovers = !host.appearanceState.rotateCovers
            host.saveState()
            CoverAppearanceRefresher.refresh(host)
            refreshDynamicLabels()
        }
        defaults(SettingsSectionResetter.Section.APPEARANCE)

        section(host.tr("Full player", "Большой плеер"))
        rotationButton = addButton(host.coverRotationSettingsController.settingLabel()) {
            host.coverRotationSettingsController.openDialog()
        }
        defaults(SettingsSectionResetter.Section.FULL_PLAYER)

        section(host.tr("Animations", "Анимации"))
        animationsButton = addButton(animationsLabel()) { toggleAnimations() }
        particlesButton = addButton(particlesLabel()) { toggleParticles() }
        addButton(host.tr("Particle settings", "Настройка частиц")) {
            host.particleSettingsController.openDialog()
        }
        defaults(SettingsSectionResetter.Section.ANIMATIONS)

        section(host.tr("Library", "Библиотека"))
        soundAnalysisButton = addButton(host.soundAnalysisController.settingLabel()) {
            host.soundAnalysisController.toggle()
        }
        reanalyzeButton = addButton(reanalysisLabel()) { confirmFullReanalysis() }
        addButton(host.tr("Check songs", "Проверить песни")) { host.openSongDiagnostics() }
        addButton(host.tr("Music folders", "Музыкальные папки")) {
            host.settingsController.openMusicFolders()
        }
        addButton(host.tr("Rescan music folders", "Повторно сканировать папки")) {
            host.audioImportController.rescanPersistedFolders()
        }
        addButton(
            host.tr("Export playlists and settings", "Экспорт плейлистов и настроек"),
        ) { host.settingsController.exportLibraryBackup() }
        addButton(
            host.tr("Import playlists and settings", "Импорт плейлистов и настроек"),
        ) { host.settingsController.importLibraryBackup() }

        addPrimaryButton(
            host.tr(
                if (advanced) "Hide advanced settings" else "Advanced settings",
                if (advanced) "Скрыть расширенные настройки" else "Расширенные настройки",
            ),
        ) {
            host.getSharedPreferences(PREFS, 0).edit().putBoolean(ADVANCED, !advanced).apply()
            host.render()
        }
        if (advanced) renderAdvanced()

        section(host.tr("About", "О приложении"))
        addButton(host.tr("GitHub project", "Проект на GitHub")) {
            host.settingsController.openGithub()
        }
        addButton(host.tr("Support the author", "Поддержка автора")) {
            host.settingsController.openAuthorSupport()
        }
    }

    private fun appearanceKey(advanced: Boolean): String = buildString {
        append(host.appearanceState.language).append('|').append(advanced)
        append('|').append(host.bg).append('|').append(host.fg).append('|').append(host.panel)
        append('|').append(host.cardStroke)
        append('|').append(host.appearanceState.settingsCardOpacity)
        append('|').append(host.appearanceState.textOutlineEnabled)
        append('|').append(host.appearanceState.textOutlineColor)
    }

    private fun artistLabel(): String =
        host.tr("Show artist: ", "Показывать исполнителя: ") +
            if (host.appearanceState.showArtistName) host.tr("on", "вкл") else host.tr("off", "выкл")

    private fun renderAdvanced() {
        section(host.tr("Advanced library", "Расширенная библиотека"))
        addButton(host.tr("Batch edit metadata", "Массовое изменение метаданных")) {
            host.metadataEditorController.openBatchSelection()
        }
        addButton(host.tr("Remove unavailable songs", "Удалить недоступные песни")) {
            host.settingsController.confirmRemoveUnavailableSongs()
        }
        addButton(host.tr("Delete all songs from app", "Удалить все песни из приложения")) {
            host.settingsController.confirmDeleteAllSongs()
        }
        addButton(host.tr("Delete all playlists", "Удалить все плейлисты")) {
            host.settingsController.confirmDeleteAllPlaylists()
        }

        section(host.tr("Diagnostics", "Диагностика"))
        addButton(
            host.tr("Crash reports: ", "Отчёты о сбоях: ") + CrashReportStore.count(host),
        ) { host.settingsController.openCrashReports() }
        addButton(
            host.tr("Export playback diagnostics", "Экспорт диагностики воспроизведения"),
        ) { host.settingsController.confirmExportPlaybackDiagnostics() }
    }

    private fun toggleAnimations() {
        host.appearanceState.animations = !host.appearanceState.animations
        host.navigationState.tabAnimating = false
        if (!host.appearanceState.animations) {
            host.list?.let {
                it.animate().cancel()
                it.translationX = 0f
                it.alpha = 1f
            }
        }
        host.saveState()
        refreshDynamicLabels()
    }

    private fun toggleParticles() {
        host.appearanceState.particlesEnabled = !host.appearanceState.particlesEnabled
        host.saveState()
        host.refreshParticleSettings()
        refreshDynamicLabels()
    }

    private fun openCoverStylePicker() {
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.dialogTitle(host.tr("Cover shape", "Форма обложки")))
        listOf(
            Triple("rounded", "Rounded square", "Скруглённый квадрат"),
            Triple("circle", "Circle", "Круг"),
            Triple("hexagon", "Hexagon", "Шестиугольник"),
            Triple("diamond", "Diamond", "Ромб"),
            Triple("star", "Star", "Звезда"),
            Triple("triangle", "Triangle", "Треугольник"),
        ).forEach { (shape, english, russian) ->
            val button = host.uiFactory.button(host.tr(english, russian))
            if (host.appearanceState.coverShape == shape) host.uiFactory.applyPrimaryButtonStyle(button)
            button.setOnClickListener {
                val previousShape = host.appearanceState.coverShape
                host.appearanceState.coverShape = shape
                host.appearanceState.circularCovers = shape == "circle"
                if (previousShape == "rounded" && shape != "rounded") {
                    host.appearanceState.rotateCovers = true
                }
                host.saveState()
                host.overlayHost.removeView(shade)
                CoverAppearanceRefresher.refresh(host)
                refreshDynamicLabels()
            }
            panel.addView(button, LinearLayout.LayoutParams(-1, host.dp(48)))
        }
        shade.addView(panel, host.centerParams(host.dp(340), -2))
        host.overlayHost.addView(shade)
    }

    private fun section(label: String) {
        val title = host.uiFactory.text(label, 20, true)
        host.list.addView(
            title,
            LinearLayout.LayoutParams(-1, host.dp(42)).apply {
                setMargins(host.dp(4), host.dp(12), host.dp(4), 0)
            },
        )
    }

    private fun subsection(label: String) {
        val title = host.uiFactory.text(label, 14, true).apply {
            setTextColor(host.secondaryText)
        }
        host.list.addView(
            title,
            LinearLayout.LayoutParams(-1, host.dp(32)).apply {
                setMargins(host.dp(10), host.dp(4), host.dp(10), 0)
            },
        )
    }

    private fun defaults(section: SettingsSectionResetter.Section) {
        addButton(host.tr("Default", "По умолчанию")) {
            SettingsSectionResetter.reset(host, section)
        }
    }

    private fun coverStyleLabel(): String = host.tr("Cover shape: ", "Форма обложки: ") +
        when (host.appearanceState.coverShape) {
            "circle" -> host.tr("Circle", "Круг")
            "hexagon" -> host.tr("Hexagon", "Шестиугольник")
            "diamond" -> host.tr("Diamond", "Ромб")
            "star" -> host.tr("Star", "Звезда")
            "triangle" -> host.tr("Triangle", "Треугольник")
            else -> host.tr("Rounded square", "Скруглённый квадрат")
        }

    private fun coverMotionLabel(): String = host.tr("Rotate covers: ", "Вращать обложки: ") +
        host.tr(
            if (host.appearanceState.rotateCovers) "on" else "off",
            if (host.appearanceState.rotateCovers) "вкл" else "выкл",
        )

    private fun animationsLabel(): String = host.tr("Animations: ", "Анимации: ") + host.tr(
        if (host.appearanceState.animations) "on" else "off",
        if (host.appearanceState.animations) "вкл" else "выкл",
    )

    private fun particlesLabel(): String = host.tr("Particles: ", "Частицы: ") + host.tr(
        if (host.appearanceState.particlesEnabled) "on" else "off",
        if (host.appearanceState.particlesEnabled) "вкл" else "выкл",
    )

    private fun reanalysisLabel(): String {
        val analysis = host.soundAnalysisController
        return if (analysis.fullReanalysis()) {
            host.tr("Re-analyzing library: ", "Повторный анализ: ") +
                analysis.analyzed() + " / " + analysis.total()
        } else {
            host.tr("Re-analyze library", "Повторно проанализировать библиотеку")
        }
    }

    private fun confirmFullReanalysis() {
        host.showConfirmPanel(
            host.tr("Re-analyze library", "Повторный анализ библиотеки"),
            host.tr(
                "Saved sound profiles will be rebuilt for every available track. " +
                    "Playback can continue, but the operation may take time.",
                "Сохранённые звуковые признаки будут заново построены для всех " +
                    "доступных треков. Воспроизведение продолжит работать, " +
                    "но операция может занять время.",
            ),
        ) { host.soundAnalysisController.reanalyzeLibrary() }
    }

    private fun addButton(label: String, listener: View.OnClickListener): Button {
        val button = host.uiFactory.button(label).apply {
            textSize = 17f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(host.dp(18), 0, host.dp(12), 0)
        }
        host.uiFactory.applySecondaryButtonStyle(
            button,
            host.appearanceState.settingsCardOpacity,
        )
        val lower = label.lowercase(Locale.ROOT)
        if (lower.contains("delete") || lower.contains("удал")) {
            button.setTextColor(Color.rgb(190, 45, 45))
        }
        button.setOnClickListener(listener)
        host.list.addView(
            button,
            LinearLayout.LayoutParams(-1, host.dp(54)).apply {
                setMargins(0, host.dp(2), 0, host.dp(2))
            },
        )
        return button
    }

    private fun addPrimaryButton(label: String, listener: View.OnClickListener) {
        val button = host.uiFactory.button(label)
        host.uiFactory.applyPrimaryButtonStyle(button)
        button.setOnClickListener(listener)
        host.list.addView(
            button,
            LinearLayout.LayoutParams(-1, host.dp(50)).apply {
                setMargins(0, host.dp(12), 0, host.dp(4))
            },
        )
    }

    private companion object {
        const val PREFS = "mp3_player_ui"
        const val ADVANCED = "advancedSettingsVisible"
    }
}
