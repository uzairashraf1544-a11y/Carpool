package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CommuteReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )

        if (action !in validActions) {
            Log.d(TAG, "IGNORED_ACTION | action=$action")
            return
        }

        Log.d(TAG, "BOOT_OR_TIME_BROADCAST_RECEIVED | action=$action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = UserPreferencesRepository(context)
                val settings = repo.getReminderSettingsFlow().first()
                CommuteReminderScheduler.scheduleAllReminders(context, settings)
                Log.d(TAG, "ALL_COMMUTE_REMINDERS_RESCHEDULED_ON_BOOT")
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling reminders on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CommuteBootReceiver"
    }
}
