package com.dumuzeyn.mp3player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class AudioEditStore(context: Context) {
    private val preferences = context.getSharedPreferences("audio_editor", Context.MODE_PRIVATE)

    fun load(): AudioEditProject = runCatching { decode(preferences.getString("draft", "[]")!!) }
        .getOrDefault(AudioEditProject())

    fun save(project: AudioEditProject) {
        preferences.edit().putString("draft", encode(project)).apply()
    }

    fun saveNow(project: AudioEditProject): Boolean =
        preferences.edit().putString("draft", encode(project)).commit()

    companion object {
        fun encode(project: AudioEditProject): String = JSONArray().apply {
            project.clips.forEach { clip -> put(JSONObject().apply {
                put("id", clip.id)
                put("uri", clip.uri)
                put("title", clip.title)
                put("duration", clip.sourceDurationMs)
                put("start", clip.startMs)
                put("end", clip.endMs)
                put("lane", clip.lane)
                put("offset", clip.offsetMs)
                put("gain", clip.gain.toDouble())
                put("fadeIn", clip.fadeInMs)
                put("fadeOut", clip.fadeOutMs)
            }) }
        }.toString()

        fun decode(value: String): AudioEditProject {
            val array = JSONArray(value)
            require(array.length() <= 200)
            return AudioEditProject((0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AudioEditClip(item.getString("id"), item.getString("uri"), item.getString("title"),
                    item.getLong("duration"), item.getLong("start"), item.getLong("end"),
                    item.getInt("lane"), item.getLong("offset"), item.getDouble("gain").toFloat(),
                    item.optLong("fadeIn", 0), item.optLong("fadeOut", 0))
            })
        }
    }
}
