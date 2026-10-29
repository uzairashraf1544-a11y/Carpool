package com.example.data.model

data class PassengerNeed(
    val needId: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val passengerDepartment: String = "",
    val passengerPhone: String = "",
    val date: String = "", // e.g. "2026-09-02"
    val pickupZone: String = "EMs",
    val pickupSide: String = "TOWNSHIP", // "TOWNSHIP", "PLANT", or "OTHER"
    val customPickupLocation: String = "",
    val message: String = "",
    val status: String = STATUS_LOOKING, // LOOKING, REQUESTED, MATCHED, COMPLETED, CANCELLED, EXPIRED
    val expiresAt: Long = System.currentTimeMillis() + 18 * 3600 * 1000L,
    val matchedDriverId: String = "",
    val matchedRideId: String = "",
    val requestedRideId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val matchedAt: Long = 0L,
    // Legacy fields for backward deserialization of older records
    val isDiscoverable: Boolean = true,
    val commuteSlotId: String = "",
    val originSide: String = "",
    val direction: String = "",
    val commuteWindow: String = "",
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
        const val STATUS_LOOKING = "LOOKING"
        const val STATUS_REQUESTED = "REQUESTED"
        const val STATUS_MATCHED = "MATCHED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
