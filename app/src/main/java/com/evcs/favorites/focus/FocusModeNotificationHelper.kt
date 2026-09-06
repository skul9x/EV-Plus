package com.evcs.favorites.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.evcs.favorites.MainActivity
import com.evcs.favorites.R
import java.util.Locale

/**
 * Builds and updates persistent Foreground Service Notifications for Focus Mode,
 * specifically providing action controls ("Đổi trạm", "Tắt") and enriched telemetry
 * when system alert overlay permission is denied or revoked.
 */
object FocusModeNotificationHelper {

    const val CHANNEL_ID = "evplus_focus_mode_channel"
    const val CHANNEL_NAME = "EV-Plus Focus Mode Telemetry"
    const val NOTIFICATION_ID = 2026

    /**
     * Initializes the notification channel required for Android O (API 26) and above.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị trạng thái cổng sạc theo thời gian thực khi đang dẫn đường"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Constructs a persistent Foreground Notification reflecting current [FocusModeState].
     *
     * @param context Application/Service context
     * @param state Live Focus Mode telemetry state
     * @param isFallbackMode True if running as fallback when overlay permission is unavailable
     * @return Built [Notification] instance
     */
    fun buildNotification(
        context: Context,
        state: FocusModeState,
        isFallbackMode: Boolean = false
    ): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = FocusModeForegroundService.createStopIntent(context)
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "⚡ Focus Mode: ${state.targetStation.name}"
        val distStr = state.distanceRemainingKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "..."
        val contentText = if (state.detailedDcTiersText != null) {
            "${state.statusBadgeOverviewText} • ${state.detailedDcTiersText} • Cách $distStr"
        } else {
            "${state.statusBadgeText} • Cách $distStr"
        }

        val priority = if (isFallbackMode && state.isDcFull) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_LOW
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(!isFallbackMode)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Tắt",
                stopPendingIntent
            )

        // When slots are full and an alternative station recommendation exists,
        // attach 1-tap "Đổi trạm" action button and BigTextStyle expansion
        if (state.isDcFull && state.alternativeStation != null) {
            val alt = state.alternativeStation
            val rerouteIntent = FocusModeForegroundService.createRerouteIntent(context, alt.station)
            val reroutePendingIntent = PendingIntent.getService(
                context,
                2,
                rerouteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val actionLabel = "Đổi trạm: ${alt.station.name}"
            builder.addAction(
                android.R.drawable.ic_menu_directions,
                actionLabel,
                reroutePendingIntent
            )

            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText("$contentText\n${alt.displayRerouteLabel}")
            )
        } else {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(contentText)
            )
        }

        return builder.build()
    }
}
