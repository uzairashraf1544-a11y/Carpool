package com.example.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.RideRepository
import com.example.ui.components.StatusChip
import com.example.ui.theme.*

private data class ActivityEntry(
    val id: String,
    val mode: String,
    val status: String,
    val date: String,
    val commuteWindow: String,
    val pickupZone: String,
    val destinationZone: String,
    val secondary: String,
    val sortTimestamp: Long
)

@Composable
fun ActivityScreen(
    user: User,
    rideRepository: RideRepository = remember { RideRepository() }
) {
    val passengerRequests by rideRepository
        .getPassengerHistoryFlow(user.uid)
        .collectAsState(initial = emptyList())
    val driverRides by rideRepository
        .getDriverRideHistoryFlow(user.uid)
        .collectAsState(initial = emptyList())

    var selectedFilter by remember { mutableStateOf("All") }

    val entries = remember(passengerRequests, driverRides) {
        val passengerEntries = passengerRequests.map { req ->
            ActivityEntry(
                id = "passenger_${req.requestId}",
                mode = "Passenger",
                status = req.status,
                date = req.date,
                commuteWindow = req.commuteWindow,
                pickupZone = req.pickupZone,
                destinationZone = req.destinationZone,
                secondary = "Driver: ${req.driverName.ifBlank { "Not assigned" }}",
                sortTimestamp = maxOf(req.requestedAt, req.responseAt)
            )
        }
        val driverEntries = driverRides
            .filter { it.status != Ride.STATUS_OFFLINE }
            .map { ride ->
                ActivityEntry(
                    id = "driver_${ride.rideId}",
                    mode = "Driver",
                    status = ride.status,
                    date = ride.date,
                    commuteWindow = ride.commuteWindow,
                    pickupZone = ride.pickupZone,
                    destinationZone = ride.destinationZone,
                    secondary = "${ride.acceptedPassengerIds.size} passenger${if (ride.acceptedPassengerIds.size == 1) "" else "s"}",
                    sortTimestamp = maxOf(ride.createdAt, ride.startedAt, ride.completedAt)
                )
            }
        (passengerEntries + driverEntries).sortedByDescending { it.sortTimestamp }
    }

    val visibleEntries = remember(entries, selectedFilter) {
        when (selectedFilter) {
            "Passenger" -> entries.filter { it.mode == "Passenger" }
            "Driver" -> entries.filter { it.mode == "Driver" }
            else -> entries
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text("Activity", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Your TravelBuddy journeys", fontSize = 14.5.sp, color = TextSecondary)

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Surface)
                .border(1.dp, Outline, RoundedCornerShape(22.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("All", "Passenger", "Driver").forEach { filter ->
                val isSelected = selectedFilter == filter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) PrimaryDark else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { selectedFilter = filter },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else TextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (visibleEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No journeys yet", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (selectedFilter == "All") "Your TravelBuddy activity will appear here."
                        else "No ${selectedFilter.lowercase()} activity yet.",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(visibleEntries, key = { it.id }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${entry.mode} Ride",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                StatusChip(status = entry.status)
                            }
                            Spacer(modifier = Modifier.height(7.dp))
                            Text(
                                "${entry.pickupZone} ➔ ${entry.destinationZone}",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                listOf(entry.date, entry.commuteWindow).filter { it.isNotBlank() }.joinToString(" • "),
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Text(entry.secondary, fontSize = 13.sp, color = TextTertiary)
                        }
                    }
                }
            }
        }
    }
}
