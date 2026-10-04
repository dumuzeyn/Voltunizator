@file:Suppress(
    "EXPOSED_FUNCTION_RETURN_TYPE",
    "EXPOSED_PARAMETER_TYPE",
    "EXPOSED_PROPERTY_TYPE",
)

package com.dumuzeyn.mp3player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dumuzeyn.mp3player.ui.layout.ResponsiveLayoutController
import com.dumuzeyn.mp3player.ui.player.PlaybackTimeFormatter
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

open class MainActivityCore : Activity() {
    companion object {
        const val COVER_FULL_SIZE = 1024
        const val TAB_CYCLES = 5
    }

    @JvmField var bg = 0
    @JvmField var fg = 0
    @JvmField var line = 0
    lateinit var list: LinearLayout
    lateinit var miniButton: Button
    lateinit var miniPlayer: LinearLayout
    lateinit var miniSub: TextView
    lateinit var miniTitle: TextView
    @JvmField var muted = 0
    @JvmField var purple = 0
    @JvmField var purpleDark = 0
    @JvmField var purpleSoft = 0
    @JvmField var yellow = 0
    @JvmField var yellowDark = 0
    @JvmField var yellowSoft = 0
    @JvmField var card = 0
    @JvmField var cardStroke = 0
    @JvmField var primaryText = 0
    @JvmField var secondaryText = 0
    lateinit var overlayHost: FrameLayout
    lateinit var page: LinearLayout
    @JvmField var panel = 0
    lateinit var root: FrameLayout
    lateinit var tabRow: LinearLayout
    lateinit var tabs: Array<String>
    lateinit var tabsScroll: HorizontalScrollView
    lateinit var contentHost: FrameLayout
    lateinit var contentScroll: ScrollView
    @JvmField var songsView: SongsView? = null

