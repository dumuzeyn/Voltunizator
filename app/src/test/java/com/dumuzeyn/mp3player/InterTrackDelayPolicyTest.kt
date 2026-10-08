package com.dumuzeyn.mp3player

import org.junit.Assert.assertEquals
import org.junit.Test

class InterTrackDelayPolicyTest {
    @Test fun delayIsDisabledByDefaultAndLimitedToFiveMinutes() {
        assertEquals(0L, InterTrackDelayPolicy.milliseconds(0))
        assertEquals(0L, InterTrackDelayPolicy.milliseconds(-5))
        assertEquals(300_000L, InterTrackDelayPolicy.milliseconds(999))
        assertEquals(1_000L, InterTrackDelayPolicy.milliseconds(1))
    }
    @Test fun changedDelayIncludesTheAlreadyElapsedTime() {
        assertEquals(2_000L, InterTrackDelayPolicy.remaining(5, 3_000))
        assertEquals(0L, InterTrackDelayPolicy.remaining(2, 3_000))
        assertEquals(0L, InterTrackDelayPolicy.remaining(0, 3_000))
        assertEquals(5_000L, InterTrackDelayPolicy.remaining(5, -1))
    }
}
