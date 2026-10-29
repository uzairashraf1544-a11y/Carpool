package com.example

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TravelBuddyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        com.example.util.TravelBuddyNotificationManager.initChannels(this)
        com.example.service.CommuteReminderScheduler.cancelLegacyWorkManagerCommuteJobs(this)

        com.example.service.CommuteReminderScheduler.reconcileAllReminders(this)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
