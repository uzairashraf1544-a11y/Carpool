package com.example.data.model

data class Co2Record(
    val recordId: String = "",
    val rideId: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverDepartment: String = "",
    val passengerCount: Int = 0,
    val baselineDistanceKm: Double = 3.0,
    val totalAvoidedKm: Double = 0.0,
    val co2FactorKgPerKm: Double = 0.12,
    val co2SavedKg: Double = 0.0,
    val monthYear: String = "", // e.g. "2026-08"
    val timestamp: Long = System.currentTimeMillis()
)
