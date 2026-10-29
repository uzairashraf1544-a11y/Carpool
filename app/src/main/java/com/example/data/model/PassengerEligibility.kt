package com.example.data.model

sealed class PassengerEligibility {
    object Loading : PassengerEligibility()
    object CanCreateRequest : PassengerEligibility()
    data class LookingForRide(val need: PassengerNeed) : PassengerEligibility()
    data class ActiveRide(val request: RideRequest, val ride: Ride? = null) : PassengerEligibility()
    data class Error(val message: String) : PassengerEligibility()
}
