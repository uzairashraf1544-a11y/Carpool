package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import com.example.data.model.LocalTravelState
import com.example.data.model.MasterData
import com.example.data.model.PickupSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "travelbuddy_user_prefs")

data class DriverRoutePreferences(
    val direction: String? = null,
    val pickupZone: String? = null,
    val destinationZone: String? = null,
    val commuteWindow: String? = null,
    val pickupDetails: String = "",
    val message: String = ""
)

data class AppReminderSettings(
    val masterEnabled: Boolean = true,
    val slot0700Enabled: Boolean = true,
    val slot0700Hour: Int = 7,
    val slot0700Minute: Int = 0,
    val slot0700Days: Set<DayOfWeek> = DEFAULT_WEEKDAYS,
    val slot1300Enabled: Boolean = true,
    val slot1300Hour: Int = 13,
    val slot1300Minute: Int = 0,
    val slot1300Days: Set<DayOfWeek> = DEFAULT_WEEKDAYS,
    val slot1400Enabled: Boolean = true,
    val slot1400Hour: Int = 14,
    val slot1400Minute: Int = 0,
    val slot1400Days: Set<DayOfWeek> = DEFAULT_WEEKDAYS,
    val slot1705Enabled: Boolean = true,
    val slot1705Hour: Int = 17,
    val slot1705Minute: Int = 5,
    val slot1705Days: Set<DayOfWeek> = DEFAULT_WEEKDAYS
) {
    companion object {
        val DEFAULT_WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
    }
}

class UserPreferencesRepository(private val context: Context) {

    private val KEY_MASTER_REMINDERS = booleanPreferencesKey("master_reminders_enabled")
    private val KEY_LEGACY_MASTER_REMINDERS = booleanPreferencesKey("reminders_master_enabled")

    private val KEY_REMINDER_1_ENABLED = booleanPreferencesKey("reminder_1_enabled")
    private val KEY_REMINDER_1_HOUR = intPreferencesKey("reminder_1_hour")
    private val KEY_REMINDER_1_MINUTE = intPreferencesKey("reminder_1_minute")
    private val KEY_REMINDER_1_DAYS = stringSetPreferencesKey("reminder_1_days")

    private val KEY_REMINDER_2_ENABLED = booleanPreferencesKey("reminder_2_enabled")
    private val KEY_REMINDER_2_HOUR = intPreferencesKey("reminder_2_hour")
    private val KEY_REMINDER_2_MINUTE = intPreferencesKey("reminder_2_minute")
    private val KEY_REMINDER_2_DAYS = stringSetPreferencesKey("reminder_2_days")

    private val KEY_REMINDER_3_ENABLED = booleanPreferencesKey("reminder_3_enabled")
    private val KEY_REMINDER_3_HOUR = intPreferencesKey("reminder_3_hour")
    private val KEY_REMINDER_3_MINUTE = intPreferencesKey("reminder_3_minute")
    private val KEY_REMINDER_3_DAYS = stringSetPreferencesKey("reminder_3_days")

    private val KEY_REMINDER_4_ENABLED = booleanPreferencesKey("reminder_4_enabled")
    private val KEY_REMINDER_4_HOUR = intPreferencesKey("reminder_4_hour")
    private val KEY_REMINDER_4_MINUTE = intPreferencesKey("reminder_4_minute")
    private val KEY_REMINDER_4_DAYS = stringSetPreferencesKey("reminder_4_days")

    // Legacy keys for fallback migration
    private val KEY_SLOT_0700 = booleanPreferencesKey("reminder_slot_0700_enabled")
    private val KEY_SLOT_0700_HOUR = intPreferencesKey("reminder_slot_0700_hour")
    private val KEY_SLOT_0700_MINUTE = intPreferencesKey("reminder_slot_0700_minute")

    private val KEY_SLOT_1300 = booleanPreferencesKey("reminder_slot_1300_enabled")
    private val KEY_SLOT_1300_HOUR = intPreferencesKey("reminder_slot_1300_hour")
    private val KEY_SLOT_1300_MINUTE = intPreferencesKey("reminder_slot_1300_minute")

