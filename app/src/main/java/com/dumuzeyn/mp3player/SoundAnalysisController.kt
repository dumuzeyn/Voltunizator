package com.dumuzeyn.mp3player

import android.content.Context
import android.os.Process
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Runs one resumable, local-only analysis queue for the current process. */
internal class SoundAnalysisController(private val host: MainActivityCore) : Closeable {
    private var store: SoundProfileStore? = null
    private var extractor: AudioFeatureExtractor? = null
    private val clusterEngine = SoundClusterEngine()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voltune-sound-analysis").apply { priority = Thread.MIN_PRIORITY }
    }
    private val generation = AtomicInteger()

    @Volatile private var groups = ArrayList<SoundGroup>()
    @Volatile private var total = 0
    @Volatile private var analyzed = 0
    @Volatile private var failed = 0
    @Volatile private var queued = 0
    @Volatile private var activeTitle = ""
    @Volatile private var rebuildingGroups = false
    @Volatile private var fullReanalysis = false
    @Volatile private var blockReason = SoundAnalysisConstraints.BlockReason.NONE
    @Volatile private var closed = false
    @Volatile private var lastLibrarySignature = Long.MIN_VALUE
    private var updatePosted = false

    fun enabled(): Boolean = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)

    fun toggle() {
        val value = !enabled()
        host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, value)
            .apply()
        if (value) {
            lastLibrarySignature = Long.MIN_VALUE
            onLibraryReady(host.libraryState.tracks)
        } else {
            generation.incrementAndGet()
            activeTitle = ""
            rebuildingGroups = false
            fullReanalysis = false
            blockReason = SoundAnalysisConstraints.BlockReason.NONE
            notifyUi()
        }
    }

    fun settingLabel(): String {
        val isEnabled = enabled()
        return host.tr("Analyze similar tracks: ", "Анализ похожих треков: ") +
            host.tr(if (isEnabled) "on" else "off", if (isEnabled) "вкл" else "выкл")
    }

    fun onLibraryReady(tracks: List<Track>) {
        if (closed) return
        val signature = librarySignature(tracks)
        if (signature == lastLibrarySignature) return
        lastLibrarySignature = signature
        val requestedGeneration = generation.incrementAndGet()
        val snapshot = ArrayList(tracks)
        executor.execute { runQueueSafely(requestedGeneration, snapshot) }
    }

    fun groups(): ArrayList<SoundGroup> = ArrayList(groups)

    fun total(): Int = total

    fun analyzed(): Int = analyzed

    fun failed(): Int = failed

    fun queued(): Int = queued

    fun activeTitle(): String = activeTitle

    fun rebuildingGroups(): Boolean = rebuildingGroups

    fun fullReanalysis(): Boolean = fullReanalysis

    @Synchronized
    fun rebuildGroupsFromSavedProfiles(): Boolean {
        if (closed || rebuildingGroups || fullReanalysis) return false
        val requestedGeneration = generation.incrementAndGet()
        rebuildingGroups = true
        activeTitle = ""
        notifyUi()
        executor.execute {
            try {
                ensureWorkerResources()
                val profiles = requireNotNull(store).loadProfiles()
                if (requestedGeneration == generation.get()) rebuildGroups(profiles)
            } catch (error: RuntimeException) {
                VoltuneLog.failure("sound_group_rebuild_failed", error)
            } finally {
                rebuildingGroups = false
                notifyUi()
            }
        }
        return true
    }

    @Synchronized
    fun reanalyzeLibrary(): Boolean {
        if (closed || rebuildingGroups || fullReanalysis) return false
        host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, true)
            .apply()
        val requestedGeneration = generation.incrementAndGet()
        val snapshot = ArrayList(host.libraryState.tracks)
        fullReanalysis = true
        total = snapshot.size
        analyzed = 0
        failed = 0
        queued = snapshot.size
        activeTitle = ""
        notifyUi()
        executor.execute {
            try {
                ensureWorkerResources()
                requireNotNull(store).clearAnalysis()
                groups = ArrayList()
                lastLibrarySignature = librarySignature(snapshot)
                runQueueSafely(requestedGeneration, snapshot)
            } catch (error: RuntimeException) {
                VoltuneLog.failure("sound_full_reanalysis_failed", error)
                fullReanalysis = false
                notifyUi()
            } finally {
                if (queued == 0 || requestedGeneration != generation.get()) {
                    fullReanalysis = false
                    notifyUi()
                }
            }
        }
        return true
    }

    fun blockReason(): SoundAnalysisConstraints.BlockReason = blockReason

    override fun close() {
        if (closed) return
        closed = true
        rebuildingGroups = false
        fullReanalysis = false
        generation.incrementAndGet()
        executor.execute(::closeWorkerResources)
        executor.shutdown()
    }

    private fun runQueueSafely(requestedGeneration: Int, tracks: ArrayList<Track>) {
        try {
            runQueue(requestedGeneration, tracks)
        } catch (error: RuntimeException) {
            VoltuneLog.failure("sound_analysis_queue_failed", error)
            closeWorkerResources()
            activeTitle = ""
            rebuildingGroups = false
            fullReanalysis = false
            blockReason = SoundAnalysisConstraints.BlockReason.NONE
            if (closed || requestedGeneration != generation.get()) return
            lastLibrarySignature = Long.MIN_VALUE
            notifyUi()
            host.uiHandler.postDelayed(
                {
                    if (!closed && requestedGeneration == generation.get()) {
                        onLibraryReady(host.libraryState.tracks)
                    }
                },
                CONSTRAINT_RECHECK_MS,
            )
        }
    }

    private fun closeWorkerResources() {
        store?.let { profileStore ->
            try {
                profileStore.close()
            } catch (error: RuntimeException) {
                VoltuneLog.failure("sound_profile_store_close_failed", error)
            }
            store = null
        }
        extractor = null
    }

    private fun runQueue(requestedGeneration: Int, tracks: ArrayList<Track>) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (!isCurrent(requestedGeneration)) return
        ensureWorkerResources()
        val profileStore = requireNotNull(store)
        val featureExtractor = requireNotNull(extractor)
        val profiles = profileStore.loadProfiles()
        groups = profileStore.loadGroups()
        val pending = prepareQueue(tracks, profiles)
        val pendingCount = pending.size
        var clusteringChanged = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(CLUSTERING_VERSION, 0) != SoundClusterEngine.CLUSTERING_VERSION
        profileStore.pruneEmptyGroups()
        groups = profileStore.loadGroups()
        publishCounts(tracks.size, profiles, pending.size)
        notifyUi()
        if (!enabled()) return
        if (clusteringChanged && usableCount(profiles) >= 4) {
            rebuildGroups(profiles)
            clusteringChanged = false
        }
        for (track in pending) {
            if (!runAllowedOrRescheduled(requestedGeneration, tracks)) return
            activeTitle = track.title
            profileStore.mark(track, SoundAnalysisState.ANALYZING, "")
            notifyUi()
            try {
                val features = featureExtractor.analyze(track) {
                    shouldYield(requestedGeneration)
                }
                if (!isCurrent(requestedGeneration)) return
                val profile = TrackAudioProfile.analyzed(track, features)
                profileStore.saveProfile(profile)
                profiles[track.trackId] = profile
                analyzed++
                queued = maxOf(0, queued - 1)
                updateGroupsAfterProfile(profiles, profile)
            } catch (_: AudioFeatureExtractor.AnalysisInterruptedException) {
                if (!isCurrent(requestedGeneration)) return
                profileStore.mark(track, SoundAnalysisState.QUEUED, "")
                lastLibrarySignature = Long.MIN_VALUE
                onLibraryReady(tracks)
                return
            } catch (error: Exception) {
                if (!isCurrent(requestedGeneration)) return
                val errorName = error.javaClass.simpleName
                profileStore.mark(track, SoundAnalysisState.FAILED, errorName)
                profiles[track.trackId] = TrackAudioProfile(
                    track.trackId,
                    TrackAudioProfile.ANALYSIS_VERSION,
                    track.fileSize,
                    track.lastModified,
                    track.fingerprint,
                    SoundAnalysisState.FAILED,
                    DoubleArray(0),
                    "",
                    errorName,
                    System.currentTimeMillis(),
                )
                failed++
                queued = maxOf(0, queued - 1)
                VoltuneLog.failure("sound_analysis_failed", error)
            }
            activeTitle = ""
            notifyUi()
        }
        if (pendingCount > 0 || clusteringChanged || groups.isEmpty()) rebuildGroups(profiles)
        activeTitle = ""
        blockReason = SoundAnalysisConstraints.BlockReason.NONE
        fullReanalysis = false
        notifyUi()
    }

    private fun ensureWorkerResources() {
        if (store == null) {
            store = SoundProfileStore(host)
            extractor = AudioFeatureExtractor(host)
        }
    }

    private fun prepareQueue(
        tracks: ArrayList<Track>,
        profiles: LinkedHashMap<String, TrackAudioProfile>,
    ): ArrayList<Track> {
        val profileStore = requireNotNull(store)
        val pending = ArrayList<Track>()
        for (track in tracks) {
            val profile = profiles[track.trackId]
            if (profile != null && profile.matches(track) &&
                (profile.usable() || profile.state == SoundAnalysisState.FAILED)
            ) {
                continue
            }
            profileStore.mark(track, SoundAnalysisState.QUEUED, "")
            profiles[track.trackId] = TrackAudioProfile.pending(track, SoundAnalysisState.QUEUED)
            pending.add(track)
        }
        return pending
    }

    private fun updateGroupsAfterProfile(
        profiles: Map<String, TrackAudioProfile>,
        profile: TrackAudioProfile,
    ) {
        if (groups.isEmpty() && usableCount(profiles) >= 4) {
            rebuildGroups(profiles)
            return
        }
        if (groups.isNotEmpty()) {
            val groupId = clusterEngine.nearestGroup(
                profile.features,
                ArrayList(profiles.values),
                groups,
            )
            if (groupId.isEmpty()) {
                rebuildGroups(profiles)
                return
            }
            requireNotNull(store).assign(profile.trackId, groupId)
            groups = requireNotNull(store).loadGroups()
        }
        if (usableCount(profiles) <= 20) rebuildGroups(profiles)
    }

    private fun rebuildGroups(profiles: Map<String, TrackAudioProfile>) {
        val profileStore = requireNotNull(store)
        val rebuilt = clusterEngine.cluster(ArrayList(profiles.values))
        profileStore.replaceGroups(rebuilt)
        groups = profileStore.loadGroups()
        host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CLUSTERING_VERSION, SoundClusterEngine.CLUSTERING_VERSION)
            .apply()
    }

    private fun runAllowedOrRescheduled(
        requestedGeneration: Int,
        tracks: ArrayList<Track>,
    ): Boolean {
        if (!isCurrent(requestedGeneration)) return false
        blockReason = SoundAnalysisConstraints.reason(host, host.isPlaybackPlaying())
        if (blockReason == SoundAnalysisConstraints.BlockReason.NONE) return true
        activeTitle = ""
        notifyUi()
        host.uiHandler.postDelayed(
            {
                if (!closed && enabled() && requestedGeneration == generation.get()) {
                    lastLibrarySignature = Long.MIN_VALUE
                    onLibraryReady(tracks)
                }
            },
            CONSTRAINT_RECHECK_MS,
        )
        return false
    }

    private fun shouldYield(requestedGeneration: Int): Boolean =
        !isCurrent(requestedGeneration) ||
            SoundAnalysisConstraints.reason(host, host.isPlaybackPlaying()) !=
            SoundAnalysisConstraints.BlockReason.NONE

    private fun isCurrent(requestedGeneration: Int): Boolean =
        !closed && enabled() && requestedGeneration == generation.get() &&
            !Thread.currentThread().isInterrupted

    private fun publishCounts(
        trackCount: Int,
        profiles: Map<String, TrackAudioProfile>,
        pendingCount: Int,
    ) {
        total = trackCount
        analyzed = 0
        failed = 0
        for (profile in profiles.values) {
            if (profile.usable()) {
                analyzed++
            } else if (profile.state == SoundAnalysisState.FAILED) {
                failed++
            }
        }
        queued = pendingCount
    }

    private fun notifyUi() {
        synchronized(this) {
            if (updatePosted || closed) return
            updatePosted = true
        }
        host.uiHandler.postDelayed(
            {
                synchronized(this) { updatePosted = false }
                if (!host.isFinishing && host.navigationState.tabIndex == LibraryTabs.SOUND) {
                    host.render()
                }
                host.refreshSettingsLabels()
            },
            200L,
        )
    }

    companion object {
        private const val PREFS = "mp3_player_ui"
        private const val ENABLED = "soundAnalysisEnabled"
        private const val CLUSTERING_VERSION = "similarClusteringVersion"
        private const val CONSTRAINT_RECHECK_MS = 5_000L

        private fun usableCount(profiles: Map<String, TrackAudioProfile>): Int =
            profiles.values.count(TrackAudioProfile::usable)

        private fun librarySignature(tracks: List<Track>): Long {
            var value = 0xcbf29ce484222325UL.toLong()
            for (track in tracks) {
                value = (value xor track.trackId.hashCode().toLong()) * 0x100000001b3L
                value = (value xor track.fileSize) * 0x100000001b3L
                value = (value xor track.lastModified) * 0x100000001b3L
                value = (value xor track.fingerprint.hashCode().toLong()) * 0x100000001b3L
            }
            return value
        }
    }
}
