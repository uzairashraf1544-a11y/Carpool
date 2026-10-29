package com.example.data.model

data class Ride(
    val rideId: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    val driverDepartment: String = "",
    val date: String = "", // e.g. "2026-09-02"
    val pickupZone: String = "EMs",
    val pickupSide: String = "TOWNSHIP", // "TOWNSHIP", "PLANT", or "OTHER"
    val customPickupLocation: String = "",
    val totalSeats: Int = 3,
    val availableSeats: Int = 3,
    val acceptedPassengerIds: List<String> = emptyList(),
    val completedPassengerIds: List<String> = emptyList(),
    val message: String = "",
    val status: String = STATUS_AVAILABLE, // AVAILABLE, PARTIALLY_FULL, FULL, COMPLETED, CANCELLED, EXPIRED
    val expiresAt: Long = System.currentTimeMillis() + 18 * 3600 * 1000L,
    val creationSource: String = "APP",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val finalPassengerCount: Int = 0,
    val confirmedPassengerCount: Int = 0,
    // Legacy fields for backward deserialization of older records
    val isDiscoverable: Boolean = true,
    val commuteSlotId: String = "",
    val originSide: String = "",
    val commuteWindow: String = "",
    val direction: String = "",
    val destinationZone: String = "",
    val pickupDetails: String = ""
) {
    val displayPickup: String
        get() = if (pickupSide.equals("OTHER", ignoreCase = true) && customPickupLocation.isNotBlank()) {
            customPickupLocation
        } else {
            pickupZone
        }

    companion object {
        const val STATUS_OFFLINE = "OFFLINE"
        const val STATUS_AVAILABLE = "AVAILABLE"
        const val STATUS_PARTIALLY_FULL = "PARTIALLY_FULL"
        const val STATUS_FULL = "FULL"
        const val STATUS_RIDE_STARTED = "RIDE_STARTED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
