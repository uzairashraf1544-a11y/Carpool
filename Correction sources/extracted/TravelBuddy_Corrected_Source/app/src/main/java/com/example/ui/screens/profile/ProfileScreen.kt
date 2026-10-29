package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MasterData
import com.example.data.model.User
import com.example.data.repository.UserRepository
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: User,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    userRepository: UserRepository = remember { UserRepository() }
) {
    val coroutineScope = rememberCoroutineScope()
    val authEmail = FirebaseAuth.getInstance().currentUser?.email ?: user.email
    val snackbarHostState = remember { SnackbarHostState() }

    var isEditing by remember { mutableStateOf(false) }

    // Draft edit state
    var nameDraft by remember { mutableStateOf(user.name) }
    var phoneDraft by remember { mutableStateOf(user.phone) }
    var deptDraft by remember { mutableStateOf(user.department) }
    var townshipDraft by remember { mutableStateOf(user.townshipLocation) }
    var defaultModeDraft by remember { mutableStateOf(user.defaultRolePreference) }
    var defaultSeatsDraft by remember { mutableStateOf(user.defaultDriverSeats) }

    var deptExpanded by remember { mutableStateOf(false) }
    var townshipExpanded by remember { mutableStateOf(false) }

    var actionError by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf<String?>(null) }
    var showMuteDialog by remember { mutableStateOf(false) }

    // Sync state only when not editing
    LaunchedEffect(user) {
        if (!isEditing) {
            nameDraft = user.name
            phoneDraft = user.phone
            deptDraft = user.department
            townshipDraft = user.townshipLocation
            defaultModeDraft = user.defaultRolePreference
            defaultSeatsDraft = user.defaultDriverSeats
        }
    }

    LaunchedEffect(actionError) {
        actionError?.let { err ->
            snackbarHostState.showSnackbar(err)
            actionError = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Profile Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(PrimaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase(),
                        color = PrimaryDark,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(user.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("${user.employeeId.ifBlank { "N/A" }} • ${user.department}", fontSize = 14.sp, color = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            actionSuccess?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SuccessContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(msg, color = Success, modifier = Modifier.padding(12.dp), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ACCOUNT CARD (Read Only Email & Employee ID)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACCOUNT", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Email Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Email Address", fontSize = 12.5.sp, color = TextSecondary)
                            Text(authEmail.ifBlank { "Not configured" }, fontSize = 15.5.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        }
                        Icon(imageVector = Icons.Rounded.Lock, contentDescription = "Read only", tint = TextTertiary, modifier = Modifier.size(18.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Employee ID Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Employee ID", fontSize = 12.5.sp, color = TextSecondary)
                            Text(user.employeeId.ifBlank { "N/A" }, fontSize = 15.5.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        }
                        Icon(imageVector = Icons.Rounded.Lock, contentDescription = "Read only", tint = TextTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // PERSONAL INFORMATION CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PERSONAL INFORMATION", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                        Text(
                            text = if (isEditing) "Cancel" else "Edit",
                            color = Primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                if (isEditing) {
                                    // Reset draft on cancel
                                    nameDraft = user.name
                                    phoneDraft = user.phone
                                    deptDraft = user.department
                                    townshipDraft = user.townshipLocation
                                    defaultModeDraft = user.defaultRolePreference
                                    defaultSeatsDraft = user.defaultDriverSeats
                                }
                                isEditing = !isEditing
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isEditing) {
                        InfoReadRow(label = "Full Name", value = user.name)
                        InfoReadRow(label = "Phone Number", value = user.phone.ifBlank { "Not provided" })
                        InfoReadRow(label = "Department", value = user.department)
                        InfoReadRow(label = "Township Location", value = user.townshipLocation)

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Divider)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("TRAVEL PREFERENCES", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoReadRow(
                            label = "Default Travel Mode",
                            value = if (user.defaultRolePreference == User.MODE_DRIVER) "Driver" else "Passenger"
                        )
                        InfoReadRow(label = "Default Driver Seats", value = user.defaultDriverSeats.toString())
                    } else {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("Full Name") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = phoneDraft,
                            onValueChange = { phoneDraft = it },
                            label = { Text("Phone Number") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        ExposedDropdownMenuBox(
                            expanded = deptExpanded,
                            onExpandedChange = { deptExpanded = !deptExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = deptDraft,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Department") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deptExpanded) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = deptExpanded,
                                onDismissRequest = { deptExpanded = false }
                            ) {
                                MasterData.DEPARTMENTS.forEach { d ->
                                    DropdownMenuItem(text = { Text(d) }, onClick = { deptDraft = d; deptExpanded = false })
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        ExposedDropdownMenuBox(
                            expanded = townshipExpanded,
                            onExpandedChange = { townshipExpanded = !townshipExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = townshipDraft,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Township Location") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = townshipExpanded) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = townshipExpanded,
                                onDismissRequest = { townshipExpanded = false }
                            ) {
                                MasterData.TOWNSHIP_LOCATIONS.forEach { loc ->
                                    DropdownMenuItem(text = { Text(loc) }, onClick = { townshipDraft = loc; townshipExpanded = false })
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Divider)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("TRAVEL PREFERENCES", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = defaultModeDraft == User.MODE_PASSENGER,
                                onClick = { defaultModeDraft = User.MODE_PASSENGER },
                                label = { Text("Passenger") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = defaultModeDraft == User.MODE_DRIVER,
                                onClick = { defaultModeDraft = User.MODE_DRIVER },
                                label = { Text("Driver") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Default Driver Seats", fontSize = 12.5.sp, color = TextSecondary)
                                Text("Used when you publish a ride", fontSize = 12.sp, color = TextTertiary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { defaultSeatsDraft = (defaultSeatsDraft - 1).coerceAtLeast(1) },
                                    modifier = Modifier.size(42.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("−", fontSize = 20.sp) }
                                Text(
                                    defaultSeatsDraft.toString(),
                                    modifier = Modifier.width(40.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = { defaultSeatsDraft = (defaultSeatsDraft + 1).coerceAtMost(8) },
                                    modifier = Modifier.size(42.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("+", fontSize = 19.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Save Changes Button: Primary #126847 background, #FFFFFF text, height 52dp, radius 16dp
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    actionError = null
                                    actionSuccess = null
                                    val res = userRepository.updateUserProfile(
                                        name = nameDraft,
                                        phone = phoneDraft,
                                        department = deptDraft,
                                        townshipLocation = townshipDraft,
                                        defaultRolePreference = defaultModeDraft,
                                        defaultDriverSeats = defaultSeatsDraft
                                    )
                                    res.onSuccess {
                                        isEditing = false
                                        actionSuccess = "Profile updated successfully!"
                                    }.onFailure {
                                        actionError = "Couldn't save your changes. Please try again."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // APP SETTINGS & SIGN OUT
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("SETTINGS", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showMuteDialog = true }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mute Commute Reminders", fontSize = 15.sp, color = TextPrimary)
                        Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onLogout() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sign Out", fontSize = 15.sp, color = Error, fontWeight = FontWeight.SemiBold)
                        Icon(imageVector = Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = Error, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showMuteDialog) {
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text("Mute Commute Reminders") },
            text = { Text("Select duration to suppress daily commute notifications:") },
            confirmButton = {
                Button(onClick = {
                    val oneDay = System.currentTimeMillis() + 24 * 3600 * 1000L
                    coroutineScope.launch {
                        userRepository.setMuteSchedule(oneDay)
                            .onSuccess {
                                actionSuccess = "Muted for 24 hours."
                                showMuteDialog = false
                            }
                            .onFailure {
                                actionError = "Couldn't update reminder settings. Please try again."
                            }
                    }
                }) { Text("Mute 24 Hours") }
            },
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InfoReadRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 12.5.sp, color = TextSecondary)
        Text(value, fontSize = 15.5.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
