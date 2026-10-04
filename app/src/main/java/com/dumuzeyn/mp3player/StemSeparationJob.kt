package com.dumuzeyn.mp3player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

internal data class StemSeparationRequest(
    val id: String,
    val project: AudioEditProject,
    val clipId: String,
    val lanes: List<Int>,
    val instrumental: Boolean,
) {
    val clip: AudioEditClip get() = project.clips.first { it.id == clipId }

    fun result(uris: List<String>, labels: List<String>): AudioEditProject {
        require(uris.size == lanes.size && labels.size == lanes.size)
        val original = clip
        val processed = uris.mapIndexed { index, uri ->
            original.copy(
                id = if (index == 0) original.id else UUID.randomUUID().toString(),
                uri = uri,
                title = "${original.title} (${labels[index]})",
                sourceDurationMs = original.durationMs,
                startMs = 0,
                endMs = original.durationMs,
                lane = lanes[index],
            )
        }
        return project.copy(clips = project.clips.filterNot { it.id == clipId } + processed)
    }

    fun encode(): String = JSONObject().apply {
        put("id", id)
        put("project", AudioEditStore.encode(project))
        put("clipId", clipId)
        put("lanes", JSONArray(lanes))
        put("instrumental", instrumental)
    }.toString()

    companion object {
        fun decode(value: String): StemSeparationRequest {
            val json = JSONObject(value)
            val lanes = json.getJSONArray("lanes")
            val result = StemSeparationRequest(
                json.getString("id"),
                AudioEditStore.decode(json.getString("project")),
                json.getString("clipId"),
                (0 until lanes.length()).map(lanes::getInt),
                json.getBoolean("instrumental"),
            )
            require(UUID.fromString(result.id).toString() == result.id)
            require(result.lanes.size == if (result.instrumental) 1 else 4)
            require(result.lanes.distinct().size == result.lanes.size)
            require(result.lanes.all { it in 0 until AudioEditClip.MAX_LANES })
            result.clip
            return result
        }
    }
}

internal object StemSeparationJob {
    data class State(val id: String?, val active: Boolean, val progress: Int, val status: String)

    private const val PREFS = "editor_stem_job"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(State) -> Unit>()
    private var state: State? = null

    fun text(context: Context, resource: Int): String {
        val language = context.getSharedPreferences("mp3_player_ui", Context.MODE_PRIVATE)
            .getString("language", "ru") ?: "ru"
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(AppLanguages.find(language).languageTag))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    @Synchronized fun snapshot(context: Context): State {
        state?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val request = prefs.getString("pending", null)?.let {
            runCatching { StemSeparationRequest.decode(it) }.getOrNull()
        }
        return State(request?.id, request != null, 0, prefs.getString("status", "") ?: "")
            .also { state = it }
    }

    @Synchronized fun pending(context: Context): StemSeparationRequest? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending", null)
            ?.let { runCatching { StemSeparationRequest.decode(it) }.getOrNull() }

    @Synchronized fun observe(listener: (State) -> Unit) { listeners.add(listener) }
    @Synchronized fun removeObserver(listener: (State) -> Unit) { listeners.remove(listener) }

    fun begin(context: Context, project: AudioEditProject, clip: AudioEditClip,
              lanes: List<Int>, instrumental: Boolean): Boolean {
        val app = context.applicationContext
        val request = StemSeparationRequest(UUID.randomUUID().toString(), project, clip.id,
            lanes, instrumental)
        synchronized(this) {
            if (snapshot(app).active) return false
            val store = AudioEditStore(app)
            if (store.load() != project || !store.saveNow(project)) return false
            if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("pending", request.encode()).remove("status").commit()) return false
            state = State(request.id, true, 0, text(app, R.string.editor_separating_audio))
        }
        publish(snapshot(app))
        return launch(app, request.id)
    }

    fun resume(context: Context) {
        val request = pending(context) ?: return
        launch(context.applicationContext, request.id)
    }

    private fun launch(app: Context, id: String): Boolean = try {
        val intent = Intent(app, StemSeparationService::class.java)
            .setAction(StemSeparationService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
        else app.startService(intent)
        true
    } catch (failure: RuntimeException) {
        VoltuneLog.failure("stem_service_start_failed", failure)
        finish(app, id, text(app, R.string.editor_separation_failed))
        false
    }

    fun progress(context: Context, id: String, value: Int) {
        val updated = synchronized(this) {
            val current = snapshot(context)
            if (current.id != id || !current.active || value <= current.progress) return
            current.copy(progress = value.coerceIn(0, 99)).also { state = it }
        }
        publish(updated)
    }

    fun finish(context: Context, id: String, status: String) {
        val updated = synchronized(this) {
            val current = snapshot(context)
            if (current.id != id) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.edit().remove("pending").putString("status", status).commit())
                VoltuneLog.warning("stem_job_status_not_persisted")
            State(null, false, 0, status)
        }
        publish(updated, completedId = id)
    }

    fun cancel(context: Context) {
        if (!snapshot(context).active) return
        context.startService(Intent(context, StemSeparationService::class.java)
            .setAction(StemSeparationService.ACTION_CANCEL))
    }

    private fun publish(value: State, completedId: String? = null) {
        main.post {
            val observers = synchronized(this) {
                if (completedId != null) {
                    if (state?.id != completedId) return@post
                    state = value
                } else if (state?.id != value.id || state?.active != value.active) return@post
                listeners.toList()
            }
            observers.forEach { it(value) }
        }
    }
}
