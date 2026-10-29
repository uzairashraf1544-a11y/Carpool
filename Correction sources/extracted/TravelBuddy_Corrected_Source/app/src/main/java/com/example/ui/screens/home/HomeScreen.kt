package com.example.ui.screens.home

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.data.model.CommuteSelection
import com.example.data.model.MasterData
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.AdminRepository
import com.example.data.repository.RideRepository
import com.example.data.repository.UserRepository
import com.example.service.RideLocationService
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

data class PickupGroupUi(
    val location: String,
    val driverCount: Int,
    val availableSeats: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: User,
    commuteSelection: CommuteSelection,
    onChangeCommuteClick: () -> Unit,
    onOpenGroupDetail: (String, List<Ride>) -> Unit,
    rideRepository: RideRepository = remember { RideRepository() },
    userRepository: UserRepository = remember { UserRepository() },
    adminRepository: AdminRepository = remember { AdminRepository() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val todayDate = remember(commuteSelection) { commuteSelection.isoDate }

    val snackbarHostState = remember { SnackbarHostState() }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf<String?>(null) }
    var isChangingMode by remember { mutableStateOf(false) }

    // ---------------------------------------------------------
    // PASSENGER REAL FIRESTORE FLOWS (FILTERED BY DYNAMIC COMMUTE SELECTION)
    // ---------------------------------------------------------
    val availableDrivers by rideRepository.getAvailableDriversFlow(
        date = todayDate,
        directionFilter = commuteSelection.direction,
        windowFilter = commuteSelection.commuteWindow
    ).collectAsState(initial = emptyList())

    val myRequests by rideRepository.getPassengerRequestsFlow(user.uid, todayDate).collectAsState(initial = emptyList())
    val sustainabilityConfig by adminRepository.getSystemConfigFlow().collectAsState(
        initial = com.example.data.model.SystemConfig()
    )

    // Real dynamic pickup groups calculation from query results
    val pickupGroups = remember(availableDrivers) {
        availableDrivers.groupBy { it.pickupZone }
            .map { (location, rides) ->
                PickupGroupUi(
                    location = location,
                    driverCount = rides.map { it.driverId }.distinct().size,
                    availableSeats = rides.sumOf { it.availableSeats }
                )
            }
            .sortedByDescending { it.availableSeats }
    }

    val totalDrivers = remember(availableDrivers) { availableDrivers.map { it.driverId }.distinct().size }
    val totalAvailableSeats = remember(availableDrivers) { availableDrivers.sumOf { it.availableSeats } }

    // Real Personal Impact calculated from user's actual completed rides
    val completedPassengerRides = remember(myRequests) {
        myRequests.filter { it.status == RideRequest.STATUS_COMPLETED }
    }
    val userSharedRidesCount = completedPassengerRides.size
    val userAvoidedKm = userSharedRidesCount * sustainabilityConfig.baselineDistanceKm
    val userCo2SavedKg = userAvoidedKm * sustainabilityConfig.co2FactorKgPerKm

    // ---------------------------------------------------------
    // DRIVER REAL FIRESTORE FLOWS
    // ---------------------------------------------------------
    val todayRideState by rideRepository.getDriverTodayRideFlow(user.uid, todayDate).collectAsState(initial = null)
    val incomingRequests by rideRepository.getIncomingRequestsForDriverFlow(user.uid, todayDate).collectAsState(initial = emptyList())

    var isDrivingToday by remember { mutableStateOf(false) }
    var selectedWindow by remember(commuteSelection.commuteWindow) { mutableStateOf(commuteSelection.commuteWindow) }
    var selectedDirection by remember(commuteSelection.direction) { mutableStateOf(commuteSelection.direction) }
    var selectedPickup by remember(commuteSelection.pickupZone) { mutableStateOf(commuteSelection.pickupZone) }
    var selectedDestination by remember(commuteSelection.destinationZone) { mutableStateOf(commuteSelection.destinationZone) }
    var seatsCount by remember(user.defaultDriverSeats) { mutableStateOf(user.defaultDriverSeats) }

    LaunchedEffect(todayRideState) {
        if (todayRideState != null && todayRideState?.status != Ride.STATUS_OFFLINE) {
            isDrivingToday = true
            selectedWindow = todayRideState?.commuteWindow ?: selectedWindow
            selectedDirection = todayRideState?.direction ?: selectedDirection
            selectedPickup = todayRideState?.pickupZone ?: selectedPickup
            selectedDestination = todayRideState?.destinationZone ?: selectedDestination
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

            // 1. Top Header: Brand Mark Logo + Greeting (Good Morning, Uzair) + Avatar
            TravelBuddyTopHeader(userName = user.name)

            Spacer(modifier = Modifier.height(20.dp))

            var activeMode by remember(user.currentMode) { mutableStateOf(user.currentMode) }
            val isDriverMode = activeMode.uppercase() == User.MODE_DRIVER

            // 2. Mode Switcher. Do not optimistically switch and then snap back.
            // Persist the mode first, then commit the visible state.
            ModeSwitcher(
                currentMode = activeMode,
                enabled = !isChangingMode,
                onModeChanged = { targetMode ->
                    if (isChangingMode || targetMode == activeMode) return@ModeSwitcher
                    val previousMode = activeMode
                    isChangingMode = true
                    coroutineScope.launch {
                        if (previousMode == User.MODE_DRIVER && targetMode == User.MODE_PASSENGER) {
                            val hasActive = rideRepository.hasActiveDriverRide(user.uid, todayDate)
                            if (hasActive) {
                                actionError = "You currently have an active driver ride. Complete or cancel the ride before switching to Passenger mode."
                                isChangingMode = false
                                return@launch
                            }
                        }

                        userRepository.updateCurrentMode(targetMode)
                            .onSuccess { activeMode = targetMode }
                            .onFailure {
                                actionError = "Couldn't switch travel mode. Please try again."
                            }
                        isChangingMode = false
                    }
                }
            )

            Spacer(modifier = Modifier.height(22.dp))

            actionSuccess?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SuccessContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(msg, color = Success, modifier = Modifier.padding(12.dp), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                }
            }

            // =========================================================
            // PASSENGER HOME VIEW
            // =========================================================
            if (!isDriverMode) {
                val activeRequest = myRequests.firstOrNull {
                    it.status == RideRequest.STATUS_REQUESTED || it.status == RideRequest.STATUS_ACCEPTED
                }

                if (activeRequest != null) {
                    // MY RIDE STATUS CARD
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(22.dp)),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("MY RIDE", color = PrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                StatusChip(status = activeRequest.status)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text("Driver: ${activeRequest.driverName}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Route: ${activeRequest.pickupZone} ➔ ${activeRequest.destinationZone}", fontSize = 14.5.sp, color = TextSecondary)
                            Text("Commute Window: ${activeRequest.commuteWindow}", fontSize = 14.sp, color = TextTertiary)

                            Spacer(modifier = Modifier.height(16.dp))

                            if (activeRequest.status == RideRequest.STATUS_REQUESTED) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            rideRepository.cancelPassengerRequest(activeRequest.requestId)
                                                .onSuccess { actionSuccess = "Ride request cancelled." }
                                                .onFailure { actionError = it.message ?: "Couldn't cancel request. Please try again." }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Cancel Request", color = Error, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Text(
                                    "Seat confirmed. Contact your driver if your plans change.",
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                } else {
                    // 1. TODAY'S COMMUTE HERO CARD (DYNAMIC SELECTION DATA & ACTIVE CHANGE CALLBACK)
                    HeroCommuteCard(
                        pickupZone = commuteSelection.pickupZone,
                        destinationZone = commuteSelection.destinationZone,
                        dateText = commuteSelection.formattedDate,
                        timeText = commuteSelection.displayTime,
                        onChangeClick = onChangeCommuteClick
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    // 2. AVAILABILITY SUMMARY CARD (REAL FIRESTORE DATA)
                    MetricSummaryCard(
                        driverCount = totalDrivers,
                        seatCount = totalAvailableSeats,
                        groupCount = pickupGroups.size
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 3. PICKUP GROUPS SECTION (REAL DYNAMIC DATA ONLY)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Rounded.LocationOn, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PICKUP GROUPS", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (pickupGroups.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No rides are available for this commute yet.", fontSize = 15.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = onChangeCommuteClick,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Change Commute", color = Primary)
                                }
                            }
                        }
                    } else {
                        pickupGroups.forEach { group ->
                            LocationGroupCard(
                                locationName = group.location,
                                driverCount = group.driverCount,
                                seatCount = group.availableSeats,
                                onClick = { onOpenGroupDetail(group.location, availableDrivers) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4. YOUR IMPACT CARD (REAL USER DATA)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(115.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(18.dp)),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Approved PNG Landscape Decoration (impact_landscape.png, alpha ~0.32, right-aligned)
                            Image(
                                painter = painterResource(id = R.drawable.impact_landscape),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .fillMaxHeight(0.92f)
                                    .fillMaxWidth(0.62f)
                                    .alpha(0.32f)
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Rounded.Eco, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("YOUR IMPACT", color = PrimaryDark, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(0.72f),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    ImpactMetricItem(iconRes = R.drawable.metric_shared_rides, value = "$userSharedRidesCount", label = "Shared Rides")
                                    ImpactMetricItem(iconRes = R.drawable.metric_distance, value = String.format(java.util.Locale.US, "%.0f km", userAvoidedKm), label = "Avoided")
                                    ImpactMetricItem(iconRes = R.drawable.metric_co2, value = String.format(java.util.Locale.US, "%.1f kg", userCo2SavedKg), label = "CO₂ Saved")
                                }
                            }
                        }
                    }
                }
            }
            // =========================================================
            // DRIVER HOME VIEW
            // =========================================================
            else {
                if (todayRideState == null || todayRideState?.status == Ride.STATUS_OFFLINE) {
                    // DRIVING TODAY HERO TOGGLE CARD
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(22.dp)),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("DRIVING TODAY?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                                    Text("Offer a ride to colleagues", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Switch(
                                    checked = isDrivingToday,
                                    onCheckedChange = { checked ->
                                        isDrivingToday = checked
                                        if (checked) {
                                            coroutineScope.launch {
                                                // We are already in Driver mode; publishing a ride must not
                                                // redundantly rewrite currentMode and create a second failure point.
                                                rideRepository.createOrUpdateDriverAvailability(
                                                    user = user,
                                                    date = todayDate,
                                                    commuteWindow = selectedWindow,
                                                    direction = selectedDirection,
                                                    pickupZone = selectedPickup,
                                                    destinationZone = selectedDestination,
                                                    totalSeats = seatsCount
                                                ).onSuccess {
                                                    actionSuccess = "Ride availability published!"
                                                }.onFailure {
                                                    isDrivingToday = false
                                                    actionError = "Couldn't publish your ride. Please try again."
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val currentRide = todayRideState!!

                    // ACTIVE RIDE SUMMARY CARD
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(22.dp)),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("TODAY'S RIDE", color = PrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                StatusChip(status = currentRide.status)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("${currentRide.pickupZone} ➔ ${currentRide.destinationZone}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("${currentRide.acceptedPassengerIds.size} booked • ${currentRide.availableSeats} available", fontSize = 14.5.sp, color = TextSecondary)

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (currentRide.status != Ride.STATUS_RIDE_STARTED && currentRide.status != Ride.STATUS_COMPLETED) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val res = rideRepository.startRide(currentRide.rideId)
                                                res.onSuccess {
                                                    RideLocationService.startService(context, currentRide.rideId)
                                                    actionSuccess = "Ride started! Location tracking active."
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text("START RIDE", fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                if (currentRide.status == Ride.STATUS_RIDE_STARTED) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val res = rideRepository.finishRide(currentRide.rideId)
                                                res.onSuccess {
                                                    RideLocationService.stopService(context)
                                                    actionSuccess = "Ride finished successfully!"
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text("FINISH RIDE", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // PASSENGER REQUESTS LIST
                val pendingReqs = incomingRequests.filter { it.status == RideRequest.STATUS_REQUESTED }
                Text("PASSENGER REQUESTS (${pendingReqs.size})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

                Spacer(modifier = Modifier.height(12.dp))

                if (pendingReqs.isEmpty()) {
                    Text("No pending ride requests right now.", color = TextSecondary, fontSize = 14.sp)
                } else {
                    pendingReqs.forEach { req ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(req.passengerName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Dept: ${req.passengerDepartment}", fontSize = 13.5.sp, color = TextSecondary)
                                Text("Pickup: ${req.pickupZone} ➔ ${req.destinationZone}", fontSize = 13.5.sp, color = TextTertiary)

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val res = rideRepository.acceptRideRequestTransaction(req.requestId, req.rideId, req.passengerId)
                                                res.onSuccess { actionSuccess = "Passenger accepted!" }
                                                    .onFailure { actionError = "That seat was just taken. Please choose another ride." }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Accept")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch { rideRepository.rejectRideRequest(req.requestId) }
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Decline")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ImpactMetricItem(
    iconRes: Int,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(38.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(label, fontSize = 12.sp, color = TextSecondary)
    }
}
