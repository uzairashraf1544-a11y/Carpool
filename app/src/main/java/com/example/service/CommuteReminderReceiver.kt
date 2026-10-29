package com.example.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.MainActivity
import com.example.data.model.CommuteSlot
import com.example.data.repository.UserPreferencesRepository
import com.example.util.TravelBuddyNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class CommuteReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (action != CommuteReminderScheduler.ACTION_COMMUTE_REMINDER) {
            Log.d(TAG, "IGNORED_ACTION | action=$action")
            return
        }

        val reminderId = intent.getStringExtra(CommuteReminderScheduler.EXTRA_REMINDER_ID)
        if (reminderId.isNullOrBlank()) {
            Log.w(TAG, "MISSING_REMINDER_ID | action=$action")
            return
        }

        Log.d(TAG, "COMMUTE_ALARM_RECEIVED | reminderId=$reminderId")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = UserPreferencesRepository(context)
                val settings = repo.getReminderSettingsFlow().first()

                val slot = CommuteReminderScheduler.mapReminderIdToSlot(reminderId)

                val isSlotEnabled = when (reminderId) {
                    CommuteReminderScheduler.REMINDER_1, CommuteSlot.MORNING_TO_PLANT.id -> settings.slot0700Enabled
                    CommuteReminderScheduler.REMINDER_2, CommuteSlot.LUNCH_TO_TOWNSHIP.id -> settings.slot1300Enabled
                    CommuteReminderScheduler.REMINDER_3, CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> settings.slot1400Enabled
                    CommuteReminderScheduler.REMINDER_4, CommuteSlot.EVENING_TO_TOWNSHIP.id -> settings.slot1705Enabled
                    else -> false
                }

                val selectedDays = when (reminderId) {
                    CommuteReminderScheduler.REMINDER_1, CommuteSlot.MORNING_TO_PLANT.id -> settings.slot0700Days
                    CommuteReminderScheduler.REMINDER_2, CommuteSlot.LUNCH_TO_TOWNSHIP.id -> settings.slot1300Days
                    CommuteReminderScheduler.REMINDER_3, CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> settings.slot1400Days
                    CommuteReminderScheduler.REMINDER_4, CommuteSlot.EVENING_TO_TOWNSHIP.id -> settings.slot1705Days
                    else -> emptySet()
                }

                val now = LocalDateTime.now()
                val isDayAllowed = selectedDays.contains(now.dayOfWeek)

                if (settings.masterEnabled && isSlotEnabled && isDayAllowed) {
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(CommuteReminderScheduler.EXTRA_SLOT_ID, slot.id)
                        putExtra(CommuteReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        slot.id.hashCode(),
                        mainIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    TravelBuddyNotificationManager.showCommuteReminderNotification(
                        context = context,
                        slotId = slot.id,
                        title = slot.notificationTitle,
                        message = slot.notificationBody,
                        pendingIntent = pendingIntent
                    )
                } else if (!isDayAllowed) {
                    Log.d(TAG, "COMMUTE_NOTIFICATION_SUPPRESSED_UNSELECTED_DAY | reminder=$reminderId | day=${now.dayOfWeek}")
                }

                // Automatically reschedule the next occurrence for this reminder
                val hour = when (reminderId) {
                    CommuteReminderScheduler.REMINDER_1, CommuteSlot.MORNING_TO_PLANT.id -> settings.slot0700Hour
                    CommuteReminderScheduler.REMINDER_2, CommuteSlot.LUNCH_TO_TOWNSHIP.id -> settings.slot1300Hour
                    CommuteReminderScheduler.REMINDER_3, CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> settings.slot1400Hour
                    else -> settings.slot1705Hour
                }
                val minute = when (reminderId) {
                    CommuteReminderScheduler.REMINDER_1, CommuteSlot.MORNING_TO_PLANT.id -> settings.slot0700Minute
                    CommuteReminderScheduler.REMINDER_2, CommuteSlot.LUNCH_TO_TOWNSHIP.id -> settings.slot1300Minute
                    CommuteReminderScheduler.REMINDER_3, CommuteSlot.LUNCH_RETURN_TO_PLANT.id -> settings.slot1400Minute
                    else -> settings.slot1705Minute
                }
                if (settings.masterEnabled && isSlotEnabled && selectedDays.isNotEmpty()) {
                    CommuteReminderScheduler.scheduleReminder(context, reminderId, hour, minute, selectedDays)
                } else {
                    CommuteReminderScheduler.cancelReminder(context, reminderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling commute reminder broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CommuteAlarmReceiver"
    }
}
