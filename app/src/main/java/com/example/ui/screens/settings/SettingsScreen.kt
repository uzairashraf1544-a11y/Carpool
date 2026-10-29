package com.example.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.app.TimePickerDialog
import com.example.data.model.CommuteSlot
import com.example.data.model.PickupSide
import com.example.data.model.User
import com.example.data.repository.AppReminderSettings
import com.example.data.repository.UserPreferencesRepository
import com.example.service.CommuteReminderScheduler
import com.example.ui.components.PickupAreaSelector
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: User,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPreferencesRepository = remember(context) { UserPreferencesRepository(context) }

    val settings by userPreferencesRepository.getReminderSettingsFlow(user.uid).collectAsState(initial = AppReminderSettings())
    val lastTownshipPickup by userPreferencesRepository.getLastTownshipPickupFlow(user.uid).collectAsState(initial = "EMs")
    val lastPlantPickup by userPreferencesRepository.getLastPlantPickupFlow(user.uid).collectAsState(initial = "FFL Plant Main Gate")

    val hasNotificationPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showResetDaysConfirmDialog by remember { mutableStateOf(false) }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset Reminder Times?") },
            text = {
                Text("This will reset all 4 reminder times back to 07:00, 13:00, 14:00, and 17:05. Your selected days and enabled/disabled toggles will be preserved.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        scope.launch {
                            val newSettings = userPreferencesRepository.resetReminderTimesToDefaults(user.uid)
                            CommuteReminderScheduler.scheduleAllReminders(context, newSettings)
                        }
                    }
                ) {
                    Text("Reset Times", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDaysConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetDaysConfirmDialog = false },
            title = { Text("Reset Reminder Days?") },
            text = {
                Text("This will reset all 4 reminders to weekdays (Monday through Friday). Your configured times and enabled/disabled toggles will be preserved.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDaysConfirmDialog = false
                        scope.launch {
                            val newSettings = userPreferencesRepository.resetReminderDaysToDefaults(user.uid)
                            CommuteReminderScheduler.scheduleAllReminders(context, newSettings)
                        }
                    }
                ) {
                    Text("Reset Days", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDaysConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. COMMUTE REMINDERS MASTER TOGGLE
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Rounded.Schedule, contentDescription = null, tint = Primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Daily Commute Reminders", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Local check-in notifications for daily shift commutes", fontSize = 12.5.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = settings.masterEnabled,
                            onCheckedChange = { checked ->
                                val newSettings = settings.copy(masterEnabled = checked)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (checked) {
                                        CommuteReminderScheduler.scheduleAllReminders(context, newSettings)
                                    } else {
                                        CommuteReminderScheduler.cancelAllReminders(context)
                                    }
                                }
                            }
                        )
                    }

                    if (settings.masterEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Outline.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("COMMUTE REMINDERS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)

                        Spacer(modifier = Modifier.height(8.dp))

                        // Reminder 1: Morning (default 07:00)
                        ReminderSlotItem(
                            title = "Morning Reminder",
                            hour = settings.slot0700Hour,
                            minute = settings.slot0700Minute,
                            selectedDays = settings.slot0700Days,
                            directionInfo = "Township → Plant",
                            isEnabled = settings.slot0700Enabled,
                            onTimeChange = { h, m ->
                                val newSettings = settings.copy(slot0700Hour = h, slot0700Minute = m)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot0700Enabled && newSettings.slot0700Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_1,
                                            h,
                                            m,
                                            newSettings.slot0700Days
                                        )
                                    }
                                }
                            },
                            onDaysChange = { days ->
                                val newSettings = settings.copy(slot0700Days = days)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot0700Enabled) {
                                        if (days.isNotEmpty()) {
                                            CommuteReminderScheduler.scheduleReminder(
                                                context,
                                                CommuteReminderScheduler.REMINDER_1,
                                                newSettings.slot0700Hour,
                                                newSettings.slot0700Minute,
                                                days
                                            )
                                        } else {
                                            CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_1)
                                        }
                                    }
                                }
                            },
                            onEnabledChange = { enabled ->
                                val newSettings = settings.copy(slot0700Enabled = enabled)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (enabled && newSettings.slot0700Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_1,
                                            newSettings.slot0700Hour,
                                            newSettings.slot0700Minute,
                                            newSettings.slot0700Days
                                        )
                                    } else {
                                        CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_1)
                                    }
                                }
                            }
                        )

                        // Reminder 2: Lunch (default 13:00)
                        ReminderSlotItem(
                            title = "Lunch Reminder",
                            hour = settings.slot1300Hour,
                            minute = settings.slot1300Minute,
                            selectedDays = settings.slot1300Days,
                            directionInfo = "Plant → Township",
                            isEnabled = settings.slot1300Enabled,
                            onTimeChange = { h, m ->
                                val newSettings = settings.copy(slot1300Hour = h, slot1300Minute = m)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1300Enabled && newSettings.slot1300Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_2,
                                            h,
                                            m,
                                            newSettings.slot1300Days
                                        )
                                    }
                                }
                            },
                            onDaysChange = { days ->
                                val newSettings = settings.copy(slot1300Days = days)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1300Enabled) {
                                        if (days.isNotEmpty()) {
                                            CommuteReminderScheduler.scheduleReminder(
                                                context,
                                                CommuteReminderScheduler.REMINDER_2,
                                                newSettings.slot1300Hour,
                                                newSettings.slot1300Minute,
                                                days
                                            )
                                        } else {
                                            CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_2)
                                        }
                                    }
                                }
                            },
                            onEnabledChange = { enabled ->
                                val newSettings = settings.copy(slot1300Enabled = enabled)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (enabled && newSettings.slot1300Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_2,
                                            newSettings.slot1300Hour,
                                            newSettings.slot1300Minute,
                                            newSettings.slot1300Days
                                        )
                                    } else {
                                        CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_2)
                                    }
                                }
                            }
                        )

                        // Reminder 3: Afternoon (default 14:00)
                        ReminderSlotItem(
                            title = "Afternoon Reminder",
                            hour = settings.slot1400Hour,
                            minute = settings.slot1400Minute,
                            selectedDays = settings.slot1400Days,
                            directionInfo = "Township → Plant",
                            isEnabled = settings.slot1400Enabled,
                            onTimeChange = { h, m ->
                                val newSettings = settings.copy(slot1400Hour = h, slot1400Minute = m)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1400Enabled && newSettings.slot1400Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_3,
                                            h,
                                            m,
                                            newSettings.slot1400Days
                                        )
                                    }
                                }
                            },
                            onDaysChange = { days ->
                                val newSettings = settings.copy(slot1400Days = days)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1400Enabled) {
                                        if (days.isNotEmpty()) {
                                            CommuteReminderScheduler.scheduleReminder(
                                                context,
                                                CommuteReminderScheduler.REMINDER_3,
                                                newSettings.slot1400Hour,
                                                newSettings.slot1400Minute,
                                                days
                                            )
                                        } else {
                                            CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_3)
                                        }
                                    }
                                }
                            },
                            onEnabledChange = { enabled ->
                                val newSettings = settings.copy(slot1400Enabled = enabled)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (enabled && newSettings.slot1400Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_3,
                                            newSettings.slot1400Hour,
                                            newSettings.slot1400Minute,
                                            newSettings.slot1400Days
                                        )
                                    } else {
                                        CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_3)
                                    }
                                }
                            }
                        )

                        // Reminder 4: Evening (default 17:05)
                        ReminderSlotItem(
                            title = "Evening Reminder",
                            hour = settings.slot1705Hour,
                            minute = settings.slot1705Minute,
                            selectedDays = settings.slot1705Days,
                            directionInfo = "Plant → Township",
                            isEnabled = settings.slot1705Enabled,
                            onTimeChange = { h, m ->
                                val newSettings = settings.copy(slot1705Hour = h, slot1705Minute = m)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1705Enabled && newSettings.slot1705Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_4,
                                            h,
                                            m,
                                            newSettings.slot1705Days
                                        )
                                    }
                                }
                            },
                            onDaysChange = { days ->
                                val newSettings = settings.copy(slot1705Days = days)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (newSettings.slot1705Enabled) {
                                        if (days.isNotEmpty()) {
                                            CommuteReminderScheduler.scheduleReminder(
                                                context,
                                                CommuteReminderScheduler.REMINDER_4,
                                                newSettings.slot1705Hour,
                                                newSettings.slot1705Minute,
                                                days
                                            )
                                        } else {
                                            CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_4)
                                        }
                                    }
                                }
                            },
                            onEnabledChange = { enabled ->
                                val newSettings = settings.copy(slot1705Enabled = enabled)
                                scope.launch {
                                    userPreferencesRepository.saveReminderSettings(user.uid, newSettings)
                                    if (enabled && newSettings.slot1705Days.isNotEmpty()) {
                                        CommuteReminderScheduler.scheduleReminder(
                                            context,
                                            CommuteReminderScheduler.REMINDER_4,
                                            newSettings.slot1705Hour,
                                            newSettings.slot1705Minute,
                                            newSettings.slot1705Days
                                        )
                                    } else {
                                        CommuteReminderScheduler.cancelReminder(context, CommuteReminderScheduler.REMINDER_4)
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { showResetConfirmDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RESET TO DEFAULT TIMES", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { showResetDaysConfirmDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RESET DAYS TO WEEKDAYS", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 2. NOTIFICATION PERMISSION STATUS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, tint = Primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Android Notifications", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(
                                    if (hasNotificationPermission) "Allowed" else "Disabled in system settings",
                                    fontSize = 12.5.sp,
                                    color = if (hasNotificationPermission) Success else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (!hasNotificationPermission) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Settings")
                            }
                        }
                    }
                }
            }

            // 3. SAVED SIDE PICKUPS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("SAVED SIDE PICKUPS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                    Text("These locations are automatically used for one-tap commute check-ins.", fontSize = 12.5.sp, color = TextSecondary)

                    // Township Default Pickup
                    PickupAreaSelector(
                        selectedArea = lastTownshipPickup,
                        onAreaSelected = { newArea ->
                            scope.launch {
                                userPreferencesRepository.saveLastTownshipPickup(user.uid, newArea)
                            }
                        },
                        label = "Default Township Pickup (Morning / 14:00 Return)",
                        highlightSide = PickupSide.TOWNSHIP
                    )

                    // Plant Default Pickup
                    PickupAreaSelector(
                        selectedArea = lastPlantPickup,
                        onAreaSelected = { newArea ->
                            scope.launch {
                                userPreferencesRepository.saveLastPlantPickup(user.uid, newArea)
                            }
                        },
                        label = "Default Plant Pickup (13:00 Lunch / 17:05 Evening)",
                        highlightSide = PickupSide.PLANT
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderSlotItem(
    title: String,
    hour: Int,
    minute: Int,
    selectedDays: Set<DayOfWeek>,
    directionInfo: String,
    isEnabled: Boolean,
    onTimeChange: (Int, Int) -> Unit,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)

    val daysOfWeek = remember {
        listOf(
            DayOfWeek.MONDAY to "M",
            DayOfWeek.TUESDAY to "T",
            DayOfWeek.WEDNESDAY to "W",
            DayOfWeek.THURSDAY to "T",
            DayOfWeek.FRIDAY to "F",
            DayOfWeek.SATURDAY to "S",
            DayOfWeek.SUNDAY to "S"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, selectedHour, selectedMinute ->
                                    onTimeChange(selectedHour, selectedMinute)
                                },
                                hour,
                                minute,
                                true
                            ).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Primary.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = "Edit time",
                                tint = Primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formattedTime,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Primary
                            )
                        }
                    }
                    Text(
                        text = "• $directionInfo",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Active Days:",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            daysOfWeek.forEach { (day, label) ->
                val isSelected = selectedDays.contains(day)
                val fullDayName = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val semanticLabel = "$fullDayName ${if (isSelected) "selected" else "not selected"}"

                Surface(
                    onClick = {
                        val newSet = if (isSelected) {
                            selectedDays - day
                        } else {
                            selectedDays + day
                        }
                        onDaysChange(newSet)
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .semantics {
                            contentDescription = semanticLabel
                        },
                    shape = CircleShape,
                    color = if (isSelected) Primary else Surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) Primary else Outline.copy(alpha = 0.5f)
                    )
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else TextPrimary
                        )
                    }
                }
            }
        }

        if (selectedDays.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Select at least one day for this reminder.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Outline.copy(alpha = 0.35f))
    }
}

