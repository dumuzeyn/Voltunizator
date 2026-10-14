package com.dumuzeyn.mp3player

import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.DistanceMeasure
import org.apache.commons.math3.random.JDKRandomGenerator
import java.util.Random

/** Library-backed fitting; audio measurements and cohesion checks remain unperturbed. */
internal class SoundKMeansPartitioner(vectors: List<DoubleArray>, trackIds: List<String>, variation: Long) {
    private val points = vectors.indices.map { index ->
        Point(index, startingPoint(vectors[index], trackIds[index], variation))
    }

    fun partition(indices: List<Int>, count: Int): List<List<Int>> {
        val unique = indices.map { points[it].coordinates.toList() }.distinct().size
        val k = count.coerceIn(1, unique)
        if (k == 1) return listOf(indices)
        val random = JDKRandomGenerator().apply { setSeed(0x564f4c54L + indices.first()) }
        val measure = DistanceMeasure { left, right -> SoundFeatureNormalizer.distance(left, right) }
        return KMeansPlusPlusClusterer<Point>(k, 60, measure, random)
            .cluster(indices.map { points[it] }).filter { it.points.isNotEmpty() }
            .map { cluster -> cluster.points.map { it.index } }
    }

    private class Point(val index: Int, val coordinates: DoubleArray) : Clusterable {
        override fun getPoint() = coordinates
    }

    companion object {
        const val MAX_START_SHIFT = 0.025

        @JvmStatic
        fun startingPoint(vector: DoubleArray, trackId: String, variation: Long): DoubleArray {
            val result = vector.clone()
            if (variation == 0L) return result
            val random = Random(variation * -7046029254386353131L xor trackId.hashCode().toLong())
            result.indices.forEach { index ->
                if (index != TrackAudioProfile.BPM && index != TrackAudioProfile.TEMPO_CONFIDENCE) {
                    result[index] += (random.nextDouble() * 2.0 - 1.0) * MAX_START_SHIFT
                }
            }
            return result
        }
    }
}
