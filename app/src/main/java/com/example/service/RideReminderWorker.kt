package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.util.TravelBuddyNotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class RideReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val rideId = inputData.getString("rideId") ?: return Result.success()
        val isDriver = inputData.getBoolean("isDriver", false)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()

        return try {
            val db = FirebaseFirestore.getInstance()
            val rideDoc = db.collection("rides").document(rideId).get().await()
            val ride = rideDoc.toObject(Ride::class.java)

            if (ride == null || ride.status in listOf(Ride.STATUS_COMPLETED, Ride.STATUS_CANCELLED)) {
                Log.d("RIDE_REMINDER_SKIPPED", "Ride is already terminal (status=${ride?.status})")
                return Result.success()
            }

            if (isDriver) {
                // Driver reminder: check if any passenger still hasn't completed
                val uncompleted = ride.acceptedPassengerIds.filter { !ride.completedPassengerIds.contains(it) }
                if (uncompleted.isNotEmpty()) {
                    TravelBuddyNotificationManager.showHeadsUpNotification(
                        context = applicationContext,
                        eventId = "reminder_driver_${rideId}_${uid}",
                        title = "Ride still open",
                        message = "Some passengers have not closed their TravelBuddy ride yet."
                    )
                    Log.d("RIDE_REMINDER_SHOWN", "Driver reminder shown for rideId=$rideId | uncompletedCount=${uncompleted.size}")
                }
            } else {
                // Passenger reminder: check if this passenger's participation is still active
                val reqQuery = db.collection("rideRequests")
                    .whereEqualTo("rideId", rideId)
                    .whereEqualTo("passengerId", uid)
                    .get()
                    .await()
                val activeReq = reqQuery.documents.mapNotNull { it.toObject(RideRequest::class.java) }
                    .firstOrNull { it.status in listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED") }

                if (activeReq != null) {
                    TravelBuddyNotificationManager.showHeadsUpNotification(
                        context = applicationContext,
                        eventId = "reminder_passenger_${rideId}_${uid}",
                        title = "Please close your ride",
                        message = "Confirm whether you completed your shared ride."
                    )
                    Log.d("RIDE_REMINDER_SHOWN", "Passenger reminder shown for rideId=$rideId | uid=$uid")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("RideReminderWorker", "Error checking 30-min reminder", e)
            Result.failure()
        }
    }
}

object RideReminderScheduler {
    const val THRESHOLD_MINUTES: Long = 30 // Production: 30 minutes

    fun schedule30MinReminder(context: Context, rideId: String, uid: String, referenceTimeMs: Long, isDriver: Boolean) {
        val reminderTime = referenceTimeMs + (THRESHOLD_MINUTES * 60 * 1000L)
        val delayMs = (reminderTime - System.currentTimeMillis()).coerceAtLeast(0L)

        val inputData = workDataOf(
            "rideId" to rideId,
            "isDriver" to isDriver
        )

        val request = OneTimeWorkRequestBuilder<RideReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()

        val workName = "ride_completion_reminder_${rideId}_${uid}"
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.d("RIDE_REMINDER_SCHEDULED", "rideId=$rideId | uid=$uid | referenceTime=$referenceTimeMs | delayMs=$delayMs")
    }

    fun cancelReminder(context: Context, rideId: String, uid: String) {
        val workName = "ride_completion_reminder_${rideId}_${uid}"
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Log.d("RIDE_REMINDER_CANCELLED", "rideId=$rideId | uid=$uid")
    }
}
