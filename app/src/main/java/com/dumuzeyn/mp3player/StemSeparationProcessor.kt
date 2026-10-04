package com.dumuzeyn.mp3player

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

/** Disk-backed stereo input and overlapping, bounded inference windows. */
internal class StemSeparationProcessor(private val context: Context,
    private val parallelWindows: Boolean = supportsParallelWindows(context)) {
    private data class Window(val index: Int, val offset: Long, val length: Int,
        val from: Long, val to: Long)
    private data class WindowResult(val window: Window, val stems: Array<FloatArray>)

    fun process(clip: AudioEditClip, outputs: List<File>, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        require(outputs.size == 1 || outputs.size == 4)
        val temporary = File.createTempFile("voltune-separation-", ".pcm", context.cacheDir)
        var locked = false
        val writers = ArrayList<PcmWaveWriter>()
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        try {
            gate.acquire()
            locked = true
            checkCancelled()
            val model = SeparationModelStore.prepare(context, cancelled)
            temporary.outputStream().buffered(65536).use { output ->
                val bytes = ByteArray(65536)
                AudioPcmResampler(context).decode(clip, RATE, true, cancelled) { pcm, _ ->
                    checkCancelled()
                    while (pcm.hasRemaining()) {
                        val count = minOf(bytes.size, pcm.remaining())
                        pcm.get(bytes, 0, count)
                        output.write(bytes, 0, count)
                    }
                }
            }
            outputs.forEach { writers.add(PcmWaveWriter(it, RATE, 2)) }
            val total = temporary.length() / 8
            check(total >= 2) { "Selection too short" }
            val windows = buildList {
                var offset = 0L
                while (offset < total) {
                    val length = minOf(CORE.toLong(), total - offset).toInt()
                    add(Window(size, offset, length, maxOf(0, offset - CONTEXT),
                        minOf(total, offset + length + CONTEXT)))
                    if (offset + length >= total) break
                    offset += CORE - OVERLAP
                }
            }
            val fractions = AtomicIntegerArray(windows.size)
            fun report(window: Window, value: Float): Boolean {
                fractions.set(window.index, (value.coerceIn(0f, 1f) * 1000).toInt())
                val completed = windows.sumOf { it.length.toLong() * fractions.get(it.index) }
                val work = windows.sumOf { it.length.toLong() } * 1000
                progress((5 + completed * 94 / work).toInt().coerceAtMost(99))
                return !cancelled() && !Thread.currentThread().isInterrupted
            }
            var tail: Array<FloatArray>? = null
            fun write(result: WindowResult) {
                val window = result.window
                val stems = result.stems
                val start = ((window.offset - window.from) * 2).toInt()
                val last = window.index == windows.lastIndex
                val writeFrames = if (last) window.length else window.length - OVERLAP
                val blendFrames = minOf(window.length, OVERLAP).takeIf { tail != null } ?: 0
                repeat(writeFrames) { frame ->
                    repeat(2) { channel ->
                        val local = frame * 2 + channel
                        val source = start + local
                        val weight = frame / OVERLAP.toFloat()
                        fun sample(stem: Int): Float = if (frame < blendFrames) {
                            checkNotNull(tail)[stem][local] * (1 - weight) + stems[stem][source] * weight
                        } else stems[stem][source]
                        if (writers.size == 1) writers[0].sample(sample(0) + sample(1) + sample(2))
                        else repeat(4) { stem -> writers[stem].sample(sample(stem)) }
                    }
                }
                if (!last) tail = Array(4) { stems[it].copyOfRange(
                    start + writeFrames * 2, start + window.length * 2) }
            }
            fun sequential() = DemucsSeparator(model).use { separator ->
                windows.forEach { window ->
                    checkCancelled()
                    write(WindowResult(window, separator.window(readWindow(temporary, window)) {
                        report(window, it)
                    }))
                }
            }
            if (parallelWindows && windows.size > 1) try {
                val pool = Executors.newFixedThreadPool(2)
                val separators = ConcurrentLinkedQueue<DemucsSeparator>()
                val local = object : ThreadLocal<DemucsSeparator>() {
                    override fun initialValue() = DemucsSeparator(model).also(separators::add)
                }
                fun submit(window: Window): Future<WindowResult> = pool.submit<WindowResult> {
                    checkCancelled()
                    WindowResult(window, checkNotNull(local.get()).window(readWindow(temporary, window)) {
                        report(window, it)
                    })
                }
                val pending = ArrayDeque<Future<WindowResult>>()
                try {
                    windows.forEach { window ->
                        pending.addLast(submit(window))
                        if (pending.size == 2) {
                            checkCancelled()
                            write(pending.removeFirst().get())
                        }
                    }
                    pending.forEach { checkCancelled(); write(it.get()) }
                } finally {
                    pool.shutdownNow()
                    pending.clear()
                    var interrupted = Thread.interrupted()
                    var terminated = false
                    while (!terminated) {
                        try { terminated = pool.awaitTermination(1, TimeUnit.SECONDS) }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                    separators.forEach(DemucsSeparator::close)
                    if (interrupted) Thread.currentThread().interrupt()
                }
            } catch (failure: Throwable) {
                val cause = if (failure is ExecutionException) failure.cause ?: failure else failure
                if (cause !is OutOfMemoryError || cancelled() || Thread.currentThread().isInterrupted) throw cause
                VoltuneLog.warning("stem_parallel_memory_fallback")
                writers.forEach(PcmWaveWriter::close)
                writers.clear()
                outputs.forEach { writers.add(PcmWaveWriter(it, RATE, 2)) }
                tail = null
                windows.forEach { fractions.set(it.index, 0) }
                sequential()
            } else sequential()
            writers.forEach { it.close() }
            writers.clear()
            progress(100)
        } catch (failure: Throwable) {
            writers.forEach { runCatching { it.close() } }
            writers.clear()
            outputs.forEach { it.delete() }
            throw failure
        } finally {
            temporary.delete()
            if (locked) gate.release()
        }
    }

    private fun readWindow(file: File, window: Window): FloatArray {
        val bytes = ByteArray(((window.to - window.from) * 8).toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(window.from * 8)
            input.readFully(bytes)
        }
        return FloatArray(bytes.size / 4).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
    }

    companion object {
        private const val RATE = 44100
        // Keep outer windows long enough to avoid repeatedly running Demucs' own 8-second splits.
        private const val CORE = RATE * 21
        private const val OVERLAP = RATE
        private const val CONTEXT = RATE
        private val gate = Semaphore(1)

        private fun supportsParallelWindows(context: Context): Boolean {
            val runtime = Runtime.getRuntime()
            val heapRoom = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            if (runtime.availableProcessors() < 8 || heapRoom < 160L * 1024 * 1024) return false
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            return !memory.lowMemory && memory.totalMem >= 6L * 1024 * 1024 * 1024 &&
                memory.availMem >= 3L * 1024 * 1024 * 1024
        }
    }
}
