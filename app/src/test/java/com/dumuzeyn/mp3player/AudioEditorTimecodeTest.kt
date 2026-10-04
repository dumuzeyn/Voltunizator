package com.dumuzeyn.mp3player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioEditorTimecodeTest {
    @Test fun acceptsSecondsAndExactTimecodes() {
        assertEquals(16_000L, AudioEditorTimecode.parse("16"))
        assertEquals(16_000L, AudioEditorTimecode.parse("0:16.000"))
        assertEquals(62_250L, AudioEditorTimecode.parse("1:02,250"))
        assertEquals(3_723_125L, AudioEditorTimecode.parse("1:02:03.125"))
        assertEquals("1:02.250", AudioEditorTimecode.format(62_250))
    }

    @Test fun rejectsInvalidOrOutOfRangeTimecodes() {
        listOf("", "1:60", "-2", "1:2:75", "1:99:00", "25:00:00", "oops")
            .forEach { assertNull(it, AudioEditorTimecode.parse(it)) }
    }
}
