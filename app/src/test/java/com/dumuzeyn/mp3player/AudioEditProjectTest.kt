package com.dumuzeyn.mp3player

import org.junit.Assert.*
import org.junit.Test

class AudioEditProjectTest {
    private fun clip(id: String = "a", lane: Int = 0, offset: Long = 0) = AudioEditClip(
        id, "content://audio/$id", id, 10_000, 1000, 9000, lane, offset,
    )

    @Test fun splitKeepsSourceAndTimelineContiguous() {
        val source = AudioEditProject(listOf(clip(offset = 2000)))
        val split = source.split("a", 3500)
        assertEquals(10_000L, split.durationMs)
        assertEquals(listOf(1000L, 3500L), split.clips.map { it.startMs })
        assertEquals(listOf(3500L, 9000L), split.clips.map { it.endMs })
        assertEquals(listOf(2000L, 4500L), split.clips.map { it.offsetMs })
        assertEquals(listOf("a (1)", "a (2)"), split.clips.map { it.title })
        assertEquals(1, source.clips.size)
    }

    @Test fun removeRangeClosesGapOnlyOnAffectedLane() {
        val source = AudioEditProject(listOf(clip(), clip("b", offset = 8000), clip("c", lane = 1)))
        val result = source.removeRange("a", 3000, 6000)
        assertEquals(listOf(1000L, 6000L), result.clips.take(2).map { it.startMs })
        assertEquals(listOf(0L, 2000L), result.clips.take(2).map { it.offsetMs })
        assertEquals(5000L, result.clips.first { it.id == "b" }.offsetMs)
        assertEquals(0L, result.clips.first { it.id == "c" }.offsetMs)
    }

    @Test fun removeRangeCanKeepSilenceOrSmoothAClosedJoin() {
        val source = AudioEditProject(listOf(clip(), clip("b", offset = 8000)))
        val open = source.removeRange("a", 3000, 6000, closeGap = false)
        assertEquals(listOf(0L, 5000L, 8000L), open.clips.map { it.offsetMs })
        assertTrue(open.clips.all { it.fadeInMs == 0L && it.fadeOutMs == 0L })

        val smooth = source.removeRange("a", 3000, 6000, closeGap = true, smoothJoin = true)
        assertEquals(listOf(0L, 2000L, 5000L), smooth.clips.map { it.offsetMs })
        assertEquals(AudioEditClip.SMOOTH_JOIN_MS, smooth.clips[0].fadeOutMs)
        assertEquals(AudioEditClip.SMOOTH_JOIN_MS, smooth.clips[1].fadeInMs)
    }

    @Test fun nearestFreeOffsetAllowsAnyFreePositionWithoutOverlap() {
        val source = AudioEditProject(listOf(clip("left"), clip("moving", offset = 12_000)))
        assertEquals(12_000L, source.nearestFreeOffset("moving", 0, 12_000))
        assertEquals(16_000L, source.nearestFreeOffset("moving", 1, 16_000))
        assertEquals(8_000L, source.nearestFreeOffset("moving", 0, 4_000))
    }

    @Test fun nearestFreeOffsetClampsToNearestAvailableGap() {
        val source = AudioEditProject(listOf(
            clip("first", offset = 0),
            clip("middle", offset = 20_000),
            clip("moving", offset = 40_000),
        ))
        assertEquals(12_000L, source.nearestFreeOffset("moving", 0, 13_000))
        assertEquals(28_000L, source.nearestFreeOffset("moving", 0, 26_000))
    }

    @Test fun removeEntireClipAndJoinRetainsOrder() {
        val source = AudioEditProject(listOf(clip(), clip("b", lane = 1, offset = 4000)))
        val result = source.removeRange("a", 1000, 9000).concatenate()
        assertEquals(listOf("b"), result.clips.map { it.id })
        assertEquals(0L, result.clips.single().offsetMs)
        assertEquals(0, result.clips.single().lane)
    }

    @Test fun concatenationPreservesEditsAndVolume() {
        val result = AudioEditProject(listOf(clip("b", lane = 2).copy(gain = 0.25f), clip(lane = 1)))
            .concatenate()
        assertEquals(16000L, result.durationMs)
        assertEquals(listOf(0L, 8000L), result.clips.map { it.offsetMs })
        assertEquals(0.25f, result.clips.last().gain)
    }

    @Test fun draftRoundTripPreservesAllEditingData() {
        val source = AudioEditProject(listOf(clip(lane = 3, offset = 9000).copy(
            gain = 0.33f, fadeInMs = 20, fadeOutMs = 30)))
        assertEquals(source, AudioEditStore.decode(AudioEditStore.encode(source)))
    }

    @Test fun rejectOverlapsButAllowTouchingAndOtherLanes() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioEditProject(listOf(clip(), clip("b", offset = 7999)))
        }
        assertEquals(3, AudioEditProject(listOf(clip(), clip("b", offset = 8000), clip("c", lane = 1))).clips.size)
    }

    @Test fun rejectInvalidTrimAndSplitBoundaries() {
        val source = AudioEditProject(listOf(clip()))
        for (position in listOf(0L, 1000L, 9000L, 10000L)) {
            assertThrows(IllegalArgumentException::class.java) { source.split("a", position) }
        }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(endMs = 10001) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(gain = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(gain = 2.01f) }
    }

    @Test fun supportsTwoHundredPercentGain() {
        val project = AudioEditProject(listOf(clip().copy(gain = AudioEditClip.MAX_GAIN)))

        assertEquals(2f, AudioEditStore.decode(AudioEditStore.encode(project)).clips.single().gain)
    }

    @Test fun appendUsesEndOfChosenLane() {
        val source = AudioEditProject(listOf(clip(), clip("b", lane = 1, offset = 500)))
        val result = source.append(clip("c"), 1)
        assertEquals(8500L, result.clips.last().offsetMs)
        assertEquals(1, result.clips.last().lane)
    }

    @Test fun movingToNewEdgeLaneCreatesAndOrdersLanes() {
        val source = AudioEditProject(listOf(
            clip("moving").copy(endMs = 3000),
            clip("same", offset = 2000),
            clip("second", lane = 4),
        ))

        val above = source.moveToNewEdgeLane("moving", above = true, nearMs = 5000)
        assertEquals(0, above.clips.first { it.id == "moving" }.lane)
        assertEquals(5000L, above.clips.first { it.id == "moving" }.offsetMs)
        assertEquals(listOf(0, 1, 2), above.clips.map(AudioEditClip::lane).distinct().sorted())
        assertEquals(1, above.clips.first { it.id == "same" }.lane)

        val below = source.moveToNewEdgeLane("moving", above = false, nearMs = 7000)
        assertEquals(2, below.clips.first { it.id == "moving" }.lane)
        assertEquals(listOf(0, 1, 2), below.clips.map(AudioEditClip::lane).distinct().sorted())
    }
}
