package com.example

import android.app.Application
import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TravelBuddyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Configure persistent disk cache for Firestore to minimize server document reads
        try {
            val firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                        .build()
                )
                .build()
            FirebaseFirestore.getInstance().firestoreSettings = firestoreSettings
        } catch (e: Exception) {
            // Already initialized or running in unit test environment
        }

        com.example.util.TravelBuddyNotificationManager.initChannels(this)
        com.example.service.CommuteReminderScheduler.cancelLegacyWorkManagerCommuteJobs(this)

        com.example.service.CommuteReminderScheduler.reconcileAllReminders(this)
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
