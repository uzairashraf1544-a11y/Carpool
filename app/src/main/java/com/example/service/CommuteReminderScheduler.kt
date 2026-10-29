package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.WorkManager
import com.example.data.model.CommuteSlot
import com.example.data.repository.AppReminderSettings
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object CommuteReminderScheduler {

    private const val TAG = "CommuteAlarmDebug"

    const val ACTION_COMMUTE_REMINDER = "com.example.action.COMMUTE_REMINDER"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_SLOT_ID = "open_commute_slot_id"

    const val REMINDER_1 = "REMINDER_1"
    const val REMINDER_2 = "REMINDER_2"
    const val REMINDER_3 = "REMINDER_3"
    const val REMINDER_4 = "REMINDER_4"

    const val REQUEST_CODE_REMINDER_1 = 1001
    const val REQUEST_CODE_REMINDER_2 = 1002
    const val REQUEST_CODE_REMINDER_3 = 1003
    const val REQUEST_CODE_REMINDER_4 = 1004

    fun getRequestCode(reminderId: String): Int {
        return when (reminderId) {
            REMINDER_1 -> REQUEST_CODE_REMINDER_1
            REMINDER_2 -> REQUEST_CODE_REMINDER_2
            REMINDER_3 -> REQUEST_CODE_REMINDER_3
            REMINDER_4 -> REQUEST_CODE_REMINDER_4
            CommuteSlot.MORNING_TO_PLANT.id -> REQUEST_CODE_REMINDER_1
            CommuteSlot.LUNCH_TO_TOWNSHIP.id -> REQUEST_CODE_REMINDER_2
            CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> REQUEST_CODE_REMINDER_3
            CommuteSlot.EVENING_TO_TOWNSHIP.id -> REQUEST_CODE_REMINDER_4
            else -> 1000
        }
    }

    fun mapReminderIdToSlot(reminderId: String): CommuteSlot {
        return when (reminderId) {
            REMINDER_1 -> CommuteSlot.MORNING_TO_PLANT
            REMINDER_2 -> CommuteSlot.LUNCH_TO_TOWNSHIP
            REMINDER_3 -> CommuteSlot.LUNCH_RETURN_TO_PLANT
            REMINDER_4 -> CommuteSlot.EVENING_TO_TOWNSHIP
            CommuteSlot.MORNING_TO_PLANT.id -> CommuteSlot.MORNING_TO_PLANT
            CommuteSlot.LUNCH_TO_TOWNSHIP.id -> CommuteSlot.LUNCH_TO_TOWNSHIP
            CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> CommuteSlot.LUNCH_RETURN_TO_PLANT
            CommuteSlot.EVENING_TO_TOWNSHIP.id -> CommuteSlot.EVENING_TO_TOWNSHIP
            else -> CommuteSlot.MORNING_TO_PLANT
        }
    }

    fun isReminderAllowedOnDay(dayOfWeek: DayOfWeek, selectedDays: Set<DayOfWeek>): Boolean {
        return dayOfWeek in selectedDays
    }

    fun isReminderAllowedOnDay(reminderId: String, dayOfWeek: DayOfWeek): Boolean {
        return when (dayOfWeek) {
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY -> true
            DayOfWeek.FRIDAY -> {
                val slot = mapReminderIdToSlot(reminderId)
                slot == CommuteSlot.MORNING_TO_PLANT
            }
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY -> false
        }
    }

    fun calculateNextTriggerDateTime(
        hour: Int,
        minute: Int,
        selectedDays: Set<DayOfWeek>,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime? {
        if (selectedDays.isEmpty()) return null

        val todayBase = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))

        // 1. Check whether today's weekday is selected AND target > now
        if (selectedDays.contains(now.dayOfWeek) && todayBase.isAfter(now)) {
            return todayBase
        }

        // 2. Otherwise check subsequent days up to 7 days forward
        for (dayOffset in 1..7) {
            val candidate = todayBase.plusDays(dayOffset.toLong())
            if (selectedDays.contains(candidate.dayOfWeek)) {
                return candidate
            }
        }
        return null
    }

    fun calculateNextTriggerDateTime(
        reminderId: String,
        hour: Int,
        minute: Int,
        selectedDays: Set<DayOfWeek>,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime? {
        return calculateNextTriggerDateTime(hour, minute, selectedDays, now)
    }

    fun calculateNextTriggerDateTime(
        reminderId: String,
        hour: Int,
        minute: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime {
        val todayBase = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))

        if (todayBase.isAfter(now) && isReminderAllowedOnDay(reminderId, todayBase.dayOfWeek)) {
            return todayBase
        }

        for (dayOffset in 1..7) {
            val candidate = todayBase.plusDays(dayOffset.toLong())
            if (isReminderAllowedOnDay(reminderId, candidate.dayOfWeek)) {
                return candidate
            }
        }
        return todayBase.plusDays(1)
    }

    fun calculateNextTriggerMillis(
        hour: Int,
        minute: Int,
        selectedDays: Set<DayOfWeek>,
        now: LocalDateTime = LocalDateTime.now()
    ): Long? {
        val target = calculateNextTriggerDateTime(hour, minute, selectedDays, now) ?: return null
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun calculateNextTriggerMillis(
        reminderId: String,
        hour: Int,
        minute: Int,
        selectedDays: Set<DayOfWeek>,
        now: LocalDateTime = LocalDateTime.now()
    ): Long? {
        return calculateNextTriggerMillis(hour, minute, selectedDays, now)
    }

    fun calculateNextTriggerMillis(
        reminderId: String,
        hour: Int,
        minute: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): Long {
        val target = calculateNextTriggerDateTime(reminderId, hour, minute, now)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun calculateNextTriggerMillis(
        hour: Int,
        minute: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): Long {
        return calculateNextTriggerMillis(REMINDER_1, hour, minute, now)
    }

    fun scheduleReminder(
        context: Context,
        reminderId: String,
        hour: Int,
        minute: Int,
        selectedDays: Set<DayOfWeek>
    ) {
        if (selectedDays.isEmpty()) {
            cancelReminder(context, reminderId)
            Log.d(TAG, "ALARM_SKIPPED_NO_DAYS_SELECTED | reminder=$reminderId")
            return
        }

        val triggerMillis = calculateNextTriggerMillis(reminderId, hour, minute, selectedDays)
        if (triggerMillis == null) {
            cancelReminder(context, reminderId)
            Log.d(TAG, "ALARM_SKIPPED_NO_NEXT_TRIGGER | reminder=$reminderId")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val slot = mapReminderIdToSlot(reminderId)

        val intent = Intent(context, CommuteReminderReceiver::class.java).apply {
            action = ACTION_COMMUTE_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_SLOT_ID, slot.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            Log.d(TAG, "ALARM_SCHEDULED_ALLOW_IDLE | reminder=$reminderId | hour=$hour | minute=$minute | triggerMillis=$triggerMillis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reminder alarm", e)
        }
    }

    fun scheduleReminder(
        context: Context,
        reminderId: String,
        hour: Int,
        minute: Int
    ) {
        scheduleReminder(context, reminderId, hour, minute, AppReminderSettings.DEFAULT_WEEKDAYS)
    }

    fun cancelReminder(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val slot = mapReminderIdToSlot(reminderId)
        val intent = Intent(context, CommuteReminderReceiver::class.java).apply {
            action = ACTION_COMMUTE_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_SLOT_ID, slot.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            getRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.d(TAG, "ALARM_CANCELLED | reminder=$reminderId")
    }

    fun scheduleAllReminders(context: Context, settings: AppReminderSettings) {
        if (!settings.masterEnabled) {
            cancelAllReminders(context)
            return
        }

        if (settings.slot0700Enabled) {
            scheduleReminder(context, REMINDER_1, settings.slot0700Hour, settings.slot0700Minute, settings.slot0700Days)
        } else {
            cancelReminder(context, REMINDER_1)
        }

        if (settings.slot1300Enabled) {
            scheduleReminder(context, REMINDER_2, settings.slot1300Hour, settings.slot1300Minute, settings.slot1300Days)
        } else {
            cancelReminder(context, REMINDER_2)
        }

        if (settings.slot1400Enabled) {
            scheduleReminder(context, REMINDER_3, settings.slot1400Hour, settings.slot1400Minute, settings.slot1400Days)
        } else {
            cancelReminder(context, REMINDER_3)
        }

        if (settings.slot1705Enabled) {
            scheduleReminder(context, REMINDER_4, settings.slot1705Hour, settings.slot1705Minute, settings.slot1705Days)
        } else {
            cancelReminder(context, REMINDER_4)
        }
    }

    fun reconcileAllReminders(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = UserPreferencesRepository(context)
                val settings = repo.getReminderSettingsFlow().first()
                scheduleAllReminders(context, settings)
                Log.d(TAG, "ALL_REMINDERS_RECONCILED")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconcile reminders", e)
            }
        }
    }

    fun cancelAllReminders(context: Context) {
        cancelReminder(context, REMINDER_1)
        cancelReminder(context, REMINDER_2)
        cancelReminder(context, REMINDER_3)
        cancelReminder(context, REMINDER_4)
        Log.d(TAG, "ALL_COMMUTE_ALARMS_CANCELLED")
    }

    /**
     * Strictly cancels ONLY the four legacy commute unique work jobs from WorkManager.
     * NEVER calls cancelAllWork(), leaving RideReminderWorker and all other WorkManager jobs completely intact.
     */
    fun cancelLegacyWorkManagerCommuteJobs(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork("travelbuddy_commute_MORNING_TO_PLANT")
            workManager.cancelUniqueWork("travelbuddy_commute_LUNCH_TO_TOWNSHIP")
            workManager.cancelUniqueWork("travelbuddy_commute_LUNCH_RETURN_TO_PLANT")
            workManager.cancelUniqueWork("travelbuddy_commute_EVENING_TO_TOWNSHIP")
            Log.d(TAG, "LEGACY_WORKMANAGER_COMMUTE_JOBS_CANCELLED")
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel legacy WorkManager jobs (WorkManager may not be initialized yet)", e)
        }
    }
}
