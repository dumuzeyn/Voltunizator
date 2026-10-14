package com.dumuzeyn.mp3player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SoundKMeansPartitionerTest {
    @Test fun rebuildStartsChangeSlightlyWithoutRewritingMeasurements() {
        val original = DoubleArray(TrackAudioProfile.FEATURE_COUNT) { it / 10.0 }
        val saved = original.clone()
        val first = SoundKMeansPartitioner.startingPoint(original, "track", 1)
        val second = SoundKMeansPartitioner.startingPoint(original, "track", 2)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(saved, original, 0.0)
        assertArrayEquals(first, SoundKMeansPartitioner.startingPoint(original, "track", 1), 0.0)
        for (index in original.indices) {
            assertTrue(abs(first[index] - original[index]) <= SoundKMeansPartitioner.MAX_START_SHIFT + 1e-12)
            if (index == TrackAudioProfile.BPM || index == TrackAudioProfile.TEMPO_CONFIDENCE) {
                assertEquals(original[index], first[index], 0.0)
            }
        }
        assertArrayEquals(original, SoundKMeansPartitioner.startingPoint(original, "track", 0), 0.0)
    }

    @Test fun libraryPartitioningKeepsEveryPointExactlyOnceAndHandlesDuplicates() {
        val vectors = (0 until 100).map { DoubleArray(TrackAudioProfile.FEATURE_COUNT) { (it % 5).toDouble() } }
        val partitioner = SoundKMeansPartitioner(vectors, vectors.indices.map { "$it" }, 0)
        val groups = partitioner.partition(vectors.indices.toList(), 12)
        assertEquals(1, groups.size)
        assertEquals(vectors.indices.toList(), groups.flatten().sorted())
    }
}
