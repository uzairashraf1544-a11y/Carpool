package com.example.ui.screens.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*

@Composable
fun SplashScreen(message: String = "Restoring your session…") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.travelbuddy_logo),
                contentDescription = "TravelBuddy",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(200.dp)
                    .height(52.dp)
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Your commute, made easier.",
                fontSize = 15.sp,
                color = TextSecondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(26.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Primary,
                strokeWidth = 2.5.dp
            )
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 12.5.sp, color = TextTertiary)
        }
    }
}
