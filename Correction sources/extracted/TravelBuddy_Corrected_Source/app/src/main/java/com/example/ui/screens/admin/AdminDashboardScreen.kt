package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.AdminRepository
import com.example.ui.components.ErrorContainer
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    user: User,
    onNavigateBack: () -> Unit,
    adminRepository: AdminRepository = remember { AdminRepository() }
) {
    if (!user.isAdmin()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Background).padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            ErrorContainer(message = "Access denied. Administrator privileges are required.")
        }
        return
    }

    val scope = rememberCoroutineScope()
    val users by adminRepository.getAllUsersFlow().collectAsState(initial = emptyList())
    val rides by adminRepository.getAllRidesFlow().collectAsState(initial = emptyList())
    val requests by adminRepository.getAllRideRequestsFlow().collectAsState(initial = emptyList())
    val config by adminRepository.getSystemConfigFlow().collectAsState(initial = com.example.data.model.SystemConfig())

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Users", "Analytics", "Settings")
    var search by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var targetUser by remember { mutableStateOf<User?>(null) }
    var promoteTarget by remember { mutableStateOf(true) }
    var baselineText by remember { mutableStateOf(config.baselineDistanceKm.toString()) }
    var factorText by remember { mutableStateOf(config.co2FactorKgPerKm.toString()) }
    var rankingMode by remember { mutableStateOf("Drivers") }

    LaunchedEffect(config) {
        baselineText = config.baselineDistanceKm.toString()
        factorText = config.co2FactorKgPerKm.toString()
    }

    val today = remember { LocalDate.now().toString() }
    val completedRides = remember(rides) { rides.filter { it.status == Ride.STATUS_COMPLETED } }
    val completedRequests = remember(requests) { requests.filter { it.status == RideRequest.STATUS_COMPLETED } }
    val totalPassengersShared = completedRides.sumOf { it.acceptedPassengerIds.size }
    val totalAvoidedKm = totalPassengersShared * config.baselineDistanceKm
    val totalCo2 = totalAvoidedKm * config.co2FactorKgPerKm
    val adminCount = users.count { it.isAdmin() }
    val passengerModeCount = users.count { it.isPassengerMode() }
    val driverModeCount = users.count { it.isDriverMode() }
    val activeParticipantIds = buildSet {
        completedRides.forEach { ride ->
            add(ride.driverId)
            addAll(ride.acceptedPassengerIds)
        }
    }

    val todayAvailableDrivers = rides.count {
        it.date == today && it.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL)
    }
    val todayPendingRequests = requests.count { it.date == today && it.status == RideRequest.STATUS_REQUESTED }
    val todayActiveRides = rides.count { it.date == today && it.status == Ride.STATUS_RIDE_STARTED }
    val todayCompletedRides = rides.count { it.date == today && it.status == Ride.STATUS_COMPLETED }
    val todayCancelledRides = rides.count { it.date == today && it.status == Ride.STATUS_CANCELLED }

    val filteredUsers = remember(users, search) {
        if (search.isBlank()) users.sortedBy { it.name.lowercase() }
        else users.filter {
            it.name.contains(search, true) ||
                it.email.contains(search, true) ||
                it.employeeId.contains(search, true) ||
                it.department.contains(search, true)
        }.sortedBy { it.name.lowercase() }
    }

    val userById = remember(users) { users.associateBy { it.uid } }

    val driverRanking = remember(completedRides, config) {
        completedRides.groupBy { it.driverId }.map { (uid, driverRides) ->
            val passengerCount = driverRides.sumOf { it.acceptedPassengerIds.size }
            ContributorRow(
                uid = uid,
                name = driverRides.firstOrNull()?.driverName.orEmpty().ifBlank { userById[uid]?.name ?: "Unknown" },
                department = driverRides.firstOrNull()?.driverDepartment.orEmpty().ifBlank { userById[uid]?.department.orEmpty() },
                rideCount = driverRides.size,
                passengerCount = passengerCount,
                co2Kg = passengerCount * config.baselineDistanceKm * config.co2FactorKgPerKm
            )
        }.sortedByDescending { it.co2Kg }
    }

    val passengerRanking = remember(completedRequests, config, userById) {
        completedRequests.groupBy { it.passengerId }.map { (uid, passengerRequests) ->
            ContributorRow(
                uid = uid,
                name = passengerRequests.firstOrNull()?.passengerName.orEmpty().ifBlank { userById[uid]?.name ?: "Unknown" },
                department = passengerRequests.firstOrNull()?.passengerDepartment.orEmpty().ifBlank { userById[uid]?.department.orEmpty() },
                rideCount = passengerRequests.size,
                passengerCount = passengerRequests.size,
                co2Kg = passengerRequests.size * config.baselineDistanceKm * config.co2FactorKgPerKm
            )
        }.sortedByDescending { it.co2Kg }
    }

    Scaffold(containerColor = Background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("TravelBuddy Admin", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Mobility & sustainability overview", fontSize = 14.sp, color = TextSecondary)
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(PrimaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = PrimaryDark)
                }
            }

            Spacer(Modifier.height(16.dp))
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium) }
                    )
                }
            }

            statusMessage?.let {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = SuccessContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp), color = Success, fontSize = 13.sp)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 32.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        SectionTitle("EXECUTIVE SNAPSHOT")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminMetricCard("Registered Users", users.size.toString(), Icons.Rounded.Groups, Modifier.weight(1f))
                            AdminMetricCard("Active Participants", activeParticipantIds.size.toString(), Icons.Rounded.Groups, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminMetricCard("Completed Rides", completedRides.size.toString(), Icons.Rounded.DirectionsCar, Modifier.weight(1f))
                            AdminMetricCard("CO₂ Saved", String.format(Locale.US, "%.1f kg", totalCo2), Icons.Rounded.Eco, Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(22.dp))
                        SectionTitle("TODAY'S OPERATIONS")
                        InfoCard {
                            OperationalRow("Available Drivers", todayAvailableDrivers, Icons.Rounded.DirectionsCar)
                            OperationalRow("Pending Requests", todayPendingRequests, Icons.Rounded.PersonAddAlt1)
                            OperationalRow("Active Rides", todayActiveRides, Icons.Rounded.Route)
                            OperationalRow("Completed Today", todayCompletedRides, Icons.Rounded.CheckCircle)
                            OperationalRow("Cancelled Today", todayCancelledRides, Icons.Rounded.Cancel)
                        }

                        Spacer(Modifier.height(22.dp))
                        SectionTitle("USER MIX")
                        InfoCard {
                            OperationalRow("Administrators", adminCount, Icons.Rounded.AdminPanelSettings)
                            OperationalRow("Passenger Mode", passengerModeCount, Icons.Rounded.Person)
                            OperationalRow("Driver Mode", driverModeCount, Icons.Rounded.DirectionsCar)
                        }

                        Spacer(Modifier.height(22.dp))
                        SectionTitle("SUSTAINABILITY")
                        InfoCard {
                            ImpactRow("Passengers Shared", totalPassengersShared.toString())
                            ImpactRow("Vehicle Trips Avoided", totalPassengersShared.toString())
                            ImpactRow("Distance Avoided", String.format(Locale.US, "%.1f km", totalAvoidedKm))
                            ImpactRow("CO₂ Saved", String.format(Locale.US, "%.2f kg", totalCo2))
                        }
                    }

                    1 -> {
                        SectionTitle("USER MANAGEMENT")
                        Text("${users.size} registered • $adminCount administrators", color = TextSecondary, fontSize = 13.5.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            label = { Text("Search name, email, employee ID or department") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        filteredUsers.forEach { employee ->
                            UserAdminCard(
                                employee = employee,
                                currentAdminUid = user.uid,
                                onPromote = {
                                    targetUser = employee
                                    promoteTarget = true
                                },
                                onDemote = {
                                    targetUser = employee
                                    promoteTarget = false
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    2 -> {
                        SectionTitle("SUSTAINABILITY ANALYTICS")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Drivers", "Passengers").forEach { mode ->
                                FilterChip(
                                    selected = rankingMode == mode,
                                    onClick = { rankingMode = mode },
                                    label = { Text(mode) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (rankingMode == "Drivers")
                                "Driver contribution credits the avoided trips carried by each completed shared ride."
                            else
                                "Passenger contribution credits each completed shared ride taken.",
                            color = TextSecondary,
                            fontSize = 12.5.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        val ranking = if (rankingMode == "Drivers") driverRanking else passengerRanking
                        if (ranking.isEmpty()) {
                            InfoCard { Text("No completed rides yet.", color = TextSecondary) }
                        } else {
                            ranking.forEachIndexed { index, row ->
                                RankingCard(index + 1, row, rankingMode)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Overall system CO₂ is counted once from completed rides; driver/passenger rankings are attribution views and are not added together.",
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }

                    3 -> {
                        SectionTitle("SUSTAINABILITY PARAMETERS")
                        InfoCard {
                            OutlinedTextField(
                                value = baselineText,
                                onValueChange = { baselineText = it },
                                label = { Text("Baseline distance per avoided trip (km)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = factorText,
                                onValueChange = { factorText = it },
                                label = { Text("CO₂ factor (kg/km)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    val km = baselineText.toDoubleOrNull()
                                    val factor = factorText.toDoubleOrNull()
                                    if (km == null || factor == null || km <= 0 || factor <= 0) {
                                        statusMessage = "Enter valid positive numeric values."
                                    } else {
                                        scope.launch {
                                            adminRepository.updateSustainabilityConfig(km, factor)
                                                .onSuccess { statusMessage = "Sustainability parameters updated." }
                                                .onFailure { statusMessage = "Couldn't update parameters. Please try again." }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Save Parameters", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    targetUser?.let { employee ->
        AlertDialog(
            onDismissRequest = { targetUser = null },
            title = { Text(if (promoteTarget) "Grant Administrator Access" else "Remove Administrator Access") },
            text = {
                Text(
                    if (promoteTarget)
                        "Grant administrator access to ${employee.name}? Their Passenger/Driver mode will not change."
                    else
                        "Remove administrator access from ${employee.name}? Their Passenger/Driver mode will not change."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            adminRepository.setUserAdminRole(employee.uid, promoteTarget)
                                .onSuccess {
                                    statusMessage = if (promoteTarget)
                                        "${employee.name} is now an administrator."
                                    else
                                        "Administrator access removed from ${employee.name}."
                                }
                                .onFailure { statusMessage = it.message ?: "Role update failed." }
                            targetUser = null
                        }
                    }
                ) { Text(if (promoteTarget) "Make Admin" else "Remove Access") }
            },
            dismissButton = {
                TextButton(onClick = { targetUser = null }) { Text("Cancel") }
            }
        )
    }
}

private data class ContributorRow(
    val uid: String,
    val name: String,
    val department: String,
    val rideCount: Int,
    val passengerCount: Int,
    val co2Kg: Double
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = PrimaryDark, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun AdminMetricCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(116.dp).border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(PrimaryVeryLight), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(label, fontSize = 12.5.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun OperationalRow(label: String, value: Int, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = 14.sp)
        Text(value.toString(), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun ImpactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UserAdminCard(
    employee: User,
    currentAdminUid: String,
    onPromote: () -> Unit,
    onDemote: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(employee.name.ifBlank { "Unnamed Employee" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("${employee.employeeId} • ${employee.department}", fontSize = 13.sp, color = TextSecondary)
                    Text(employee.email, fontSize = 12.5.sp, color = TextTertiary)
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (employee.isAdmin()) PrimaryVeryLight else SurfaceVariant
                ) {
                    Text(
                        employee.effectiveRole,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (employee.isAdmin()) Primary else TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Current travel mode: ${employee.effectiveMode}", fontSize = 12.5.sp, color = TextSecondary)
            Spacer(Modifier.height(10.dp))
            if (employee.uid == currentAdminUid) {
                Text("Current Admin", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            } else if (employee.isAdmin()) {
                OutlinedButton(onClick = onDemote, modifier = Modifier.height(40.dp)) { Text("Remove Admin Access") }
            } else {
                Button(onClick = onPromote, modifier = Modifier.height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Text("Make Admin", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RankingCard(rank: Int, row: ContributorRow, mode: String) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(PrimaryVeryLight), contentAlignment = Alignment.Center) {
                Text(rank.toString(), fontWeight = FontWeight.Bold, color = PrimaryDark)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.5.sp)
                Text(row.department, color = TextSecondary, fontSize = 12.5.sp)
                Text(
                    if (mode == "Drivers") "${row.rideCount} rides • ${row.passengerCount} passengers"
                    else "${row.rideCount} shared rides",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
            Text(String.format(Locale.US, "%.2f kg", row.co2Kg), color = Primary, fontWeight = FontWeight.Bold)
        }
    }
}
