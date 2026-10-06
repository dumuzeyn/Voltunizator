package com.dumuzeyn.mp3player

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueCreationModeTest {
    @Test fun storedModesRestoreAndInvalidPreferencesDefaultToRandom() {
        QueueCreationMode.entries.forEach { mode ->
            assertEquals(mode, QueueCreationMode.fromStored(mode.name))
        }
        assertEquals(QueueCreationMode.RANDOM, QueueCreationMode.fromStored(null))
        assertEquals(QueueCreationMode.RANDOM, QueueCreationMode.fromStored("broken"))
    }
}
