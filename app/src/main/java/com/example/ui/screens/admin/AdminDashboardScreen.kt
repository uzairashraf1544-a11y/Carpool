package com.example.ui.screens.admin

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import com.example.data.repository.GlobalResetPreview
import com.example.data.repository.GlobalResetType
import com.example.data.repository.ManagementReportSummary
import com.example.data.repository.UserDataDeletionPreview
import com.example.ui.components.ErrorContainer
import com.example.ui.theme.*
import com.example.util.ManagementReportRow
import com.example.util.RemoteConfigDefaults
import com.example.util.ReportCacheManager
import com.example.util.ReportExporter
import com.example.util.TimeUtils
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.flow.flowOf
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val today = remember { TimeUtils.todayKarachiString() }
    val config by adminRepository.getSystemConfigFlow().collectAsState(initial = com.example.data.model.SystemConfig())

    // Stream the single pre-aggregated report document (Costs EXACTLY 1 read for entire dashboard summary)
    val reportSummary by adminRepository.getManagementReportFlow().collectAsState(initial = ManagementReportSummary())

    // Paged members state (Task T2: 25 per page instead of streaming 300 users)
    var membersList by remember { mutableStateOf<List<User>>(emptyList()) }
    var lastUserDoc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var hasMoreMembers by remember { mutableStateOf(true) }
    var isLoadingMembers by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (selectedTab in listOf(2, 7) && membersList.isEmpty()) {
            isLoadingMembers = true
            adminRepository.fetchUsersPage(pageSize = 25L).onSuccess { (initialUsers, lastDoc) ->
                membersList = initialUsers
                lastUserDoc = lastDoc
                if (initialUsers.size < 25) hasMoreMembers = false
            }
            isLoadingMembers = false
        }
    }

    val users = membersList

    // Stream today's operations ONLY when on Overview tab (0) and enabled via RemoteConfig
    var isOverviewLivePaused by remember { mutableStateOf(false) }
    var lastOverviewInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var preservedTodayRides by remember { mutableStateOf<List<Ride>>(emptyList()) }
    var preservedTodayRequests by remember { mutableStateOf<List<RideRequest>>(emptyList()) }

    LaunchedEffect(selectedTab, isOverviewLivePaused) {
        if (selectedTab == 0 && !isOverviewLivePaused) {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                if (System.currentTimeMillis() - lastOverviewInteractionTime >= 5 * 60 * 1000L) {
                    isOverviewLivePaused = true
                    break
                }
            }
        }
    }

    val todayRides by remember(selectedTab, today, isOverviewLivePaused) {
        if (selectedTab == 0 && RemoteConfigDefaults.adminLiveOverviewEnabled && !isOverviewLivePaused) {
            adminRepository.getTodayRidesFlow(today)
        } else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val todayRequests by remember(selectedTab, today, isOverviewLivePaused) {
        if (selectedTab == 0 && RemoteConfigDefaults.adminLiveOverviewEnabled && !isOverviewLivePaused) {
            adminRepository.getTodayRequestsFlow(today)
        } else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    LaunchedEffect(todayRides) {
        if (todayRides.isNotEmpty()) preservedTodayRides = todayRides
    }
    LaunchedEffect(todayRequests) {
        if (todayRequests.isNotEmpty()) preservedTodayRequests = todayRequests
    }
    val activeRidesList = if (todayRides.isNotEmpty()) todayRides else preservedTodayRides
    val activeRequestsList = if (todayRequests.isNotEmpty()) todayRequests else preservedTodayRequests

    // Only stream audit logs when the admin actually views the Audit Logs tab (index 5)
    val auditLogs by remember(selectedTab) {
        if (selectedTab == 5) adminRepository.getAuditLogsFlow() else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val pendingUsers by adminRepository.getPendingApprovalsFlow().collectAsState(initial = emptyList())
    val pendingApprovalsCount = pendingUsers.size
    val tabs = if (pendingApprovalsCount > 0) {
        listOf("Overview", "Pending ($pendingApprovalsCount)", "Members", "Reports", "Analytics", "Audit Logs", "Settings", "Data Management")
    } else {
        listOf("Overview", "Pending", "Members", "Reports", "Analytics", "Audit Logs", "Settings", "Data Management")
    }

    var search by remember { mutableStateOf("") }
    var memberStatusFilter by remember { mutableStateOf("All") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Data Management States
    var removeAdminDataOnReset by remember { mutableStateOf(true) }
    var pendingGlobalResetType by remember { mutableStateOf<GlobalResetType?>(null) }
    var globalResetPreview by remember { mutableStateOf<GlobalResetPreview?>(null) }
    var isCalculatingResetPreview by remember { mutableStateOf(false) }
    var isExecutingReset by remember { mutableStateOf(false) }
    var globalResetReason by remember { mutableStateOf("") }

    var selectedUserForDeletion by remember { mutableStateOf<User?>(null) }
    var deleteUserAccountOption by remember { mutableStateOf(false) }
    var userDeletionPreview by remember { mutableStateOf<UserDataDeletionPreview?>(null) }
    var isCalculatingUserPreview by remember { mutableStateOf(false) }
    var isExecutingUserDeletion by remember { mutableStateOf(false) }
    var userSearchQuery by remember { mutableStateOf("") }
    var userDeletionReason by remember { mutableStateOf("") }
    var showUserDeletionConfirmDialog by remember { mutableStateOf(false) }

    // Dialog States
    var targetMemberAction by remember { mutableStateOf<User?>(null) }
    var memberActionType by remember { mutableStateOf<String?>(null) } // "BLOCK", "UNBLOCK", "REMOVE", "ROLE"
    var actionReason by remember { mutableStateOf("") }
    var promoteTargetRole by remember { mutableStateOf(true) }
    var purgeCompletely by remember { mutableStateOf(false) }
    var isExecutingAction by remember { mutableStateOf(false) }

    // Management Report Local Storage Cached State
    var cachedReportRows by remember { mutableStateOf<List<ManagementReportRow>>(emptyList()) }
    var reportLastUpdatedTimestamp by remember { mutableLongStateOf(0L) }
    var isReportRefreshing by remember { mutableStateOf(false) }
    var isCsvDownloading by remember { mutableStateOf(false) }
    var isPendingRefreshing by remember { mutableStateOf(false) }

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

    // Load Report from Local Storage Cache on Launch (0 Firestore reads)
    LaunchedEffect(Unit) {
        val cached = ReportCacheManager.getCachedReport(context)
        if (cached.rows.isNotEmpty()) {
            cachedReportRows = cached.rows
            reportLastUpdatedTimestamp = cached.lastUpdatedTimestamp
        } else {
            // First-time load: populate cache with single Firestore read (systemConfig/managementReport)
            isReportRefreshing = true
            adminRepository.fetchManagementReport(forceServer = false)
                .onSuccess { (rows, ts) ->
                    cachedReportRows = rows
                    reportLastUpdatedTimestamp = ts
                    ReportCacheManager.saveReportCache(context, rows, ts)
                }
            isReportRefreshing = false
        }
    }

    LaunchedEffect(config) {
        baselineText = config.baselineDistanceKm.toString()
        factorText = config.co2FactorKgPerKm.toString()
    }

    LaunchedEffect(reportSummary) {
        if (reportSummary.rows.isNotEmpty()) {
            cachedReportRows = reportSummary.rows
            reportLastUpdatedTimestamp = reportSummary.updatedAt
            ReportCacheManager.saveReportCache(context, reportSummary.rows, reportSummary.updatedAt)
        }
    }

    val totalPassengersShared = if (reportSummary.totalPassengerRides > 0) {
        reportSummary.totalPassengerRides
    } else {
        cachedReportRows.sumOf { it.ridesAsPassenger }
    }
    val totalAvoidedKm = totalPassengersShared * config.baselineDistanceKm
    val totalCo2 = if (reportSummary.totalCo2Saved > 0.0) {
        reportSummary.totalCo2Saved
    } else {
        cachedReportRows.sumOf { it.co2Saved }
    }
    val totalMembersCount = if (reportSummary.totalMembers > 0) reportSummary.totalMembers else maxOf(users.size, cachedReportRows.size)
    val adminCount = users.count { it.isAdmin() }
    val activeMembersCount = if (users.isNotEmpty()) users.count { it.isActive() } else totalMembersCount
    val blockedMembersCount = users.count { it.isBlocked() }
    val removedMembersCount = users.count { it.isRemoved() }

    val activeParticipantCount = if (reportSummary.rows.isNotEmpty()) {
        reportSummary.rows.count { it.ridesAsDriver > 0 || it.ridesAsPassenger > 0 }
    } else {
        cachedReportRows.count { it.ridesAsDriver > 0 || it.ridesAsPassenger > 0 }
    }

    val todayAvailableDrivers = activeRidesList.count {
        it.date == today && it.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL)
    }
    val todayPendingRequests = activeRequestsList.count { it.date == today && it.status == RideRequest.STATUS_REQUESTED }
    val todayActiveRides = activeRidesList.count { it.date == today && it.status in listOf(Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED) }
    val todayCompletedRides = activeRidesList.count { it.date == today && it.status == Ride.STATUS_COMPLETED }
    val todayCancelledRides = activeRidesList.count { it.date == today && it.status == Ride.STATUS_CANCELLED }

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

    // Management Report Row Aggregations (Single Consolidated Document & Cache Priority)
    val managementReportRows: List<ManagementReportRow> = remember(
        cachedReportRows,
        reportSummary.rows,
        reportSortBy,
        search
    ) {
        val baseRows = when {
            cachedReportRows.isNotEmpty() -> cachedReportRows
            reportSummary.rows.isNotEmpty() -> reportSummary.rows
            else -> emptyList()
        }

        val filteredBySearch = if (search.isNotBlank()) {
            baseRows.filter { it.personName.contains(search, ignoreCase = true) }
        } else {
            baseRows
        }

        val sortedRows = when (reportSortBy) {
            "CO2_DESC" -> filteredBySearch.sortedByDescending { it.co2Saved }
            "NAME_ASC" -> filteredBySearch.sortedBy { it.personName.lowercase() }
            "DRIVER_DESC" -> filteredBySearch.sortedByDescending { it.ridesAsDriver }
            "PASSENGER_DESC" -> filteredBySearch.sortedByDescending { it.ridesAsPassenger }
            else -> filteredBySearch.sortedByDescending { it.co2Saved }
        }

        sortedRows.mapIndexed { index, row -> row.copy(serialNumber = index + 1) }
    }

    // Report Period Summary Metrics
    val reportTotalMembers = if (reportSummary.totalMembers > 0) reportSummary.totalMembers else managementReportRows.size
    val reportTotalCo2Saved = if (reportSummary.totalCo2Saved > 0.0) reportSummary.totalCo2Saved else managementReportRows.sumOf { it.co2Saved }
    val reportTotalDriverRides = reportSummary.totalDriverRides
    val reportTotalPassengerRides = reportSummary.totalPassengerRides

    // Contributor Rankings for Analytics Tab (Derived from pre-aggregated report rows with 0 queries)
    val driverRanking = remember(managementReportRows) {
        managementReportRows.filter { it.ridesAsDriver > 0 }.map { row ->
            ContributorRow(
                uid = row.serialNumber.toString(),
                name = row.personName,
                department = "Operations",
                rideCount = row.ridesAsDriver,
                passengerCount = row.ridesAsDriver,
                co2Kg = row.co2Saved
            )
        }.sortedByDescending { it.rideCount }
    }

    val passengerRanking = remember(managementReportRows) {
        managementReportRows.filter { it.ridesAsPassenger > 0 }.map { row ->
            ContributorRow(
                uid = row.serialNumber.toString(),
                name = row.personName,
                department = "Operations",
                rideCount = row.ridesAsPassenger,
                passengerCount = row.ridesAsPassenger,
                co2Kg = row.co2Saved
            )
        }.sortedByDescending { it.rideCount }
    }

    val statsByMemberName = remember(managementReportRows) {
        managementReportRows.associateBy { it.personName }
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
                    onDismissRequest = {
                        if (!isExecutingAction) {
                            targetMemberAction = null
                            actionReason = ""
                            purgeCompletely = false
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (purgeCompletely) Icons.Rounded.DeleteForever else Icons.Rounded.PersonOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (purgeCompletely) "Permanently Purge Member" else "Remove Member",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Member Summary Banner
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(target.name.ifBlank { "Unknown Member" }, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    Text("${target.department} • ${target.email.ifBlank { target.employeeId }}", fontSize = 12.sp, color = TextSecondary)
                                }
                            }

                            Text("Select removal option:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                            // Option 1: Deactivate (Keep Data)
                            Card(
                                onClick = { if (!isExecutingAction) purgeCompletely = false },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (!purgeCompletely) Primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (!purgeCompletely) Primary else Outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    RadioButton(
                                        selected = !purgeCompletely,
                                        onClick = { if (!isExecutingAction) purgeCompletely = false },
                                        colors = RadioButtonDefaults.colors(selectedColor = Primary)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Deactivate Account", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                color = Primary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text("Keep Data", fontSize = 10.sp, color = PrimaryDark, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                        }
                                        Text(
                                            "Revokes app login immediately. Keeps past commute records, shared rides, and CO₂ stats intact for reporting.",
                                            fontSize = 11.5.sp,
                                            color = TextSecondary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            // Option 2: Complete Purge (Zero Trace)
                            Card(
                                onClick = { if (!isExecutingAction) purgeCompletely = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (purgeCompletely) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (purgeCompletely) MaterialTheme.colorScheme.error else Outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    RadioButton(
                                        selected = purgeCompletely,
                                        onClick = { if (!isExecutingAction) purgeCompletely = true },
                                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Complete Purge", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text("Zero Trace", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                        }
                                        Text(
                                            "Permanently deletes user profile, ride requests, vehicle listings, and personal CO₂ records from Firestore. Irreversible.",
                                            fontSize = 11.5.sp,
                                            color = TextSecondary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            if (purgeCompletely) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Warning: Every trace of this user in Firestore will be erased.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = actionReason,
                                onValueChange = { actionReason = it },
                                label = { Text("Reason for removal (optional)") },
                                placeholder = { Text(if (purgeCompletely) "e.g. Test cleanup / user requested complete erasure" else "e.g. Employee offboarded") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (isExecutingAction) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (purgeCompletely) "Purging all Firestore records..." else "Deactivating member...", fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = !isExecutingAction,
                            onClick = {
                                val reason = actionReason
                                val member = target
                                val isPurge = purgeCompletely
                                isExecutingAction = true
                                scope.launch {
                                    val result = if (isPurge) {
                                        adminRepository.purgeMemberCompletely(user, member, reason)
                                    } else {
                                        adminRepository.removeMember(user, member, reason)
                                    }
                                    isExecutingAction = false
                                    targetMemberAction = null
                                    actionReason = ""
                                    purgeCompletely = false
                                    result.onSuccess {
                                        statusMessage = if (isPurge) {
                                            "${member.name} and all data have been purged."
                                        } else {
                                            "${member.name} has been deactivated."
                                        }
                                        Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        statusMessage = it.message ?: "Failed to remove member."
                                        Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (purgeCompletely) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            )
                        ) {
                            Text(if (purgeCompletely) "Purge All Data" else "Deactivate Member")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !isExecutingAction,
                            onClick = {
                                targetMemberAction = null
                                actionReason = ""
                                purgeCompletely = false
                            }
                        ) {
                            Text("Cancel")
                        }
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

    // ----------------------------------------------------
    // DATA MANAGEMENT CONFIRMATION DIALOGS
    // ----------------------------------------------------

    pendingGlobalResetType?.let { resetType ->
        val resetTypeName = when (resetType) {
            GlobalResetType.RIDES_ONLY -> "Clear Rides Data Only"
            GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN -> "Clear Everything Including User Accounts"
            GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS -> "Clear Everything Except Admin Accounts"
        }

        AlertDialog(
            onDismissRequest = {
                if (!isExecutingReset) {
                    pendingGlobalResetType = null
                    globalResetPreview = null
                    globalResetReason = ""
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Confirm: $resetTypeName",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isCalculatingResetPreview) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Calculating affected database records...", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else if (globalResetPreview != null) {
                        val preview = globalResetPreview!!
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "This action will permanently delete:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text("• ${preview.ridesCount} rides and driver listings", fontSize = 12.5.sp, color = TextPrimary)
                                Text("• ${preview.bookingsCount} ride bookings & requests", fontSize = 12.5.sp, color = TextPrimary)
                                Text("• ${preview.passengerNeedsCount} passenger commute needs", fontSize = 12.5.sp, color = TextPrimary)
                                if (preview.userAccountsCount > 0) {
                                    Text("• ${preview.userAccountsCount} user accounts and employee IDs", fontSize = 12.5.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("• 0 user accounts (all employee logins preserved)", fontSize = 12.5.sp, color = PrimaryDark)
                                }
                                Text(
                                    if (removeAdminDataOnReset) "• Administrator's own ride history: INCLUDED (wiped)" else "• Administrator's own ride history: KEPT",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    "• Current admin account (${user.email}): NEVER DELETED",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Success
                                )
                            }
                        }

                        Text(
                            "This cannot be undone. All cumulative counters, statistics, and leaderboards will be reset to 0.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )

                        OutlinedTextField(
                            value = globalResetReason,
                            onValueChange = { globalResetReason = it },
                            label = { Text("Reason for reset (audit log)") },
                            placeholder = { Text("e.g. Pre-launch data cleanup") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (isExecutingReset) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Executing atomic data wipe in Firestore...", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isCalculatingResetPreview && !isExecutingReset && globalResetPreview != null,
                    onClick = {
                        val preview = globalResetPreview ?: return@Button
                        val reason = globalResetReason
                        isExecutingReset = true
                        scope.launch {
                            adminRepository.executeGlobalReset(user, resetType, removeAdminDataOnReset, reason)
                                .onSuccess {
                                    ReportCacheManager.clearCache(context)
                                    cachedReportRows = emptyList()
                                    reportLastUpdatedTimestamp = 0L
                                    isExecutingReset = false
                                    pendingGlobalResetType = null
                                    globalResetPreview = null
                                    globalResetReason = ""
                                    statusMessage = "Data wipe complete: deleted ${preview.ridesCount} rides and ${preview.bookingsCount} bookings. App starts with zero rides."
                                    Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                }
                                .onFailure {
                                    isExecutingReset = false
                                    statusMessage = "Data reset failed: ${it.message}"
                                    Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Wipe")
                }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !isExecutingReset,
                    onClick = {
                        pendingGlobalResetType = null
                        globalResetPreview = null
                        globalResetReason = ""
                    }
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showUserDeletionConfirmDialog && selectedUserForDeletion != null) {
        val target = selectedUserForDeletion!!
        AlertDialog(
            onDismissRequest = {
                if (!isExecutingUserDeletion) {
                    showUserDeletionConfirmDialog = false
                    userDeletionPreview = null
                    userDeletionReason = ""
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (deleteUserAccountOption) "Delete User & Data" else "Delete User Data Only",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isCalculatingUserPreview) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Calculating user records...", fontSize = 13.sp, color = TextSecondary)
                        }
                    } else if (userDeletionPreview != null) {
                        val preview = userDeletionPreview!!
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Target User: ${target.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = TextPrimary
                                )
                                Text("${target.department} • ${target.email.ifBlank { target.employeeId }}", fontSize = 12.sp, color = TextSecondary)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Records affected for this user:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text("• ${preview.ridesAsDriverCount} rides created as driver", fontSize = 12.sp, color = TextPrimary)
                                Text("• ${preview.bookingsAsPassengerCount} bookings taken as passenger", fontSize = 12.sp, color = TextPrimary)
                                Text("• ${preview.passengerNeedsCount} commute needs posted", fontSize = 12.sp, color = TextPrimary)
                                if (preview.ridesAnonymizedForPassengersCount > 0) {
                                    Surface(
                                        color = Primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Note: ${preview.ridesAnonymizedForPassengersCount} shared rides had other passenger bookings. Those passengers' history is preserved; driver details will show as 'Former Colleague'.",
                                            modifier = Modifier.padding(6.dp),
                                            fontSize = 11.5.sp,
                                            color = PrimaryDark,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                                Text(
                                    if (deleteUserAccountOption) "• User Account: PERMANENTLY REMOVED from Firestore" else "• User Account: KEPT (login active, stats reset to 0)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (deleteUserAccountOption) MaterialTheme.colorScheme.error else PrimaryDark
                                )
                            }
                        }

                        Text("This cannot be undone.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)

                        OutlinedTextField(
                            value = userDeletionReason,
                            onValueChange = { userDeletionReason = it },
                            label = { Text("Reason (audit log)") },
                            placeholder = { Text(if (deleteUserAccountOption) "e.g. Employee offboarded / data wipe requested" else "e.g. User requested ride history reset") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (isExecutingUserDeletion) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Deleting user records in Firestore...", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isCalculatingUserPreview && !isExecutingUserDeletion && userDeletionPreview != null,
                    onClick = {
                        val reason = userDeletionReason
                        val isDeleteAcc = deleteUserAccountOption
                        isExecutingUserDeletion = true
                        scope.launch {
                            adminRepository.executePerUserDataDeletion(user, target, isDeleteAcc, reason)
                                .onSuccess { preview ->
                                    ReportCacheManager.clearCache(context)
                                    isExecutingUserDeletion = false
                                    showUserDeletionConfirmDialog = false
                                    userDeletionPreview = null
                                    userDeletionReason = ""
                                    selectedUserForDeletion = null
                                    statusMessage = if (isDeleteAcc) {
                                        "${target.name} and all data (${preview.ridesAsDriverCount} rides, ${preview.bookingsAsPassengerCount} bookings) have been permanently deleted."
                                    } else {
                                        "Data for ${target.name} has been deleted (${preview.ridesAsDriverCount} rides, ${preview.bookingsAsPassengerCount} bookings). Account kept."
                                    }
                                    Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
                                }
                                .onFailure {
                                    isExecutingUserDeletion = false
                                    statusMessage = "Deletion failed: ${it.message}"
                                    Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (deleteUserAccountOption) "Delete User & Data" else "Delete Data Only")
                }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !isExecutingUserDeletion,
                    onClick = {
                        showUserDeletionConfirmDialog = false
                        userDeletionPreview = null
                        userDeletionReason = ""
                    }
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            }
        )
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
                            AdminMetricCard("Total Members", totalMembersCount.toString(), Icons.Rounded.Groups, Modifier.weight(1f))
                            AdminMetricCard("Active Participants", activeParticipantCount.toString(), Icons.Rounded.PersonPinCircle, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AdminMetricCard("Completed Rides", reportSummary.totalDriverRides.toString(), Icons.Rounded.DirectionsCar, Modifier.weight(1f))
                            AdminMetricCard("CO₂ Saved", String.format(Locale.US, "%.1f kg", totalCo2), Icons.Rounded.Eco, Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(22.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionTitle("TODAY'S OPERATIONS")
                            if (RemoteConfigDefaults.adminLiveOverviewEnabled) {
                                if (isOverviewLivePaused) {
                                    AssistChip(
                                        onClick = {
                                            isOverviewLivePaused = false
                                            lastOverviewInteractionTime = System.currentTimeMillis()
                                        },
                                        label = { Text("Paused (Idle) • Resume", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = WarningContainer,
                                            labelColor = Warning,
                                            leadingIconContentColor = Warning
                                        )
                                    )
                                } else {
                                    AssistChip(
                                        onClick = { isOverviewLivePaused = true },
                                        label = { Text("Live", fontSize = 11.sp) },
                                        leadingIcon = { Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = SuccessContainer,
                                            labelColor = Success,
                                            leadingIconContentColor = Success
                                        )
                                    )
                                }
                            }
                        }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SectionTitle("PENDING USER APPROVALS")
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isPendingRefreshing = true
                                        val res = adminRepository.fetchPendingApprovals(forceServer = true)
                                        isPendingRefreshing = false
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Pending approvals synced (${res.getOrThrow().size} pending)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isPendingRefreshing
                            ) {
                                if (isPendingRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Primary)
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Pending Approvals", tint = Primary)
                                }
                            }
                        }
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
                                val rowStats = statsByMemberName[member.name]
                                val memberDriverRides = rowStats?.ridesAsDriver ?: 0
                                val memberPassengerRides = rowStats?.ridesAsPassenger ?: 0
                                val memberCo2 = if (member.totalCo2SavedKg > 0.0) member.totalCo2SavedKg else (rowStats?.co2Saved ?: 0.0)

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

                            if (hasMoreMembers && search.isBlank()) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            isLoadingMembers = true
                                            adminRepository.fetchUsersPage(pageSize = 25L, startAfterDoc = lastUserDoc).onSuccess { (newUsers, nextDoc) ->
                                                if (newUsers.isEmpty()) {
                                                    hasMoreMembers = false
                                                } else {
                                                    membersList = membersList + newUsers
                                                    lastUserDoc = nextDoc
                                                    if (newUsers.size < 25) hasMoreMembers = false
                                                }
                                            }
                                            isLoadingMembers = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isLoadingMembers
                                ) {
                                    if (isLoadingMembers) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Loading next page...")
                                    } else {
                                        Text("Load More Members (Showing ${membersList.size})")
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // TAB 3: REPORTS (ADMIN → REPORTS & CSV DOWNLOAD)
                    // =========================================================
                    3 -> {
                        SectionTitle("MANAGEMENT REPORT")

                        // LOCAL STORAGE CACHE STATUS & LAST UPDATED TIMESTAMP BANNER
                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, PrimaryLight, RoundedCornerShape(14.dp)),
                            colors = CardDefaults.cardColors(containerColor = PrimaryLight.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = PrimaryDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Last updated: ${ReportCacheManager.formatTimestamp(reportLastUpdatedTimestamp)}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = PrimaryDark
                                        )
                                        Text(
                                            text = "Data served from local device storage (0 server reads)",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                if (isReportRefreshing || isCsvDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PrimaryDark)
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

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

                        Spacer(Modifier.height(16.dp))

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

                        // 3. DUAL ACTION BUTTONS: REFRESH DATA & DOWNLOAD CSV
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isReportRefreshing = true
                                        statusMessage = "Fetching fresh report from Firestore…"
                                        val res = adminRepository.fetchManagementReport(forceServer = true)
                                        isReportRefreshing = false
                                        res.onSuccess { (freshRows, ts) ->
                                            cachedReportRows = freshRows
                                            reportLastUpdatedTimestamp = ts
                                            ReportCacheManager.saveReportCache(context, freshRows, ts)
                                            statusMessage = "Report refreshed successfully from Firestore!"
                                            Toast.makeText(context, "Report refreshed from Firestore!", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            statusMessage = "Failed to refresh report: ${err.message}"
                                            Toast.makeText(context, "Refresh failed: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                enabled = !isReportRefreshing && !isCsvDownloading
                            ) {
                                if (isReportRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("REFRESH DATA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        isCsvDownloading = true
                                        statusMessage = "Fetching fresh report and downloading CSV…"
                                        val res = adminRepository.fetchManagementReport(forceServer = true)
                                        isCsvDownloading = false
                                        val rowsToExport = if (res.isSuccess) {
                                            val (freshRows, ts) = res.getOrThrow()
                                            cachedReportRows = freshRows
                                            reportLastUpdatedTimestamp = ts
                                            ReportCacheManager.saveReportCache(context, freshRows, ts)
                                            freshRows
                                        } else {
                                            managementReportRows
                                        }
                                        try {
                                            ReportExporter.exportAndShareCsv(context, rowsToExport)
                                            Toast.makeText(context, "Fresh CSV generated & cache updated!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            statusMessage = "Couldn't generate CSV: ${e.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                enabled = !isReportRefreshing && !isCsvDownloading
                            ) {
                                if (isCsvDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("DOWNLOAD CSV", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
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
                                            val actionColor = if (log.action.contains("PURGE")) MaterialTheme.colorScheme.error else PrimaryDark
                                            Text(log.action.replace("_", " "), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = actionColor)
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

                    // =========================================================
                    // TAB 7: DATA MANAGEMENT
                    // =========================================================
                    7 -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Admin Data Management", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                                    Text(
                                        "Wipe pre-launch test data or process user data deletion requests. All actions are verified with atomic transactions and logged in audit trail.",
                                        fontSize = 12.sp,
                                        color = TextPrimary,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        SectionTitle("PART 1: GLOBAL RESET (PRE-LAUNCH CLEANUP)")

                        InfoCard {
                            // Toggle: "Also remove admin's data (keep admin account)"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { removeAdminDataOnReset = !removeAdminDataOnReset },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = removeAdminDataOnReset,
                                    onCheckedChange = { removeAdminDataOnReset = it }
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text("Also remove admin's data (keep admin account)", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = TextPrimary)
                                    Text(
                                        "When ticked, the admin's own rides, bookings, and history are deleted too, but the admin account itself (login, role) is NEVER deleted.",
                                        fontSize = 11.5.sp,
                                        color = TextSecondary,
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = Outline.copy(alpha = 0.25f))
                            Spacer(Modifier.height(16.dp))

                            // Action 1: Clear rides data only
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                border = BorderStroke(1.dp, Warning.copy(alpha = 0.6f))
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("1. Clear Rides Data Only", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Deletes all rides, bookings, and ride history. Keeps all user accounts intact. Resets community metrics.",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            pendingGlobalResetType = GlobalResetType.RIDES_ONLY
                                            isCalculatingResetPreview = true
                                            scope.launch {
                                                adminRepository.previewGlobalReset(user, GlobalResetType.RIDES_ONLY, removeAdminDataOnReset)
                                                    .onSuccess { preview ->
                                                        globalResetPreview = preview
                                                        isCalculatingResetPreview = false
                                                    }
                                                    .onFailure {
                                                        isCalculatingResetPreview = false
                                                        pendingGlobalResetType = null
                                                        statusMessage = "Preview failed: ${it.message}"
                                                    }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Warning),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("Clear Rides Data Only", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Action 2: Clear everything including all user accounts
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("2. Clear Everything Including User Accounts", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Deletes all rides data AND all user accounts, except the current admin account (${user.email}).",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            pendingGlobalResetType = GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN
                                            isCalculatingResetPreview = true
                                            scope.launch {
                                                adminRepository.previewGlobalReset(user, GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN, removeAdminDataOnReset)
                                                    .onSuccess { preview ->
                                                        globalResetPreview = preview
                                                        isCalculatingResetPreview = false
                                                    }
                                                    .onFailure {
                                                        isCalculatingResetPreview = false
                                                        pendingGlobalResetType = null
                                                        statusMessage = "Preview failed: ${it.message}"
                                                    }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("Clear Everything Except Current Admin", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Action 3: Clear everything except admin accounts
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.SupervisorAccount, contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("3. Clear Everything Except Admin Accounts", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Deletes all rides data and all non-admin user accounts; keeps all administrator accounts untouched.",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            pendingGlobalResetType = GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS
                                            isCalculatingResetPreview = true
                                            scope.launch {
                                                adminRepository.previewGlobalReset(user, GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS, removeAdminDataOnReset)
                                                    .onSuccess { preview ->
                                                        globalResetPreview = preview
                                                        isCalculatingResetPreview = false
                                                    }
                                                    .onFailure {
                                                        isCalculatingResetPreview = false
                                                        pendingGlobalResetType = null
                                                        statusMessage = "Preview failed: ${it.message}"
                                                    }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("Clear Everything Except Admin Accounts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        SectionTitle("PART 2: PER-USER DATA DELETION")

                        InfoCard {
                            Text(
                                "Search and select a user to delete their personal rides and records. If the user gave a ride that others booked, passengers' history remains intact with the deleted user's personal details removed.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = userSearchQuery,
                                onValueChange = { userSearchQuery = it },
                                label = { Text("Search User by Name, Email, or Emp ID") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (userSearchQuery.isNotBlank()) {
                                        IconButton(onClick = { userSearchQuery = "" }) {
                                            Icon(Icons.Rounded.Clear, contentDescription = null)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(Modifier.height(10.dp))

                            // Search Results list
                            val filteredUsers = remember(users, userSearchQuery) {
                                val q = userSearchQuery.trim().lowercase()
                                if (q.isBlank()) emptyList()
                                else users.filter { u ->
                                    u.name.lowercase().contains(q) ||
                                    u.email.lowercase().contains(q) ||
                                    u.employeeId.lowercase().contains(q) ||
                                    u.department.lowercase().contains(q)
                                }.take(5)
                            }

                            if (filteredUsers.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    filteredUsers.forEach { candidate ->
                                        val isSelected = selectedUserForDeletion?.uid == candidate.uid
                                        Card(
                                            onClick = { selectedUserForDeletion = candidate },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) Primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                            ),
                                            border = if (isSelected) BorderStroke(1.5.dp, Primary) else null,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(candidate.name.ifBlank { "User" }, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                                    Text("${candidate.department} • ${candidate.email.ifBlank { candidate.employeeId }}", fontSize = 11.5.sp, color = TextSecondary)
                                                }
                                                if (isSelected) {
                                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Selected User Action Panel
                            selectedUserForDeletion?.let { target ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Surface),
                                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(target.name.ifBlank { "Selected User" }, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                                                Text("Email: ${target.email} • Emp ID: ${target.employeeId}", fontSize = 12.sp, color = TextSecondary)
                                            }
                                            IconButton(onClick = { selectedUserForDeletion = null }) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Deselect", tint = TextTertiary)
                                            }
                                        }

                                        HorizontalDivider(color = Outline.copy(alpha = 0.2f))

                                        Text("Deletion Scope:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                                        // Option A: Delete data only
                                        Card(
                                            onClick = { deleteUserAccountOption = false },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (!deleteUserAccountOption) Primary.copy(alpha = 0.08f) else Surface
                                            ),
                                            border = BorderStroke(1.dp, if (!deleteUserAccountOption) Primary else Outline.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = !deleteUserAccountOption,
                                                    onClick = { deleteUserAccountOption = false }
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Column {
                                                    Text("Delete Data Only (Keep Account)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text("Wipes rides given, bookings, and needs. Resets CO₂ to 0. Login account stays active.", fontSize = 11.sp, color = TextSecondary)
                                                }
                                            }
                                        }

                                        val isSelfAdmin = target.uid == user.uid
                                        if (isSelfAdmin) {
                                            LaunchedEffect(target.uid) {
                                                deleteUserAccountOption = false
                                            }
                                        }

                                        // Option B: Delete data + account
                                        Card(
                                            onClick = { if (!isSelfAdmin) deleteUserAccountOption = true },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (deleteUserAccountOption) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Surface.copy(alpha = if (isSelfAdmin) 0.5f else 1f)
                                            ),
                                            border = BorderStroke(1.dp, if (deleteUserAccountOption) MaterialTheme.colorScheme.error else Outline.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = deleteUserAccountOption,
                                                    onClick = { if (!isSelfAdmin) deleteUserAccountOption = true },
                                                    enabled = !isSelfAdmin,
                                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        "Delete Data + Account",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (isSelfAdmin) TextSecondary else MaterialTheme.colorScheme.error
                                                    )
                                                    Text(
                                                        if (isSelfAdmin) "Admin account login cannot be deleted. Select 'Delete Data Only' to wipe your personal ride history."
                                                        else "Permanently erases user profile, employee ID registry, and all rides/bookings.",
                                                        fontSize = 11.sp,
                                                        color = TextSecondary
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))

                                        Button(
                                            onClick = {
                                                isCalculatingUserPreview = true
                                                showUserDeletionConfirmDialog = true
                                                scope.launch {
                                                    adminRepository.previewUserDeletion(user, target, deleteUserAccountOption)
                                                        .onSuccess { preview ->
                                                            userDeletionPreview = preview
                                                            isCalculatingUserPreview = false
                                                        }
                                                        .onFailure {
                                                            isCalculatingUserPreview = false
                                                            showUserDeletionConfirmDialog = false
                                                            statusMessage = "Preview failed: ${it.message}"
                                                        }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().height(44.dp)
                                        ) {
                                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (deleteUserAccountOption) "Delete User & Data" else "Delete User Data Only", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                        }
                                    }
                                }
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
