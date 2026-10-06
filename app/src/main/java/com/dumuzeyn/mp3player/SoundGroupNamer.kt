package com.dumuzeyn.mp3player

import kotlin.math.abs

/** Short collection-style names restored from the pre-KMeans implementation. */
internal object SoundGroupNamer {
    private val TRAITS = arrayOf(
        Trait(TrackAudioProfile.ENERGY, "Энергичный поток", "Energetic flow", "Спокойный поток", "Calm flow"),
        Trait(TrackAudioProfile.CENTROID, "Яркий тембр", "Bright timbre", "Тёмный тембр", "Dark timbre"),
        Trait(TrackAudioProfile.BASS, "Глубокий бас", "Deep bass", "Лёгкий бас", "Light bass"),
        Trait(TrackAudioProfile.DYNAMIC_RANGE, "Живой контраст", "Vivid contrast", "Ровный контраст", "Even contrast"),
        Trait(TrackAudioProfile.RHYTHM, "Чёткий пульс", "Crisp pulse", "Плавный пульс", "Smooth pulse"),
        Trait(TrackAudioProfile.TREBLE, "Воздушный верх", "Airy treble", "Тёплый верх", "Warm treble"),
        Trait(TrackAudioProfile.ZERO_CROSSING, "Острый рисунок", "Sharp texture", "Мягкий рисунок", "Soft texture"),
    )
    private val FALLBACK_RU_ADJECTIVES = arrayOf(
        "Лунный", "Чистый", "Глубокий", "Тёплый", "Свежий", "Звонкий", "Мягкий", "Светлый",
    )
    private val FALLBACK_EN_ADJECTIVES = arrayOf(
        "Lunar", "Clear", "Deep", "Warm", "Fresh", "Resonant", "Soft", "Light",
    )
    private val FALLBACK_RU_NOUNS = arrayOf(
        "пульс", "ритм", "поток", "тембр", "контур", "бас", "отклик", "узор",
    )
    private val FALLBACK_EN_NOUNS = arrayOf(
        "pulse", "rhythm", "flow", "timbre", "contour", "bass", "response", "pattern",
    )

    @JvmStatic
    fun name(source: List<SoundGroup>): ArrayList<SoundGroup> {
        val ordered = ArrayList(source)
        ordered.sortWith { left, right -> left.id.compareTo(right.id) }
        val used = HashSet<String>()
        val result = ArrayList<SoundGroup>(ordered.size)
        ordered.forEach { group ->
            val selected = candidates(group.centroid).firstOrNull { used.add(it.russian) }
                ?: fallback(group.id, used).also { used.add(it.russian) }
            result.add(group.named(selected.russian, selected.english))
        }
        return result
    }

    private fun fallback(id: String, used: Set<String>): Candidate {
        val combinations = FALLBACK_RU_ADJECTIVES.size * FALLBACK_RU_NOUNS.size
        val start = (id.hashCode() and Int.MAX_VALUE) % combinations
        for (offset in 0 until combinations) {
            val value = (start + offset) % combinations
            val adjective = value / FALLBACK_RU_NOUNS.size
            val noun = value % FALLBACK_RU_NOUNS.size
            val russian = FALLBACK_RU_ADJECTIVES[adjective] + " " + FALLBACK_RU_NOUNS[noun]
            if (russian !in used) {
                return Candidate(
                    russian,
                    FALLBACK_EN_ADJECTIVES[adjective] + " " + FALLBACK_EN_NOUNS[noun],
                    0.0,
                )
            }
        }
        var number = 1
        while ("Звуковой поток $number" in used) number++
        return Candidate("Звуковой поток $number", "Sound flow $number", 0.0)
    }

    private fun candidates(centroid: DoubleArray): ArrayList<Candidate> {
        val result = ArrayList<Candidate>(TRAITS.size)
        TRAITS.forEach { trait ->
            val value = centroid.getOrElse(trait.feature) { 0.0 }
            result.add(
                if (value >= 0.0) {
                    Candidate(trait.highRussian, trait.highEnglish, abs(value))
                } else {
                    Candidate(trait.lowRussian, trait.lowEnglish, abs(value))
                },
            )
        }
        result.sortWith { left, right -> right.score.compareTo(left.score) }
        return result
    }

    private data class Trait(
        val feature: Int,
        val highRussian: String,
        val highEnglish: String,
        val lowRussian: String,
        val lowEnglish: String,
    )

    private data class Candidate(val russian: String, val english: String, val score: Double)
}
