package com.example.data.model

/**
 * Local UI representation of travel mode for screen browsing.
 * Kept strictly separate from security Role (USER vs ADMIN).
 */
enum class TravelMode {
    PASSENGER,
    DRIVER;

    fun toUserMode(): String = when (this) {
        PASSENGER -> User.MODE_PASSENGER
        DRIVER -> User.MODE_DRIVER
    }

    companion object {
        fun fromString(value: String?): TravelMode {
            return when (value?.trim()?.uppercase()) {
                User.MODE_DRIVER, "DRIVER" -> DRIVER
                else -> PASSENGER
            }
        }
    }
}
