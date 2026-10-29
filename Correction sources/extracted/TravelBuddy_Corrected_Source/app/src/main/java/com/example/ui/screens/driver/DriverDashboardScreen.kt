package com.example.ui.screens.driver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.MasterData
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.RideRepository
import com.example.data.repository.UserRepository
import com.example.service.RideLocationService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboardScreen(
    user: User,
    onNavigateToProfile: () -> Unit,
    onNavigateToPassengerMode: () -> Unit,
    onNavigateToAdminDashboard: () -> Unit,
    rideRepository: RideRepository = remember { RideRepository() },
    userRepository: UserRepository = remember { UserRepository() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val todayDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    val todayRideState by rideRepository.getDriverTodayRideFlow(user.uid, todayDate).collectAsState(initial = null)
    val incomingRequests by rideRepository.getIncomingRequestsForDriverFlow(user.uid, todayDate).collectAsState(initial = emptyList())

    var isDrivingToday by remember { mutableStateOf(false) }
    var selectedWindow by remember { mutableStateOf(MasterData.COMMUTE_WINDOWS.first().name) }
    var selectedDirection by remember { mutableStateOf("Township → Plant") }
    var selectedPickup by remember { mutableStateOf(user.townshipLocation) }
    var selectedDestination by remember { mutableStateOf(MasterData.PLANT_LOCATIONS.first()) }
    var seatsText by remember { mutableStateOf(user.defaultDriverSeats.toString()) }

    var windowExpanded by remember { mutableStateOf(false) }
    var pickupExpanded by remember { mutableStateOf(false) }
    var destExpanded by remember { mutableStateOf(false) }

    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(todayRideState) {
        if (todayRideState != null && todayRideState?.status != Ride.STATUS_OFFLINE) {
            isDrivingToday = true
            selectedWindow = todayRideState?.commuteWindow ?: selectedWindow
            selectedDirection = todayRideState?.direction ?: selectedDirection
            selectedPickup = todayRideState?.pickupZone ?: selectedPickup
            selectedDestination = todayRideState?.destinationZone ?: selectedDestination
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Driver Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                val hasActive = rideRepository.hasActiveDriverRide(user.uid, todayDate)
                                if (hasActive) {
                                    actionMessage = "You currently have an active driver ride. Complete or cancel the ride before switching to Passenger mode."
                                } else {
                                    userRepository.updateCurrentMode(User.MODE_PASSENGER)
                                    onNavigateToPassengerMode()
                                }
                            }
                        }
                    ) {
                        Text("Passenger Mode")
                    }
                    if (user.isAdmin()) {
                        TextButton(onClick = onNavigateToAdminDashboard) {
                            Text("Admin")
                        }
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
            // Driving Today Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Driving Today", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (isDrivingToday) "Status: ${todayRideState?.status ?: "AVAILABLE"}" else "Offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Switch(
                        checked = isDrivingToday,
                        onCheckedChange = { checked ->
                            isDrivingToday = checked
                            if (!checked) {
                                coroutineScope.launch {
                                    rideRepository.setDriverOffline(user.uid, todayDate)
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            actionMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (isDrivingToday) {
                if (todayRideState == null || todayRideState?.status == Ride.STATUS_OFFLINE) {
                    // Availability Setup Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Setup Today's Route", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Window Dropdown
                            ExposedDropdownMenuBox(
                                expanded = windowExpanded,
                                onExpandedChange = { windowExpanded = !windowExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedWindow,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Commute Window") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = windowExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = windowExpanded, onDismissRequest = { windowExpanded = false }) {
                                    MasterData.COMMUTE_WINDOWS.forEach { w ->
                                        DropdownMenuItem(
                                            text = { Text("${w.name} (${w.timeSlot})") },
                                            onClick = {
                                                selectedWindow = w.name
                                                selectedDirection = w.direction
                                                windowExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Direction Radio
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

                            Spacer(modifier = Modifier.height(8.dp))

                            // Pickup Dropdown
                            ExposedDropdownMenuBox(
                                expanded = pickupExpanded,
                                onExpandedChange = { pickupExpanded = !pickupExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedPickup,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Pickup Location") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pickupExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = pickupExpanded, onDismissRequest = { pickupExpanded = false }) {
                                    (MasterData.TOWNSHIP_LOCATIONS + MasterData.PLANT_LOCATIONS).forEach { loc ->
                                        DropdownMenuItem(
                                            text = { Text(loc) },
                                            onClick = {
                                                selectedPickup = loc
                                                pickupExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Destination Dropdown
                            ExposedDropdownMenuBox(
                                expanded = destExpanded,
                                onExpandedChange = { destExpanded = !destExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedDestination,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Destination") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = destExpanded, onDismissRequest = { destExpanded = false }) {
                                    (MasterData.PLANT_LOCATIONS + MasterData.TOWNSHIP_LOCATIONS).forEach { loc ->
                                        DropdownMenuItem(
                                            text = { Text(loc) },
                                            onClick = {
                                                selectedDestination = loc
                                                destExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = seatsText,
                                onValueChange = { seatsText = it.filter { c -> c.isDigit() } },
                                label = { Text("Seats Available Today (Default: ${user.defaultDriverSeats})") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val seats = seatsText.toIntOrNull() ?: user.defaultDriverSeats
                                    coroutineScope.launch {
                                        val res = rideRepository.createOrUpdateDriverAvailability(
                                            user = user,
                                            date = todayDate,
                                            commuteWindow = selectedWindow,
                                            direction = selectedDirection,
                                            pickupZone = selectedPickup,
                                            destinationZone = selectedDestination,
                                            totalSeats = seats
                                        )
                                        res.onSuccess {
                                            actionMessage = "Ride availability activated!"
                                        }.onFailure {
                                            actionMessage = "Error: ${it.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Publish Ride Availability")
                            }
                        }
                    }
                } else {
                    val currentRide = todayRideState!!

                    // Active Ride Management Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Active Ride Summary", style = MaterialTheme.typography.titleMedium)
                            Text("Route: ${currentRide.pickupZone} ➔ ${currentRide.destinationZone}")
                            Text("Seats: ${currentRide.acceptedPassengerIds.size} / ${currentRide.totalSeats} accepted (${currentRide.availableSeats} remaining)")
                            Text("Status: ${currentRide.status}", color = MaterialTheme.colorScheme.primary)

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (currentRide.status != Ride.STATUS_RIDE_STARTED && currentRide.status != Ride.STATUS_COMPLETED) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val res = rideRepository.startRide(currentRide.rideId)
                                                res.onSuccess {
                                                    RideLocationService.startService(context, currentRide.rideId)
                                                    actionMessage = "Ride started! Low-power location active."
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("START RIDE")
                                    }
                                }

                                if (currentRide.status == Ride.STATUS_RIDE_STARTED) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val res = rideRepository.finishRide(currentRide.rideId)
                                                res.onSuccess {
                                                    RideLocationService.stopService(context)
                                                    actionMessage = "Ride completed! Location tracking stopped."
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("FINISH RIDE")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Passenger Requests", style = MaterialTheme.typography.titleLarge)
                    val pendingRequests = incomingRequests.filter { it.status == RideRequest.STATUS_REQUESTED }

                    if (pendingRequests.isEmpty()) {
                        Text("No pending requests right now.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(pendingRequests) { req ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("Passenger: ${req.passengerName}", style = MaterialTheme.typography.titleMedium)
                                        Text("Dept: ${req.passengerDepartment} | Phone: ${req.passengerPhone}")
                                        Text("Pickup: ${req.pickupZone} ➔ ${req.destinationZone}")
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    isProcessing = true
                                                    coroutineScope.launch {
                                                        val res = rideRepository.acceptRideRequestTransaction(
                                                            requestId = req.requestId,
                                                            rideId = currentRide.rideId,
                                                            passengerId = req.passengerId
                                                        )
                                                        isProcessing = false
                                                        res.onSuccess {
                                                            actionMessage = "Passenger request accepted!"
                                                        }.onFailure {
                                                            actionMessage = it.message ?: "Failed to accept request."
                                                        }
                                                    }
                                                },
                                                enabled = !isProcessing
                                            ) {
                                                Text("Accept")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        rideRepository.rejectRideRequest(req.requestId)
                                                    }
                                                }
                                            ) {
                                                Text("Reject")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Toggle 'Driving Today' to post availability and receive passenger ride requests.")
            }
        }
    }
}
