package com.tabtap.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tabtap.app.MainActivity
import com.tabtap.app.R

/**
 * Keeps playback and the metronome running when the app is not in the foreground.
 *
 * The engine itself lives in the application object, not here, so audio is never
 * interrupted by the service starting or stopping. This service exists only to tell
 * Android that the process is doing something the user can hear, which is what stops the
 * system from throttling the render thread or killing the process mid-take. It is started
 * when playback begins and stopped as soon as it ends, so there is no idle notification.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.playback_channel_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "tabtap_playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tabtap.app.STOP_PLAYBACK"

        /** Called when playback starts. Failures are ignored: audio still works. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            }
        }

        /** Called when playback stops. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PlaybackService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
