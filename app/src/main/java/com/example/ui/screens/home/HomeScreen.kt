package com.example.ui.screens.home

import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.CommuteSelection
import com.example.data.model.CommuteSlot
import com.example.data.model.LocalTravelState
import com.example.data.model.MasterData
import com.example.data.model.PassengerEligibility
import com.example.data.model.PassengerNeed
import com.example.data.model.PickupSide
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.SystemConfig
import com.example.data.model.TravelMode
import com.example.data.model.User
import com.example.data.model.LeaderboardEntry
import com.example.ui.components.LeaderboardWidget
import com.example.data.repository.AdminRepository
import com.example.data.repository.RideRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.repository.UserRepository
import com.example.ui.components.ClickablePhoneRow
import com.example.ui.components.LocationGroupCard
import com.example.ui.components.MetricSummaryCard
import com.example.ui.components.ModeSwitcher
import com.example.ui.components.PickupAreaSelector
import com.example.ui.components.StatusChip
import com.example.ui.components.TravelBuddyTopHeader
import com.example.ui.theme.*
import com.example.util.TravelBuddyNotificationManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PickupGroupUiModel(
    val location: String,
    val driverCount: Int,
    val availableSeats: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: User,
    userRepository: UserRepository = remember { UserRepository() },
    rideRepository: RideRepository = remember { RideRepository() },
    adminRepository: AdminRepository = remember { AdminRepository() },
    commuteSelection: CommuteSelection,
    onChangeCommuteClick: () -> Unit,
    onOpenGroupDetail: (location: String, rides: List<Ride>) -> Unit,
    openCommuteSlotId: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userPreferencesRepository = remember { UserPreferencesRepository(context) }
    val todayDate = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }

    // Local UID-scoped UI Mode (Single source of truth for screen browsing)
    val localSavedMode by userPreferencesRepository.getLastUiModeFlow(user.uid).collectAsState(initial = null)
    val localTravelState by userPreferencesRepository.getLocalTravelStateFlow(user.uid).collectAsState(initial = LocalTravelState.NONE)
    var selectedMode by remember(user.uid) {
        val initial = TravelMode.fromString(
            user.currentMode.ifBlank { user.defaultRolePreference }
        )
        mutableStateOf(initial)
    }

    var hasInitializedFromDataStore by remember(user.uid) { mutableStateOf(false) }
    LaunchedEffect(localSavedMode) {
        if (!hasInitializedFromDataStore && localSavedMode != null) {
            selectedMode = TravelMode.fromString(localSavedMode)
            hasInitializedFromDataStore = true
        }
    }

    val isDriverMode = selectedMode == TravelMode.DRIVER

    val handleModeSwitch: (TravelMode) -> Unit = remember(user.uid, selectedMode) {
        { targetMode ->
            if (targetMode != selectedMode) {
                val previousMode = selectedMode
                selectedMode = targetMode
                Log.d(
                    "ModeSwitchDebug",
                    "LOCAL_MODE_CHANGE | from=$previousMode | to=$targetMode | FIRESTORE_WRITE=false"
                )
                coroutineScope.launch {
                    userPreferencesRepository.saveLastUiMode(user.uid, targetMode.toUserMode())
                }
            }
        }
    }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionSuccess by remember { mutableStateOf<String?>(null) }
    var showDriverCloseDialog by remember { mutableStateOf(false) }
    var showCancelNeedDialog by remember { mutableStateOf(false) }
    var showPassengerCloseSheet by remember { mutableStateOf(false) }
    var isPostingNeed by remember { mutableStateOf(false) }
    var isAcceptingNeed by remember { mutableStateOf(false) }
    var isClosingRide by remember { mutableStateOf(false) }
    var passengerClosingAction by remember { mutableStateOf<String?>(null) }
    var driverClosingAction by remember { mutableStateOf<String?>(null) }
    var selectedPassengerNeedDetail by remember { mutableStateOf<PassengerNeed?>(null) }
    var showNotificationSheet by remember { mutableStateOf(false) }

    // In-App Notification Center
    val inAppNotifications by TravelBuddyNotificationManager.notificationsFlow.collectAsState(initial = emptyList())
    val unreadNotificationCount = remember(inAppNotifications) { inAppNotifications.count { !it.isRead } }

    // Check-in Sheet State
    var checkInSlot by remember { mutableStateOf<CommuteSlot?>(null) }

    LaunchedEffect(openCommuteSlotId) {
        if (!openCommuteSlotId.isNullOrBlank()) {
            val slot = CommuteSlot.fromId(openCommuteSlotId)
            checkInSlot = slot
        }
    }

    val lastTownshipPickup by userPreferencesRepository.getLastTownshipPickupFlow(user.uid).collectAsState(initial = "EMs")
    val lastPlantPickup by userPreferencesRepository.getLastPlantPickupFlow(user.uid).collectAsState(initial = "FFL Plant Main Gate")

    // Dynamic Pickup Selection for Driver and Passenger
    var driverPickupZone by remember { mutableStateOf("EMs") }
    var driverCustomPickup by remember { mutableStateOf("") }
    var driverMessage by remember { mutableStateOf("") }
    var driverSeats by remember { mutableIntStateOf(user.defaultDriverSeats.coerceIn(1, 8)) }

    var passengerPickupZone by remember { mutableStateOf("EMs") }
    var passengerCustomPickup by remember { mutableStateOf("") }
    var passengerMessage by remember { mutableStateOf("") }

    val todayRideState by remember(user.uid, todayDate) {
        rideRepository.getDriverTodayRideFlow(user.uid, todayDate)
    }.collectAsState(initial = null)

    LaunchedEffect(todayRideState, lastTownshipPickup) {
        if (todayRideState != null && todayRideState?.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED)) {
            val ride = todayRideState!!
            driverPickupZone = ride.pickupZone
            driverCustomPickup = ride.customPickupLocation
            driverMessage = ride.message
            driverSeats = ride.totalSeats
        } else {
            driverPickupZone = lastTownshipPickup
            passengerPickupZone = lastTownshipPickup
        }
    }

    // Determine current active side for discovery queries
    val activeDriverSide = remember(driverPickupZone) {
        MasterData.getSideForPickup(driverPickupZone).name
    }
    val activePassengerSide = remember(passengerPickupZone) {
        MasterData.getSideForPickup(passengerPickupZone).name
    }

    val incomingRequests by remember(user.uid, todayDate) {
        var isInitial = true
        var knownRequestIds = setOf<String>()
        rideRepository.getIncomingRequestsForDriverFlow(user.uid, todayDate)
            .onEach { requests ->
                if (isInitial) {
                    knownRequestIds = requests.map { it.requestId }.toSet()
                    isInitial = false
                } else {
                    val newRequests = requests.filter { it.requestId !in knownRequestIds && it.passengerId != user.uid }
                    for (req in newRequests) {
                        TravelBuddyNotificationManager.showHeadsUpNotification(
                            context = context,
                            eventId = "joined_driver_${req.requestId}",
                            title = "New Passenger Joined!",
                            message = "${req.passengerName} joined your ride from ${req.displayPickup}."
                        )
                    }
                    knownRequestIds = requests.map { it.requestId }.toSet()
                }
            }
    }.collectAsState(initial = emptyList())

    // CENTRALIZED PASSENGER ELIGIBILITY & ACTIVE RIDE EXCLUSIVITY
    val passengerEligibility by remember(user.uid, todayDate) {
        var isInitial = true
        var knownActiveRideId: String? = null
        rideRepository.getPassengerEligibilityFlow(user.uid, todayDate)
            .onEach { eligibility ->
                val currentActiveRide = (eligibility as? PassengerEligibility.ActiveRide)?.request
                if (isInitial) {
                    knownActiveRideId = currentActiveRide?.requestId
                    isInitial = false
                } else {
                    if (currentActiveRide != null && currentActiveRide.requestId != knownActiveRideId) {
                        TravelBuddyNotificationManager.showHeadsUpNotification(
                            context = context,
                            eventId = "acc_passenger_${currentActiveRide.requestId}",
                            title = "Driver Accepted Your Ride!",
                            message = "${currentActiveRide.driverName} confirmed your ride from ${currentActiveRide.displayPickup}."
                        )
                    }
                    knownActiveRideId = currentActiveRide?.requestId
                }
            }
    }.collectAsState(initial = PassengerEligibility.Loading)

    val hasActivePassengerRide = passengerEligibility is PassengerEligibility.ActiveRide
    val activePassengerRequest = (passengerEligibility as? PassengerEligibility.ActiveRide)?.request
    val lookingPassengerNeed = (passengerEligibility as? PassengerEligibility.LookingForRide)?.need

    // Reconcile local travel state from existing live Flows (Zero extra Firestore queries)
    LaunchedEffect(passengerEligibility, todayRideState) {
        when (passengerEligibility) {
            is PassengerEligibility.ActiveRide -> {
                userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.PASSENGER_ACTIVE)
            }
            is PassengerEligibility.LookingForRide -> {
                val need = (passengerEligibility as PassengerEligibility.LookingForRide).need
                userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.PASSENGER_LOOKING, need.needId)
            }
            is PassengerEligibility.CanCreateRequest -> {
                if (todayRideState != null && todayRideState?.status in listOf(
                        Ride.STATUS_AVAILABLE,
                        Ride.STATUS_PARTIALLY_FULL,
                        Ride.STATUS_FULL,
                        Ride.STATUS_RIDE_STARTED
                    )
                ) {
                    val driverState = if (todayRideState!!.acceptedPassengerIds.isNotEmpty()) {
                        LocalTravelState.DRIVER_ACTIVE
                    } else {
                        LocalTravelState.DRIVER_AVAILABLE
                    }
                    userPreferencesRepository.saveLocalTravelState(user.uid, driverState, todayRideState!!.rideId)
                } else {
                    userPreferencesRepository.clearLocalTravelState(user.uid)
                }
            }
            else -> {}
        }
    }

    // DRIVER DISCOVERS PASSENGERS ON SAME PICKUP SIDE (ACTIVE ONLY IN DRIVER MODE)
    val matchingPassengerNeeds by remember(selectedMode, todayDate, activeDriverSide) {
        if (selectedMode == TravelMode.DRIVER) {
            Log.d("ModeSwitchDebug", "MODE_LISTENER_CHANGE | added=PASSENGER_NEEDS | removed=AVAILABLE_DRIVERS")
            var isInitial = true
            var knownNeedIds = setOf<String>()
            rideRepository.getMatchingPassengerNeedsFlow(
                date = todayDate,
                pickupSide = activeDriverSide
            ).onEach { needs ->
                if (isInitial) {
                    knownNeedIds = needs.map { it.needId }.toSet()
                    isInitial = false
                } else {
                    val newNeeds = needs.filter { it.needId !in knownNeedIds && it.passengerId != user.uid }
                    for (need in newNeeds) {
                        TravelBuddyNotificationManager.showHeadsUpNotification(
                            context = context,
                            eventId = "new_need_${need.needId}",
                            title = "New Passenger Looking for a Ride!",
                            message = "${need.passengerName} needs a ride from ${need.displayPickup}."
                        )
                    }
                    knownNeedIds = needs.map { it.needId }.toSet()
                }
            }
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    // PASSENGER DISCOVERS DRIVERS ON SAME PICKUP SIDE (ACTIVE ONLY IN PASSENGER MODE AND WHEN NOT MATCHED)
    val availableDrivers by remember(selectedMode, todayDate, activePassengerSide, hasActivePassengerRide) {
        if (selectedMode == TravelMode.PASSENGER && !hasActivePassengerRide) {
            Log.d("ModeSwitchDebug", "MODE_LISTENER_CHANGE | added=AVAILABLE_DRIVERS | removed=PASSENGER_NEEDS")
            var isInitial = true
            var knownRideIds = setOf<String>()
            rideRepository.getAvailableDriversFlow(
                date = todayDate,
                pickupSide = activePassengerSide
            ).onEach { drivers ->
                if (isInitial) {
                    knownRideIds = drivers.map { it.rideId }.toSet()
                    isInitial = false
                } else {
                    val newDrivers = drivers.filter { it.rideId !in knownRideIds && it.driverId != user.uid }
                    for (driver in newDrivers) {
                        TravelBuddyNotificationManager.showHeadsUpNotification(
                            context = context,
                            eventId = "new_driver_${driver.rideId}",
                            title = "New Driver Available!",
                            message = "${driver.driverName} is offering a ride from ${driver.displayPickup}."
                        )
                    }
                    knownRideIds = drivers.map { it.rideId }.toSet()
                }
            }
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    val passengerHistory by remember(user.uid) {
        rideRepository.getPassengerHistoryFlow(user.uid)
    }.collectAsState(initial = emptyList())

    val driverHistory by remember(user.uid) {
        rideRepository.getDriverRideHistoryFlow(user.uid)
    }.collectAsState(initial = emptyList())

    val systemConfig by remember {
        adminRepository.getSystemConfigFlow()
    }.collectAsState(initial = SystemConfig())

    val topCo2Savers by remember {
        adminRepository.getTopCo2SaversFlow()
    }.collectAsState(initial = adminRepository.getDefaultLeaderboardEntries())

    val pickupGroups: List<PickupGroupUiModel> = remember(availableDrivers) {
        availableDrivers.groupBy { it.pickupZone }
            .map { (location, list) ->
                PickupGroupUiModel(
                    location = location,
                    driverCount = list.size,
                    availableSeats = list.sumOf { it.availableSeats }
                )
            }
    }

    val totalDrivers = availableDrivers.size
    val totalAvailableSeats = availableDrivers.sumOf { it.availableSeats }

    // PASSENGER PERSONAL IMPACT CALCULATION
    val eligibleCompletedPassengerRides = passengerHistory.filter { it.status == RideRequest.STATUS_COMPLETED }
    val eligiblePassengerCompletedRidesCount = eligibleCompletedPassengerRides.size
    val userAvoidedKm = eligiblePassengerCompletedRidesCount * systemConfig.baselineDistanceKm
    val userCo2SavedKg = userAvoidedKm * systemConfig.co2FactorKgPerKm

    // DRIVER PERSONAL IMPACT CALCULATION
    val completedDriverRides = driverHistory.filter { it.status == Ride.STATUS_COMPLETED }
    val driverCompletedRidesCount = completedDriverRides.size
    val totalPassengersDriven = completedDriverRides.sumOf {
        if (it.finalPassengerCount > 0) it.finalPassengerCount else it.completedPassengerIds.size.coerceAtLeast(it.acceptedPassengerIds.size)
    }
    val driverAvoidedKm = totalPassengersDriven * systemConfig.baselineDistanceKm
    val driverCo2SavedKg = driverAvoidedKm * systemConfig.co2FactorKgPerKm

    // COMMUNITY & PERSONAL TOTAL IMPACT (UNIFIED ACROSS MODES)
    val userTotalCo2SavedKg = userCo2SavedKg + driverCo2SavedKg
    val userTotalCompletedRides = eligiblePassengerCompletedRidesCount + driverCompletedRidesCount

    val displayLeaderboardEntries = remember(
        topCo2Savers,
        userTotalCo2SavedKg,
        userTotalCompletedRides,
        user.uid,
        user.name,
        user.department
    ) {
        val baseList = topCo2Savers.filter { it.co2SavedKg > 0.0 }
        val userEntry = if (userTotalCo2SavedKg > 0.0) {
            LeaderboardEntry(
                uid = user.uid,
                name = user.name.ifBlank { "You" },
                department = user.department.ifBlank { "Operations" },
                co2SavedKg = userTotalCo2SavedKg,
                rideCount = userTotalCompletedRides,
                isCurrentUser = true
            )
        } else null

        val merged = if (userEntry != null) {
            val listWithoutUser = baseList.filter { it.uid != user.uid }
            (listWithoutUser + userEntry).sortedByDescending { it.co2SavedKg }.take(3)
        } else {
            baseList.take(3)
        }

        merged.mapIndexed { idx, entry ->
            entry.copy(
                rank = idx + 1,
                isCurrentUser = entry.uid == user.uid || (userEntry != null && entry.uid == userEntry.uid)
            )
        }
    }

    // Auto-calculate real leaderboard from backend rides when admin visits home
    LaunchedEffect(user.effectiveRole) {
        if (user.isAdmin()) {
            adminRepository.recalculateAndSyncLeaderboard()
        }
    }

    // ----------------------------------------------------
    // POPUPS & DIALOGS
    // ----------------------------------------------------

    // 1. DRIVER RIDE CLOSURE DIALOG
    if (showDriverCloseDialog) {
        val currentRide = todayRideState
        val totalAccepted = currentRide?.acceptedPassengerIds?.size ?: 0
        val hasPassengers = totalAccepted > 0

        Dialog(
            onDismissRequest = {
                if (driverClosingAction == null) {
                    showDriverCloseDialog = false
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .widthIn(max = 480.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (hasPassengers) "CLOSE RIDE" else "CLOSE AVAILABILITY",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )

                    Text(
                        text = if (hasPassengers) {
                            "Are you ready to close this ride for today?"
                        } else {
                            "Close your ride offer for today? Colleagues will no longer be able to join."
                        },
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )

                    if (hasPassengers) {
                        Text(
                            text = "Choosing \"Ride Happened\" will complete the ride. Passengers who confirmed completion receive sustainability credit.",
                            fontSize = 12.5.sp,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (hasPassengers) {
                        // Button 1: RIDE HAPPENED
                        Button(
                            enabled = driverClosingAction == null,
                            onClick = {
                                val rideId = currentRide?.rideId ?: return@Button
                                driverClosingAction = "HAPPENED"
                                coroutineScope.launch {
                                    rideRepository.closeRideAsDriver(rideId, RideRepository.OUTCOME_COMPLETED)
                                        .onSuccess {
                                            driverClosingAction = null
                                            showDriverCloseDialog = false
                                            userPreferencesRepository.clearLocalTravelState(user.uid)
                                            actionSuccess = "Ride completed! Thank you for driving colleagues and saving CO₂."
                                        }
                                        .onFailure { err ->
                                            driverClosingAction = null
                                            actionError = when {
                                                err.message?.contains("already closed", ignoreCase = true) == true ||
                                                err.message?.contains("already been closed", ignoreCase = true) == true ->
                                                    "This ride is already closed."
                                                err.message?.contains("network", ignoreCase = true) == true ||
                                                err.message?.contains("unavailable", ignoreCase = true) == true ->
                                                    "Connect to the internet to close this ride."
                                                else -> "Couldn't close this ride. Please try again."
                                            }
                                        }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                        ) {
                            if (driverClosingAction == "HAPPENED") {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = "RIDE HAPPENED",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    // Button 2: RIDE DIDN'T HAPPEN / CONFIRM CLOSE
                    OutlinedButton(
                        enabled = driverClosingAction == null,
                        onClick = {
                            val rideId = currentRide?.rideId ?: return@OutlinedButton
                            driverClosingAction = "DID_NOT_HAPPEN"
                            coroutineScope.launch {
                                rideRepository.closeRideAsDriver(rideId, RideRepository.OUTCOME_DID_NOT_TRAVEL)
                                    .onSuccess {
                                        driverClosingAction = null
                                        showDriverCloseDialog = false
                                        userPreferencesRepository.clearLocalTravelState(user.uid)
                                        actionSuccess = if (hasPassengers) "Ride cancelled for everyone." else "Availability closed."
                                    }
                                    .onFailure { err ->
                                        driverClosingAction = null
                                        actionError = when {
                                            err.message?.contains("already closed", ignoreCase = true) == true ||
                                            err.message?.contains("already been closed", ignoreCase = true) == true ->
                                                "This ride is already closed."
                                            err.message?.contains("network", ignoreCase = true) == true ||
                                            err.message?.contains("unavailable", ignoreCase = true) == true ->
                                                "Connect to the internet to close this ride."
                                            else -> "Couldn't close this ride. Please try again."
                                        }
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (driverClosingAction == "DID_NOT_HAPPEN") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.error, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = if (hasPassengers) "RIDE DIDN'T HAPPEN" else "CONFIRM CLOSE",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }

                    // Button 3: KEEP RIDE OPEN
                    TextButton(
                        onClick = { showDriverCloseDialog = false },
                        enabled = driverClosingAction == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "KEEP RIDE OPEN",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = TextSecondary,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }

    // 2. PASSENGER SELF-CLOSE DIALOG
    if (showPassengerCloseSheet) {
        val req = activePassengerRequest
        Dialog(
            onDismissRequest = {
                if (passengerClosingAction == null) {
                    showPassengerCloseSheet = false
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .widthIn(max = 480.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CLOSE YOUR RIDE",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )

                    Text(
                        text = "Did you complete this journey with your driver?",
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )

                    Text(
                        text = "Confirming completion credits your personal CO₂ savings toward your sustainability impact.",
                        fontSize = 12.5.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Button 1: I COMPLETED THIS RIDE
                    Button(
                        enabled = passengerClosingAction == null,
                        onClick = {
                            val reqId = req?.requestId ?: return@Button
                            passengerClosingAction = "COMPLETE"
                            coroutineScope.launch {
                                rideRepository.closeMyRide(reqId, RideRepository.OUTCOME_COMPLETED)
                                    .onSuccess {
                                        passengerClosingAction = null
                                        showPassengerCloseSheet = false
                                        userPreferencesRepository.clearLocalTravelState(user.uid)
                                        actionSuccess = "Ride completed! CO₂ savings have been credited."
                                    }
                                    .onFailure { err ->
                                        passengerClosingAction = null
                                        actionError = when {
                                            err.message?.contains("already closed", ignoreCase = true) == true ||
                                            err.message?.contains("already been closed", ignoreCase = true) == true ->
                                                "This ride has already been closed."
                                            err.message?.contains("network", ignoreCase = true) == true ||
                                            err.message?.contains("unavailable", ignoreCase = true) == true ->
                                                "Connect to the internet to close your ride."
                                            else -> "Couldn't close your ride. Please try again."
                                        }
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        if (passengerClosingAction == "COMPLETE") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "I COMPLETED THIS RIDE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }

                    // Button 2: I DIDN'T TAKE THIS RIDE
                    OutlinedButton(
                        enabled = passengerClosingAction == null,
                        onClick = {
                            val reqId = req?.requestId ?: return@OutlinedButton
                            passengerClosingAction = "DID_NOT_TRAVEL"
                            coroutineScope.launch {
                                rideRepository.closeMyRide(reqId, RideRepository.OUTCOME_DID_NOT_TRAVEL)
                                    .onSuccess {
                                        passengerClosingAction = null
                                        showPassengerCloseSheet = false
                                        userPreferencesRepository.clearLocalTravelState(user.uid)
                                        actionSuccess = "Participation closed. Zero CO₂ credit recorded."
                                    }
                                    .onFailure { err ->
                                        passengerClosingAction = null
                                        actionError = when {
                                            err.message?.contains("already closed", ignoreCase = true) == true ||
                                            err.message?.contains("already been closed", ignoreCase = true) == true ->
                                                "This ride has already been closed."
                                            err.message?.contains("network", ignoreCase = true) == true ||
                                            err.message?.contains("unavailable", ignoreCase = true) == true ->
                                                "Connect to the internet to close your ride."
                                            else -> "Couldn't close your ride. Please try again."
                                        }
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (passengerClosingAction == "DID_NOT_TRAVEL") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.error, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "I DIDN'T TAKE THIS RIDE",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }

                    // Button 3: KEEP RIDE OPEN
                    TextButton(
                        onClick = { showPassengerCloseSheet = false },
                        enabled = passengerClosingAction == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "KEEP RIDE OPEN",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = TextSecondary,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }

    // 3. CANCEL PASSENGER NEED DIALOG
    if (showCancelNeedDialog) {
        val need = lookingPassengerNeed
        AlertDialog(
            onDismissRequest = { showCancelNeedDialog = false },
            title = { Text("Cancel Ride Request?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = { Text("Drivers will no longer see your request for today.") },
            confirmButton = {
                Button(
                    onClick = {
                        val needId = need?.needId ?: return@Button
                        showCancelNeedDialog = false
                        coroutineScope.launch {
                            rideRepository.cancelPassengerNeed(needId)
                                .onSuccess {
                                    userPreferencesRepository.clearLocalTravelState(user.uid)
                                    actionSuccess = "Ride request cancelled."
                                }
                                .onFailure { actionError = "Couldn't cancel request." }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Cancel Request") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelNeedDialog = false }) { Text("Keep Request") }
            }
        )
    }

    // 4. NOTIFICATION CENTER SHEET
    if (showNotificationSheet) {
        AlertDialog(
            onDismissRequest = { showNotificationSheet = false },
            title = { Text("NOTIFICATIONS", fontWeight = FontWeight.Bold, color = PrimaryDark) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    if (inAppNotifications.isEmpty()) {
                        Text("No notifications yet.", color = TextSecondary, fontSize = 14.sp)
                    } else {
                        inAppNotifications.forEach { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = if (item.isRead) Surface else PrimaryVeryLight)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(item.message, fontSize = 13.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotificationSheet = false }) { Text("Close") }
            }
        )
    }

    // 5. PASSENGER REQUEST DETAIL DIALOG (FOR DRIVERS - DRIVE & ACCEPT)
    selectedPassengerNeedDetail?.let { need ->
        AlertDialog(
            onDismissRequest = { if (!isAcceptingNeed) selectedPassengerNeedDetail = null },
            title = { Text("RIDE REQUEST", fontWeight = FontWeight.Bold, color = PrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(need.passengerName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Department: ${need.passengerDepartment}", fontSize = 14.sp, color = TextSecondary)
                    Text("Pickup Area: ${need.displayPickup} (${need.pickupSide})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Primary)
                    if (need.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Message: \"${need.message}\"", fontSize = 13.5.sp, color = TextTertiary, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isAcceptingNeed,
                    onClick = {
                        val targetNeed = selectedPassengerNeedDetail ?: return@Button
                        if (hasActivePassengerRide || localTravelState.isPassengerPostOrActive) {
                            actionError = "You already have an active passenger request. Close it before posting Driver availability."
                            selectedPassengerNeedDetail = null
                            return@Button
                        }
                        isAcceptingNeed = true
                        coroutineScope.launch {
                            rideRepository.acceptPassengerNeedDirectly(user, targetNeed)
                                .onSuccess {
                                    isAcceptingNeed = false
                                    selectedPassengerNeedDetail = null
                                    userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.DRIVER_ACTIVE, targetNeed.needId)
                                    handleModeSwitch(TravelMode.DRIVER)
                                    actionSuccess = "Ride confirmed! You're picking up ${targetNeed.passengerName}."
                                }
                                .onFailure { err ->
                                    isAcceptingNeed = false
                                    selectedPassengerNeedDetail = null
                                    val rawMsg = err.message.orEmpty()
                                    actionError = when {
                                        rawMsg.contains("already been accepted", ignoreCase = true) ||
                                        rawMsg.contains("no longer available", ignoreCase = true) ->
                                            "This ride request is no longer available."
                                        rawMsg.contains("network", ignoreCase = true) || rawMsg.contains("unavailable", ignoreCase = true) ->
                                            "Connect to the internet to confirm this ride."
                                        else ->
                                            err.message ?: "Couldn't accept this ride. Please try again."
                                    }
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (isAcceptingNeed) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("DRIVE & ACCEPT", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPassengerNeedDetail = null }, enabled = !isAcceptingNeed) { Text("Close") }
            }
        )
    }

    // 6. COMMUTE CHECK-IN MODAL (RESPECTS LOCAL ACTIVE TRAVEL STATE)
    checkInSlot?.let { slot ->
        val hasActiveDriver = (todayRideState != null && todayRideState?.status in listOf(
            Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED
        )) || localTravelState.isDriverPostOrActive
        val hasActivePassenger = hasActivePassengerRide || localTravelState.isPassengerPostOrActive

        if (hasActivePassenger) {
            AlertDialog(
                onDismissRequest = { checkInSlot = null },
                title = { Text("ACTIVE PASSENGER REQUEST", fontWeight = FontWeight.Bold, color = PrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (hasActivePassengerRide)
                                "You already have an active confirmed ride with ${activePassengerRequest?.driverName.orEmpty().ifBlank { "your driver" }}."
                            else
                                "You already have an active passenger request.",
                            fontSize = 14.5.sp,
                            color = TextPrimary
                        )
                        Text("Close or complete your current ride before posting as a Driver.", fontSize = 13.5.sp, color = TextSecondary)
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                checkInSlot = null
                                handleModeSwitch(TravelMode.PASSENGER)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("VIEW CURRENT RIDE", fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        }

                        TextButton(
                            onClick = { checkInSlot = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("NOT THIS TIME", fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        }
                    }
                },
                dismissButton = {}
            )
        } else if (hasActiveDriver) {
            AlertDialog(
                onDismissRequest = { checkInSlot = null },
                title = { Text("ACTIVE DRIVER AVAILABILITY", fontWeight = FontWeight.Bold, color = PrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You already have active Driver availability.", fontSize = 14.5.sp, color = TextPrimary)
                        Text("Close your current availability before requesting a ride as a Passenger.", fontSize = 13.5.sp, color = TextSecondary)
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                checkInSlot = null
                                handleModeSwitch(TravelMode.DRIVER)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("VIEW ACTIVE RIDE", fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        }

                        TextButton(
                            onClick = { checkInSlot = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("NOT THIS TIME", fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        }
                    }
                },
                dismissButton = {}
            )
        } else {
            AlertDialog(
                onDismissRequest = { checkInSlot = null },
                title = { Text("HOW ARE YOU TRAVELLING?", fontWeight = FontWeight.Bold, color = PrimaryDark) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Check in for today's commute", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Let colleagues know whether you're driving or need a ride.", fontSize = 13.5.sp, color = TextSecondary)
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Option 1: I'M DRIVING
                        Button(
                            onClick = {
                                checkInSlot = null
                                handleModeSwitch(TravelMode.DRIVER)
                                coroutineScope.launch {
                                    val pickup = if (driverPickupZone.isNotBlank()) driverPickupZone else lastTownshipPickup
                                    rideRepository.createOrUpdateDriverAvailability(
                                        user = user,
                                        date = todayDate,
                                        pickupZone = pickup,
                                        totalSeats = user.defaultDriverSeats.coerceIn(1, 8)
                                    ).onSuccess { ride ->
                                        userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.DRIVER_AVAILABLE, ride.rideId)
                                        actionSuccess = "Availability posted."
                                    }.onFailure { err ->
                                        actionError = "Couldn't post availability: ${err.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 2.dp)) {
                                Text("🚗  I'M DRIVING", fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                                Text("Offer seats to colleagues", fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                            }
                        }

                        // Option 2: I NEED A RIDE
                        OutlinedButton(
                            onClick = {
                                checkInSlot = null
                                handleModeSwitch(TravelMode.PASSENGER)
                                coroutineScope.launch {
                                    val pickup = if (passengerPickupZone.isNotBlank()) passengerPickupZone else lastTownshipPickup
                                    rideRepository.createPassengerNeed(
                                        user = user,
                                        date = todayDate,
                                        pickupZone = pickup
                                    ).onSuccess { need ->
                                        userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.PASSENGER_LOOKING, need.needId)
                                        actionSuccess = "Ride request posted."
                                    }.onFailure { err ->
                                        actionError = "Couldn't post ride request: ${err.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 2.dp)) {
                                Text("👤  I NEED A RIDE", fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = PrimaryDark)
                                Text("Let drivers know you need a seat", fontSize = 11.5.sp, color = TextSecondary)
                            }
                        }

                        // Option 3: NOT THIS TIME
                        TextButton(
                            onClick = {
                                checkInSlot = null
                                actionSuccess = "No ride posted."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("—  NOT THIS TIME", fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        }
                    }
                },
                dismissButton = {}
            )
        }
    }

    // ----------------------------------------------------
    // MAIN HOME SCREEN SCAFFOLD
    // ----------------------------------------------------

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // 1. Top Header with Notification Bell
            TravelBuddyTopHeader(
                userName = user.name,
                unreadNotificationCount = unreadNotificationCount,
                onNotificationClick = {
                    showNotificationSheet = true
                    TravelBuddyNotificationManager.markAllRead()
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Mode Switcher (Pure Local UI preference - 0 Firestore reads/writes)
            ModeSwitcher(
                currentMode = selectedMode.toUserMode(),
                enabled = true,
                onModeChanged = { targetModeStr ->
                    handleModeSwitch(TravelMode.fromString(targetModeStr))
                }
            )

            Spacer(modifier = Modifier.height(22.dp))

            actionError?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actionError = null }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            actionSuccess?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SuccessContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, color = Success, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actionSuccess = null }) {
                            Text("OK", color = Success)
                        }
                    }
                }
            }

            // =========================================================
            // PASSENGER HOME VIEW
            // =========================================================
            if (!isDriverMode) {
                when (val eligibility = passengerEligibility) {
                    is PassengerEligibility.ActiveRide -> {
                        val activeReq = eligibility.request
                        // 1. MY RIDE CARD FOR PASSENGER (ONLY ACTION PANEL WHEN MATCHED)
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
                                    StatusChip(status = activeReq.status)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text("Driver: ${activeReq.driverName}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Pickup: ${activeReq.displayPickup}", fontSize = 14.5.sp, color = TextSecondary)
                                val driverPhone = activeReq.driverPhone.ifBlank { eligibility.ride?.driverPhone.orEmpty() }
                                if (driverPhone.isNotBlank()) {
                                    ClickablePhoneRow(
                                        phone = driverPhone,
                                        contactName = activeReq.driverName,
                                        label = "Driver Phone"
                                    )
                                }
                                if (activeReq.message.isNotBlank()) {
                                    Text("Note: \"${activeReq.message}\"", fontSize = 13.5.sp, color = TextTertiary)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { showPassengerCloseSheet = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("CLOSE MY RIDE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    is PassengerEligibility.LookingForRide -> {
                        val need = eligibility.need
                        // 2. LOOKING FOR A RIDE CARD (ONLY ACTION PANEL WHEN LOOKING)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "LOOKING FOR A RIDE",
                                        color = PrimaryDark,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    StatusChip(status = need.status)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Pickup: ${need.displayPickup} (${need.pickupSide})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Status: Looking for a driver", fontSize = 14.sp, color = TextSecondary)

                                if (need.message.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Message: \"${need.message}\"", fontSize = 13.5.sp, color = TextTertiary, fontWeight = FontWeight.Medium)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                OutlinedButton(
                                    onClick = { showCancelNeedDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Cancel Request", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    is PassengerEligibility.CanCreateRequest -> {
                        val hasActiveDriver = (todayRideState != null && todayRideState?.status in listOf(
                            Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED
                        )) || localTravelState.isDriverPostOrActive

                        if (hasActiveDriver) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text(
                                        "ACTIVE DRIVER AVAILABILITY",
                                        color = PrimaryDark,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "You already have active Driver availability. Close it before requesting a ride.",
                                        fontSize = 14.5.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { handleModeSwitch(TravelMode.DRIVER) },
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("VIEW DRIVER AVAILABILITY", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            // 3. I NEED A RIDE CREATION FORM (ONLY ACTION PANEL WHEN OPEN)
                            val isOtherPickup = passengerPickupZone == "OTHER" || passengerPickupZone.equals(MasterData.OTHER_LOCATION_NAME, ignoreCase = true)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(22.dp)),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("I NEED A RIDE", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                                    Text("Post your ride request", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Shared Reusable Pickup Area Selector
                                    PickupAreaSelector(
                                        selectedArea = passengerPickupZone,
                                        onAreaSelected = { newArea ->
                                            passengerPickupZone = newArea
                                            coroutineScope.launch {
                                                userPreferencesRepository.savePickupForSide(user.uid, newArea)
                                            }
                                        }
                                    )

                                    if (isOtherPickup) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = passengerCustomPickup,
                                            onValueChange = { if (it.length <= 150) passengerCustomPickup = it },
                                            label = { Text("Pickup Location (Required)") },
                                            placeholder = { Text("e.g. Main Hospital Gate, Head Office Parking") },
                                            singleLine = true,
                                            isError = passengerCustomPickup.isBlank(),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = passengerMessage,
                                        onValueChange = { if (it.length <= 250) passengerMessage = it },
                                        label = { Text("Message for Drivers (optional)") },
                                        placeholder = { Text("e.g. Waiting near C-Type market.") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        enabled = !isPostingNeed,
                                        onClick = {
                                            if (user.isPendingApproval()) {
                                                actionError = "Your account is awaiting Admin approval. Please wait for an administrator to activate your account."
                                                return@Button
                                            }
                                            if (hasActiveDriver) {
                                                actionError = "You already have active Driver availability. Close it before requesting another ride."
                                                return@Button
                                            }
                                            if (passengerPickupZone.isBlank()) {
                                                actionError = "Please select your pickup area."
                                                return@Button
                                            }
                                            if (isOtherPickup && passengerCustomPickup.isBlank()) {
                                                actionError = "Please enter your pickup location."
                                                return@Button
                                            }
                                            isPostingNeed = true
                                            coroutineScope.launch {
                                                rideRepository.createPassengerNeed(
                                                    user = user,
                                                    date = todayDate,
                                                    pickupZone = passengerPickupZone,
                                                    message = passengerMessage,
                                                    customPickupLocation = if (isOtherPickup) passengerCustomPickup.trim() else ""
                                                ).onSuccess { need ->
                                                    isPostingNeed = false
                                                    userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.PASSENGER_LOOKING, need.needId)
                                                    actionSuccess = "Ride request posted."
                                                }.onFailure { err ->
                                                    isPostingNeed = false
                                                    val msg = err.message.orEmpty()
                                                    actionError = when {
                                                        msg.contains("PERMISSION_DENIED", ignoreCase = true) || msg.contains("restricted", ignoreCase = true) ->
                                                            "Permission error: Your account may be pending Admin approval or Firestore rules need to be published in Firebase Console."
                                                        else -> "Couldn't post request: ${err.message}"
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        if (isPostingNeed) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                                        } else {
                                            Text("POST RIDE REQUEST", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is PassengerEligibility.Loading -> {
                        // 4. RESTORING / LOADING STATE (PREVENTS RACING INITIAL ACTIVE-STATE CHECK)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .border(1.dp, Outline.copy(alpha = 0.50f), RoundedCornerShape(22.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Primary)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Checking ride status...", fontSize = 13.5.sp, color = TextSecondary)
                            }
                        }
                    }

                    is PassengerEligibility.Error -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(
                                text = eligibility.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }

                // 4. DISCOVERY & AVAILABLE DRIVERS SECTION (OMITTED WHEN MATCHED TO SAVE SPARK READS)
                if (!hasActivePassengerRide && passengerEligibility !is PassengerEligibility.Loading) {
                    Spacer(modifier = Modifier.height(22.dp))

                    MetricSummaryCard(
                        driverCount = totalDrivers,
                        seatCount = totalAvailableSeats,
                        groupCount = pickupGroups.size
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    val visibleDrivers = availableDrivers.filter { driverRide ->
                        driverRide.rideId != activePassengerRequest?.rideId &&
                            !driverRide.acceptedPassengerIds.contains(user.uid)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Rounded.LocationOn, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AVAILABLE DRIVERS (${activePassengerSide})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (visibleDrivers.isEmpty()) {
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
                                Text("No drivers published for ${activePassengerSide} yet.", fontSize = 14.5.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Post your ride request so nearby drivers can accept directly!", fontSize = 13.sp, color = TextTertiary, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        visibleDrivers.forEach { driverRide ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(driverRide.driverName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        StatusChip(status = driverRide.status)
                                    }
                                    Text("Dept: ${driverRide.driverDepartment}", fontSize = 13.5.sp, color = TextSecondary)
                                    Text("Pickup: ${driverRide.displayPickup}", fontSize = 13.5.sp, color = TextTertiary, fontWeight = FontWeight.Medium)
                                    Text("${driverRide.availableSeats} seat(s) available", fontSize = 13.sp, color = PrimaryDark, fontWeight = FontWeight.Bold)

                                    if (driverRide.message.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Note: \"${driverRide.message}\"", fontSize = 13.sp, color = TextSecondary)
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val hasActiveDriver = (todayRideState != null && todayRideState?.status in listOf(
                                                Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED
                                            )) || localTravelState.isDriverPostOrActive
                                            if (hasActiveDriver) {
                                                actionError = "You already have active Driver availability. Close it before requesting another ride."
                                                return@Button
                                            }
                                            coroutineScope.launch {
                                                rideRepository.joinRide(user, driverRide)
                                                    .onSuccess {
                                                        userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.PASSENGER_ACTIVE, driverRide.rideId)
                                                        actionSuccess = "Joined ${driverRide.driverName}'s ride!"
                                                    }
                                                    .onFailure { err ->
                                                        actionError = "Couldn't join ride: ${err.message}"
                                                    }
                                            }
                                        },
                                        enabled = !hasActivePassengerRide,
                                        modifier = Modifier.fillMaxWidth().height(38.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("JOIN RIDE", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 5. PASSENGER IMPACT CARD
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 155.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.impact_landscape),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .fillMaxHeight(0.75f)
                                .fillMaxWidth(0.45f)
                                .alpha(0.28f)
                        )

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Rounded.Eco, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("YOUR PASSENGER IMPACT", color = PrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_shared_rides,
                                    value = "$eligiblePassengerCompletedRidesCount",
                                    label = "Shared Rides",
                                    modifier = Modifier.weight(1f)
                                )
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_distance,
                                    value = String.format(Locale.US, "%.0f km", userAvoidedKm),
                                    label = "Avoided",
                                    modifier = Modifier.weight(1f)
                                )
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_co2,
                                    value = String.format(Locale.US, "%.1f kg", userCo2SavedKg),
                                    label = "CO₂ Saved",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            // =========================================================
            // DRIVER HOME VIEW
            // =========================================================
            else {
                val hasActiveRide = todayRideState != null &&
                    todayRideState?.status in listOf(
                        Ride.STATUS_AVAILABLE,
                        Ride.STATUS_PARTIALLY_FULL,
                        Ride.STATUS_FULL,
                        Ride.STATUS_RIDE_STARTED
                    )

                if (!hasActiveRide) {
                    val hasActivePassenger = hasActivePassengerRide || localTravelState.isPassengerPostOrActive
                    if (hasActivePassenger) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    "ACTIVE PASSENGER REQUEST",
                                    color = PrimaryDark,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "You already have an active passenger request. Close it before posting Driver availability.",
                                    fontSize = 14.5.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { handleModeSwitch(TravelMode.PASSENGER) },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("VIEW PASSENGER REQUEST", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // OFFER A RIDE CARD
                        val isOtherDriverPickup = driverPickupZone == "OTHER" || driverPickupZone.equals(MasterData.OTHER_LOCATION_NAME, ignoreCase = true)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(22.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("OFFER A RIDE TO COLLEAGUES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryDark, letterSpacing = 0.5.sp)
                                Text("Set up your availability", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

                                Spacer(modifier = Modifier.height(14.dp))

                                // Shared Reusable Pickup Area Selector
                                PickupAreaSelector(
                                    selectedArea = driverPickupZone,
                                    onAreaSelected = { newArea ->
                                        driverPickupZone = newArea
                                        coroutineScope.launch {
                                            userPreferencesRepository.savePickupForSide(user.uid, newArea)
                                        }
                                    }
                                )

                                if (isOtherDriverPickup) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = driverCustomPickup,
                                        onValueChange = { if (it.length <= 150) driverCustomPickup = it },
                                        label = { Text("Pickup Location (Required)") },
                                        placeholder = { Text("e.g. Main Hospital Gate, Head Office Parking") },
                                        singleLine = true,
                                        isError = driverCustomPickup.isBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Available Seats Counter
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Available Seats", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(
                                            onClick = { driverSeats = (driverSeats - 1).coerceAtLeast(1) },
                                            modifier = Modifier.size(38.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("−", fontSize = 18.sp) }
                                        Text(
                                            driverSeats.toString(),
                                            modifier = Modifier.width(36.dp),
                                            textAlign = TextAlign.Center,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedButton(
                                            onClick = { driverSeats = (driverSeats + 1).coerceAtMost(8) },
                                            modifier = Modifier.size(38.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("+", fontSize = 18.sp) }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = driverMessage,
                                    onValueChange = { if (it.length <= 250) driverMessage = it },
                                    label = { Text("Message for Passengers (optional)") },
                                    placeholder = { Text("e.g. Leaving from EMs main gate.") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        if (user.isPendingApproval()) {
                                            actionError = "Your account is awaiting Admin approval. Please wait for an administrator to activate your account."
                                            return@Button
                                        }
                                        if (hasActivePassenger) {
                                            actionError = "You already have an active passenger request. Close it before posting Driver availability."
                                            return@Button
                                        }
                                        if (driverPickupZone.isBlank()) {
                                            actionError = "Please select your pickup area."
                                            return@Button
                                        }
                                        if (isOtherDriverPickup && driverCustomPickup.isBlank()) {
                                            actionError = "Please enter your pickup location."
                                            return@Button
                                        }
                                        coroutineScope.launch {
                                            rideRepository.createOrUpdateDriverAvailability(
                                                user = user,
                                                date = todayDate,
                                                pickupZone = driverPickupZone,
                                                totalSeats = driverSeats,
                                                message = driverMessage,
                                                customPickupLocation = if (isOtherDriverPickup) driverCustomPickup.trim() else ""
                                            ).onSuccess { ride ->
                                                userPreferencesRepository.saveLocalTravelState(user.uid, LocalTravelState.DRIVER_AVAILABLE, ride.rideId)
                                                actionSuccess = "Availability posted."
                                            }.onFailure { err ->
                                                val msg = err.message.orEmpty()
                                                actionError = when {
                                                    msg.contains("PERMISSION_DENIED", ignoreCase = true) || msg.contains("restricted", ignoreCase = true) ->
                                                        "Permission error: Your account may be pending Admin approval or Firestore rules need to be published in Firebase Console."
                                                    else -> "Couldn't publish your ride: ${err.message}"
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("PUBLISH AVAILABILITY", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    val currentRide = todayRideState!!
                    val completedCount = currentRide.completedPassengerIds.size
                    val totalAccepted = currentRide.acceptedPassengerIds.size

                    // ACTIVE RIDE CARD FOR DRIVER
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
                                Text("TODAY'S ACTIVE RIDE", color = PrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                StatusChip(status = currentRide.status)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text("Pickup: ${currentRide.displayPickup} (${currentRide.pickupSide})", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("$totalAccepted confirmed • ${currentRide.availableSeats} available", fontSize = 14.5.sp, color = TextSecondary)

                            if (currentRide.message.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Message: \"${currentRide.message}\"", fontSize = 13.5.sp, color = TextTertiary)
                            }

                            if (totalAccepted > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Passengers completed: $completedCount / $totalAccepted", fontSize = 14.sp, color = PrimaryDark, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { showDriverCloseDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(if (totalAccepted == 0) "CLOSE AVAILABILITY" else "CLOSE RIDE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // PEOPLE LOOKING FOR A RIDE (DISCOVERED BY PICKUP SIDE + OTHER WILDCARD)
                Text("PEOPLE LOOKING FOR A RIDE (${activeDriverSide})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

                Spacer(modifier = Modifier.height(12.dp))

                if (matchingPassengerNeeds.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(18.dp)),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No passengers currently looking for a ride on ${activeDriverSide}.", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                } else {
                    matchingPassengerNeeds.forEach { need ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { selectedPassengerNeedDetail = need },
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(need.passengerName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    StatusChip(status = need.status)
                                }
                                Text("Dept: ${need.passengerDepartment}", fontSize = 13.5.sp, color = TextSecondary)
                                Text("Pickup: ${need.displayPickup}", fontSize = 13.5.sp, color = TextTertiary, fontWeight = FontWeight.Medium)

                                if (need.message.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Note: \"${need.message}\"", fontSize = 13.sp, color = TextSecondary)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { selectedPassengerNeedDetail = need },
                                    modifier = Modifier.fillMaxWidth().height(38.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("DRIVE & ACCEPT", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // CONFIRMED PASSENGERS FOR PUBLISHED DRIVER
                val confirmedPassengers = incomingRequests.filter { it.status == RideRequest.STATUS_ACCEPTED }
                Text("CONFIRMED PASSENGERS (${confirmedPassengers.size})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

                Spacer(modifier = Modifier.height(12.dp))

                if (confirmedPassengers.isEmpty()) {
                    Text("No passengers have joined this ride yet.", color = TextSecondary, fontSize = 14.sp)
                } else {
                    confirmedPassengers.forEach { req ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(req.passengerName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    StatusChip(status = req.status)
                                }
                                Text("Dept: ${req.passengerDepartment}", fontSize = 13.5.sp, color = TextSecondary)
                                Text("Pickup Area: ${req.displayPickup}", fontSize = 13.5.sp, color = TextTertiary)
                                if (req.passengerPhone.isNotBlank()) {
                                    ClickablePhoneRow(
                                        phone = req.passengerPhone,
                                        contactName = req.passengerName,
                                        label = "Phone"
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // DRIVER IMPACT CARD
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 155.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, Outline.copy(alpha = 0.70f), RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.impact_landscape),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .fillMaxHeight(0.75f)
                                .fillMaxWidth(0.45f)
                                .alpha(0.28f)
                        )

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Rounded.Eco, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("YOUR DRIVER IMPACT", color = PrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_shared_rides,
                                    value = "$driverCompletedRidesCount",
                                    label = "Shared Rides",
                                    modifier = Modifier.weight(1f)
                                )
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_distance,
                                    value = "$totalPassengersDriven",
                                    label = "Passengers",
                                    modifier = Modifier.weight(1f)
                                )
                                ImpactMetricItem(
                                    iconRes = R.drawable.metric_co2,
                                    value = String.format(Locale.US, "%.1f kg", driverCo2SavedKg),
                                    label = "CO₂ Saved",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6. TOP CO₂ SAVERS LEADERBOARD WIDGET (UNIFIED TOTAL IMPACT)
            LeaderboardWidget(
                topSavers = displayLeaderboardEntries,
                currentUserId = user.uid
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ImpactMetricItem(
    iconRes: Int,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(38.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = TextSecondary,
            maxLines = 2,
            fontWeight = FontWeight.Medium
        )
    }
}
