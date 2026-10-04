package com.dumuzeyn.mp3player

import android.content.Context
import android.net.Uri
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
class StemSeparationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun benchmarkParallelInference() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("parallelSeparationBenchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = monitor.waitForActivityWithTimeout(15000) as MainActivityCore
        instrumentation.removeMonitor(monitor)
        try {
            val model = SeparationModelStore.prepare(context) { false }
            val input = FloatArray(44100 * 2 * 2) { index ->
                (.25 * sin(2 * PI * (if (index % 2 == 0) 110 else 440) * (index / 2) / 44100)).toFloat()
            }
            DemucsSeparator(model).use { it.window(input) { true } }
            for (parallel in listOf(false, true)) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(if (parallel) 2 else 1)
                val started = android.os.SystemClock.elapsedRealtime()
                try {
                    val futures = List(2) { pool.submit<Array<FloatArray>> {
                        DemucsSeparator(model).use { it.window(input) { true } }
                    } }
                    futures.forEach { future -> future.get().forEach { stem ->
                        assertEquals(input.size, stem.size)
                        assertTrue(stem.all(Float::isFinite))
                    } }
                    android.util.Log.i("VoltuneSeparationTest",
                        "two windows parallel=$parallel ms=${android.os.SystemClock.elapsedRealtime() - started}")
                } finally { pool.shutdownNow() }
            }
        } finally { InstrumentedTestSupport.finishActivity(instrumentation, activity) }
    }

    @Test fun benchmarkFullSeparationWindowing() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("separationBenchmark") == "true",
        )
        val source = File(context.cacheDir, "separation-benchmark-source.wav")
        val stems = List(4) { File(context.cacheDir, "separation-benchmark-$it.wav") }
        try {
            PcmWaveWriter(source, 44100, 2).use { writer ->
                repeat(44100 * 16) { frame ->
                    writer.sample((.25 * sin(2 * PI * 110 * frame / 44100)).toFloat())
                    writer.sample((.2 * sin(2 * PI * 440 * frame / 44100)).toFloat())
                }
            }
            val clip = AudioEditClip(uri = Uri.fromFile(source).toString(), title = "Benchmark",
                sourceDurationMs = 16000, startMs = 0, endMs = 16000)
            val started = android.os.SystemClock.elapsedRealtime()
            StemSeparationProcessor(context).process(clip, stems, { false }) { }
            android.util.Log.i("VoltuneSeparationTest",
                "16s full separation ms=${android.os.SystemClock.elapsedRealtime() - started}")
            stems.forEach { assertEquals(44 + 16000 * 44100 / 1000 * 4L, it.length()) }
        } finally {
            (stems + source).forEach { it.delete() }
        }
    }

    @Test fun pinnedModelProducesFourFiniteDistinctStemsAndCancellationWorks() {
        val model = SeparationModelStore.prepare(context) { false }
        assertEquals(83994361, model.length())
        DemucsSeparator(model).use { separator ->
            assertTrue("Tiled attention must match dense attention", separator.verifyAttention())
            val input = FloatArray(44100 * 2 * 2) { index ->
                val frame = index / 2
                (.3 * sin(2 * PI * 110 * frame / 44100) +
                    .12 * sin(2 * PI * 440 * frame / 44100)).toFloat()
            }
            val cancelled = runCatching { separator.window(input) { it < .1f } }
            assertTrue(cancelled.exceptionOrNull() is java.util.concurrent.CancellationException)
            val started = android.os.SystemClock.elapsedRealtime()
            var reported = -1
            val stems = separator.window(input) { progress ->
                val bucket = (progress * 10).toInt()
                if (bucket != reported) {
                    reported = bucket
                    android.util.Log.i("VoltuneSeparationTest", "progress=$bucket nativeMB=${android.os.Debug.getNativeHeapAllocatedSize() / 1048576}")
                }
                true
            }
            android.util.Log.i("VoltuneSeparationTest", "2s inference ms=${android.os.SystemClock.elapsedRealtime() - started}")
            assertEquals(4, stems.size)
            stems.forEach {
                assertEquals(input.size, it.size)
                assertTrue(it.all(Float::isFinite))
            }
            assertTrue(stems.any { stem -> stem.any { kotlin.math.abs(it) > .01f } })
            assertFalse(stems[0].contentEquals(stems[1]))
            val silent = separator.window(FloatArray(44100 * 2)) { true }
            assertTrue(silent.all { stem -> stem.all { it == 0f } })
        }
    }

    @Test fun overlappingWindowsPreserveTimelineAndInstrumentalExportWithoutChangingSource() {
        val source = File(context.cacheDir, "separation-overlap-source.wav")
        val stems = List(4) { File(context.cacheDir, "separation-overlap-$it.wav") }
        val instrumental = File(context.cacheDir, "separation-instrumental.wav")
        try {
            // Anti-phase audio exercises the protected zero-mono-variance path across windows.
            PcmWaveWriter(source, 44100, 2).use { writer ->
                repeat(44100 * 9) { frame ->
                    val value = (.2 * sin(2 * PI * 110 * frame / 44100)).toFloat()
                    writer.sample(value)
                    writer.sample(-value)
                }
            }
            val original = source.readBytes()
            val clip = AudioEditClip(uri = Uri.fromFile(source).toString(), title = "Overlap",
                sourceDurationMs = 9000, startMs = 1500, endMs = 8500)
            val processor = StemSeparationProcessor(context)
            processor.process(clip, stems, { false }) { }
            for (stem in stems) assertEquals(44 + 7000 * 44100 / 1000 * 4L, stem.length())
            for (index in listOf(0, 1, 3)) {
                assertTrue(stems[index].readBytes().drop(44).all { it == 0.toByte() })
            }
            processor.process(clip, listOf(instrumental), { false }) { }
            assertArrayEquals(stems[2].readBytes(), instrumental.readBytes())
            var frames = 0
            AudioPcmDecoder(context).decode(Uri.fromFile(instrumental).toString(), 0, 7000, { false }) { format, pcm, _ ->
                while (pcm.remaining() >= format.frameBytes) {
                    val expected = (.2 * sin(2 * PI * 110 * (frames + 66150) / 44100)).toFloat()
                    assertEquals(expected, format.sample(pcm), 3f / 32768)
                    assertEquals(-expected, format.sample(pcm), 3f / 32768)
                    frames++
                }
            }
            assertEquals(308700, frames)
            assertArrayEquals(original, source.readBytes())
        } finally { (stems + source + instrumental).forEach { it.delete() } }
    }

    @Test fun longSelectionPreservesSamplesAcrossOuterWindowBoundary() {
        val source = File(context.cacheDir, "separation-long-source.wav")
        val output = File(context.cacheDir, "separation-long-instrumental.wav")
        val parallelOutput = File(context.cacheDir, "separation-long-parallel.wav")
        val durationFrames = 44100 * 23
        try {
            PcmWaveWriter(source, 44100, 2).use { writer ->
                repeat(durationFrames) { frame ->
                    val value = (.2 * sin(2 * PI * 110 * frame / 44100)).toFloat()
                    writer.sample(value)
                    writer.sample(-value)
                }
            }
            val clip = AudioEditClip(uri = Uri.fromFile(source).toString(), title = "Long overlap",
                sourceDurationMs = 23000, startMs = 0, endMs = 23000)
            StemSeparationProcessor(context).process(clip, listOf(output), { false }) { }
            StemSeparationProcessor(context, parallelWindows = true)
                .process(clip, listOf(parallelOutput), { false }) { }
            assertArrayEquals(output.readBytes(), parallelOutput.readBytes())
            assertEquals(44 + durationFrames * 4L, output.length())
            val samples = setOf(0, 44100 * 20 - 1, 44100 * 20, 44100 * 20 + 1,
                durationFrames - 1)
            var frame = 0
            AudioPcmDecoder(context).decode(Uri.fromFile(output).toString(), 0, 23000,
                { false }) { format, pcm, _ ->
                while (pcm.remaining() >= format.frameBytes) {
                    val left = format.sample(pcm)
                    val right = format.sample(pcm)
                    if (frame in samples) {
                        val expected = (.2 * sin(2 * PI * 110 * frame / 44100)).toFloat()
                        assertEquals(expected, left, 3f / 32768)
                        assertEquals(-expected, right, 3f / 32768)
                    }
                    frame++
                }
            }
            assertEquals(durationFrames, frame)
        } finally {
            source.delete()
            output.delete()
            parallelOutput.delete()
        }
    }
}