    private val KEY_SLOT_1400 = booleanPreferencesKey("reminder_slot_1400_enabled")
    private val KEY_SLOT_1400_HOUR = intPreferencesKey("reminder_slot_1400_hour")
    private val KEY_SLOT_1400_MINUTE = intPreferencesKey("reminder_slot_1400_minute")

    private val KEY_SLOT_1705 = booleanPreferencesKey("reminder_slot_1705_enabled")
    private val KEY_SLOT_1705_HOUR = intPreferencesKey("reminder_slot_1705_hour")
    private val KEY_SLOT_1705_MINUTE = intPreferencesKey("reminder_slot_1705_minute")

    private fun parseDaysFromPrefs(
        prefs: Preferences,
        primaryKey: Preferences.Key<Set<String>>,
        fallbackKey: Preferences.Key<Set<String>>? = null
    ): Set<DayOfWeek> {
        val raw = when {
            prefs.contains(primaryKey) -> prefs[primaryKey]
            fallbackKey != null && prefs.contains(fallbackKey) && !prefs[fallbackKey].isNullOrEmpty() -> prefs[fallbackKey]
            else -> null
        } ?: return AppReminderSettings.DEFAULT_WEEKDAYS

        return raw.mapNotNull { name ->
            try {
                DayOfWeek.valueOf(name.trim().uppercase())
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }

    suspend fun clearAllForTesting() {
        context.dataStore.edit { it.clear() }
    }

    fun getReminderSettingsFlow(uid: String = ""): Flow<AppReminderSettings> {
        val masterKey = if (uid.isNotBlank()) booleanPreferencesKey("master_reminders_enabled_$uid") else KEY_MASTER_REMINDERS
        val legacyMasterKey = if (uid.isNotBlank()) booleanPreferencesKey("reminders_master_enabled_$uid") else KEY_LEGACY_MASTER_REMINDERS

        val r1EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_1_enabled_$uid") else KEY_REMINDER_1_ENABLED
        val r1HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_1_hour_$uid") else KEY_REMINDER_1_HOUR
        val r1MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_1_minute_$uid") else KEY_REMINDER_1_MINUTE
        val r1DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_1_days_$uid") else KEY_REMINDER_1_DAYS
        val legacyR1EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_slot_0700_enabled_$uid") else KEY_SLOT_0700
        val legacyR1HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_0700_hour_$uid") else KEY_SLOT_0700_HOUR
        val legacyR1MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_0700_minute_$uid") else KEY_SLOT_0700_MINUTE

        val r2EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_2_enabled_$uid") else KEY_REMINDER_2_ENABLED
        val r2HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_2_hour_$uid") else KEY_REMINDER_2_HOUR
        val r2MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_2_minute_$uid") else KEY_REMINDER_2_MINUTE
        val r2DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_2_days_$uid") else KEY_REMINDER_2_DAYS
        val legacyR2EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_slot_1300_enabled_$uid") else KEY_SLOT_1300
        val legacyR2HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1300_hour_$uid") else KEY_SLOT_1300_HOUR
        val legacyR2MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1300_minute_$uid") else KEY_SLOT_1300_MINUTE

        val r3EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_3_enabled_$uid") else KEY_REMINDER_3_ENABLED
        val r3HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_3_hour_$uid") else KEY_REMINDER_3_HOUR
        val r3MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_3_minute_$uid") else KEY_REMINDER_3_MINUTE
        val r3DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_3_days_$uid") else KEY_REMINDER_3_DAYS
        val legacyR3EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_slot_1400_enabled_$uid") else KEY_SLOT_1400
        val legacyR3HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1400_hour_$uid") else KEY_SLOT_1400_HOUR
        val legacyR3MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1400_minute_$uid") else KEY_SLOT_1400_MINUTE

        val r4EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_4_enabled_$uid") else KEY_REMINDER_4_ENABLED
        val r4HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_4_hour_$uid") else KEY_REMINDER_4_HOUR
        val r4MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_4_minute_$uid") else KEY_REMINDER_4_MINUTE
        val r4DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_4_days_$uid") else KEY_REMINDER_4_DAYS
        val legacyR4EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_slot_1705_enabled_$uid") else KEY_SLOT_1705
        val legacyR4HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1705_hour_$uid") else KEY_SLOT_1705_HOUR
        val legacyR4MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_slot_1705_minute_$uid") else KEY_SLOT_1705_MINUTE

        return context.dataStore.data.map { prefs ->
            AppReminderSettings(
                masterEnabled = prefs[masterKey] ?: prefs[legacyMasterKey] ?: prefs[KEY_MASTER_REMINDERS] ?: prefs[KEY_LEGACY_MASTER_REMINDERS] ?: true,
                slot0700Enabled = prefs[r1EnabledKey] ?: prefs[legacyR1EnabledKey] ?: prefs[KEY_REMINDER_1_ENABLED] ?: prefs[KEY_SLOT_0700] ?: true,
                slot0700Hour = prefs[r1HourKey] ?: prefs[legacyR1HourKey] ?: prefs[KEY_REMINDER_1_HOUR] ?: prefs[KEY_SLOT_0700_HOUR] ?: 7,
                slot0700Minute = prefs[r1MinuteKey] ?: prefs[legacyR1MinuteKey] ?: prefs[KEY_REMINDER_1_MINUTE] ?: prefs[KEY_SLOT_0700_MINUTE] ?: 0,
                slot0700Days = parseDaysFromPrefs(prefs, r1DaysKey, KEY_REMINDER_1_DAYS),
                slot1300Enabled = prefs[r2EnabledKey] ?: prefs[legacyR2EnabledKey] ?: prefs[KEY_REMINDER_2_ENABLED] ?: prefs[KEY_SLOT_1300] ?: true,
                slot1300Hour = prefs[r2HourKey] ?: prefs[legacyR2HourKey] ?: prefs[KEY_REMINDER_2_HOUR] ?: prefs[KEY_SLOT_1300_HOUR] ?: 13,
                slot1300Minute = prefs[r2MinuteKey] ?: prefs[legacyR2MinuteKey] ?: prefs[KEY_REMINDER_2_MINUTE] ?: prefs[KEY_SLOT_1300_MINUTE] ?: 0,
                slot1300Days = parseDaysFromPrefs(prefs, r2DaysKey, KEY_REMINDER_2_DAYS),
                slot1400Enabled = prefs[r3EnabledKey] ?: prefs[legacyR3EnabledKey] ?: prefs[KEY_REMINDER_3_ENABLED] ?: prefs[KEY_SLOT_1400] ?: true,
                slot1400Hour = prefs[r3HourKey] ?: prefs[legacyR3HourKey] ?: prefs[KEY_REMINDER_3_HOUR] ?: prefs[KEY_SLOT_1400_HOUR] ?: 14,
                slot1400Minute = prefs[r3MinuteKey] ?: prefs[legacyR3MinuteKey] ?: prefs[KEY_REMINDER_3_MINUTE] ?: prefs[KEY_SLOT_1400_MINUTE] ?: 0,
                slot1400Days = parseDaysFromPrefs(prefs, r3DaysKey, KEY_REMINDER_3_DAYS),
                slot1705Enabled = prefs[r4EnabledKey] ?: prefs[legacyR4EnabledKey] ?: prefs[KEY_REMINDER_4_ENABLED] ?: prefs[KEY_SLOT_1705] ?: true,
                slot1705Hour = prefs[r4HourKey] ?: prefs[legacyR4HourKey] ?: prefs[KEY_REMINDER_4_HOUR] ?: prefs[KEY_SLOT_1705_HOUR] ?: 17,
                slot1705Minute = prefs[r4MinuteKey] ?: prefs[legacyR4MinuteKey] ?: prefs[KEY_REMINDER_4_MINUTE] ?: prefs[KEY_SLOT_1705_MINUTE] ?: 5,
                slot1705Days = parseDaysFromPrefs(prefs, r4DaysKey, KEY_REMINDER_4_DAYS)
            )
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    suspend fun saveReminderSettings(uid: String = "", settings: AppReminderSettings) {
        val masterKey = if (uid.isNotBlank()) booleanPreferencesKey("master_reminders_enabled_$uid") else KEY_MASTER_REMINDERS
        val legacyMasterKey = if (uid.isNotBlank()) booleanPreferencesKey("reminders_master_enabled_$uid") else KEY_LEGACY_MASTER_REMINDERS

        val r1EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_1_enabled_$uid") else KEY_REMINDER_1_ENABLED
        val r1HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_1_hour_$uid") else KEY_REMINDER_1_HOUR
        val r1MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_1_minute_$uid") else KEY_REMINDER_1_MINUTE
        val r1DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_1_days_$uid") else KEY_REMINDER_1_DAYS

        val r2EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_2_enabled_$uid") else KEY_REMINDER_2_ENABLED
        val r2HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_2_hour_$uid") else KEY_REMINDER_2_HOUR
        val r2MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_2_minute_$uid") else KEY_REMINDER_2_MINUTE
        val r2DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_2_days_$uid") else KEY_REMINDER_2_DAYS

        val r3EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_3_enabled_$uid") else KEY_REMINDER_3_ENABLED
        val r3HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_3_hour_$uid") else KEY_REMINDER_3_HOUR
        val r3MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_3_minute_$uid") else KEY_REMINDER_3_MINUTE
        val r3DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_3_days_$uid") else KEY_REMINDER_3_DAYS

        val r4EnabledKey = if (uid.isNotBlank()) booleanPreferencesKey("reminder_4_enabled_$uid") else KEY_REMINDER_4_ENABLED
        val r4HourKey = if (uid.isNotBlank()) intPreferencesKey("reminder_4_hour_$uid") else KEY_REMINDER_4_HOUR
        val r4MinuteKey = if (uid.isNotBlank()) intPreferencesKey("reminder_4_minute_$uid") else KEY_REMINDER_4_MINUTE
        val r4DaysKey = if (uid.isNotBlank()) stringSetPreferencesKey("reminder_4_days_$uid") else KEY_REMINDER_4_DAYS

        val r1DaysStrings = settings.slot0700Days.map { it.name }.toSet()
        val r2DaysStrings = settings.slot1300Days.map { it.name }.toSet()
        val r3DaysStrings = settings.slot1400Days.map { it.name }.toSet()
        val r4DaysStrings = settings.slot1705Days.map { it.name }.toSet()

        context.dataStore.edit { prefs ->
            // Save UID-scoped keys
            prefs[masterKey] = settings.masterEnabled
            prefs[legacyMasterKey] = settings.masterEnabled

            prefs[r1EnabledKey] = settings.slot0700Enabled
            prefs[r1HourKey] = settings.slot0700Hour
            prefs[r1MinuteKey] = settings.slot0700Minute
            prefs[r1DaysKey] = r1DaysStrings

            prefs[r2EnabledKey] = settings.slot1300Enabled
            prefs[r2HourKey] = settings.slot1300Hour
            prefs[r2MinuteKey] = settings.slot1300Minute
            prefs[r2DaysKey] = r2DaysStrings

            prefs[r3EnabledKey] = settings.slot1400Enabled
            prefs[r3HourKey] = settings.slot1400Hour
            prefs[r3MinuteKey] = settings.slot1400Minute
            prefs[r3DaysKey] = r3DaysStrings

            prefs[r4EnabledKey] = settings.slot1705Enabled
            prefs[r4HourKey] = settings.slot1705Hour
            prefs[r4MinuteKey] = settings.slot1705Minute
            prefs[r4DaysKey] = r4DaysStrings

            // Also synchronize global fallback keys so background receivers without UID context read current settings
            prefs[KEY_MASTER_REMINDERS] = settings.masterEnabled
            prefs[KEY_LEGACY_MASTER_REMINDERS] = settings.masterEnabled

            prefs[KEY_REMINDER_1_ENABLED] = settings.slot0700Enabled
            prefs[KEY_REMINDER_1_HOUR] = settings.slot0700Hour
            prefs[KEY_REMINDER_1_MINUTE] = settings.slot0700Minute
            prefs[KEY_REMINDER_1_DAYS] = r1DaysStrings
            prefs[KEY_SLOT_0700] = settings.slot0700Enabled
            prefs[KEY_SLOT_0700_HOUR] = settings.slot0700Hour
            prefs[KEY_SLOT_0700_MINUTE] = settings.slot0700Minute

            prefs[KEY_REMINDER_2_ENABLED] = settings.slot1300Enabled
            prefs[KEY_REMINDER_2_HOUR] = settings.slot1300Hour
            prefs[KEY_REMINDER_2_MINUTE] = settings.slot1300Minute
            prefs[KEY_REMINDER_2_DAYS] = r2DaysStrings
            prefs[KEY_SLOT_1300] = settings.slot1300Enabled
            prefs[KEY_SLOT_1300_HOUR] = settings.slot1300Hour
            prefs[KEY_SLOT_1300_MINUTE] = settings.slot1300Minute

            prefs[KEY_REMINDER_3_ENABLED] = settings.slot1400Enabled
            prefs[KEY_REMINDER_3_HOUR] = settings.slot1400Hour
            prefs[KEY_REMINDER_3_MINUTE] = settings.slot1400Minute
            prefs[KEY_REMINDER_3_DAYS] = r3DaysStrings
            prefs[KEY_SLOT_1400] = settings.slot1400Enabled
            prefs[KEY_SLOT_1400_HOUR] = settings.slot1400Hour
            prefs[KEY_SLOT_1400_MINUTE] = settings.slot1400Minute

            prefs[KEY_REMINDER_4_ENABLED] = settings.slot1705Enabled
            prefs[KEY_REMINDER_4_HOUR] = settings.slot1705Hour
            prefs[KEY_REMINDER_4_MINUTE] = settings.slot1705Minute
            prefs[KEY_REMINDER_4_DAYS] = r4DaysStrings
            prefs[KEY_SLOT_1705] = settings.slot1705Enabled
            prefs[KEY_SLOT_1705_HOUR] = settings.slot1705Hour
            prefs[KEY_SLOT_1705_MINUTE] = settings.slot1705Minute
        }
    }

    suspend fun saveReminderSettings(settings: AppReminderSettings) {
        saveReminderSettings(uid = "", settings = settings)
    }

    suspend fun resetReminderTimesToDefaults(uid: String = ""): AppReminderSettings {
        val current = getReminderSettingsFlow(uid).first()
        val resetSettings = current.copy(
            slot0700Hour = 7,
            slot0700Minute = 0,
            slot1300Hour = 13,
            slot1300Minute = 0,
            slot1400Hour = 14,
            slot1400Minute = 0,
            slot1705Hour = 17,
            slot1705Minute = 5
        )
        saveReminderSettings(uid, resetSettings)
        return resetSettings
    }

    suspend fun resetReminderDaysToDefaults(uid: String = ""): AppReminderSettings {
        val current = getReminderSettingsFlow(uid).first()
        val resetSettings = current.copy(
            slot0700Days = AppReminderSettings.DEFAULT_WEEKDAYS,
            slot1300Days = AppReminderSettings.DEFAULT_WEEKDAYS,
            slot1400Days = AppReminderSettings.DEFAULT_WEEKDAYS,
            slot1705Days = AppReminderSettings.DEFAULT_WEEKDAYS
        )
        saveReminderSettings(uid, resetSettings)
        return resetSettings
    }

    fun getLastTownshipPickupFlow(uid: String): Flow<String> {
        val prefKey = stringPreferencesKey("last_township_pickup_$uid")
        return context.dataStore.data.map { prefs ->
            prefs[prefKey] ?: "EMs"
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    fun getLastPlantPickupFlow(uid: String): Flow<String> {
        val prefKey = stringPreferencesKey("last_plant_pickup_$uid")
        return context.dataStore.data.map { prefs ->
            prefs[prefKey] ?: "FFL Plant Main Gate"
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    fun getLastPickupForSideFlow(uid: String, side: PickupSide): Flow<String> {
        return if (side == PickupSide.TOWNSHIP) {
            getLastTownshipPickupFlow(uid)
        } else {
            getLastPlantPickupFlow(uid)
        }
    }

    suspend fun saveLastTownshipPickup(uid: String, pickup: String) {
        if (uid.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("last_township_pickup_$uid")] = pickup
        }
    }

    suspend fun saveLastPlantPickup(uid: String, pickup: String) {
        if (uid.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("last_plant_pickup_$uid")] = pickup
        }
    }

    suspend fun savePickupForSide(uid: String, pickup: String) {
        if (uid.isBlank()) return
        val side = MasterData.getSideForPickup(pickup)
        if (side == PickupSide.TOWNSHIP) {
            saveLastTownshipPickup(uid, pickup)
        } else {
            saveLastPlantPickup(uid, pickup)
        }
    }

    // Backward compatibility helpers
    fun getDirectionPickupFlow(uid: String, direction: String): Flow<String?> {
        val side = if (direction == "Township → Plant") PickupSide.TOWNSHIP else PickupSide.PLANT
        return getLastPickupForSideFlow(uid, side).map { it }
    }

    suspend fun saveDirectionPickup(uid: String, direction: String, pickupZone: String) {
        savePickupForSide(uid, pickupZone)
    }

    fun getDriverPreferencesFlow(uid: String): Flow<DriverRoutePreferences> {
        val dirKey = stringPreferencesKey("driver_dir_$uid")
        val pickKey = stringPreferencesKey("driver_pick_$uid")
        val destKey = stringPreferencesKey("driver_dest_$uid")
        val winKey = stringPreferencesKey("driver_win_$uid")
        val detKey = stringPreferencesKey("driver_det_$uid")
        val msgKey = stringPreferencesKey("driver_msg_$uid")

        return context.dataStore.data.map { prefs ->
            DriverRoutePreferences(
                direction = prefs[dirKey],
                pickupZone = prefs[pickKey],
                destinationZone = prefs[destKey],
                commuteWindow = prefs[winKey],
                pickupDetails = prefs[detKey].orEmpty(),
                message = prefs[msgKey].orEmpty()
            )
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    suspend fun saveDriverPreferences(
        uid: String,
        direction: String,
        pickupZone: String,
        destinationZone: String,
        commuteWindow: String,
        pickupDetails: String = "",
        message: String = ""
    ) {
        if (uid.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("driver_dir_$uid")] = direction
            prefs[stringPreferencesKey("driver_pick_$uid")] = pickupZone
            prefs[stringPreferencesKey("driver_dest_$uid")] = destinationZone
            prefs[stringPreferencesKey("driver_win_$uid")] = commuteWindow
            prefs[stringPreferencesKey("driver_det_$uid")] = pickupDetails
            prefs[stringPreferencesKey("driver_msg_$uid")] = message
        }
        savePickupForSide(uid, pickupZone)
    }

    fun getLastUiModeFlow(uid: String): Flow<String?> {
        if (uid.isBlank()) return kotlinx.coroutines.flow.flowOf(null)
        val prefKey = stringPreferencesKey("last_ui_mode_$uid")
        return context.dataStore.data.map { prefs ->
            prefs[prefKey]
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    suspend fun saveLastUiMode(uid: String, mode: String) {
        if (uid.isBlank()) return
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[stringPreferencesKey("last_ui_mode_$uid")] = mode
            }
        }.onFailure { error ->
            android.util.Log.w("ModeSwitchDebug", "Failed to persist last_ui_mode for uid=$uid", error)
        }
    }

    fun getLocalTravelStateFlow(uid: String): Flow<LocalTravelState> {
        if (uid.isBlank()) return kotlinx.coroutines.flow.flowOf(LocalTravelState.NONE)
        val stateKey = stringPreferencesKey("active_travel_state_$uid")
        return context.dataStore.data.map { prefs ->
            val raw = prefs[stateKey]
            try {
                if (raw != null) LocalTravelState.valueOf(raw) else LocalTravelState.NONE
            } catch (e: Exception) {
                LocalTravelState.NONE
            }
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    suspend fun saveLocalTravelState(uid: String, state: LocalTravelState, postId: String = "") {
        if (uid.isBlank()) return
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[stringPreferencesKey("active_travel_state_$uid")] = state.name
                if (postId.isNotBlank()) {
                    prefs[stringPreferencesKey("active_post_id_$uid")] = postId
                } else if (state == LocalTravelState.NONE) {
                    prefs.remove(stringPreferencesKey("active_post_id_$uid"))
                }
            }
        }.onFailure { e ->
            android.util.Log.w("LocalTravelStateDebug", "Failed to save local travel state for uid=$uid", e)
        }
    }

    suspend fun clearLocalTravelState(uid: String) {
        saveLocalTravelState(uid, LocalTravelState.NONE, "")
    }
}
