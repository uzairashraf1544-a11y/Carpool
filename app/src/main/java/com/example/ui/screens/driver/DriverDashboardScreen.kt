package com.example.ui.screens.driver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MasterData
import com.example.data.model.PassengerNeed
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.DriverRoutePreferences
import com.example.data.repository.RideRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.ui.components.StatusChip
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboardScreen(
    user: User,
    rideRepository: RideRepository = remember { RideRepository() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val todayDate = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }
    val userPreferencesRepository = remember { UserPreferencesRepository(context) }

    val todayRide by remember(user.uid, todayDate) {
        rideRepository.getDriverTodayRideFlow(user.uid, todayDate)
    }.collectAsState(initial = null)

    val driverRoutePrefs by remember(user.uid) {
        userPreferencesRepository.getDriverPreferencesFlow(user.uid)
    }.collectAsState(initial = DriverRoutePreferences())

    val incomingRequests by remember(user.uid, todayDate) {
        rideRepository.getIncomingRequestsForDriverFlow(user.uid, todayDate)
    }.collectAsState(initial = emptyList())

    val matchingPassengerNeeds by remember(todayDate) {
        rideRepository.getMatchingPassengerNeedsFlow(todayDate, "All", "All")
    }.collectAsState(initial = emptyList())

    var selectedDirection by remember { mutableStateOf("Township → Plant") }
    var selectedPickupZone by remember { mutableStateOf("EMs") }
    var selectedCommuteWindow by remember { mutableStateOf("Morning Inbound") }
    var selectedDestinationZone by remember { mutableStateOf("FFL Plant Main Gate") }
    var seatsCount by remember { mutableIntStateOf(user.defaultDriverSeats.coerceIn(1, 8)) }
    var pickupDetails by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    var expandedDirection by remember { mutableStateOf(false) }
    var expandedPickup by remember { mutableStateOf(false) }
    var expandedWindow by remember { mutableStateOf(false) }

    var actionMessage by remember { mutableStateOf<String?>(null) }
    var selectedPassengerNeedDetail by remember { mutableStateOf<PassengerNeed?>(null) }

    LaunchedEffect(todayRide, driverRoutePrefs, user) {
        if (todayRide != null) {
            val ride = todayRide!!
            selectedDirection = ride.direction
            selectedPickupZone = ride.pickupZone
            selectedDestinationZone = ride.destinationZone
            selectedCommuteWindow = ride.commuteWindow
            seatsCount = ride.totalSeats
            pickupDetails = ride.pickupDetails
            message = ride.message
        } else if (driverRoutePrefs.pickupZone != null || driverRoutePrefs.direction != null) {
            val dir = driverRoutePrefs.direction ?: "Township → Plant"
            selectedDirection = dir
            val validPickups = MasterData.pickupLocationsFor(dir)
            selectedPickupZone = if (driverRoutePrefs.pickupZone in validPickups) driverRoutePrefs.pickupZone!! else validPickups.firstOrNull() ?: "EMs"
            selectedDestinationZone = if (dir == "Township → Plant") "FFL Plant Main Gate" else user.townshipLocation.ifBlank { "EMs" }
            selectedCommuteWindow = driverRoutePrefs.commuteWindow ?: "Morning Inbound"
            pickupDetails = driverRoutePrefs.pickupDetails
            message = driverRoutePrefs.message
            seatsCount = user.defaultDriverSeats.coerceIn(1, 8)
        } else {
            selectedDirection = "Township → Plant"
            val validPickups = MasterData.TOWNSHIP_LOCATIONS
            selectedPickupZone = if (user.townshipLocation in validPickups) user.townshipLocation else validPickups.firstOrNull() ?: "EMs"
            selectedDestinationZone = "FFL Plant Main Gate"
            selectedCommuteWindow = "Morning Inbound"
            seatsCount = user.defaultDriverSeats.coerceIn(1, 8)
        }
    }

    val pickupOptions = MasterData.pickupLocationsFor(selectedDirection)
    val windowOptions = MasterData.windowsForDirection(selectedDirection)

    // Passenger Detail Dialog for Direct Accept
    selectedPassengerNeedDetail?.let { need ->
        AlertDialog(
            onDismissRequest = { selectedPassengerNeedDetail = null },
            title = { Text("RIDE REQUEST", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Passenger: ${need.passengerName}", style = MaterialTheme.typography.titleSmall)
                    Text("Department: ${need.passengerDepartment}")
                    Text("Route: ${need.direction}")
                    Text("Date: ${need.date}")
                    Text("Pickup Area: ${need.pickupZone}")
                    Text("Commute Window: ${need.commuteWindow}")
                    if (need.message.isNotBlank()) {
                        Text("Note: \"${need.message}\"")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = selectedPassengerNeedDetail ?: return@Button
                        selectedPassengerNeedDetail = null
                        coroutineScope.launch {
                            rideRepository.acceptPassengerNeedDirectly(user, target)
                                .onSuccess {
                                    actionMessage = "Accepted ${target.passengerName}'s request!"
                                }
                                .onFailure { err ->
                                    actionMessage = err.message ?: "Failed to accept request."
                                }
                        }
                    }
                ) {
                    Text("DRIVE & ACCEPT")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPassengerNeedDetail = null }) { Text("Close") }
            }
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Driver Dashboard",
                style = MaterialTheme.typography.headlineMedium
            )

            actionMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actionMessage = null }) { Text("OK") }
                    }
                }
            }

            if (todayRide == null || todayRide?.status in listOf(Ride.STATUS_COMPLETED, Ride.STATUS_CANCELLED)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Offer a Ride", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 1. DIRECTION
                        ExposedDropdownMenuBox(
                            expanded = expandedDirection,
                            onExpandedChange = { expandedDirection = !expandedDirection },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedDirection,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Direction") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDirection) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedDirection,
                                onDismissRequest = { expandedDirection = false }
                            ) {
                                listOf("Township → Plant", "Plant → Township").forEach { dir ->
                                    DropdownMenuItem(
                                        text = { Text(dir) },
                                        onClick = {
                                            expandedDirection = false
                                            selectedDirection = dir
                                            val validPickups = MasterData.pickupLocationsFor(dir)
                                            if (selectedPickupZone !in validPickups) {
                                                selectedPickupZone = validPickups.firstOrNull() ?: "EMs"
                                            }
                                            selectedDestinationZone = if (dir == "Township → Plant") "FFL Plant Main Gate" else user.townshipLocation.ifBlank { "EMs" }
                                            val validWindows = MasterData.windowsForDirection(dir)
                                            if (validWindows.isNotEmpty() && validWindows.none { it.name == selectedCommuteWindow }) {
                                                selectedCommuteWindow = validWindows.first().name
                                            }
                                            coroutineScope.launch {
                                                userPreferencesRepository.saveDriverPreferences(
                                                    uid = user.uid,
                                                    direction = selectedDirection,
                                                    pickupZone = selectedPickupZone,
                                                    destinationZone = selectedDestinationZone,
                                                    commuteWindow = selectedCommuteWindow,
                                                    pickupDetails = pickupDetails,
                                                    message = message
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 2. PICKUP AREA
                        ExposedDropdownMenuBox(
                            expanded = expandedPickup,
                            onExpandedChange = { expandedPickup = !expandedPickup },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedPickupZone,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Pickup Area") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedPickup) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedPickup,
                                onDismissRequest = { expandedPickup = false }
                            ) {
                                pickupOptions.forEach { loc ->
                                    DropdownMenuItem(
                                        text = { Text(loc) },
                                        onClick = {
                                            expandedPickup = false
                                            selectedPickupZone = loc
                                            coroutineScope.launch {
                                                userPreferencesRepository.saveDriverPreferences(
                                                    uid = user.uid,
                                                    direction = selectedDirection,
                                                    pickupZone = selectedPickupZone,
                                                    destinationZone = selectedDestinationZone,
                                                    commuteWindow = selectedCommuteWindow,
                                                    pickupDetails = pickupDetails,
                                                    message = message
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. COMMUTE WINDOW
                        ExposedDropdownMenuBox(
                            expanded = expandedWindow,
                            onExpandedChange = { expandedWindow = !expandedWindow },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val winObj = MasterData.windowByValue(selectedCommuteWindow)
                            val displayTxt = if (winObj != null) "${winObj.name} (${winObj.timeSlot})" else selectedCommuteWindow

                            OutlinedTextField(
                                value = displayTxt,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Commute Window") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedWindow) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedWindow,
                                onDismissRequest = { expandedWindow = false }
                            ) {
                                windowOptions.forEach { cw ->
                                    DropdownMenuItem(
                                        text = { Text("${cw.name} (${cw.timeSlot})") },
                                        onClick = {
                                            expandedWindow = false
                                            selectedCommuteWindow = cw.name
                                            coroutineScope.launch {
                                                userPreferencesRepository.saveDriverPreferences(
                                                    uid = user.uid,
                                                    direction = selectedDirection,
                                                    pickupZone = selectedPickupZone,
                                                    destinationZone = selectedDestinationZone,
                                                    commuteWindow = selectedCommuteWindow,
                                                    pickupDetails = pickupDetails,
                                                    message = message
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Available Seats")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { if (seatsCount > 1) seatsCount-- }) { Text("-") }
                                Text("$seatsCount", modifier = Modifier.padding(horizontal = 16.dp))
                                OutlinedButton(onClick = { if (seatsCount < 8) seatsCount++ }) { Text("+") }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            label = { Text("Message / Note (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val res = rideRepository.createOrUpdateDriverAvailability(
                                        user = user,
                                        date = todayDate,
                                        commuteWindow = selectedCommuteWindow,
                                        direction = selectedDirection,
                                        pickupZone = selectedPickupZone,
                                        destinationZone = selectedDestinationZone,
                                        totalSeats = seatsCount,
                                        pickupDetails = pickupDetails,
                                        message = message
                                    )
                                    res.onSuccess { actionMessage = "Ride availability published!" }
                                        .onFailure { actionMessage = "Failed to publish ride." }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("PUBLISH AVAILABILITY")
                        }
                    }
                }
            } else {
                val currentRide = todayRide!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Ride Summary", style = MaterialTheme.typography.titleMedium)
                        Text("Route: ${currentRide.pickupZone} ➔ ${currentRide.destinationZone}")
                        Text("Seats: ${currentRide.acceptedPassengerIds.size} / ${currentRide.totalSeats} accepted (${currentRide.availableSeats} remaining)")
                        Text("Status: ${currentRide.status}", color = MaterialTheme.colorScheme.primary)

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val res = rideRepository.closeRideAsDriver(currentRide.rideId, RideRepository.OUTCOME_COMPLETED)
                                    res.onSuccess {
                                        actionMessage = if (currentRide.acceptedPassengerIds.isEmpty()) "Availability closed." else "Ride closed."
                                    }.onFailure {
                                        actionMessage = "Couldn't close ride."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (currentRide.acceptedPassengerIds.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (currentRide.acceptedPassengerIds.isEmpty()) "CLOSE AVAILABILITY" else "CLOSE RIDE")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Confirmed Passengers", style = MaterialTheme.typography.titleLarge)
                val confirmedPassengers = incomingRequests.filter { it.status == RideRequest.STATUS_ACCEPTED }
                if (confirmedPassengers.isEmpty()) {
                    Text("No passengers have joined this ride yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    confirmedPassengers.forEach { req ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(req.passengerName, style = MaterialTheme.typography.titleMedium)
                                Text("Dept: ${req.passengerDepartment}")
                                Text("Pickup: ${req.pickupZone}")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Colleagues Looking for a Ride", style = MaterialTheme.typography.titleLarge)
            if (matchingPassengerNeeds.isEmpty()) {
                Text("No colleagues currently looking for a ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                matchingPassengerNeeds.forEach { need ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { selectedPassengerNeedDetail = need },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(need.passengerName, style = MaterialTheme.typography.titleMedium)
                                StatusChip(status = need.status)
                            }
                            Text("Route: ${need.direction} (${need.pickupZone})")
                            Text("Time: ${need.commuteWindow}")
                            if (need.message.isNotBlank()) {
                                Text("Note: ${need.message}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { selectedPassengerNeedDetail = need },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("DRIVE & ACCEPT")
                            }
                        }
                    }
                }
            }
        }
    }
}
