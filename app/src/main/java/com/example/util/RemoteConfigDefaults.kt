package com.example.util

import android.util.Log

/**
 * Remote switches for Firestore limit management.
 * In safe mode, initial query limits are halved and cache TTLs are quadrupled to survive unexpected load spikes.
 */
object RemoteConfigDefaults {
    private const val TAG = "RemoteConfigDefaults"

    var readsSafeMode: Boolean = false
        private set

    var discoveryInitialLimit: Long = 10L
        private set

    var adminLiveOverviewEnabled: Boolean = true
        private set

    var leaderboardCacheHours: Long = 6L
        private set

    fun applyConfiguration(
        safeMode: Boolean = false,
        customDiscoveryLimit: Long? = null,
        customOverviewEnabled: Boolean? = null,
        customLeaderboardCacheHours: Long? = null
    ) {
        readsSafeMode = safeMode
        if (safeMode) {
            discoveryInitialLimit = 5L
            adminLiveOverviewEnabled = false
            leaderboardCacheHours = 24L
            Log.w(TAG, "SAFE_MODE_ENABLED: discoveryInitialLimit=5, adminLiveOverviewEnabled=false, leaderboardCacheHours=24h")
        } else {
            discoveryInitialLimit = customDiscoveryLimit ?: 10L
            adminLiveOverviewEnabled = customOverviewEnabled ?: true
            leaderboardCacheHours = customLeaderboardCacheHours ?: 6L
            Log.i(TAG, "NORMAL_MODE: discoveryInitialLimit=$discoveryInitialLimit, adminLiveOverviewEnabled=$adminLiveOverviewEnabled, leaderboardCacheHours=$leaderboardCacheHours")
        }
    }
}
