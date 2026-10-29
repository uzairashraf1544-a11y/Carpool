package com.example.data.model

/**
 * Local UI/device representation of a user's running travel state.
 * Enforces that a user may have only one active post/participation on this device.
 */
enum class LocalTravelState {
    NONE,
    PASSENGER_LOOKING,
    PASSENGER_ACTIVE,
    DRIVER_AVAILABLE,
    DRIVER_ACTIVE;

    val isPassengerPostOrActive: Boolean
        get() = this == PASSENGER_LOOKING || this == PASSENGER_ACTIVE

    val isDriverPostOrActive: Boolean
        get() = this == DRIVER_AVAILABLE || this == DRIVER_ACTIVE
}
