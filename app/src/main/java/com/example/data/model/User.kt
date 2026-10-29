package com.example.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val employeeId: String = "",
    val department: String = "Administration",

    // SECURITY AUTHORIZATION (USER, ADMIN)
    val role: String = ROLE_USER,

    // ACCOUNT LIFECYCLE STATUS (ACTIVE, BLOCKED, REMOVED)
    val accountStatus: String = STATUS_ACTIVE,

    val phone: String = "",
    val townshipLocation: String = "EMs",

    // CURRENT OPERATING MODE (PASSENGER, DRIVER)
    val currentMode: String = MODE_PASSENGER,

    // USER'S DEFAULT/PREFERRED MODE (PASSENGER, DRIVER)
    val defaultRolePreference: String = MODE_PASSENGER,

    val defaultDriverSeats: Int = 3,
    val muteUntilTimestamp: Long = 0L,
    val isPasswordChanged: Boolean = true,

    // AUDIT & LIFECYCLE TRACKING
    val createdAt: Long = System.currentTimeMillis(),
    val blockedAt: Long = 0L,
    val blockedBy: String = "",
    val blockReason: String = "",
    val removedAt: Long = 0L,
    val removedBy: String = "",
    val removeReason: String = "",

    // APPROVAL & ONBOARDING CONSENT TRACKING
    val approvalRequestedAt: Long = 0L,
    val approvedAt: Long = 0L,
    val approvedBy: String = "",
    val consentAcceptedAt: Long = 0L,
    val consentVersion: Int = 1,
    val privacyNoticeVersion: Int = 1
) {
    val effectiveRole: String
        get() = when (role.trim().uppercase()) {
            ROLE_ADMIN -> ROLE_ADMIN
            else -> ROLE_USER
        }

    val effectiveAccountStatus: String
        get() = when (accountStatus.trim().uppercase()) {
            STATUS_PENDING_APPROVAL -> STATUS_PENDING_APPROVAL
            STATUS_BLOCKED -> STATUS_BLOCKED
            STATUS_REMOVED -> STATUS_REMOVED
            else -> STATUS_ACTIVE
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
    fun isActive(): Boolean = effectiveAccountStatus == STATUS_ACTIVE
    fun isPendingApproval(): Boolean = effectiveAccountStatus == STATUS_PENDING_APPROVAL
    fun isBlocked(): Boolean = effectiveAccountStatus == STATUS_BLOCKED
    fun isRemoved(): Boolean = effectiveAccountStatus == STATUS_REMOVED

    fun isDriverMode(): Boolean = effectiveMode == MODE_DRIVER
    fun isPassengerMode(): Boolean = effectiveMode == MODE_PASSENGER

    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ADMIN = "ADMIN"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PENDING_APPROVAL = "PENDING_APPROVAL"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_REMOVED = "REMOVED"

        const val MODE_PASSENGER = "PASSENGER"
        const val MODE_DRIVER = "DRIVER"
    }
}
