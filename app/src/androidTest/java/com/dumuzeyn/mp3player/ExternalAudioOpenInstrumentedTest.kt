package com.dumuzeyn.mp3player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalAudioOpenInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun audioViewIsOfferedButVideoIsNot() {
        val uri = Uri.parse("content://media/external/audio/media/1")
        fun accepts(type: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            return context.packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name == ExternalAudioActivity::class.java.name
            }
        }
        assertTrue(accepts("audio/mpeg"))
        assertTrue(accepts("audio/wav"))
        assertTrue(accepts("application/ogg"))
        assertFalse(accepts("video/mp4"))
    }

    @Test fun untaggedContentAudioUsesItsFilename() {
        val source = InstrumentedTestSupport.createTestWave(context, "external-open-test.wav", 2)
        val directory = File(context.cacheDir, "editor-test-exports").apply { mkdirs() }
        val file = File(directory, source.name)
        assertTrue(source.renameTo(file))
        try {
            val uri = FileProvider.getUriForFile(context,
                "${context.packageName}.testexports", file)
            val track = TrackStore.fromUri(context, uri)
            assertTrue(track?.title?.contains("external-open-test") == true)
        } finally {
            file.delete()
        }
    }
}
