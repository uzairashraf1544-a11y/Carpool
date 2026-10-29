package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.User
import com.example.ui.theme.*

@Composable
fun ModeSwitcher(
    currentMode: String,
    onModeChanged: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isPassenger = currentMode == User.MODE_PASSENGER

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Surface)
            .border(1.dp, Outline, RoundedCornerShape(28.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // PASSENGER Segment
            ModeSegment(
                title = "PASSENGER",
                icon = Icons.Rounded.Person,
                isSelected = isPassenger,
                onClick = { if (enabled && !isPassenger) onModeChanged(User.MODE_PASSENGER) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )

            // DRIVER Segment
            ModeSegment(
                title = "DRIVER",
                icon = Icons.Rounded.DirectionsCar,
                isSelected = !isPassenger,
                onClick = { if (enabled && isPassenger) onModeChanged(User.MODE_DRIVER) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeSegment(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryDark else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "segmentBackground"
    )

    val contentColor by animateColorAsState(
        targetValue = if (!enabled) TextTertiary else if (isSelected) Color.White else TextSecondary,
        animationSpec = tween(durationMillis = 220),
        label = "segmentContent"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = contentColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
