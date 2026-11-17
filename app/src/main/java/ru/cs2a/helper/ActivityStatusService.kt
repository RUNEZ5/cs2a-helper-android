package ru.cs2a.helper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps a single, low-priority ongoing notification while one or more background
 * features are enabled. It reports only features whose switches are actually on.
 */
class ActivityStatusService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TokenStore.init(this)
        val active = activeFeatures()
        running = true
        activeSignature = active.joinToString("|")
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(active))
        return START_STICKY
    }

    private fun activeFeatures(): List<String> = buildList {
        if (TokenStore.getBool("rafflesAuto")) add("розыгрыши")
        if (TokenStore.getBool("farmAuto")) add("яйца фермы")
        if (TokenStore.getBool("promoAuto")) add("промокоды")
    }

    private fun notification(active: List<String>): Notification {
        val open = PendingIntent.getActivity(
            this, 901, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("CS2AHelper работает")
            .setContentText(active.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText("Активно: " + active.joinToString(", ")))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        activeSignature = ""
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false
        @Volatile private var activeSignature = ""
        private const val NOTIFICATION_ID = 41

        /** Reconciles the persistent notification with the user's enabled background tasks. */
        fun sync(context: Context) {
            val app = context.applicationContext
            TokenStore.init(app)
            val features = buildList {
                if (TokenStore.getBool("rafflesAuto")) add("розыгрыши")
                if (TokenStore.getBool("farmAuto") || TokenStore.getBool("cashbackAuto")) add("яйца и кэшбек")
                if (TokenStore.getBool("promoAuto")) add("промокоды")
            }
            val signature = features.joinToString("|")
            val intent = Intent(app, ActivityStatusService::class.java)
            if (features.isNotEmpty()) {
                if (running && activeSignature == signature) return

                androidx.core.content.ContextCompat.startForegroundService(app, intent)
            } else {
                running = false
                activeSignature = ""
                app.stopService(intent)
            }
        }
    }
}
