package com.dumuzeyn.mp3player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.CancellationException

/** Keeps the unchanged Demucs pipeline alive independently of the editor Activity. */
internal class StemSeparationService : Service() {
    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var latestStartId = 0
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            worker?.interrupt()
            if (worker == null) {
                StemSeparationJob.pending(this)?.let { request ->
                    StemSeparationJob.finish(this, request.id, text(R.string.editor_separation_cancelled))
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (worker != null) return START_REDELIVER_INTENT
        val request = StemSeparationJob.pending(this) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        cancelled = false
        createChannel()
        try {
            showNotification(0)
            val manager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voltunizator:stem-separation")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (error: RuntimeException) {
            VoltuneLog.failure("stem_foreground_start_failed", error)
            StemSeparationJob.finish(this, request.id, text(R.string.editor_separation_failed))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        worker = Thread({ process(request) }, "stem-separation").also { it.start() }
        return START_REDELIVER_INTENT
    }

    private fun process(request: StemSeparationRequest) {
        val directory = File(filesDir, "editor-audio")
        val outputs = List(request.lanes.size) { File(directory, "stem-${request.id}-$it.wav") }
        val store = AudioEditStore(this)
        var committed = false
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Editor audio directory unavailable" }
            val current = store.load()
            if (current != request.project) {
                // A process may have died after committing the result but before clearing the job.
                val outputUris = outputs.map { Uri.fromFile(it).toString() }.toSet()
                val committed = current.clips.any { it.uri in outputUris }
                if (!committed) outputs.forEach(File::delete)
                StemSeparationJob.finish(this, request.id, text(
                    if (committed) R.string.editor_separation_complete else R.string.editor_separation_changed))
                return
            }
            outputs.forEach(File::delete)
            val labels = if (request.instrumental) listOf(text(R.string.editor_stem_instrumental))
                else listOf(R.string.editor_stem_drums, R.string.editor_stem_bass,
                    R.string.editor_stem_other, R.string.editor_stem_vocals).map(::text)
            var lastShown = -1
            StemSeparationProcessor(this).process(request.clip, outputs, { cancelled }) { value ->
                StemSeparationJob.progress(this, request.id, value)
                if (value / 5 != lastShown) {
                    lastShown = value / 5
                    showNotification(value)
                }
            }
            if (cancelled) throw CancellationException()
            check(store.load() == request.project) { "Editor project changed" }
            val updated = request.result(outputs.map { Uri.fromFile(it).toString() }, labels)
            check(store.saveNow(updated)) { "Editor project could not be saved" }
            committed = true
            StemSeparationJob.finish(this, request.id, text(R.string.editor_separation_complete))
        } catch (error: Throwable) {
            if (!committed) outputs.forEach(File::delete)
            if (error !is CancellationException) VoltuneLog.failure("stem_separation_failed", error)
            val status = if (cancelled || error is CancellationException)
                R.string.editor_separation_cancelled else R.string.editor_separation_failed
            StemSeparationJob.finish(this, request.id, text(status))
        } finally {
            releaseWakeLock()
            main.post {
                if (!destroyed) {
                    worker = null
                    val next = StemSeparationJob.pending(this)
                    if (next != null && next.id != request.id) {
                        onStartCommand(Intent(this, StemSeparationService::class.java)
                            .setAction(ACTION_START), 0, latestStartId)
                    } else {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf(latestStartId)
                    }
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, text(R.string.editor_separating_audio),
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showNotification(progress: Int) {
        val cancel = Intent(this, StemSeparationService::class.java).setAction(ACTION_CANCEL)
        val action = PendingIntent.getService(this, 0, cancel,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(text(R.string.editor_separating_audio))
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                text(R.string.editor_separation_cancel), action)
            .build()
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        worker?.interrupt()
        stopSelf(startId)
    }

    override fun onDestroy() {
        destroyed = true
        cancelled = true
        worker?.interrupt()
        releaseWakeLock()
        super.onDestroy()
    }

    @Synchronized private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun text(resource: Int) = StemSeparationJob.text(this, resource)

    companion object {
        const val ACTION_START = "com.dumuzeyn.mp3player.SEPARATE_STEMS"
        const val ACTION_CANCEL = "com.dumuzeyn.mp3player.CANCEL_STEMS"
        private const val CHANNEL = "editor_stem_separation"
        private const val NOTIFICATION_ID = 4202
    }
}
