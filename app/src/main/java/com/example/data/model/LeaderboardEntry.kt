package com.example.data.model

data class LeaderboardEntry(
    val uid: String = "",
    val name: String = "",
    val department: String = "",
    val co2SavedKg: Double = 0.0,
    val rideCount: Int = 0,
    val rank: Int = 1,
    val isCurrentUser: Boolean = false
)
