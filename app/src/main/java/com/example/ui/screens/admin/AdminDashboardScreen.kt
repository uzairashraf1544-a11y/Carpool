package com.example.ui.screens.admin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AuditLog
import com.example.data.model.LeaderboardEntry
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.AdminRepository
import com.example.ui.components.ErrorContainer
import com.example.ui.theme.*
import com.example.util.ManagementReportRow
import com.example.util.ReportExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val users by adminRepository.getAllUsersFlow().collectAsState(initial = emptyList())
    val rides by adminRepository.getAllRidesFlow().collectAsState(initial = emptyList())
    val requests by adminRepository.getAllRideRequestsFlow().collectAsState(initial = emptyList())
    val auditLogs by adminRepository.getAuditLogsFlow().collectAsState(initial = emptyList())
    val config by adminRepository.getSystemConfigFlow().collectAsState(initial = com.example.data.model.SystemConfig())

    var selectedTab by remember { mutableIntStateOf(0) }
    val pendingApprovalsCount = users.count { it.isPendingApproval() }
    val pendingUsers = remember(users) {
        users.filter { it.isPendingApproval() }
            .sortedByDescending { it.approvalRequestedAt.takeIf { t -> t > 0L } ?: it.createdAt }
    }
    val tabs = if (pendingApprovalsCount > 0) {
        listOf("Overview", "Pending ($pendingApprovalsCount)", "Members", "Reports", "Analytics", "Audit Logs", "Settings")
    } else {
        listOf("Overview", "Pending", "Members", "Reports", "Analytics", "Audit Logs", "Settings")
    }

    var search by remember { mutableStateOf("") }
    var memberStatusFilter by remember { mutableStateOf("All") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Dialog States
    var targetMemberAction by remember { mutableStateOf<User?>(null) }
    var memberActionType by remember { mutableStateOf<String?>(null) } // "BLOCK", "UNBLOCK", "REMOVE", "ROLE"
    var actionReason by remember { mutableStateOf("") }
    var promoteTargetRole by remember { mutableStateOf(true) }

    // Sustainability settings
    var baselineText by remember { mutableStateOf(config.baselineDistanceKm.toString()) }
    var factorText by remember { mutableStateOf(config.co2FactorKgPerKm.toString()) }

    // Analytics state
    var rankingMode by remember { mutableStateOf("Drivers") }

    // Report State
    var reportPreset by remember { mutableStateOf("All Time") }
    var reportFromDate by remember { mutableStateOf("") }
    var reportToDate by remember { mutableStateOf("") }
    var reportStatusFilter by remember { mutableStateOf("All") }
    var reportSortBy by remember { mutableStateOf("CO2_DESC") } // CO2_DESC, NAME_ASC, DRIVER_DESC, PASSENGER_DESC

    LaunchedEffect(config) {
        baselineText = config.baselineDistanceKm.toString()
        factorText = config.co2FactorKgPerKm.toString()
    }

    val today = remember { LocalDate.now().toString() }
    val completedRides = remember(rides) { rides.filter { it.status == Ride.STATUS_COMPLETED } }
    val completedRequests = remember(requests) { requests.filter { it.status == RideRequest.STATUS_COMPLETED } }

    val totalPassengersShared = completedRides.sumOf {
        if (it.finalPassengerCount > 0) it.finalPassengerCount else it.acceptedPassengerIds.size
    }
    val totalAvoidedKm = totalPassengersShared * config.baselineDistanceKm
    val totalCo2 = totalAvoidedKm * config.co2FactorKgPerKm
    val adminCount = users.count { it.isAdmin() }
    val activeMembersCount = users.count { it.isActive() }
    val blockedMembersCount = users.count { it.isBlocked() }
    val removedMembersCount = users.count { it.isRemoved() }

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
    val todayActiveRides = rides.count { it.date == today && it.status in listOf(Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED) }
    val todayCompletedRides = rides.count { it.date == today && it.status == Ride.STATUS_COMPLETED }
    val todayCancelledRides = rides.count { it.date == today && it.status == Ride.STATUS_CANCELLED }

    // Filtered Members for Member Management Tab
    val filteredMembers = remember(users, search, memberStatusFilter) {
        users.filter { member ->
            val matchesSearch = search.isBlank() ||
                member.name.contains(search, ignoreCase = true) ||
                member.email.contains(search, ignoreCase = true) ||
                member.employeeId.contains(search, ignoreCase = true) ||
                member.department.contains(search, ignoreCase = true)

            val matchesStatus = when (memberStatusFilter) {
                "Active" -> member.isActive()
                "Pending" -> member.isPendingApproval()
                "Blocked" -> member.isBlocked()
                "Removed" -> member.isRemoved()
                else -> true
            }

            matchesSearch && matchesStatus
        }.sortedBy { it.name.lowercase() }
    }

    val userById = remember(users) { users.associateBy { it.uid } }

    // Date range resolver for Management Report
    fun isDateInRange(dateStr: String): Boolean {
        if (reportPreset == "All Time" && reportFromDate.isBlank() && reportToDate.isBlank()) return true
        if (dateStr.isBlank()) return false
        val from = reportFromDate.takeIf { it.isNotBlank() } ?: "1970-01-01"
        val to = reportToDate.takeIf { it.isNotBlank() } ?: "2099-12-31"
        return dateStr >= from && dateStr <= to
    }

    // Set presets
    LaunchedEffect(reportPreset) {
        val now = LocalDate.now()
        when (reportPreset) {
            "All Time" -> {
                reportFromDate = ""
                reportToDate = ""
            }
            "Current Month" -> {
                reportFromDate = now.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                reportToDate = now.withDayOfMonth(now.lengthOfMonth()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
            "Previous Month" -> {
                val prev = now.minusMonths(1)
                reportFromDate = prev.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                reportToDate = prev.withDayOfMonth(prev.lengthOfMonth()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
            "Current Year" -> {
                reportFromDate = now.withDayOfYear(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                reportToDate = now.withDayOfYear(now.lengthOfYear()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
        }
    }

    // Filtered rides and requests for report period
    val periodCompletedRides = remember(completedRides, reportPreset, reportFromDate, reportToDate) {
        completedRides.filter { isDateInRange(it.date) }
    }
    val periodCompletedRequests = remember(completedRequests, reportPreset, reportFromDate, reportToDate) {
        completedRequests.filter { isDateInRange(it.date) }
    }

    // Management Report Row Aggregations (One row per person)
    val managementReportRows: List<ManagementReportRow> = remember(
        users,
        periodCompletedRides,
        periodCompletedRequests,
        config,
        reportStatusFilter,
        reportSortBy
    ) {
        val filteredUsersForReport = users.filter { u ->
            when (reportStatusFilter) {
                "Active" -> u.isActive()
                "Blocked" -> u.isBlocked()
                "Removed" -> u.isRemoved()
                else -> true
            }
        }

        val rawRows = filteredUsersForReport.map { person ->
            val driverRides = periodCompletedRides.filter { it.driverId == person.uid }
            val driverRideCount = driverRides.size
            val passengersDelivered = driverRides.sumOf {
                if (it.finalPassengerCount > 0) it.finalPassengerCount else it.completedPassengerIds.size.coerceAtLeast(it.acceptedPassengerIds.size)
            }

            val passengerReqs = periodCompletedRequests.filter { it.passengerId == person.uid }
            val passengerRideCount = passengerReqs.size

            val avoidedDistanceKm = (passengersDelivered + passengerRideCount) * config.baselineDistanceKm
            val personCo2Saved = avoidedDistanceKm * config.co2FactorKgPerKm

            ManagementReportRow(
                serialNumber = 0, // Assigned after sorting
                personName = person.name.ifBlank { "User ${person.uid.take(5)}" },
                co2Saved = personCo2Saved,
                ridesAsDriver = driverRideCount,
                ridesAsPassenger = passengerRideCount
            )
        }

        val sortedRows = when (reportSortBy) {
            "CO2_DESC" -> rawRows.sortedByDescending { it.co2Saved }
            "NAME_ASC" -> rawRows.sortedBy { it.personName.lowercase() }
            "DRIVER_DESC" -> rawRows.sortedByDescending { it.ridesAsDriver }
            "PASSENGER_DESC" -> rawRows.sortedByDescending { it.ridesAsPassenger }
            else -> rawRows.sortedByDescending { it.co2Saved }
        }

        sortedRows.mapIndexed { index, row -> row.copy(serialNumber = index + 1) }
    }

    // Auto-sync real Top 3 Community CO2 Savers to systemConfig/leaderboard for all members
    LaunchedEffect(managementReportRows) {
        val top3Rows = managementReportRows.filter { it.co2Saved > 0.0 }.take(3)
        if (top3Rows.isNotEmpty()) {
            val topEntries = top3Rows.mapIndexed { idx, row ->
                val userObj = users.firstOrNull { it.name == row.personName || it.uid == row.personName }
                LeaderboardEntry(
                    uid = userObj?.uid.orEmpty(),
                    name = row.personName,
                    department = userObj?.department.orEmpty().ifBlank { "Operations" },
                    co2SavedKg = row.co2Saved,
                    rideCount = row.ridesAsDriver + row.ridesAsPassenger,
                    rank = idx + 1
                )
            }
            adminRepository.updateLeaderboard(topEntries)
        }
    }

    // Report Period Summary Metrics
    val reportTotalMembers = managementReportRows.size
    val reportTotalCo2Saved = managementReportRows.sumOf { it.co2Saved }
    val reportTotalDriverRides = periodCompletedRides.size
    val reportTotalPassengerRides = periodCompletedRequests.size

    // Contributor Rankings for Analytics Tab
    val driverRanking = remember(completedRides, config, userById) {
        completedRides.groupBy { it.driverId }.map { (uid, driverRides) ->
            val passengerCount = driverRides.sumOf {
                if (it.finalPassengerCount > 0) it.finalPassengerCount else it.acceptedPassengerIds.size
            }
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

    // ----------------------------------------------------
    // ACTION DIALOGS (BLOCK, UNBLOCK, REMOVE, ADMIN ROLE)
    // ----------------------------------------------------

    targetMemberAction?.let { target ->
        when (memberActionType) {
            "BLOCK" -> {
                AlertDialog(
                    onDismissRequest = { targetMemberAction = null },
                    title = { Text("Block this member?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("The member will no longer be allowed to join or use the application.")
                            Text("Existing historical ride and CO₂ records will be preserved.", fontSize = 13.sp, color = TextSecondary)
                            OutlinedTextField(
                                value = actionReason,
                                onValueChange = { actionReason = it },
                                label = { Text("Reason for block (optional)") },
                                placeholder = { Text("e.g. Left organization / Policy violation") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val reason = actionReason
                                val member = target
                                targetMemberAction = null
                                actionReason = ""
                                scope.launch {
                                    adminRepository.blockMember(user, member, reason)
                                        .onSuccess { statusMessage = "${member.name} has been blocked." }
                                        .onFailure { statusMessage = it.message ?: "Failed to block member." }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Confirm Block")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { targetMemberAction = null }) { Text("Cancel") }
                    }
                )
            }

            "UNBLOCK" -> {
                AlertDialog(
                    onDismissRequest = { targetMemberAction = null },
                    title = { Text("Unblock ${target.name}?", fontWeight = FontWeight.Bold, color = PrimaryDark) },
                    text = {
                        Text("The member will be allowed to log in and use the application normally again. Historical data remains unchanged.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val member = target
                                targetMemberAction = null
                                scope.launch {
                                    adminRepository.unblockMember(user, member)
                                        .onSuccess { statusMessage = "${member.name} has been unblocked." }
                                        .onFailure { statusMessage = it.message ?: "Failed to unblock member." }
                                }
                            }
                        ) {
                            Text("Confirm Unblock")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { targetMemberAction = null }) { Text("Cancel") }
                    }
                )
            }

            "REMOVE" -> {
                AlertDialog(
                    onDismissRequest = { targetMemberAction = null },
                    title = { Text("Remove ${target.name}?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("The account will no longer be active.")
                            Text("Historical ride and CO₂ records will be preserved.", fontSize = 13.sp, color = TextSecondary)
                            OutlinedTextField(
                                value = actionReason,
                                onValueChange = { actionReason = it },
                                label = { Text("Reason for removal (optional)") },
                                placeholder = { Text("e.g. Employee offboarded") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val reason = actionReason
                                val member = target
                                targetMemberAction = null
                                actionReason = ""
                                scope.launch {
                                    adminRepository.removeMember(user, member, reason)
                                        .onSuccess { statusMessage = "${member.name} has been removed." }
                                        .onFailure { statusMessage = it.message ?: "Failed to remove member." }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Confirm Remove")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { targetMemberAction = null }) { Text("Cancel") }
                    }
                )
            }

            "ROLE" -> {
                AlertDialog(
                    onDismissRequest = { targetMemberAction = null },
                    title = { Text(if (promoteTargetRole) "Grant Administrator Access" else "Remove Administrator Access") },
                    text = {
                        Text(
                            if (promoteTargetRole)
                                "Grant administrator access to ${target.name}? Their Passenger/Driver mode will not change."
                            else
                                "Remove administrator access from ${target.name}? Their Passenger/Driver mode will not change."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val member = target
                                val promote = promoteTargetRole
                                targetMemberAction = null
                                scope.launch {
                                    adminRepository.setUserAdminRole(member.uid, promote, user)
                                        .onSuccess {
                                            statusMessage = if (promote)
                                                "${member.name} is now an administrator."
                                            else
                                                "Administrator access removed from ${member.name}."
                                        }
                                        .onFailure { statusMessage = it.message ?: "Role update failed." }
                                }
                            }
                        ) { Text(if (promoteTargetRole) "Make Admin" else "Remove Access") }
                    },
                    dismissButton = {
                        TextButton(onClick = { targetMemberAction = null }) { Text("Cancel") }
                    }
                )
            }

            "APPROVE" -> {
                AlertDialog(
                    onDismissRequest = { targetMemberAction = null },
                    title = { Text("Approve Account Access", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Grant TravelBuddy access to ${target.name}?")
                            Text("Employee ID: ${target.employeeId.ifBlank { "—" }}", fontSize = 13.sp, color = TextSecondary)
                            Text("Department: ${target.department}", fontSize = 13.sp, color = TextSecondary)
                            Text("Email: ${target.email}", fontSize = 13.sp, color = TextSecondary)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val member = target
                                targetMemberAction = null
                                scope.launch {
                                    adminRepository.approveUser(user, member)
                                        .onSuccess {
                                            statusMessage = "Approved ${member.name} successfully."
                                            Toast.makeText(context, "Account approved", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure {
                                            statusMessage = it.message ?: "Failed to approve account."
                                        }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Success)
                        ) {
                            Text("Approve Access", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { targetMemberAction = null }) { Text("Cancel") }
                    }
                )
            }
        }
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
                    Text("Mobility, Members & Management Reports", fontSize = 14.sp, color = TextSecondary)
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(it, color = Success, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { statusMessage = null }) { Text("OK", color = Success) }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 32.dp)
            ) {
                when (selectedTab) {
                    // =========================================================
                    // TAB 0: OVERVIEW
                    // =========================================================
                    0 -> {
                        if (pendingApprovalsCount > 0) {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selectedTab = 1 },
                                colors = CardDefaults.cardColors(containerColor = WarningContainer),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.HourglassTop, contentDescription = null, tint = Warning, modifier = Modifier.size(26.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "$pendingApprovalsCount Pending Approval${if (pendingApprovalsCount > 1) "s" else ""}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp,
                                                color = Warning
                                            )
                                            Text(
                                                text = "New user access requests waiting for review.",
                                                fontSize = 12.sp,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = { selectedTab = 1 },
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Review", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }

                        SectionTitle("EXECUTIVE SNAPSHOT")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminMetricCard("Total Members", users.size.toString(), Icons.Rounded.Groups, Modifier.weight(1f))
                            AdminMetricCard("Active Participants", activeParticipantIds.size.toString(), Icons.Rounded.PersonPinCircle, Modifier.weight(1f))
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
                        SectionTitle("MEMBER MIX & STATUS")
                        InfoCard {
                            OperationalRow("Active Members", activeMembersCount, Icons.Rounded.CheckCircle)
                            OperationalRow("Pending Approvals", pendingApprovalsCount, Icons.Rounded.HourglassTop)
                            OperationalRow("Blocked Members", blockedMembersCount, Icons.Rounded.Block)
                            OperationalRow("Removed Members", removedMembersCount, Icons.Rounded.PersonRemove)
                            OperationalRow("Administrators", adminCount, Icons.Rounded.AdminPanelSettings)
                        }

                        Spacer(Modifier.height(22.dp))
                        SectionTitle("TOTAL SUSTAINABILITY")
                        InfoCard {
                            ImpactRow("Passengers Shared", totalPassengersShared.toString())
                            ImpactRow("Vehicle Trips Avoided", totalPassengersShared.toString())
                            ImpactRow("Distance Avoided", String.format(Locale.US, "%.1f km", totalAvoidedKm))
                            ImpactRow("CO₂ Saved", String.format(Locale.US, "%.2f kg", totalCo2))
                        }
                    }

                    // =========================================================
                    // TAB 1: PENDING APPROVALS
                    // =========================================================
                    1 -> {
                        SectionTitle("PENDING USER APPROVALS")
                        Text(
                            "${pendingUsers.size} request${if (pendingUsers.size != 1) "s" else ""} awaiting administrator verification.",
                            color = TextSecondary,
                            fontSize = 13.5.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        if (pendingUsers.isEmpty()) {
                            InfoCard {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Success,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "No Pending Approvals",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        "All newly registered employees have been approved.",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                            pendingUsers.forEachIndexed { index, pendingUser ->
                                val reqTime = if (pendingUser.approvalRequestedAt > 0) pendingUser.approvalRequestedAt else pendingUser.createdAt
                                val reqDateStr = if (reqTime > 0) sdf.format(Date(reqTime)) else "Recently"

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                        .border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
                                    colors = CardDefaults.cardColors(containerColor = Surface),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("${index + 1}. ", fontWeight = FontWeight.Bold, color = TextTertiary, fontSize = 14.sp)
                                                Text(
                                                    pendingUser.name.ifBlank { "User ${pendingUser.uid.take(5)}" },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                    color = TextPrimary
                                                )
                                            }

                                            Surface(
                                                color = WarningContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "PENDING",
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    color = Warning,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(6.dp))

                                        Text("Email: ${pendingUser.email.ifBlank { "—" }}", color = TextSecondary, fontSize = 13.sp)
                                        Text("Emp ID: ${pendingUser.employeeId.ifBlank { "—" }} • Dept: ${pendingUser.department}", color = TextSecondary, fontSize = 13.sp)
                                        Text("Township: ${pendingUser.townshipLocation} • Preferred Mode: ${pendingUser.defaultRolePreference}", color = TextSecondary, fontSize = 13.sp)
                                        Text("Requested: $reqDateStr", color = TextTertiary, fontSize = 12.sp)

                                        Spacer(Modifier.height(14.dp))
                                        HorizontalDivider(color = Outline.copy(alpha = 0.25f))
                                        Spacer(Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    targetMemberAction = pendingUser
                                                    memberActionType = "APPROVE"
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Success)
                                            ) {
                                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Approve Access", color = Color.White, fontWeight = FontWeight.SemiBold)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    targetMemberAction = pendingUser
                                                    memberActionType = "REMOVE"
                                                    actionReason = "Rejected by administrator"
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Reject")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // TAB 2: MEMBERS (ADMIN → MEMBERS)
                    // =========================================================
                    2 -> {
                        SectionTitle("MEMBER MANAGEMENT")
                        Text("${filteredMembers.size} members shown • $activeMembersCount active • $pendingApprovalsCount pending • $blockedMembersCount blocked • $removedMembersCount removed", color = TextSecondary, fontSize = 13.5.sp)

                        Spacer(Modifier.height(12.dp))

                        // Status Filter Chips
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("All", "Active", "Pending", "Blocked", "Removed").forEach { st ->
                                FilterChip(
                                    selected = memberStatusFilter == st,
                                    onClick = { memberStatusFilter = st },
                                    label = { Text(st) }
                                )
                            }
                        }

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

                        Spacer(Modifier.height(16.dp))

                        if (filteredMembers.isEmpty()) {
                            InfoCard { Text("No members found matching your search and filter.", color = TextSecondary) }
                        } else {
                            filteredMembers.forEachIndexed { index, member ->
                                val memberDriverRides = completedRides.count { it.driverId == member.uid }
                                val memberPassengerRides = completedRequests.count { it.passengerId == member.uid }
                                val memberDriverPassengers = completedRides.filter { it.driverId == member.uid }.sumOf {
                                    if (it.finalPassengerCount > 0) it.finalPassengerCount else it.completedPassengerIds.size.coerceAtLeast(it.acceptedPassengerIds.size)
                                }
                                val memberAvoidedKm = (memberDriverPassengers + memberPassengerRides) * config.baselineDistanceKm
                                val memberCo2 = memberAvoidedKm * config.co2FactorKgPerKm

                                MemberManagementCard(
                                    serialNumber = index + 1,
                                    member = member,
                                    currentAdminUid = user.uid,
                                    driverRidesCount = memberDriverRides,
                                    passengerRidesCount = memberPassengerRides,
                                    co2Saved = memberCo2,
                                    onApprove = {
                                        targetMemberAction = member
                                        memberActionType = "APPROVE"
                                    },
                                    onBlock = {
                                        targetMemberAction = member
                                        memberActionType = "BLOCK"
                                        actionReason = ""
                                    },
                                    onUnblock = {
                                        targetMemberAction = member
                                        memberActionType = "UNBLOCK"
                                    },
                                    onRemove = {
                                        targetMemberAction = member
                                        memberActionType = "REMOVE"
                                        actionReason = ""
                                    },
                                    onPromote = {
                                        targetMemberAction = member
                                        memberActionType = "ROLE"
                                        promoteTargetRole = true
                                    },
                                    onDemote = {
                                        targetMemberAction = member
                                        memberActionType = "ROLE"
                                        promoteTargetRole = false
                                    }
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }

                    // =========================================================
                    // TAB 3: REPORTS (ADMIN → REPORTS & CSV DOWNLOAD)
                    // =========================================================
                    3 -> {
                        SectionTitle("MANAGEMENT REPORT")

                        // 1. SUMMARY CARDS ABOVE REPORT
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportSummaryMiniCard("TOTAL MEMBERS", "$reportTotalMembers", Modifier.weight(1f))
                            ReportSummaryMiniCard("CO₂ SAVED", String.format(Locale.US, "%.1f kg", reportTotalCo2Saved), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportSummaryMiniCard("DRIVER RIDES", "$reportTotalDriverRides", Modifier.weight(1f))
                            ReportSummaryMiniCard("PASSENGER RIDES", "$reportTotalPassengerRides", Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(18.dp))

                        // 2. PERIOD & STATUS FILTERS
                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Report Period:", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = TextPrimary)
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("All Time", "Current Month", "Previous Month", "Current Year").forEach { p ->
                                        FilterChip(
                                            selected = reportPreset == p,
                                            onClick = { reportPreset = p },
                                            label = { Text(p, fontSize = 12.sp) }
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = reportFromDate,
                                        onValueChange = { reportFromDate = it; reportPreset = "Custom" },
                                        label = { Text("From Date (YYYY-MM-DD)", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = reportToDate,
                                        onValueChange = { reportToDate = it; reportPreset = "Custom" },
                                        label = { Text("To Date (YYYY-MM-DD)", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Member Status:", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("All", "Active", "Blocked", "Removed").forEach { st ->
                                            FilterChip(
                                                selected = reportStatusFilter == st,
                                                onClick = { reportStatusFilter = st },
                                                label = { Text(st, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 3. CSV DOWNLOAD BUTTON
                        Button(
                            onClick = {
                                try {
                                    ReportExporter.exportAndShareCsv(context, managementReportRows)
                                    Toast.makeText(context, "Management Report CSV generated!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    statusMessage = "Couldn't generate CSV: ${e.message}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("DOWNLOAD CSV", fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(18.dp))

                        // 4. SORTING CONTROLS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("SORT BY:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = reportSortBy == "CO2_DESC",
                                    onClick = { reportSortBy = "CO2_DESC" },
                                    label = { Text("CO₂", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = reportSortBy == "NAME_ASC",
                                    onClick = { reportSortBy = "NAME_ASC" },
                                    label = { Text("Name", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = reportSortBy == "DRIVER_DESC",
                                    onClick = { reportSortBy = "DRIVER_DESC" },
                                    label = { Text("Driver", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = reportSortBy == "PASSENGER_DESC",
                                    onClick = { reportSortBy = "PASSENGER_DESC" },
                                    label = { Text("Passenger", fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 5. EXACT 5-COLUMN MANAGEMENT REPORT TABLE
                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                // Table Header
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Sr.#", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(36.dp), color = PrimaryDark)
                                    Text("Person Name", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1.3f), color = PrimaryDark)
                                    Text("CO₂ Saved", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), color = PrimaryDark)
                                    Text("Driver", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(46.dp), color = PrimaryDark)
                                    Text("Pass.", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(46.dp), color = PrimaryDark)
                                }
                                HorizontalDivider(color = Outline.copy(alpha = 0.4f))

                                if (managementReportRows.isEmpty()) {
                                    Text("No records match the selected period and filters.", modifier = Modifier.padding(vertical = 16.dp), color = TextSecondary, fontSize = 13.sp)
                                } else {
                                    managementReportRows.forEach { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${row.serialNumber}", fontSize = 12.5.sp, modifier = Modifier.width(36.dp), color = TextSecondary)
                                            Text(row.personName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.3f), color = TextPrimary)
                                            Text("${String.format(Locale.US, "%.2f", row.co2Saved)} kg", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = Primary)
                                            Text("${row.ridesAsDriver}", fontSize = 13.sp, modifier = Modifier.width(46.dp), color = TextPrimary)
                                            Text("${row.ridesAsPassenger}", fontSize = 13.sp, modifier = Modifier.width(46.dp), color = TextPrimary)
                                        }
                                        HorizontalDivider(color = Outline.copy(alpha = 0.2f))
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // TAB 4: ANALYTICS
                    // =========================================================
                    4 -> {
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

                    // =========================================================
                    // TAB 5: AUDIT LOGS
                    // =========================================================
                    5 -> {
                        SectionTitle("ADMIN AUDIT TRAIL")
                        Text("Immutable record of administrative actions", color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))

                        if (auditLogs.isEmpty()) {
                            InfoCard { Text("No admin actions recorded yet.", color = TextSecondary) }
                        } else {
                            val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                            auditLogs.forEach { log ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(14.dp)),
                                    colors = CardDefaults.cardColors(containerColor = Surface),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(log.action.replace("_", " "), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PrimaryDark)
                                            Text(sdf.format(Date(log.timestamp)), fontSize = 11.5.sp, color = TextTertiary)
                                        }
                                        Text("Target: ${log.targetName} (${log.targetEmail.ifBlank { log.targetUid }})", fontSize = 13.sp, color = TextPrimary)
                                        Text("Admin: ${log.adminName.ifBlank { log.adminEmail }}", fontSize = 12.sp, color = TextSecondary)
                                        if (log.reason.isNotBlank()) {
                                            Text("Reason: \"${log.reason}\"", fontSize = 12.5.sp, color = TextSecondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // TAB 6: SETTINGS
                    // =========================================================
                    6 -> {
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
private fun ReportSummaryMiniCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
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
private fun MemberManagementCard(
    serialNumber: Int,
    member: User,
    currentAdminUid: String,
    driverRidesCount: Int,
    passengerRidesCount: Int,
    co2Saved: Double,
    onApprove: () -> Unit = {},
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onRemove: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit
) {
    val isSelf = member.uid == currentAdminUid
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val joinedDateStr = if (member.createdAt > 0) sdf.format(Date(member.createdAt)) else "Earlier"

    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header Row: Sr#, Name, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$serialNumber. ", fontWeight = FontWeight.Bold, color = TextTertiary, fontSize = 14.sp)
                    Text(member.name.ifBlank { "User ${member.uid.take(5)}" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                }

                // Account Status Badge
                val (badgeBg, badgeFg, badgeText) = when {
                    member.isPendingApproval() -> Triple(WarningContainer, Warning, "PENDING")
                    member.isBlocked() -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "BLOCKED")
                    member.isRemoved() -> Triple(Color(0xFFE0E0E0), Color(0xFF616161), "REMOVED")
                    else -> Triple(SuccessContainer, Success, "ACTIVE")
                }
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(badgeText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = badgeFg, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(4.dp))

            Text("Email: ${member.email.ifBlank { "—" }}", color = TextSecondary, fontSize = 13.sp)
            Text("Emp ID: ${member.employeeId.ifBlank { "—" }} • Dept: ${member.department}", color = TextSecondary, fontSize = 13.sp)
            if (member.phone.isNotBlank()) {
                Text("Phone: ${member.phone}", color = TextSecondary, fontSize = 13.sp)
            }
            Text("Joined: $joinedDateStr", color = TextTertiary, fontSize = 12.sp)

            Spacer(Modifier.height(10.dp))

            // Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Driver: $driverRidesCount rides", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("Passenger: $passengerRidesCount rides", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("CO₂: ${String.format(Locale.US, "%.2f kg", co2Saved)}", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Primary)
            }

            if (!isSelf) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Outline.copy(alpha = 0.25f))
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (member.isPendingApproval()) {
                        Button(
                            onClick = onApprove,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Success),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Approve", fontSize = 12.sp, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = onRemove,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Reject", fontSize = 12.sp)
                        }
                    } else if (member.isActive()) {
                        OutlinedButton(
                            onClick = onBlock,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Block", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onRemove,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Remove", fontSize = 12.sp)
                        }

                        if (member.isAdmin()) {
                            TextButton(
                                onClick = onDemote,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Remove Admin", fontSize = 12.sp)
                            }
                        } else {
                            TextButton(
                                onClick = onPromote,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Make Admin", fontSize = 12.sp)
                            }
                        }
                    } else if (member.isBlocked()) {
                        Button(
                            onClick = onUnblock,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Unblock", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onRemove,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Remove", fontSize = 12.sp)
                        }
                    } else if (member.isRemoved()) {
                        Button(
                            onClick = onUnblock,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Restore Member", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingCard(rank: Int, row: ContributorRow, mode: String) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(PrimaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$rank", fontWeight = FontWeight.Bold, color = PrimaryDark, fontSize = 12.5.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(row.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Text("${row.department} • ${row.rideCount} rides (${row.passengerCount} passengers)", color = TextSecondary, fontSize = 12.sp)
                }
            }
            Text(
                String.format(Locale.US, "%.1f kg", row.co2Kg),
                fontWeight = FontWeight.Bold,
                color = Primary,
                fontSize = 14.sp
            )
        }
    }
}
