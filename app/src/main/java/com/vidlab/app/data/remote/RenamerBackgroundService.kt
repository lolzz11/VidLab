package com.vidlab.app.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vidlab.app.MainActivity
import com.vidlab.app.R

class RenamerBackgroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VidLab::RenamerWakeLock").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Organizing VidLab Vault"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Scanning storage outside /VidLab..."
        val processed = intent?.getIntExtra(EXTRA_PROCESSED, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0

        val notification = buildProgressNotification(this, title, text, processed, total)
        startForeground(NOTIF_ID_PROGRESS, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_PROGRESS = "vidlab_renamer_progress"
        const val CHANNEL_MANUAL = "vidlab_renamer_manual"
        const val NOTIF_ID_PROGRESS = 4401
        const val NOTIF_ID_MANUAL = 4402
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROCESSED = "processed"
        const val EXTRA_TOTAL = "total"
        const val ACTION_OPEN_RENAMER = "com.vidlab.app.OPEN_RENAMER"
        const val ACTION_OPEN_MANUAL_QUEUE = "com.vidlab.app.OPEN_MANUAL_QUEUE"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_PROGRESS, "Storage Auto-Renamer", NotificationManager.IMPORTANCE_LOW).apply {
                            description = "Live progress while organizing media into /VidLab"
                        }
                    )
                }
                if (nm.getNotificationChannel(CHANNEL_MANUAL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_MANUAL, "Manual Match Required", NotificationManager.IMPORTANCE_HIGH).apply {
                            description = "Alerts when files need manual identification"
                        }
                    )
                }
            }
        }

        private fun buildProgressNotification(
            context: Context,
            title: String,
            text: String,
            processed: Int,
            total: Int
        ) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), processed.coerceAtLeast(0), total <= 0)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    4401,
                    Intent(context, MainActivity::class.java).apply {
                        action = ACTION_OPEN_RENAMER
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        fun startOrUpdate(context: Context, title: String, text: String, processed: Int = 0, total: Int = 0) {
            ensureChannels(context)
            val intent = Intent(context, RenamerBackgroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROCESSED, processed)
                putExtra(EXTRA_TOTAL, total)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context, summaryText: String? = null) {
            runCatching { context.stopService(Intent(context, RenamerBackgroundService::class.java)) }
            if (!summaryText.isNullOrBlank()) {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val doneNotif = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("VidLab Vault Sync Complete")
                    .setContentText(summaryText)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            4403,
                            Intent(context, MainActivity::class.java).apply {
                                action = ACTION_OPEN_RENAMER
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()
                nm.notify(NOTIF_ID_PROGRESS + 10, doneNotif)
            }
        }

        fun notifyManualInputNeeded(context: Context, count: Int, sampleName: String) {
            if (count <= 0) return
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, CHANNEL_MANUAL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$count file(s) need manual match")
                .setContentText("Tap to identify \"$sampleName\" & move to /VidLab")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        4402,
                        Intent(context, MainActivity::class.java).apply {
                            action = ACTION_OPEN_MANUAL_QUEUE
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            nm.notify(NOTIF_ID_MANUAL, notif)
        }
    }
}
