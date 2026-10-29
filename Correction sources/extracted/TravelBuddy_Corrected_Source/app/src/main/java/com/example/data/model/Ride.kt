package com.example.data.model

data class Ride(
    val rideId: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    val driverDepartment: String = "",
    val date: String = "", // e.g. "2026-08-29"
    val commuteWindow: String = "Morning Inbound",
    val direction: String = "Township → Plant",
    val pickupZone: String = "EMs",
    val destinationZone: String = "Maintenance South Workshop",
    val totalSeats: Int = 3,
    val availableSeats: Int = 3,
    val acceptedPassengerIds: List<String> = emptyList(),
    val status: String = STATUS_AVAILABLE, // OFFLINE, AVAILABLE, PARTIALLY_FULL, FULL, RIDE_STARTED, COMPLETED, CANCELLED
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L
) {
    companion object {
        const val STATUS_OFFLINE = "OFFLINE"
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_PARTIALLY_FULL = "PARTIALLY_FULL"
        const val STATUS_FULL = "FULL"
        const val STATUS_RIDE_STARTED = "RIDE_STARTED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
