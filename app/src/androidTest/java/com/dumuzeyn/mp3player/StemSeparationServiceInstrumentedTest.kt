package com.dumuzeyn.mp3player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StemSeparationServiceInstrumentedTest {
    @Test fun redeliveryKeepsAlreadyCommittedAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = AudioEditStore(context)
        val original = store.load()
        val source = File(context.cacheDir, "committed-stem-source.wav")
        var output: File? = null
        var activity: MainActivityCore? = null
        try {
            PcmWaveWriter(source, 44100, 2).use { writer -> repeat(44100) {
                writer.sample(.1f)
                writer.sample(-.1f)
            } }
            val clip = AudioEditClip(uri = Uri.fromFile(source).toString(), title = "Committed test",
                sourceDurationMs = 1000, startMs = 0, endMs = 1000)
            val project = AudioEditProject(listOf(clip))
            assertTrue(store.saveNow(project))
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = monitor.waitForActivityWithTimeout(15000) as MainActivityCore
            instrumentation.removeMonitor(monitor)
            var committed: AudioEditProject? = null
            instrumentation.runOnMainSync {
                assertTrue(StemSeparationJob.begin(context, project, clip, listOf(0), true))
                val request = checkNotNull(StemSeparationJob.pending(context))
                val directory = File(context.filesDir, "editor-audio").apply { mkdirs() }
                val resultFile = File(directory, "stem-${request.id}-0.wav")
                source.copyTo(resultFile, overwrite = true)
                output = resultFile
                committed = request.result(listOf(Uri.fromFile(resultFile).toString()), listOf("instrumental"))
                assertTrue(store.saveNow(checkNotNull(committed)))
            }
            InstrumentedTestSupport.waitFor("Committed separation was not recognized", 15000) {
                !StemSeparationJob.snapshot(context).active
            }
            assertEquals(committed, store.load())
            assertArrayEquals(source.readBytes(), checkNotNull(output).readBytes())
        } finally {
            activity?.let { InstrumentedTestSupport.finishActivity(instrumentation, it) }
            store.saveNow(original)
            output?.delete()
            source.delete()
        }
    }

    @Test fun separationSurvivesEditorClosureAndScreenOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(context.cacheDir, "background-stem-source.wav")
        val original = AudioEditStore(context).load()
        val outputDir = File(context.filesDir, "editor-audio")
        var activity: MainActivityCore? = null
        var requestId: String? = null
        try {
            PcmWaveWriter(source, 44100, 2).use { writer ->
                repeat(44100 * 3) { frame ->
                    val value = (.2 * sin(2 * PI * 110 * frame / 44100)).toFloat()
                    writer.sample(value)
                    writer.sample(value * .8f)
                }
            }
            val clip = AudioEditClip(uri = Uri.fromFile(source).toString(), title = "Background test",
                sourceDurationMs = 3000, startMs = 0, endMs = 3000)
            assertTrue(AudioEditStore(context).saveNow(AudioEditProject(listOf(clip))))
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            activity = monitor.waitForActivityWithTimeout(15000) as MainActivityCore
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                val editor = checkNotNull(activity).audioEditorController
                editor.load()
                assertTrue(editor.processing.status, editor.processing.separate(clip, false))
            }
            requestId = StemSeparationJob.snapshot(context).id
            InstrumentedTestSupport.finishActivity(instrumentation, activity)
            activity = null
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent 223"))
                .use { it.readBytes() }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            InstrumentedTestSupport.waitFor("Screen did not switch off", 5000) { !power.isInteractive }
            assertTrue("Separation ended before the screen-off check", StemSeparationJob.snapshot(context).active)
            InstrumentedTestSupport.waitFor("Foreground separation did not finish with screen off", 120000) {
                !StemSeparationJob.snapshot(context).active
            }
            val result = AudioEditStore(context).load()
            assertEquals(StemSeparationJob.snapshot(context).status, 4, result.clips.size)
            result.clips.forEach { processed ->
                assertTrue(File(Uri.parse(processed.uri).path!!).length() > 44)
            }
        } finally {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent 224"))
                .use { it.readBytes() }
            if (StemSeparationJob.snapshot(context).active) {
                StemSeparationJob.cancel(context)
                InstrumentedTestSupport.waitFor("Separation cleanup did not finish", 15000) {
                    !StemSeparationJob.snapshot(context).active
                }
            }
            activity?.let { InstrumentedTestSupport.finishActivity(instrumentation, it) }
            val result = AudioEditStore(context).load()
            result.clips.filter { it.uri.contains("stem-${requestId ?: "none"}-") }
                .forEach { File(Uri.parse(it.uri).path!!).delete() }
            if (requestId != null) (0..3).forEach { index ->
                File(outputDir, "stem-$requestId-$index.wav").delete()
            }
            AudioEditStore(context).saveNow(original)
            source.delete()
        }
    }
}
