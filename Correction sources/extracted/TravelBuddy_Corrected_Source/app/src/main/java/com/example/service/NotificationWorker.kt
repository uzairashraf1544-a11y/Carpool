package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.data.repository.UserRepository

class NotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "travelbuddy_commute_prompts"
        const val NOTIFICATION_ID = 2001
    }

    override suspend fun doWork(): ListenableWorker.Result {
        val userRepo = UserRepository()
        val user = userRepo.getCurrentUser() ?: return ListenableWorker.Result.success()

        val currentTime = System.currentTimeMillis()
        if (user.muteUntilTimestamp > currentTime) {
            // User is currently on Mute / Leave schedule - suppress prompt
            return ListenableWorker.Result.success()
        }

        val promptType = inputData.getString("promptType") ?: "MORNING"
        val title = if (promptType == "MORNING") "Morning Commute — TravelBuddy" else "Evening Return — TravelBuddy"
        val message = if (promptType == "MORNING") {
            "Are you travelling to the Plant today?"
        } else {
            "Are you travelling back to Township today?"
        }

        sendNotification(title, message)
        return ListenableWorker.Result.success()
    }

    private fun sendNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Commute Prompts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily morning & evening carpool reminders"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