    @JvmField val libraryState = LibraryState()
    @JvmField val navigationState = NavigationState()
    @JvmField val appearanceState = AppearanceState()
    private val localization = LocalizationController(this)
    @JvmField val playbackUiState = PlaybackUiState()
    @JvmField var particleEffectsView: ParticleEffectsView? = null
    @JvmField val uiHandler = Handler(Looper.getMainLooper())
    @JvmField val playbackHandler = Handler(Looper.getMainLooper())
    @JvmField val songRows = SongRowStateRegistry()
    @JvmField val previewSongRows = SongRowStateRegistry()
    @JvmField val artworkUi = TrackArtworkUi(
        this,
        uiHandler,
        TrackArtworkDependencies(
            { navigationState.renderingTabPreview },
            ::activeSongRows,
            ::findTrack,
            ::isCurrent,
            ::isPlaybackPlaying,
            { purple },
            { yellow },
            { purpleSoft },
            { appearanceState.animations },
        ),
    )
    @JvmField val songsRenderer = SongsRenderer(this)
    private val settingsRenderer = SettingsRenderer(this)
    @JvmField val settingsController = SettingsController(this)
    @JvmField val tabsController = TabsController(this)
    @JvmField val swipeController = SwipeController(this)
    @JvmField val audioImportController = AudioImportController(this)
    @JvmField val audioEditorController = AudioEditorController(this)
    @JvmField val libraryMaintenanceController = LibraryMaintenanceController(this, uiHandler)
    @JvmField val uiFactory = UiFactory(this)
    @JvmField val headerController = HeaderController(this)
    @JvmField val overlayController = OverlayController(this)
    private val diagnosticsDialogs = LibraryDiagnosticsDialogController(this)
    @JvmField val librarySnapshotApplier = LibrarySnapshotApplier(this)
    @JvmField val backNavigationController = BackNavigationController(this)
    @JvmField val themeController = ThemeController(this)
    @JvmField val playbackController = PlaybackController(this)
    @JvmField val playbackQueueController = PlaybackQueueController(this, playbackController)
    @JvmField val playbackActions: PlaybackActions =
        Media3PlaybackActions(playbackQueueController, playbackController)
    @JvmField val playbackStateProvider: PlaybackStateProvider =
        Media3PlaybackStateProvider(libraryState, playbackUiState)
    @JvmField val playerUiController =
        PlayerUiController(this, playbackActions, playbackStateProvider)
    @JvmField val sleepTimerController = SleepTimerController(this)
    @JvmField val equalizerController = EqualizerController(this)
    @JvmField val volumeLevelingController = VolumeLevelingController(this)
    @JvmField val particleSettingsController = ParticleSettingsController(this)
    @JvmField val coverRotationSettingsController = CoverRotationSettingsController(this)
    @JvmField val uninterruptedPlaybackController = UninterruptedPlaybackController(this)
    @JvmField val stableVolumeController = StableVolumeController(this)
    @JvmField val backgroundPlaybackSettingsController = BackgroundPlaybackSettingsController(this)
    @JvmField val cardTransparencyController = CardTransparencyController(this)
    @JvmField val backgroundSettingsController = BackgroundSettingsController(this)
    @JvmField val libraryListController = LibraryListController(this)
    @JvmField val playlistController = PlaylistController(this)
    @JvmField val mainRenderer = MainRenderer(this)
    @JvmField val uiPreferencesStore = UiPreferencesStore(this)
    @JvmField val menuConfigurationController = MenuConfigurationController(this)
    @JvmField val menuConfigurationDialog = MenuConfigurationDialog(this)
    @JvmField val libraryPersistenceController = LibraryPersistenceController(this)
    @JvmField val libraryLoader = LibraryLoader(this, uiHandler)
    @JvmField val soundAnalysisController = SoundAnalysisController(this)
    @JvmField val trackSearchController = TrackSearchController(uiHandler)
    @JvmField val globalSearchController = GlobalSearchController(uiHandler)
    @JvmField val lyricsRepository = LyricsRepository(this, uiHandler)
    @JvmField val metadataEditorController = MetadataEditorController(this)
    @JvmField val libraryRepository = LibraryRepository(
        libraryState.tracks,
        libraryState.favorites,
        libraryState.playlists,
        libraryPersistenceController::save,
    )
    @JvmField val responsiveLayoutController = ResponsiveLayoutController(this)
    private val mainScreenView = MainScreenView(this, responsiveLayoutController)
    private val mainScreenCallbacks = MainScreenCallbacks(
        tabsController,
        playerUiController,
        songsRenderer,
        { ParticleEffectsView(this) },
        { appearanceState.animations },
        { appearanceState.dark },
        { bg },
        artworkUi::scheduleVisibleArtworkPromotion,
    )
    private val activityViewController =
        MainActivityViewController(this, mainScreenView, mainScreenCallbacks)
    @JvmField val tabTransitionCoordinator = TabTransitionCoordinator(
        navigationState,
        mainScreenView,
        mainRenderer,
        backNavigationController,
        tabsController,
        songsRenderer,
    ) { scroll, content ->
        contentScroll = scroll
        list = content
    }
    @JvmField val activityCoordinator = MainActivityCoordinator(this)
    @JvmField var sourcePlayButton: Button? = null

    fun interface InputDone {
        fun done(value: String)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioImportController.openExternalAudio(intent)
        activityCoordinator.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        audioImportController.openExternalAudio(intent)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        particleEffectsView?.observeTouch(event)
        if (swipeController.handle(event)) return true
        return super.dispatchTouchEvent(event)
    }

    fun cancelActiveContentTouch(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    override fun onStop() {
        activityCoordinator.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        activityCoordinator.onResume()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        artworkUi.onTrimMemory(level)
    }

    override fun onDestroy() {
        activityCoordinator.onDestroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!activityCoordinator.handleBack()) super.onBackPressed()
    }

    fun restoreTabFromBack(targetIndex: Int, previousSearch: String) {
        if (isEditorNavigationLocked() && targetIndex != LibraryTabs.EDITOR) return
        swipeController.animateToTab(
            targetIndex,
            tabsController.directionTo(targetIndex),
            false,
            previousSearch,
        )
    }

    fun tr(english: String, russian: String): String = localization.text(english, russian)

