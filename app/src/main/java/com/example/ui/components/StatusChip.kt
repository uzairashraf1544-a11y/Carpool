package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Ride
import com.example.data.model.RideRequest

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, textColor) = when (status.uppercase()) {
        Ride.STATUS_OFFLINE -> Color(0xFFF2F6F3) to Color(0xFF64716C)
        Ride.STATUS_AVAILABLE -> Color(0xFFE6F4EC) to Color(0xFF176A43)
        Ride.STATUS_PARTIALLY_FULL -> Color(0xFFE7F3F2) to Color(0xFF256C67)
        Ride.STATUS_FULL -> Color(0xFFFFF1D9) to Color(0xFF986A18)
        Ride.STATUS_RIDE_STARTED -> Color(0xFFE6EFFA) to Color(0xFF356B9A)
        Ride.STATUS_COMPLETED -> Color(0xFFEAEFEC) to Color(0xFF52645C)
        Ride.STATUS_CANCELLED -> Color(0xFFFCE9E7) to Color(0xFF9D4242)
        RideRequest.STATUS_REQUESTED -> Color(0xFFEBF2F7) to Color(0xFF466B83)
        RideRequest.STATUS_ACCEPTED -> Color(0xFFE6F4EC) to Color(0xFF176A43)
        RideRequest.STATUS_REJECTED -> Color(0xFFFCE9E7) to Color(0xFF9D4242)
        RideRequest.STATUS_EXPIRED -> Color(0xFFF2F6F3) to Color(0xFF64716C)
        else -> Color(0xFFF2F6F3) to Color(0xFF64716C)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = status.replace("_", " "),
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
