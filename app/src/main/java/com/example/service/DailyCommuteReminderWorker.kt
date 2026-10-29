package com.example.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.example.MainActivity
import com.example.data.model.CommuteSlot
import com.example.data.repository.AppReminderSettings
import com.example.data.repository.UserPreferencesRepository
import com.example.util.TravelBuddyNotificationManager
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class DailyCommuteReminderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val slotId = inputData.getString(KEY_SLOT_ID) ?: return Result.success()
        val slot = CommuteSlot.fromId(slotId) ?: return Result.success()

        Log.d("CommuteReminderDebug", "COMMUTE_REMINDER_FIRED | slotId=${slot.id}")

        val repo = UserPreferencesRepository(appContext)
        val settings = repo.getReminderSettingsFlow().first()

        val isEnabled = settings.masterEnabled && when (slot) {
            CommuteSlot.MORNING_TO_PLANT -> settings.slot0700Enabled
            CommuteSlot.LUNCH_TO_TOWNSHIP -> settings.slot1300Enabled
            CommuteSlot.LUNCH_RETURN_TO_PLANT -> settings.slot1400Enabled
            CommuteSlot.EVENING_TO_TOWNSHIP -> settings.slot1705Enabled
        }

        val leadMinutes = 0
        val selectedDays = when (slot) {
            CommuteSlot.MORNING_TO_PLANT -> settings.slot0700Days
            CommuteSlot.LUNCH_TO_TOWNSHIP -> settings.slot1300Days
            CommuteSlot.LUNCH_RETURN_TO_PLANT -> settings.slot1400Days
            CommuteSlot.EVENING_TO_TOWNSHIP -> settings.slot1705Days
        }
        val isDayAllowed = selectedDays.contains(LocalDateTime.now().dayOfWeek)

        if (isEnabled && isDayAllowed) {
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_commute_slot_id", slot.id)
            }

            val pendingIntent = PendingIntent.getActivity(
                appContext,
                slot.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            TravelBuddyNotificationManager.showHeadsUpNotification(
                context = appContext,
                eventId = "commute_checkin_${slot.id}",
                title = slot.notificationTitle,
                message = slot.notificationBody,
                pendingIntent = pendingIntent
            )
        }

        // Automatically reschedule next day's occurrence for this slot
        DailyCommuteScheduler.scheduleSlotWorker(appContext, slot, leadMinutes)

        return Result.success()
    }

    companion object {
        const val KEY_SLOT_ID = "slot_id"
    }
}

object DailyCommuteScheduler {
    private const val TAG = "DailyCommuteScheduler"

    fun calculateReminderDateTime(
        baseHour: Int,
        baseMinute: Int,
        leadMinutes: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime {
        val safeLead = leadMinutes.coerceAtLeast(0)
        val todayBase = LocalDateTime.of(now.toLocalDate(), LocalTime.of(baseHour, baseMinute))
        var target = todayBase.minusMinutes(safeLead.toLong())
        if (target.isBefore(now) || target.isEqual(now)) {
            // Target time for today has already passed, schedule for tomorrow
            target = todayBase.plusDays(1).minusMinutes(safeLead.toLong())
        }
        return target
    }

    fun scheduleAllReminders(context: Context, settings: AppReminderSettings) {
        if (!settings.masterEnabled) {
            cancelAllReminders(context)
            return
        }
        if (settings.slot0700Enabled) {
            scheduleSlotWorker(context, CommuteSlot.MORNING_TO_PLANT, 0)
        } else {
            cancelSlotReminder(context, CommuteSlot.MORNING_TO_PLANT)
        }
        if (settings.slot1300Enabled) {
            scheduleSlotWorker(context, CommuteSlot.LUNCH_TO_TOWNSHIP, 0)
        } else {
            cancelSlotReminder(context, CommuteSlot.LUNCH_TO_TOWNSHIP)
        }
        if (settings.slot1400Enabled) {
            scheduleSlotWorker(context, CommuteSlot.LUNCH_RETURN_TO_PLANT, 0)
        } else {
            cancelSlotReminder(context, CommuteSlot.LUNCH_RETURN_TO_PLANT)
        }
        if (settings.slot1705Enabled) {
            scheduleSlotWorker(context, CommuteSlot.EVENING_TO_TOWNSHIP, 0)
        } else {
            cancelSlotReminder(context, CommuteSlot.EVENING_TO_TOWNSHIP)
        }
    }

    fun scheduleAllReminders(context: Context) {
        CommuteSlot.values().forEach { slot ->
            scheduleSlotWorker(context, slot, 10)
        }
    }

    fun scheduleSlotWorker(context: Context, slot: CommuteSlot, leadMinutes: Int = 10) {
        val now = LocalDateTime.now()
        val targetDateTime = calculateReminderDateTime(slot.hour, slot.minute, leadMinutes, now)
        val initialDelay = Duration.between(now, targetDateTime).toMillis().coerceAtLeast(0L)

        val data = Data.Builder()
            .putString(DailyCommuteReminderWorker.KEY_SLOT_ID, slot.id)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DailyCommuteReminderWorker>()
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("travelbuddy_commute_${slot.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "travelbuddy_commute_${slot.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "COMMUTE_REMINDER_SCHEDULED | slotId=${slot.id} | leadMinutes=$leadMinutes | scheduledLocalTime=$targetDateTime | delay=${initialDelay / 1000}s")
    }

    fun cancelAllReminders(context: Context) {
        CommuteSlot.values().forEach { slot ->
            WorkManager.getInstance(context).cancelUniqueWork("travelbuddy_commute_${slot.id}")
        }
        Log.d(TAG, "Cancelled all daily commute reminders")
    }

    fun cancelSlotReminder(context: Context, slot: CommuteSlot) {
        WorkManager.getInstance(context).cancelUniqueWork("travelbuddy_commute_${slot.id}")
        Log.d(TAG, "Cancelled commute reminder slot=${slot.id}")
    }

    // Helper for string slotId
    fun scheduleSlotWorker(context: Context, slotId: String, leadMinutes: Int = 10) {
        val slot = CommuteSlot.fromId(slotId) ?: return
        scheduleSlotWorker(context, slot, leadMinutes)
    }

    fun cancelSlotReminder(context: Context, slotId: String) {
        val slot = CommuteSlot.fromId(slotId) ?: return
        cancelSlotReminder(context, slot)
    }
}
