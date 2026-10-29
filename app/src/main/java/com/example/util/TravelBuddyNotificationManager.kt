package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppNotificationItem(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isRead: Boolean = false
)

object TravelBuddyNotificationManager {

    const val CHANNEL_RIDE_UPDATES = "ride_updates"
    const val CHANNEL_RIDE_TRACKING = "ride_tracking"
    const val CHANNEL_COMMUTE_REMINDERS = "commute_reminders"

    private val notifiedEvents = mutableSetOf<String>()
    private val _notifications = MutableStateFlow<List<AppNotificationItem>>(emptyList())
    val notificationsFlow: StateFlow<List<AppNotificationItem>> = _notifications.asStateFlow()

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // 1. Ride Updates (High Importance for heads-up notifications)
            val updatesChannel = NotificationChannel(
                CHANNEL_RIDE_UPDATES,
                "Ride Updates & Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for ride requests, confirmations, and cancellations."
                enableVibration(true)
            }

            // 2. Ride Tracking (Low Importance for persistent foreground service)
            val trackingChannel = NotificationChannel(
                CHANNEL_RIDE_TRACKING,
                "Active Ride Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification displayed while a ride is in progress."
            }

            // 3. Commute Reminders (Default Importance)
            val remindersChannel = NotificationChannel(
                CHANNEL_COMMUTE_REMINDERS,
                "Commute Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Scheduled reminders for upcoming daily commutes."
            }

            manager.createNotificationChannel(updatesChannel)
            manager.createNotificationChannel(trackingChannel)
            manager.createNotificationChannel(remindersChannel)
        }
    }

    fun showHeadsUpNotification(
        context: Context,
        eventId: String,
        title: String,
        message: String,
        notificationId: Int = eventId.hashCode(),
        pendingIntent: PendingIntent? = null
    ) {
        // Record in-app notification state for offline / in-app viewing
        addInAppNotification(title, message)

        if (notifiedEvents.contains(eventId)) {
            return // Prevent duplicate system notifications for the same state transition
        }
        notifiedEvents.add(eventId)

        initChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("NotificationDebug", "POST_NOTIFICATIONS permission not granted. Skipping system notification.")
                return
            }
        }

        val targetPendingIntent = pendingIntent ?: run {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_RIDE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(targetPendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d("NotificationDebug", "SHOW_NOTIFICATION | eventId=$eventId | title=$title")
        } catch (e: Exception) {
            Log.e("NotificationDebug", "Failed to send notification", e)
        }
    }

    fun showCommuteReminderNotification(
        context: Context,
        slotId: String,
        title: String,
        message: String,
        pendingIntent: PendingIntent
    ) {
        addInAppNotification(title, message)

        initChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("NotificationDebug", "POST_NOTIFICATIONS permission not granted. Skipping commute reminder notification.")
                return
            }
        }

        val notificationId = "commute_reminder_$slotId".hashCode()

        val builder = NotificationCompat.Builder(context, CHANNEL_COMMUTE_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d("NotificationDebug", "SHOW_COMMUTE_NOTIFICATION | slotId=$slotId | title=$title")
        } catch (e: Exception) {
            Log.e("NotificationDebug", "Failed to send commute reminder notification", e)
        }
    }

    private fun addInAppNotification(title: String, message: String) {
        val current = _notifications.value.toMutableList()
        // Deduplicate recent identical title/message within 5 seconds
        val existsRecent = current.any { it.title == title && it.message == message && (System.currentTimeMillis() - it.timestamp) < 5000 }
        if (!existsRecent) {
            current.add(0, AppNotificationItem(title = title, message = message))
            _notifications.value = current.take(50)
        }
    }

    fun markAllRead() {
        val current = _notifications.value.map { it.copy(isRead = true) }
        _notifications.value = current
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    fun cancelRideNotifications(
        context: Context,
        rideId: String,
        requestId: String? = null,
        needId: String? = null
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (rideId.isNotBlank()) {
            manager.cancel(rideId.hashCode())
            manager.cancel("joined_driver_$rideId".hashCode())
            manager.cancel("active_ride_$rideId".hashCode())
        }
        if (!requestId.isNullOrBlank()) {
            manager.cancel(requestId.hashCode())
        }
        if (!needId.isNullOrBlank()) {
            manager.cancel("acc_passenger_$needId".hashCode())
            manager.cancel(needId.hashCode())
        }
        try {
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("ride_$rideId")
        } catch (_: Exception) {}
    }
}
