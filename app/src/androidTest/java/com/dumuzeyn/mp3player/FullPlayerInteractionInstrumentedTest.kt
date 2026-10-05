package com.dumuzeyn.mp3player

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.dumuzeyn.mp3player.data.playback.PlaybackStateManager
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPlayerInteractionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val files = ArrayList<File>()
    private var activity: MainActivityCore? = null

    @After fun cleanup() {
        activity?.let { host ->
            instrumentation.runOnMainSync { host.playbackController.clearQueue() }
            InstrumentedTestSupport.finishActivity(instrumentation, host)
        }
        PlaybackStateManager(context).clear()
        files.forEach { it.delete() }
    }

    @Test fun plainLyricsScrollBothWaysWithoutDismissingPlayer() = checkLyrics(false)

    @Test fun clickableTimedLyricsKeepScrollingAndHorizontalNavigation() = checkLyrics(true)

    @Suppress("DEPRECATION")
    @Test fun visibleArtworkRefreshAndMemoryTrimKeepFullResolution() {
        val host = launch(false)
        lateinit var track: Track
        instrumentation.runOnMainSync { track = host.libraryState.tracks.first() }
        val cache = ArtworkDiskCache(context)
        for (size in listOf(CoverLoader.THUMB_SIZE, MainActivityCore.COVER_FULL_SIZE)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(52, 88, 210))
            }
            val key = track.trackId + "|" + track.uri + "|" + track.fileSize + "|" +
                track.lastModified + "|" + track.fingerprint + "#" + size
            cache.write(key, bitmap)
            bitmap.recycle()
        }
        openPage(host, FullPlayerPageOrder.PLAYER)
        val sheet = sheet(host)
        lateinit var cover: RotatingCoverImageView
        instrumentation.runOnMainSync {
            cover = descendants(sheet).filterIsInstance<RotatingCoverImageView>().single()
        }
        repeat(3) {
            instrumentation.runOnMainSync {
                host.artworkUi.promoteVisibleArtwork()
                host.playerUiController.syncPlaybackUi()
                assertEquals(MainActivityCore.COVER_FULL_SIZE,
                    (cover.drawable as BitmapDrawable).bitmap.width)
                assertTrue(cover.tag.toString().endsWith("#${MainActivityCore.COVER_FULL_SIZE}"))
                host.artworkUi.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
            SystemClock.sleep(100)
        }
    }

    private fun checkLyrics(timed: Boolean) {
        val host = launch(timed)
        openPage(host, FullPlayerPageOrder.LYRICS)
        val sheet = sheet(host)
        lateinit var scroll: ScrollView
        await("Embedded lyrics were not rendered") {
            val visible = descendants(sheet).filterIsInstance<ScrollView>().firstOrNull {
                it.isShown && descendants(it).filterIsInstance<TextView>().any { text ->
                    text.text.contains("Gesture line 79")
                }
            }
            if (visible != null) scroll = visible
            visible != null && visible.height > 0 && visible.getChildAt(0).height > visible.height * 2
        }
        swipe(sheet, scroll, .5f, .75f, .5f, .25f)
        await("Lyrics did not scroll up") { scroll.scrollY > host.dp(50) }
        var before = 0
        instrumentation.runOnMainSync {
            scroll.fling(0)
            scroll.scrollTo(0, host.dp(500))
            before = scroll.scrollY
        }
        swipe(sheet, scroll, .5f, .3f, .5f, .7f)
        await("Lyrics did not scroll down") { scroll.scrollY < before }
        instrumentation.runOnMainSync {
            assertTrue(sheet.isAttachedToWindow)
            assertEquals(0f, sheet.translationY, .01f)
            scroll.fling(0)
            scroll.scrollTo(0, 0)
        }
        // A downward gesture at the beginning must stay in the lyrics page too.
        swipe(sheet, scroll, .5f, .3f, .5f, .7f)
        instrumentation.runOnMainSync {
            assertTrue(sheet.isAttachedToWindow)
            assertEquals(0f, sheet.translationY, .01f)
        }
        swipe(sheet, scroll, .9f, .5f, .1f, .5f)
        val pager = descendants(sheet).filterIsInstance<ViewPager2>().single()
        await("Horizontal page navigation was blocked") { pager.currentItem != FullPlayerPageOrder.LYRICS }
        // Dismissal remains available from the full-player header.
        swipe(sheet, sheet, .5f, .02f, .5f, .22f)
        await("Header swipe no longer closes the player") { !sheet.isAttachedToWindow }
    }

    private fun launch(timed: Boolean): MainActivityCore {
        PlaybackStateManager(context).clear()
        val lyrics = (0..79).joinToString("\n") { index ->
            val timestamp = if (timed) "[%02d:00.00]".format(index) else ""
            timestamp + "Gesture line $index"
        }
        val payload = byteArrayOf(3, 101, 110, 103, 0) + lyrics.toByteArray(Charsets.UTF_8)
        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) +
            ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
        val size = frame.size
        val tag = byteArrayOf(73, 68, 51, 3, 0, 0,
            ((size shr 21) and 127).toByte(), ((size shr 14) and 127).toByte(),
            ((size shr 7) and 127).toByte(), (size and 127).toByte()) + frame
        val file = File(context.cacheDir, "full-player-${System.nanoTime()}.mp3")
        files.add(file)
        file.outputStream().use { output ->
            output.write(tag)
            instrumentation.context.assets.open("audio-formats/tone.mp3").use { it.copyTo(output) }
        }
        val track = Track(Uri.fromFile(file).toString(), "Gesture test", "Artist", "Album", "", 2000)
        TrackStore.save(context, listOf(track))
        val host = InstrumentedTestSupport.launchForPlayback(instrumentation, context)
        activity = host
        instrumentation.runOnMainSync {
            host.appearanceState.animations = false
            host.playbackController.submitQueue(host.libraryState.tracks.take(1), 0, 0, 0, false)
        }
        await("Playback queue was not ready") {
            host.playbackSnapshot().phase == PlaybackPhase.READY &&
                host.playbackSnapshot().currentMediaId.isNotEmpty()
        }
        return host
    }

    private fun openPage(host: MainActivityCore, page: Int) {
        instrumentation.runOnMainSync { host.playerUiController.openFullPlayer() }
        await("Player did not open") { descendants(host.overlayHost).any { it is ViewPager2 } }
        instrumentation.runOnMainSync {
            descendants(host.overlayHost).filterIsInstance<ViewPager2>().single().setCurrentItem(page, false)
        }
        await("Player page was not laid out") {
            val pager = descendants(host.overlayHost).filterIsInstance<ViewPager2>().single()
            pager.currentItem == page && pager.width > 0 && pager.height > 0
        }
    }

    private fun sheet(host: MainActivityCore): FullPlayerSheet {
        lateinit var result: FullPlayerSheet
        instrumentation.runOnMainSync {
            result = descendants(host.overlayHost).filterIsInstance<FullPlayerSheet>().single()
        }
        return result
    }

    private fun swipe(sheet: FullPlayerSheet, target: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        val origin = IntArray(2)
        val offset = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            sheet.getLocationOnScreen(origin)
            target.getLocationOnScreen(offset)
            width = target.width
            height = target.height
        }
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val fraction = step / 12f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            instrumentation.runOnMainSync {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    offset[0] - origin[0] + (x1 + (x2 - x1) * fraction) * width,
                    offset[1] - origin[1] + (y1 + (y2 - y1) * fraction) * height, 0)
                sheet.dispatchTouchEvent(event)
                event.recycle()
            }
            SystemClock.sleep(20)
        }
        SystemClock.sleep(100)
    }

    private fun await(message: String, condition: () -> Boolean) {
        InstrumentedTestSupport.waitFor(message, 15000) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            ready
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }
}
