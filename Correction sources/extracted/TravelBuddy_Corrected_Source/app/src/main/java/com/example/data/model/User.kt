package com.example.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val employeeId: String = "",
    val department: String = "Administration",

    // SECURITY AUTHORIZATION (USER, ADMIN)
    val role: String = ROLE_USER,

    val phone: String = "",
    val townshipLocation: String = "EMs",

    // CURRENT OPERATING MODE (PASSENGER, DRIVER)
    val currentMode: String = MODE_PASSENGER,

    // USER'S DEFAULT/PREFERRED MODE (PASSENGER, DRIVER)
    val defaultRolePreference: String = MODE_PASSENGER,

    val defaultDriverSeats: Int = 3,
    val muteUntilTimestamp: Long = 0L,
    val isPasswordChanged: Boolean = true
) {
    val effectiveRole: String
        get() = when (role.trim().uppercase()) {
            ROLE_ADMIN -> ROLE_ADMIN
            else -> ROLE_USER
        }

    val effectiveMode: String
        get() {
            val modeUpper = currentMode.trim().uppercase()
            if (modeUpper == MODE_PASSENGER || modeUpper == MODE_DRIVER) {
                return modeUpper
            }
            val prefUpper = defaultRolePreference.trim().uppercase()
            if (prefUpper == MODE_PASSENGER || prefUpper == MODE_DRIVER) {
                return prefUpper
            }
            return MODE_PASSENGER
        }

    fun isAdmin(): Boolean = effectiveRole == ROLE_ADMIN
    fun isDriverMode(): Boolean = effectiveMode == MODE_DRIVER
    fun isPassengerMode(): Boolean = effectiveMode == MODE_PASSENGER

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ADMIN = "ADMIN"

        const val MODE_PASSENGER = "PASSENGER"
        const val MODE_DRIVER = "DRIVER"
    }
}
