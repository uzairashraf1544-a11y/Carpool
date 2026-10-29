package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CommuteSelection
import com.example.data.model.MasterData
import com.example.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeCommuteBottomSheet(
    initialSelection: CommuteSelection,
    onDismiss: () -> Unit,
    onApply: (CommuteSelection) -> Unit
) {
    var selectedDate by remember(initialSelection) { mutableStateOf(initialSelection.date) }
    var selectedDirection by remember(initialSelection) { mutableStateOf(initialSelection.direction) }
    var selectedWindow by remember(initialSelection) { mutableStateOf(initialSelection.commuteWindow) }
    var selectedPickup by remember(initialSelection) { mutableStateOf(initialSelection.pickupZone) }
    var selectedDestination by remember(initialSelection) { mutableStateOf(initialSelection.destinationZone) }
    var showDatePicker by remember { mutableStateOf(false) }

    val windows = remember(selectedDirection) { MasterData.windowsForDirection(selectedDirection) }
    val pickupOptions = remember(selectedDirection) { MasterData.pickupLocationsFor(selectedDirection) }
    val destinationOptions = remember(selectedDirection) { MasterData.destinationLocationsFor(selectedDirection) }

    // Keep dependent selections valid when direction changes.
    LaunchedEffect(selectedDirection) {
        if (windows.none { it.name == selectedWindow || it.timeSlot == selectedWindow }) {
            selectedWindow = windows.firstOrNull()?.name.orEmpty()
        }
        if (selectedPickup !in pickupOptions) selectedPickup = pickupOptions.firstOrNull().orEmpty()
        if (selectedDestination !in destinationOptions) selectedDestination = destinationOptions.firstOrNull().orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CHANGE COMMUTE",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Choose when and where you want to travel",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("DATE")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectableChip(
                    text = "Today",
                    isSelected = selectedDate == LocalDate.now(),
                    onClick = { selectedDate = LocalDate.now() }
                )
                SelectableChip(
                    text = "Tomorrow",
                    isSelected = selectedDate == LocalDate.now().plusDays(1),
                    onClick = { selectedDate = LocalDate.now().plusDays(1) }
                )
                SelectableChip(
                    text = selectedDate.toString(),
                    isSelected = selectedDate != LocalDate.now() && selectedDate != LocalDate.now().plusDays(1),
                    onClick = { showDatePicker = true }
                )
                SelectableChip(
                    text = "Select date",
                    isSelected = false,
                    onClick = { showDatePicker = true }
                )
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("DIRECTION")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionRow(
                    text = "Township → Plant",
                    isSelected = selectedDirection == "Township → Plant",
                    onClick = { selectedDirection = "Township → Plant" }
                )
                DirectionRow(
                    text = "Plant → Township",
                    isSelected = selectedDirection == "Plant → Township",
                    onClick = { selectedDirection = "Plant → Township" }
                )
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("COMMUTE WINDOW")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                windows.forEach { window ->
                    SelectionRow(
                        title = window.name,
                        subtitle = window.timeSlot,
                        isSelected = selectedWindow == window.name || selectedWindow == window.timeSlot,
                        onClick = { selectedWindow = window.name }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("PICKUP ZONE")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pickupOptions.forEach { loc ->
                    SelectableChip(
                        text = loc,
                        isSelected = selectedPickup == loc,
                        onClick = { selectedPickup = loc }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            SectionLabel("DESTINATION")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                destinationOptions.forEach { loc ->
                    SelectionRow(
                        title = loc,
                        subtitle = null,
                        isSelected = selectedDestination == loc,
                        onClick = { selectedDestination = loc }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Text("Cancel", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        if (
                            selectedWindow.isNotBlank() &&
                            selectedPickup.isNotBlank() &&
                            selectedDestination.isNotBlank()
                        ) {
                            onApply(
                                CommuteSelection(
                                    date = selectedDate,
                                    direction = selectedDirection,
                                    commuteWindow = MasterData.windowByValue(selectedWindow)?.name ?: selectedWindow,
                                    pickupZone = selectedPickup,
                                    destinationZone = selectedDestination
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Text("Apply Commute", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = PrimaryDark,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun SelectableChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) PrimaryDark else SurfaceVariant)
            .border(1.dp, if (isSelected) PrimaryDark else Outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun DirectionRow(text: String, isSelected: Boolean, onClick: () -> Unit) {
    SelectionRow(title = text, subtitle = null, isSelected = isSelected, onClick = onClick)
}

@Composable
private fun SelectionRow(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) PrimaryLight else SurfaceVariant)
            .border(1.dp, if (isSelected) Primary else Outline.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isSelected) PrimaryDark else TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = TextSecondary, fontSize = 12.5.sp)
            }
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}
