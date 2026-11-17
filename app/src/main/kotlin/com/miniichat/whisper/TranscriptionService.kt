package com.miniichat.whisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.miniichat.MainActivity
import com.miniichat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps transcription running with the screen off or the app in the
 * background, and shows its progress as a notification (with a Cancel action).
 */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.tx_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TranscriptionManager.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, build(null), type) }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miniichat:transcription").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L) // safety cap: 3 h
                }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            var last = 0L
            kotlinx.coroutines.flow.combine(TranscriptionManager.state, TranscriptionManager.busy) { st, busy -> st to busy }
                .collectLatest { (st, busy) ->
                if (!busy) { stopSelf(); return@collectLatest }
                val now = System.currentTimeMillis()
                // notifications are rate limited by the system; ~2 updates/s is plenty
                if (now - last > 500 || st == null || st.progress >= 1f) {
                    last = now
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(st))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(st: com.miniichat.TranscriptionUi?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pct = ((st?.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
        val title = when (st?.phase) {
            "decoding" -> getString(R.string.decoding_audio)
            "loading" -> getString(R.string.loading_model)
            null -> getString(R.string.tx_notification_title)
            else -> getString(R.string.transcribing_pct, pct)
        } + (st?.queued?.takeIf { it > 0 }?.let { "  (+$it)" } ?: "")
        val text = st?.partial?.takeLast(120)?.takeIf { it.isNotBlank() } ?: st?.label ?: ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, pct, st == null || st.phase == "loading")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel), cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transcription"
        private const val NOTIF_ID = 4712
        const val ACTION_CANCEL = "com.miniichat.lab.CANCEL_TRANSCRIPTION"
    }
}
