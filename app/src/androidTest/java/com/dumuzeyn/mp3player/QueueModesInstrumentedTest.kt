package com.dumuzeyn.mp3player

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dumuzeyn.mp3player.data.playback.PlaybackStateManager
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueModesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val files = ArrayList<File>()
    private var activity: MainActivityCore? = null

    @After fun cleanup() {
        activity?.let { host ->
            instrumentation.runOnMainSync { host.playbackController.clearQueue() }
            InstrumentedTestSupport.finishActivity(instrumentation, host)
        }
        PlaybackStateManager(context).clear()
        context.getSharedPreferences("mp3_player_ui", 0).edit()
            .remove(QueueCreationMode.PREFERENCE).remove(SoundAnalysisController.CLUSTERING_VARIATION)
            .putBoolean("soundAnalysisEnabled", true).commit()
        files.forEach { it.delete() }
    }

    @Test fun oneButtonOffersFourModesAndRemembersSelectionWithoutStartingPlayback() {
        val host = launch(prepareTracks(true))
        lateinit var button: Button
        lateinit var count: RandomQueueCountView
        instrumentation.runOnMainSync {
            button = host.list.findViewById(R.id.random_queue_button)
            count = host.list.findViewById(R.id.random_queue_count)
            count.performClick()
            assertEquals(1, count.value)
            assertNull(host.list.findViewById<View>(R.id.similar_queue_button))
            assertNull(host.list.findViewById<View>(R.id.similar_queue_count))
        }
        for (mode in QueueCreationMode.entries) {
            select(host, button, mode)
            instrumentation.runOnMainSync {
                assertEquals(host.tr(mode.english, mode.russian), button.text.toString())
                assertEquals(1, count.value)
                assertTrue(host.playbackUiState.queue.isEmpty())
            }
        }
        instrumentation.runOnMainSync {
            host.render()
            val restored = host.list.findViewById<Button>(R.id.random_queue_button)
            assertEquals("Давно не слушали", restored.text.toString())
        }
    }

    @Test fun historyQueuesRegenerateAndEmptyHistoryDoesNotReplaceExistingQueue() {
        val tracks = prepareTracks(true)
        val host = launch(tracks)
        for (mode in listOf(QueueCreationMode.RECENT, QueueCreationMode.OLDEST)) {
            instrumentation.runOnMainSync { host.playbackController.clearQueue() }
            await("Previous queue did not clear") { host.playbackUiState.queue.isEmpty() }
            instrumentation.runOnMainSync { host.playbackQueueController.playGenerated(mode, 3) }
            await("History queue did not start: $mode") {
                host.playbackUiState.queue.size == 3 &&
                    host.playbackSnapshot().phase == PlaybackPhase.READY
            }
            var before = emptyList<String>()
            instrumentation.runOnMainSync {
                before = host.playbackUiState.queue.map { it.trackId }
                if (mode == QueueCreationMode.RECENT) assertTrue(host.playbackUiState.queue.all { it.lastPlayedAt > 0L })
                host.playbackQueueController.playGenerated(mode, 3)
            }
            await("Regenerated queue stayed identical: $mode") {
                host.playbackUiState.queue.size == 3 && host.playbackUiState.queue.map { it.trackId } != before
            }
        }
        instrumentation.runOnMainSync {
            if (host.isPlaybackPlaying()) host.playbackController.toggle()
            val before = host.playbackUiState.queue.map { it.trackId }
            host.libraryState.tracks.replaceAll { it.withPlaybackStats(0, 0, 0, 0) }
            host.playbackQueueController.playGenerated(QueueCreationMode.RECENT, 3)
            assertEquals(before, host.playbackUiState.queue.map { it.trackId })
        }
    }

    @Test fun oldGroupsRebuildFromSavedFeaturesWithoutReanalyzingAudio() {
        val tracks = prepareTracks(false)
        TrackStore.save(context, tracks)
        val store = SoundProfileStore(context)
        val before = tracks.mapIndexed { index, track ->
            TrackAudioProfile.analyzed(track, DoubleArray(TrackAudioProfile.FEATURE_COUNT) {
                if (index == tracks.lastIndex) 100.0 else 0.0
            }).also(store::saveProfile)
        }
        store.replaceGroups(listOf(SoundGroup("old-mixed", "Старая группа", "Old group",
            DoubleArray(TrackAudioProfile.FEATURE_COUNT), tracks.map { it.trackId })))
        store.close()
        val preferences = context.getSharedPreferences("mp3_player_ui", 0)
        preferences.edit().putBoolean("soundAnalysisEnabled", true)
            .putInt("similarClusteringVersion", 3).remove(SoundAnalysisController.CLUSTERING_VARIATION).commit()
        val host = launch(tracks, migration = true)
        await("Saved groups were not migrated") {
            preferences.getInt("similarClusteringVersion", 0) == SoundClusterEngine.CLUSTERING_VERSION &&
                host.soundAnalysisController.groups().size == 2
        }
        instrumentation.runOnMainSync { host.navigationState.tabIndex = LibraryTabs.SOUND; host.render() }
        for (variation in 1L..2L) {
            instrumentation.runOnMainSync {
                descendants(host.list).filterIsInstance<Button>()
                    .single { it.text.toString() == "Пересобрать группы" }.performClick()
            }
            await("Rebuild did not persist a new start variation") {
                preferences.getLong(SoundAnalysisController.CLUSTERING_VARIATION, 0L) == variation &&
                    !host.soundAnalysisController.rebuildingGroups() &&
                    descendants(host.list).filterIsInstance<Button>().any {
                        it.text.toString() == "Пересобрать группы" && it.isEnabled
                    }
            }
            assertEquals(8, host.soundAnalysisController.groups().sumOf { it.trackIds.size })
        }
        val updated = SoundProfileStore(context)
        try {
            val profiles = updated.loadProfiles()
            before.forEach { previous ->
                val current = profiles.getValue(previous.trackId)
                assertEquals(previous.updatedAt, current.updatedAt)
                assertArrayEquals(previous.features, current.features, 0.00000001)
            }
            val isolated = updated.loadGroups().single { tracks.last().trackId in it.trackIds }
            assertEquals(1, isolated.trackIds.size)
        } finally {
            updated.close()
        }
    }

    @Test fun manualRebuildResumesPendingAudioAnalysis() {
        val tracks = prepareTracks(false)
        val store = SoundProfileStore(context)
        tracks.forEach { track -> store.saveProfile(TrackAudioProfile.analyzed(track,
            DoubleArray(TrackAudioProfile.FEATURE_COUNT))) }
        store.close()
        val host = launch(tracks, migration = true)
        await("Cached groups were not loaded") {
            host.soundAnalysisController.groups().sumOf { it.trackIds.size } == tracks.size
        }
        val pendingStore = SoundProfileStore(context)
        pendingStore.mark(tracks.last(), SoundAnalysisState.QUEUED, "")
        pendingStore.close()
        instrumentation.runOnMainSync {
            host.navigationState.tabIndex = LibraryTabs.SOUND
            host.render()
            descendants(host.list).filterIsInstance<Button>()
                .single { it.text.toString() == "Пересобрать группы" }.performClick()
        }
        InstrumentedTestSupport.waitFor("Manual rebuild left pending analysis stopped", 30000) {
            val profiles = SoundProfileStore(context)
            try {
                val updated = profiles.loadProfiles()[tracks.last().trackId]
                updated?.usable() == true && updated.features.any { it != 0.0 }
            } finally { profiles.close() }
        }
    }

    private fun prepareTracks(history: Boolean): List<Track> {
        context.deleteDatabase(LibraryDatabase.DB_NAME)
        context.getSharedPreferences("mp3_player_store", 0).edit()
            .putBoolean("sqlite_migrated", true).commit()
        return (0..7).map { index ->
            val file = InstrumentedTestSupport.createTestWave(context, "queue-mode-$index.wav", 20)
            files.add(file)
            requireNotNull(TrackStore.fromUri(context, Uri.fromFile(file)))
                .withMetadata("Queue mode $index", "Artist", "Album", "Artist", "Genre", 0, 0, 0)
                .withPlaybackStats(if (history && index > 0) 1 else 0, 0,
                    if (history && index > 0) index * 1000L else 0, 0)
        }
    }

    private fun launch(tracks: List<Track>, migration: Boolean = false): MainActivityCore {
        PlaybackStateManager(context).clear()
        TrackStore.save(context, tracks)
        context.getSharedPreferences("mp3_player_ui", 0).edit()
            .putString("language", "ru").putBoolean("animations", false)
            .putBoolean("particlesEnabled", false).putBoolean("soundAnalysisEnabled", migration)
            .remove(QueueCreationMode.PREFERENCE).commit()
        return InstrumentedTestSupport.launchForPlayback(instrumentation, context).also { activity = it }
    }

    private fun select(host: MainActivityCore, button: Button, mode: QueueCreationMode) {
        instrumentation.runOnMainSync { button.performLongClick() }
        await("Queue mode picker did not open") {
            descendants(host.overlayHost).count { it.tag is QueueCreationMode } == 4
        }
        instrumentation.runOnMainSync {
            descendants(host.overlayHost).single { it.tag == mode }.performClick()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        InstrumentedTestSupport.waitFor(message, 15000) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            ready
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }
}
