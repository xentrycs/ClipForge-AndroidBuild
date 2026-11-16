package com.clipforge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

const val EXTRA_PROJECT = "projectId"

private const val CHANNEL_PROGRESS = "analysis_progress"
private const val CHANNEL_DONE = "analysis_done"
private const val ID_PROGRESS = 101
private const val ID_DONE = 102

/**
 * Keeps ClipForge alive while a video is being analysed, so it keeps working when you switch apps or the
 * screen turns off. It only shows a notification and holds a wake lock; the real work is in AnalysisManager.
 */
@UnstableApi
class AnalysisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progressNotification(this, "ClipForge is working", "Starting…", 0f, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipForge:analysis").apply {
                setReferenceCounted(false)
                acquire(3L * 60 * 60 * 1000)   // safety limit: 3 hours
            }
        }
        collector?.cancel()
        collector = scope.launch {
            combine(AnalysisManager.ui, AnalysisManager.exportNote) { a, e -> a to e }
                .collect { (a, e) -> handle(a, e) }
        }
        return START_NOT_STICKY
    }

    private var sawAnalysis = false
    private var sawExport = false

    private fun handle(a: AnalysisUi?, e: ExportNote?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val analysing = a != null && !a.done && a.error == null
        val exporting = e != null && !e.finished && e.error == null

        if (analysing) {
            sawAnalysis = true
            nm.notify(ID_PROGRESS, progressNotification(this, "ClipForge is analysing your video", a!!.stage, a.progress, false))
            return
        }
        if (exporting) {
            sawExport = true
            nm.notify(ID_PROGRESS, progressNotification(this, e!!.title, e.text, e.progress, false))
            return
        }
        // Nothing is running any more: tell the user how it went (only if they are not looking at the app).
        if (sawAnalysis && a != null && !AnalysisManager.appVisible) {
            if (a.done) nm.notify(ID_DONE, resultNotification(this, "Your highlights are ready", "Tap to review and export your clips.", a.projectId))
            else if (a.error != null) nm.notify(ID_DONE, resultNotification(this, "Analysis didn't finish", a.error, a.projectId))
        }
        if (sawExport && e != null && !e.quiet && !AnalysisManager.appVisible) {
            if (e.finished) nm.notify(ID_DONE + 1, resultNotification(this, e.doneTitle, e.doneText, null))
            else if (e.error != null) nm.notify(ID_DONE + 1, resultNotification(this, "Export didn't finish", e.error, null))
        }
        finish()
    }

    private fun finish() {
        collector?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, AnalysisService::class.java))
            } catch (e: Exception) {
                // Not allowed right now: the analysis still runs while the app stays open.
            }
        }
    }
}

private fun createChannels(ctx: Context) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Analysis progress", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Highlights ready", NotificationManager.IMPORTANCE_DEFAULT))
}

private fun openAppIntent(ctx: Context, projectId: String?): PendingIntent {
    val i = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (projectId != null) putExtra(EXTRA_PROJECT, projectId)
    }
    return PendingIntent.getActivity(
        ctx, projectId?.hashCode() ?: 0, i,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun progressNotification(ctx: Context, title: String, text: String, progress: Float, indeterminate: Boolean) =
    NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(100, (progress * 100).toInt(), indeterminate)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent(ctx, null))
        .build()

private fun resultNotification(ctx: Context, title: String, text: String, projectId: String?) =
    NotificationCompat.Builder(ctx, CHANNEL_DONE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(openAppIntent(ctx, projectId))
        .build()