    fun tr3(english: String, russian: String, @Suppress("UNUSED_PARAMETER") third: String): String =
        localization.text(english, russian)

    fun languageName(): String = localization.languageName()

    fun refreshTabLabels() = localization.refreshTabLabels()

    fun saveState() {
        uiPreferencesStore.save()
        saveLibraryState()
    }

    fun saveUiState() = uiPreferencesStore.save()

    fun saveLibraryState() = libraryRepository.persistCollections()

    fun playbackSnapshot(): PlaybackSnapshot = playbackUiState.snapshot()

    fun updatePlaybackSnapshot(snapshot: PlaybackSnapshot) = playbackUiState.updateSnapshot(snapshot)

    fun currentTrackIndex(): Int = playbackUiState.currentTrackIndex(libraryState)

    fun isPlaybackPlaying(): Boolean = playbackUiState.isPlaying()

    fun repeatMode(): Int = playbackUiState.repeatMode()

    fun isShuffleEnabled(): Boolean = playbackUiState.shuffleEnabled()

    fun refreshAfterTrackChange() = activityViewController.refreshPlaybackChrome()

    fun songRowStateResolver(): SongRowStateRegistry.StateResolver =
        activityViewController.stateResolver()

    fun buildUi() = activityViewController.build()

    fun setParticleEffectsView(value: ParticleEffectsView?) {
        particleEffectsView = value
    }

    fun applyLibrarySnapshot(snapshot: LibraryLoader.Snapshot) = librarySnapshotApplier.apply(snapshot)

    fun rebuildUiForTheme() {
        mainRenderer.captureScrollBeforeUiRebuild()
        buildUi()
    }

    fun rebuildUi() {
        mainRenderer.captureScrollBeforeUiRebuild()
        buildUi()
    }

    fun refreshTabs() = tabsController.refreshTabs()

    fun refreshMenuConfiguration() = tabsController.rebuildTabs()

    fun isEditorNavigationLocked(): Boolean =
        navigationState.tabIndex == LibraryTabs.EDITOR && audioEditorController.editingMode

    fun switchTabAnimated(targetIndex: Int, direction: Int) {
        if (
            !::tabs.isInitialized ||
            isEditorNavigationLocked() && targetIndex != LibraryTabs.EDITOR ||
            !menuConfigurationController.isVisible(targetIndex) ||
            targetIndex == navigationState.tabIndex ||
            navigationState.tabAnimating
        ) {
            return
        }
        navigationState.preferredTabDirection = direction
        swipeController.animateToTab(targetIndex, direction, true, "")
    }

    fun scrollTabsToActive(animated: Boolean) =
        tabsController.scrollToActive(animated, navigationState.tabIndex)

    fun scrollTabsToActive(animated: Boolean, index: Int) =
        tabsController.scrollToActive(animated, index)

    fun render() = mainRenderer.render()

    fun renderTabPreview(
        target: LinearLayout,
        targetIndex: Int,
        targetSearch: String,
    ): MainRenderer.PreviewState = mainRenderer.renderPreview(target, targetIndex, targetSearch)

    fun discardTabPreview() = mainRenderer.discardPreview()

    fun activeSongRows(): SongRowStateRegistry =
        if (navigationState.renderingTabPreview) previewSongRows else songRows

    fun renderSectionHeader() = headerController.renderSectionHeader()

    fun renderSettings() = settingsRenderer.render()

    fun refreshSettingsLabels() = settingsRenderer.refreshDynamicLabels()

    fun themeName(): String = themeController.themeName()

    fun openThemeDialog() = themeController.openDialog()

    fun stopPlaybackAndClearQueue() {
        playbackQueueController.clear()
        playerUiController.syncPlaybackUi()
        refreshAfterTrackChange()
    }

    fun currentVisibleTracks(): ArrayList<Track> {
        val currentSongsView = songsView
        if (navigationState.tabIndex == LibraryTabs.SONGS && currentSongsView != null) {
            return ArrayList(currentSongsView.visibleTracks())
        }
        return libraryListController.currentVisibleTracks()
    }

