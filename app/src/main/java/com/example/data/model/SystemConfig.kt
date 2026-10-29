package com.example.data.model

data class SystemConfig(
    val baselineDistanceKm: Double = 3.0,
    val co2FactorKgPerKm: Double = 0.12,
    val bootstrapAdminUsed: Boolean = false,
    val bootstrapAdminUid: String = "",
    val approverDisplayName: String = "Administrator",
    val approverEmail: String = ""
)
