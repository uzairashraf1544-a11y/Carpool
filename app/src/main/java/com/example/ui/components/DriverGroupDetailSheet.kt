package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CommuteSelection
import com.example.data.model.Ride
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverGroupDetailSheet(
    pickupZone: String,
    commuteSelection: CommuteSelection,
    matchingRides: List<Ride>,
    onDismiss: () -> Unit,
    onJoinRide: (Ride) -> Unit
) {
    val groupRides = remember(matchingRides, pickupZone) {
        matchingRides.filter { it.pickupZone == pickupZone && it.availableSeats > 0 }
    }
    val totalSeats = remember(groupRides) { groupRides.sumOf { it.availableSeats } }

    var rideToConfirm by remember { mutableStateOf<Ride?>(null) }

    if (rideToConfirm != null) {
        val ride = rideToConfirm!!
        AlertDialog(
            onDismissRequest = { rideToConfirm = null },
            title = { Text("Join ${ride.driverName}'s ride?", fontWeight = FontWeight.Bold, color = PrimaryDark) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Route: ${ride.pickupZone} ➔ ${ride.destinationZone}", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Time: ${ride.commuteWindow}", fontSize = 14.sp, color = TextSecondary)
                    Text("Available Seats: ${ride.availableSeats} of ${ride.totalSeats}", fontSize = 13.5.sp, color = Primary)
                    if (ride.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Driver Note: \"${ride.message}\"", fontSize = 13.sp, color = TextTertiary)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = rideToConfirm ?: return@Button
                        rideToConfirm = null
                        onJoinRide(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Join Ride", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { rideToConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$pickupZone ➔ ${commuteSelection.destinationZone}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                    Text(
                        text = "${commuteSelection.formattedDate} • ${commuteSelection.commuteWindow}",
                        fontSize = 13.5.sp,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Summary Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryVeryLight)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${groupRides.size} Drivers Available • $totalSeats Total Seats",
                    color = PrimaryDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (groupRides.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No active drivers currently available for $pickupZone.", color = TextSecondary, fontSize = 14.5.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                ) {
                    items(groupRides) { ride ->
                        val initials = ride.driverName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier.size(42.dp).clip(CircleShape).background(PrimaryLight),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(if (initials.isNotBlank()) initials else "D", color = PrimaryDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(ride.driverName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text("Dept: ${ride.driverDepartment}", fontSize = 13.sp, color = TextSecondary)
                                        }
                                    }
                                    StatusChip(status = ride.status)
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Window: ${ride.commuteWindow}", fontSize = 13.5.sp, color = TextSecondary)
                                Text("Available Seats: ${ride.availableSeats} of ${ride.totalSeats}", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.SemiBold)

                                if (ride.message.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Note: \"${ride.message}\"", fontSize = 13.sp, color = TextTertiary)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { rideToConfirm = ride },
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    Text("JOIN RIDE", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
