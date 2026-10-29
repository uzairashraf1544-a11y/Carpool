package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), // Chips
    small = RoundedCornerShape(14.dp),      // Inputs
    medium = RoundedCornerShape(16.dp),     // Buttons
    large = RoundedCornerShape(18.dp),      // Standard cards
    extraLarge = RoundedCornerShape(22.dp)  // Hero cards
)
