package com.example.data.model

data class RideRequest(
    val requestId: String = "",
    val rideId: String = "",
    val needId: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val passengerPhone: String = "",
    val passengerDepartment: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val driverDepartment: String = "",
    val driverPhone: String = "",
    val date: String = "",
    val direction: String = "Township → Plant",
    val commuteWindow: String = "",
    val pickupZone: String = "",
    val customPickupLocation: String = "",
    val destinationZone: String = "",
    val status: String = STATUS_ACCEPTED, // ACCEPTED, REQUESTED, REJECTED, CANCELLED, CANCELLED_BY_PASSENGER, CANCELLED_BY_DRIVER, CLOSED_UNCONFIRMED, EXPIRED, COMPLETED
    val pickupDetails: String = "",
    val message: String = "",
    val requestedAt: Long = System.currentTimeMillis(),
    val responseAt: Long = 0L,
    val passengerCompletedAt: Long = 0L
) {
    val displayPickup: String
        get() = if (customPickupLocation.isNotBlank()) customPickupLocation else pickupZone

    companion object {
        const val STATUS_REQUESTED = "REQUESTED"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_CANCELLED_BY_PASSENGER = "CANCELLED_BY_PASSENGER"
        const val STATUS_CANCELLED_BY_DRIVER = "CANCELLED_BY_DRIVER"
        const val STATUS_CLOSED_UNCONFIRMED = "CLOSED_UNCONFIRMED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_COMPLETED = "COMPLETED"

        fun isActivePassengerParticipation(status: String?): Boolean {
            return status == STATUS_ACCEPTED || status == "RIDE_STARTED"
        }
    }
}
