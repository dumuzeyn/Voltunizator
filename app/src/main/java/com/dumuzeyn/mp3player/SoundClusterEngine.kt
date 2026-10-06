package com.dumuzeyn.mp3player

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Adaptive audio grouping with a bounded pairwise diameter and no forced outlier merges. */
internal class SoundClusterEngine {
    fun cluster(source: List<TrackAudioProfile?>?): ArrayList<SoundGroup> {
        val profiles = usableProfiles(source)
        if (profiles.size < MIN_LIBRARY_SIZE) return ArrayList()
        val normalized = SoundFeatureNormalizer.normalize(profiles)
        val threshold = adaptiveThreshold(normalized.vectors)
        val clusters = ArrayList<MutableCluster>()
        for (index in profiles.indices) {
            addToNearest(
                clusters,
                profiles[index].trackId,
                normalized.vectors[index],
                threshold,
            )
        }
        val groups = ArrayList<SoundGroup>()
        for (cluster in clusters) {
            cluster.trackIds.sort()
            groups.add(
                SoundGroup(
                    stableId(cluster.trackIds),
                    "",
                    "",
                    cluster.centroid,
                    cluster.trackIds,
                ),
            )
        }
        return SoundGroupNamer.name(groups)
    }

    fun nearestGroup(
        rawFeatures: DoubleArray?,
        library: List<TrackAudioProfile?>?,
        groups: List<SoundGroup>?,
    ): String {
        if (
            rawFeatures == null || rawFeatures.size != TrackAudioProfile.FEATURE_COUNT ||
            groups.isNullOrEmpty()
        ) {
            return ""
        }
        val usable = usableProfiles(library)
        if (usable.isEmpty()) return ""
        val normalization = SoundFeatureNormalizer.normalize(usable)
        val vector = normalization.vector(rawFeatures)
        val threshold = adaptiveThreshold(normalization.vectors)
        val byId = usable.indices.associate { usable[it].trackId to normalization.vectors[it] }
        var nearest: SoundGroup? = null
        var distance = Double.POSITIVE_INFINITY
        for (group in groups) {
            val members = group.trackIds.mapNotNull(byId::get)
            if (members.isEmpty() || members.any {
                    SoundFeatureNormalizer.distance(vector, it) > threshold
                }) continue
            // Stored centroids use the previous library's scaling; recompute in the current space.
            val centroid = DoubleArray(TrackAudioProfile.FEATURE_COUNT)
            members.forEach { member ->
                centroid.indices.forEach { centroid[it] += member[it] / members.size }
            }
            val current = SoundFeatureNormalizer.distance(vector, centroid)
            if (current < distance) {
                distance = current
                nearest = group
            }
        }
        return nearest?.id.orEmpty()
    }

    private class MutableCluster(trackId: String, vector: DoubleArray) {
        val trackIds = arrayListOf(trackId)
        val members = arrayListOf(vector)
        var centroid = vector.clone()

        fun add(trackId: String, vector: DoubleArray) {
            val previous = trackIds.size
            trackIds.add(trackId)
            members.add(vector)
            for (index in centroid.indices) {
                centroid[index] = (centroid[index] * previous + vector[index]) / (previous + 1)
            }
        }

        fun accepts(vector: DoubleArray, threshold: Double): Boolean =
            members.all { SoundFeatureNormalizer.distance(vector, it) <= threshold }
    }

    companion object {
        const val CLUSTERING_VERSION = 4
        private const val MIN_LIBRARY_SIZE = 4
        private const val DISTANCE_SAMPLE_LIMIT = 256

        private fun usableProfiles(
            source: List<TrackAudioProfile?>?,
        ): ArrayList<TrackAudioProfile> {
            val result = ArrayList<TrackAudioProfile>()
            if (source != null) {
                for (profile in source) {
                    if (profile != null && profile.usable()) result.add(profile)
                }
            }
            result.sortBy(TrackAudioProfile::trackId)
            return result
        }

        private fun adaptiveThreshold(vectors: List<DoubleArray>): Double {
            val count = min(vectors.size, DISTANCE_SAMPLE_LIMIT)
            if (count < 2) return 0.35
            val nearest = ArrayList<Double>()
            val sampled = (0 until count).map { vectors[it * (vectors.size - 1) / (count - 1)] }
            for (left in 0 until count) {
                var best = Double.POSITIVE_INFINITY
                for (right in 0 until count) {
                    if (left != right) {
                        best = min(
                            best,
                            SoundFeatureNormalizer.distance(sampled[left], sampled[right]),
                        )
                    }
                }
                if (best.isFinite()) nearest.add(best)
            }
            nearest.sort()
            val median = if (nearest.isEmpty()) 0.75 else nearest[nearest.size / 2]
            return max(0.35, min(1.75, median * 1.35))
        }

        private fun addToNearest(
            clusters: ArrayList<MutableCluster>,
            trackId: String,
            vector: DoubleArray,
            threshold: Double,
        ) {
            var nearest: MutableCluster? = null
            var distance = Double.POSITIVE_INFINITY
            for (cluster in clusters) {
                val current = SoundFeatureNormalizer.distance(vector, cluster.centroid)
                if (current < distance && current <= threshold && cluster.accepts(vector, threshold)) {
                    distance = current
                    nearest = cluster
                }
            }
            if (nearest == null || distance > threshold) {
                clusters.add(MutableCluster(trackId, vector))
            } else {
                nearest.add(trackId, vector)
            }
        }

        private fun stableId(trackIds: List<String>): String {
            val joined = trackIds.joinToString("\n")
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.toByteArray(StandardCharsets.UTF_8))
                buildString("sound-".length + 12) {
                    append("sound-")
                    for (index in 0 until 6) {
                        append(String.format(Locale.ROOT, "%02x", digest[index]))
                    }
                }
            } catch (_: Exception) {
                "sound-${Integer.toHexString(joined.hashCode())}"
            }
        }
    }
}
