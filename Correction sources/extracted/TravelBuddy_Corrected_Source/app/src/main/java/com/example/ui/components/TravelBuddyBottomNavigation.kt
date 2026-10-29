package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.User
import com.example.ui.theme.*

@Composable
fun TravelBuddyBottomNavigation(
    currentRoute: String,
    userRole: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isAdmin = userRole.uppercase() == User.ROLE_ADMIN

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Home
            NavItem(
                title = "Home",
                icon = Icons.Rounded.Home,
                isSelected = currentRoute == "home" || currentRoute == "passenger_dashboard" || currentRoute == "driver_dashboard",
                onClick = { onNavigate("home") }
            )

            // Activity
            NavItem(
                title = "Activity",
                icon = Icons.Rounded.Timeline,
                isSelected = currentRoute == "activity",
                onClick = { onNavigate("activity") }
            )

            // Admin (ONLY APPEARS IF userRole == ADMIN)
            if (isAdmin) {
                NavItem(
                    title = "Admin",
                    icon = Icons.Rounded.AdminPanelSettings,
                    isSelected = currentRoute == "admin_dashboard" || currentRoute.startsWith("admin"),
                    onClick = { onNavigate("admin_dashboard") }
                )
            }

            // Profile
            NavItem(
                title = "Profile",
                icon = Icons.Rounded.Person,
                isSelected = currentRoute == "profile",
                onClick = { onNavigate("profile") }
            )
        }
    }
}

@Composable
private fun NavItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (isSelected) Primary else TextTertiary
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
        if (isSelected) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Primary)
            )
        }
    }
}
