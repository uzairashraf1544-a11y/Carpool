package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MasterData
import com.example.data.model.PickupArea
import com.example.data.model.PickupSide
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupAreaSelector(
    selectedArea: String,
    onAreaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Pickup Area",
    highlightSide: PickupSide? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Full 48dp+ tap target container - entire area opens bottom sheet
    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { showSheet = true }
            .border(1.dp, Outline.copy(alpha = 0.65f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selectedArea.ifBlank { "Select pickup area" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedArea.isBlank()) TextTertiary else TextPrimary
                )
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Select Area",
                tint = Primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = Surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SELECT PICKUP AREA",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Choose your pickup meeting point",
                            fontSize = 12.5.sp,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. TOWNSHIP AREAS
                    item {
                        AreaSectionHeader(
                            title = "TOWNSHIP AREAS",
                            isHighlighted = highlightSide == PickupSide.TOWNSHIP
                        )
                    }
                    items(MasterData.TOWNSHIP_PICKUP_AREAS, key = { "township_${it.id}" }) { area ->
                        AreaItemRow(
                            area = area,
                            isSelected = selectedArea.equals(area.displayName, ignoreCase = true),
                            onClick = {
                                onAreaSelected(area.displayName)
                                showSheet = false
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // 2. PLANT AREAS
                    item {
                        AreaSectionHeader(
                            title = "PLANT AREAS",
                            isHighlighted = highlightSide == PickupSide.PLANT
                        )
                    }
                    items(MasterData.PLANT_PICKUP_AREAS, key = { "plant_${it.id}" }) { area ->
                        AreaItemRow(
                            area = area,
                            isSelected = selectedArea.equals(area.displayName, ignoreCase = true),
                            onClick = {
                                onAreaSelected(area.displayName)
                                showSheet = false
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // 3. OTHER
                    item {
                        AreaSectionHeader(
                            title = "OTHER",
                            isHighlighted = highlightSide == PickupSide.OTHER
                        )
                    }
                    items(MasterData.OTHER_PICKUP_AREAS, key = { "other_${it.id}" }) { area ->
                        AreaItemRow(
                            area = area,
                            isSelected = selectedArea.equals(area.displayName, ignoreCase = true) || selectedArea.equals(MasterData.OTHER_ZONE, ignoreCase = true),
                            onClick = {
                                onAreaSelected(area.displayName)
                                showSheet = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaSectionHeader(title: String, isHighlighted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHighlighted) PrimaryDark else TextSecondary,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun AreaItemRow(
    area: PickupArea,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) PrimaryVeryLight else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = if (isSelected) Primary else TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = area.displayName,
                    fontSize = 14.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) PrimaryDark else TextPrimary
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
