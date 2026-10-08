package com.dumuzeyn.mp3player.data.playback

import android.content.Context
import android.content.SharedPreferences
import com.dumuzeyn.mp3player.MiniPlayerRetentionPolicy
import com.dumuzeyn.mp3player.PlaybackSnapshot
import com.dumuzeyn.mp3player.PauseReason
import com.dumuzeyn.mp3player.QueueRemovalPlan
import com.dumuzeyn.mp3player.RepeatModeMapper
import com.dumuzeyn.mp3player.Track
import org.json.JSONArray

/** Persists the playback session independently from the service and UI. */
class PlaybackStateManager(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lastSavedQueueJson = ""

    fun load(): State = State(
        preferences.getString(URI, "").orEmpty(),
        preferences.getInt(POSITION, 0).coerceAtLeast(0),
        preferences.getInt(DURATION, 0).coerceAtLeast(0),
        preferences.getInt(INDEX, 0).coerceAtLeast(0),
        preferences.getInt(LOOP_MODE, 0),
        preferences.getBoolean(PLAYING, false),
        preferences.getBoolean(SHUFFLE, false),
        preferences.getLong(SAVED_AT, 0L),
        preferences.getLong(INACTIVE_SINCE, 0L),
        queueUris(preferences.getString(QUEUE, "[]")),
    )

    fun save(snapshot: Snapshot, includeQueue: Boolean) {
        val queueJson = queueJson(snapshot.queueUris)
        val now = System.currentTimeMillis()
        var inactiveSince = if (snapshot.playing) 0L else preferences.getLong(INACTIVE_SINCE, 0L)
        if (!snapshot.playing && inactiveSince <= 0L) inactiveSince = now
        val editor = preferences.edit()
            .putString(URI, snapshot.uri)
            .putInt(POSITION, snapshot.position.coerceAtLeast(0))
            .putInt(DURATION, snapshot.duration.coerceAtLeast(0))
            .putInt(INDEX, snapshot.index.coerceAtLeast(0))
            .putInt(LOOP_MODE, snapshot.loopMode)
            .putBoolean(PLAYING, snapshot.playing)
            .putBoolean(SHUFFLE, snapshot.shuffle)
            .putLong(SAVED_AT, now)
            .putLong(INACTIVE_SINCE, inactiveSince)
        if (includeQueue || queueJson != lastSavedQueueJson) {
            editor.putString(QUEUE, queueJson)
            lastSavedQueueJson = queueJson
        }
        editor.apply()
    }

    fun save(
        snapshot: PlaybackSnapshot,
        currentUri: String?,
        queue: List<Track>,
        includeQueue: Boolean,
    ) {
        save(snapshot.toPersisted(currentUri, queue.mapTo(ArrayList()) { it.trackId }), includeQueue)
    }

    fun save(snapshot: PlaybackSnapshot, currentUri: String?, includeQueue: Boolean) {
        save(snapshot.toPersisted(currentUri, ArrayList(snapshot.queueMediaIds)), includeQueue)
    }

    fun clear() {
        lastSavedQueueJson = ""
        preferences.edit().clear().apply()
    }

    fun removeTracks(trackIds: Set<String>, trackUris: Set<String>) {
        val state = load()
        val plan = QueueRemovalPlan.create(state.queueUris, state.index, trackIds)
        if (plan.remaining.isEmpty()) {
            clear()
            return
        }
        val currentRemoved = state.uri in trackUris || plan.currentRemoved
        save(
            Snapshot(
                if (currentRemoved) "" else state.uri,
                if (currentRemoved) 0 else state.position,
                state.duration,
                plan.currentIndex,
                state.loopMode,
                state.playing,
                state.shuffle,
                plan.remaining,
            ),
            true,
        )
    }

    class State(
        @JvmField val uri: String,
        @JvmField val position: Int,
        @JvmField val duration: Int,
        @JvmField val index: Int,
        @JvmField val loopMode: Int,
        @JvmField val playing: Boolean,
        @JvmField val shuffle: Boolean,
        @JvmField val savedAt: Long,
        @JvmField val inactiveSince: Long,
        @JvmField val queueUris: ArrayList<String>,
    )

    class Snapshot(
        @JvmField val uri: String,
        @JvmField val position: Int,
        @JvmField val duration: Int,
        @JvmField val index: Int,
        @JvmField val loopMode: Int,
        @JvmField val playing: Boolean,
        @JvmField val shuffle: Boolean,
        @JvmField val queueUris: ArrayList<String>,
    ) {
        constructor(
            uri: String?,
            position: Int,
            duration: Int,
            index: Int,
            loopMode: Int,
            playing: Boolean,
            shuffle: Boolean,
            queue: List<Track>,
        ) : this(
            uri.orEmpty(),
            position,
            duration,
            index,
            loopMode,
            playing,
            shuffle,
            queue.mapTo(ArrayList()) { it.trackId },
        )
    }

    private fun PlaybackSnapshot.toPersisted(
        currentUri: String?,
        queueIds: ArrayList<String>,
    ): Snapshot = Snapshot(
        currentUri.orEmpty(),
        positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        currentIndex,
        RepeatModeMapper.fromMedia3(repeatMode),
        pauseReason == PauseReason.TRACK_GAP || MiniPlayerRetentionPolicy.isPlaybackActive(playWhenReady, phase, stopReason),
        shuffleEnabled,
        queueIds,
    )

    private fun queueUris(json: String?): ArrayList<String> = ArrayList<String>().also { uris ->
        runCatching {
            val queue = JSONArray(json ?: "[]")
            repeat(queue.length()) { index ->
                queue.optString(index, "").takeIf(String::isNotEmpty)?.let(uris::add)
            }
        }
    }

    private fun queueJson(uris: List<String>): String = JSONArray().apply {
        uris.forEach(::put)
    }.toString()

    companion object {
        const val PREFS = "player_resume"
        private const val DURATION = "duration"
        private const val INDEX = "index"
        private const val INACTIVE_SINCE = "inactiveSince"
        private const val LOOP_MODE = "loopMode"
        private const val PLAYING = "playing"
        private const val POSITION = "position"
        private const val QUEUE = "queue"
        private const val SAVED_AT = "savedAt"
        private const val SHUFFLE = "shuffle"
        private const val URI = "uri"
    }
}
