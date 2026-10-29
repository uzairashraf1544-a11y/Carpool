package com.example.ui.screens.passenger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.MasterData
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.RideRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengerDashboardScreen(
    user: User,
    onNavigateToProfile: () -> Unit,
    onNavigateToDriverMode: () -> Unit,
    onNavigateToAdminDashboard: () -> Unit,
    rideRepository: RideRepository = remember { RideRepository() },
    userRepository: UserRepository = remember { UserRepository() }
) {
    val coroutineScope = rememberCoroutineScope()
    val todayDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    var selectedDirection by remember { mutableStateOf("Township → Plant") }
    var selectedWindow by remember { mutableStateOf("All") }
    var windowExpanded by remember { mutableStateOf(false) }

    val availableDrivers by remember(todayDate, selectedDirection, selectedWindow) {
        rideRepository.getAvailableDriversFlow(todayDate, selectedDirection, selectedWindow)
    }.collectAsState(initial = emptyList())
    val myRequests by remember(user.uid, todayDate) {
        rideRepository.getPassengerRequestsFlow(user.uid, todayDate)
    }.collectAsState(initial = emptyList())

    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showMuteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passenger Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    TextButton(
                        onClick = {
                            onNavigateToDriverMode()
                        }
                    ) {
                        Text("Driver Mode")
                    }
                    if (user.isAdmin()) {
                        TextButton(onClick = onNavigateToAdminDashboard) {
                            Text("Admin")
                        }
                    }
                    TextButton(onClick = { showMuteDialog = true }) {
                        Text("Mute")
                    }
                    TextButton(onClick = onNavigateToProfile) {
                        Text("Profile")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            actionMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            // My Ride Section
            val activeRequest = myRequests.firstOrNull {
                it.status == RideRequest.STATUS_ACCEPTED
            }

            if (activeRequest != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("My Current Ride", style = MaterialTheme.typography.titleMedium)
                        Text("Driver: ${activeRequest.driverName}")
                        Text("Route: ${activeRequest.pickupZone} ➔ ${activeRequest.destinationZone}")
                        Text("Window: ${activeRequest.commuteWindow}")
                        Text("Status: ${activeRequest.status}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val res = rideRepository.closeMyRide(activeRequest.requestId, RideRepository.OUTCOME_COMPLETED)
                                        res.onSuccess { actionMessage = "Ride completed." }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("I Have Arrived")
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val res = rideRepository.closeMyRide(activeRequest.requestId, RideRepository.OUTCOME_DID_NOT_TRAVEL)
                                        res.onSuccess { actionMessage = "Seat cancelled." }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel Seat")
                            }
                        }
                    }
                }
            }

            // Filter Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Search Rides", style = MaterialTheme.typography.titleSmall)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedDirection == "Township → Plant",
                                onClick = { selectedDirection = "Township → Plant" }
                            )
                            Text("Township → Plant")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedDirection == "Plant → Township",
                                onClick = { selectedDirection = "Plant → Township" }
                            )
                            Text("Plant → Township")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    ExposedDropdownMenuBox(
                        expanded = windowExpanded,
                        onExpandedChange = { windowExpanded = !windowExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedWindow,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Filter Commute Window") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = windowExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = windowExpanded, onDismissRequest = { windowExpanded = false }) {
                            DropdownMenuItem(text = { Text("All") }, onClick = { selectedWindow = "All"; windowExpanded = false })
                            MasterData.COMMUTE_WINDOWS.forEach { w ->
                                DropdownMenuItem(text = { Text(w.name) }, onClick = { selectedWindow = w.name; windowExpanded = false })
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Available Drivers", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            if (availableDrivers.isEmpty()) {
                Text(
                    text = "No available drivers found for the selected route/time window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(availableDrivers) { ride ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Driver: ${ride.driverName}", style = MaterialTheme.typography.titleMedium)
                                Text("Dept: ${ride.driverDepartment}")
                                Text("Route: ${ride.pickupZone} ➔ ${ride.destinationZone}")
                                Text("Window: ${ride.commuteWindow}")
                                Text("Seats Remaining: ${ride.availableSeats} / ${ride.totalSeats}", color = MaterialTheme.colorScheme.secondary)

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val res = rideRepository.joinRide(user, ride)
                                            res.onSuccess {
                                                actionMessage = "Ride confirmed with ${ride.driverName}!"
                                            }.onFailure {
                                                actionMessage = "Error: ${it.message}"
                                            }
                                        }
                                    },
                                    enabled = activeRequest == null
                                ) {
                                    Text("JOIN RIDE")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMuteDialog) {
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text("Mute Commute Prompts / Leave") },
            text = {
                Column {
                    Text("Select leave or mute duration to suppress daily commute notifications:")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val oneDay = System.currentTimeMillis() + 24 * 3600 * 1000L
                            coroutineScope.launch {
                                userRepository.setMuteSchedule(oneDay)
                                actionMessage = "Muted for 24 hours."
                                showMuteDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mute Today (24 Hours)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val fiveDays = System.currentTimeMillis() + 5 * 24 * 3600 * 1000L
                            coroutineScope.launch {
                                userRepository.setMuteSchedule(fiveDays)
                                actionMessage = "Leave schedule set for 5 days."
                                showMuteDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("5-Day Leave Period")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) { Text("Cancel") }
            }
        )
    }
}
