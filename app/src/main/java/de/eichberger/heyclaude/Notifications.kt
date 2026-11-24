package de.eichberger.heyclaude

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifications {

    const val SERVICE_ID = 1
    private const val PROMPT_ID = 2

    private const val CHANNEL_SERVICE = "listening"
    private const val CHANNEL_HINTS = "hints"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HINTS,
                context.getString(R.string.channel_hints),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** Dauerhafte, unauffällige Benachrichtigung des Dienstes mit Pausieren/Fortsetzen. */
    fun service(context: Context, state: ServiceStatus.State, message: String?): Notification {
        createChannels(context)
        val text = when (state) {
            ServiceStatus.State.LISTENING -> context.getString(R.string.notif_listening)
            ServiceStatus.State.PAUSED -> context.getString(R.string.notif_paused)
            ServiceStatus.State.WAITING -> context.getString(R.string.notif_waiting)
            ServiceStatus.State.STARTING -> context.getString(R.string.notif_starting)
            ServiceStatus.State.ERROR -> message ?: context.getString(R.string.notif_error)
            ServiceStatus.State.STOPPED -> context.getString(R.string.notif_paused)
        }
        val actionLabel = if (state == ServiceStatus.State.PAUSED) {
            context.getString(R.string.action_resume)
        } else {
            context.getString(R.string.action_pause)
        }
        val toggle = PendingIntent.getService(
            context,
            1,
            Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentIntent(openApp(context, false))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, actionLabel, toggle)
            .build()
    }

    /** Nach einem Neustart (ab Android 14): „Tippen zum Aktivieren“. */
    fun showActivatePrompt(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_HINTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.prompt_title))
            .setContentText(context.getString(R.string.prompt_text))
            .setContentIntent(openApp(context, true))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, n)
    }

    fun cancelActivatePrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
    }

    private fun openApp(context: Context, autostart: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTOSTART, autostart)
        return PendingIntent.getActivity(
            context,
            if (autostart) 3 else 2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
