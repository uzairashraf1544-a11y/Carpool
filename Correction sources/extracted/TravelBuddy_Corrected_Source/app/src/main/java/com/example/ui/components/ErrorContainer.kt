package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ErrorContainer(
    message: String,
    modifier: Modifier = Modifier
) {
    val friendlyMessage = when {
        message.contains("PERMISSION_DENIED", ignoreCase = true) || message.contains("permissions", ignoreCase = true) -> {
            "We couldn't update your information. Please check your details and try again."
        }
        message.contains("network", ignoreCase = true) || message.contains("unavailable", ignoreCase = true) -> {
            "No internet connection. Check your connection and try again."
        }
        message.contains("expired", ignoreCase = true) || message.contains("auth", ignoreCase = true) -> {
            "Your session has expired. Please sign in again."
        }
        message.isNotBlank() -> message
        else -> "Something went wrong. Please try again."
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFDECEA))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Error",
                tint = Color(0xFF9F3434),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = friendlyMessage,
                color = Color(0xFF9F3434),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
