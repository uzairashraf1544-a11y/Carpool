package com.example.data.model

data class RideRequest(
    val requestId: String = "",
    val rideId: String = "",
    val passengerId: String = "",
    val passengerName: String = "",
    val passengerPhone: String = "",
    val passengerDepartment: String = "",
    val driverId: String = "",
    val driverName: String = "",
    val date: String = "",
    val commuteWindow: String = "",
    val pickupZone: String = "",
    val destinationZone: String = "",
    val status: String = STATUS_REQUESTED, // REQUESTED, ACCEPTED, REJECTED, CANCELLED, EXPIRED, COMPLETED
    val requestedAt: Long = System.currentTimeMillis(),
    val responseAt: Long = 0L
) {
    companion object {
        const val STATUS_REQUESTED = "REQUESTED"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}