    fun matchesTrackSearch(track: Track, query: String): Boolean =
        libraryListController.matchesTrackSearch(track, query)

    fun containsSearch(value: String, query: String): Boolean =
        libraryListController.containsSearch(value, query)

    fun renderSongs(tracks: ArrayList<Track>) = songsRenderer.render(tracks)

    fun loopLabel(): String = playbackQueueController.loopLabel()

    fun formatMs(milliseconds: Int): String = PlaybackTimeFormatter.formatMilliseconds(milliseconds)

    fun formatTrackDuration(track: Track): String =
        if (track.durationMs > 0) formatMs(track.durationMs) else "--:--"

    fun playbackDurationFor(track: Track?): Int {
        val serviceDuration = min(Int.MAX_VALUE.toLong(), max(0L, playbackController.duration())).toInt()
        if (serviceDuration > 0) return serviceDuration
        return track?.let { max(0, it.durationMs) } ?: 0
    }

    fun formatSeconds(seconds: Long): String = PlaybackTimeFormatter.formatSeconds(seconds)

    fun refreshParticleSettings() = particleEffectsView?.settingsChanged() ?: Unit

    fun refreshPlaybackAppearance() = playbackController.refreshAudioEffects()

    fun timerButtonText(): String = sleepTimerController.buttonText()

    fun playbackPosition(): Int =
        min(Int.MAX_VALUE.toLong(), playbackController.currentPosition()).toInt()

    fun toggleFavorite(track: Track) {
        libraryRepository.toggleFavorite(track)
        librarySnapshotApplier.rebuildDerivedAndRender()
    }

    fun isCurrent(track: Track): Boolean {
        val currentIndex = currentTrackIndex()
        return currentIndex >= 0 &&
            currentIndex < libraryState.tracks.size &&
            libraryState.tracks[currentIndex].uri == track.uri
    }

    fun findTrack(uri: String): Track? = libraryRepository.find(uri)

    fun reloadUiPreferences() {
        uiPreferencesStore.load()
        menuConfigurationController.load()
        rebuildUi()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (overlayController.handleActivityResult(requestCode, resultCode)) return
        if (audioEditorController.handleActivityResult(requestCode, resultCode, data)) return
        if (settingsController.handleActivityResult(requestCode, resultCode, data)) return
        if (backgroundPlaybackSettingsController.handleActivityResult(requestCode)) return
        if (backgroundSettingsController.handleActivityResult(requestCode, resultCode, data)) return
        audioImportController.handleActivityResult(requestCode, resultCode, data)
    }

    fun cardSurfaceColor(color: Int): Int = cardSurfaceColor(color, appearanceState.cardOpacity)

    fun cardSurfaceColor(color: Int, opacity: Int): Int = Color.argb(
        (255f * opacity / 100f).roundToInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    fun addMiniSpacerIfNeeded() = MiniPlayerSpacer.addIfNeeded(this)

    fun openSongDiagnostics() = diagnosticsDialogs.openSongDiagnostics()

    fun showConfirmPanel(title: String, message: String, yesAction: Runnable) =
        diagnosticsDialogs.confirm(title, message, yesAction)

    fun showActionPanel(
        title: String,
        message: String,
        negativeLabel: String,
        positiveLabel: String,
        action: Runnable,
    ) = diagnosticsDialogs.action(title, message, negativeLabel, positiveLabel, action)

    fun showActionPanel(
        title: String,
        message: String,
        negativeLabel: String,
        positiveLabel: String,
        emphasizePositive: Boolean,
        action: Runnable,
    ) = diagnosticsDialogs.action(
        title,
        message,
        negativeLabel,
        positiveLabel,
        emphasizePositive,
        action,
    )

    fun centerParams(width: Int, height: Int): FrameLayout.LayoutParams =
        responsiveLayoutController.centeredPanelParams(width, height, overlayHost.height)

    fun bottomParams(): FrameLayout.LayoutParams = responsiveLayoutController.bottomPanelParams()

    fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
