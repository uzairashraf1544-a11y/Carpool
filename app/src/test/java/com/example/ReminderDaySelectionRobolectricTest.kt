package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.AppReminderSettings
import com.example.data.repository.UserPreferencesRepository
import com.example.service.CommuteReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderDaySelectionRobolectricTest {

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)
        repo.clearAllForTesting()
    }

    @Test
    fun testDataStoreDayPersistenceAndDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)

        // 1. Initial read: Defaults to Monday - Friday
        val initial = repo.getReminderSettingsFlow("test_user_1").first()
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, initial.slot0700Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, initial.slot1300Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, initial.slot1400Days)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, initial.slot1705Days)

        // 2. Save custom day combinations
        val custom = initial.copy(
            slot0700Days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            slot1300Days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            slot1400Days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            slot1705Days = DayOfWeek.entries.toSet()
        )
        repo.saveReminderSettings("test_user_1", custom)

        val retrieved = repo.getReminderSettingsFlow("test_user_1").first()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), retrieved.slot0700Days)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), retrieved.slot1300Days)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), retrieved.slot1400Days)
        assertEquals(DayOfWeek.entries.toSet(), retrieved.slot1705Days)
    }

    @Test
    fun testZeroDaysSelectedPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)

        val settings = repo.getReminderSettingsFlow("test_user_empty").first()
        val emptyDaysSettings = settings.copy(
            slot0700Days = emptySet()
        )
        repo.saveReminderSettings("test_user_empty", emptyDaysSettings)

        val retrieved = repo.getReminderSettingsFlow("test_user_empty").first()
        assertTrue(retrieved.slot0700Days.isEmpty())

        // Verify scheduler does not schedule when days are empty
        val nextTrigger = CommuteReminderScheduler.calculateNextTriggerDateTime(
            hour = retrieved.slot0700Hour,
            minute = retrieved.slot0700Minute,
            selectedDays = retrieved.slot0700Days
        )
        assertNull(nextTrigger)
    }

    @Test
    fun testResetTimesPreservesDays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)

        val custom = AppReminderSettings(
            slot0700Hour = 6,
            slot0700Minute = 45,
            slot0700Days = setOf(DayOfWeek.WEDNESDAY)
        )
        repo.saveReminderSettings("test_user_reset", custom)

        // Reset times
        val afterTimeReset = repo.resetReminderTimesToDefaults("test_user_reset")
        assertEquals(7, afterTimeReset.slot0700Hour)
        assertEquals(0, afterTimeReset.slot0700Minute)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), afterTimeReset.slot0700Days)
    }

    @Test
    fun testResetDaysPreservesTimes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)

        val custom = AppReminderSettings(
            slot0700Hour = 8,
            slot0700Minute = 15,
            slot0700Days = setOf(DayOfWeek.SUNDAY)
        )
        repo.saveReminderSettings("test_user_reset_days", custom)

        // Reset days
        val afterDaysReset = repo.resetReminderDaysToDefaults("test_user_reset_days")
        assertEquals(8, afterDaysReset.slot0700Hour)
        assertEquals(15, afterDaysReset.slot0700Minute)
        assertEquals(AppReminderSettings.DEFAULT_WEEKDAYS, afterDaysReset.slot0700Days)
    }

    @Test
    fun testUidScopedSettingsIsolation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = UserPreferencesRepository(context)

        val userASettings = AppReminderSettings(
            slot0700Days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        )
        val userBSettings = AppReminderSettings(
            slot0700Days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        )

        repo.saveReminderSettings("user_A", userASettings)
        repo.saveReminderSettings("user_B", userBSettings)

        val readA = repo.getReminderSettingsFlow("user_A").first()
        val readB = repo.getReminderSettingsFlow("user_B").first()

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), readA.slot0700Days)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), readB.slot0700Days)
    }
}
