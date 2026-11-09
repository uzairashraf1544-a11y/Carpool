package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

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
    val privacyNoticeVersion: Int = 1,

    // CUMULATIVE IMPACT METRICS (CACHED DIRECTLY ON USER FOR ZERO-READ PERSONAL STATS)
    val totalCo2SavedKg: Double = 0.0,
    val totalCompletedRides: Int = 0
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

        /**
         * Robust, fault-tolerant parser that converts any Firestore document into a [User]
         * instance. Handles:
         * - Missing or null documents
         * - Timestamps stored as [Timestamp], [Number], [Date], or ISO [String]
         * - Numeric fields stored as [Number] or [String] (e.g. employeeId or phone)
         * - Boolean flags stored as [Boolean], [Number], or [String]
         * - Fallback from blank 'name' to 'displayName' or 'fullName'
         * - Normalization of role, accountStatus, and currentMode
         */
        fun fromSnapshot(doc: DocumentSnapshot?): User? {
            if (doc == null || !doc.exists()) return null
            val data = doc.data ?: return User(uid = doc.id)

            fun parseString(vararg keys: String, fallback: String = ""): String {
                for (key in keys) {
                    val v = data[key] ?: continue
                    val str = when (v) {
                        is String -> v
                        is Number -> v.toLong().toString()
                        else -> v.toString()
                    }
                    if (str.isNotBlank()) return str.trim()
                }
                return fallback
            }

            fun parseLong(vararg keys: String, fallback: Long = 0L): Long {
                for (key in keys) {
                    val v = data[key] ?: continue
                    return when (v) {
                        is Timestamp -> v.toDate().time
                        is Number -> v.toLong()
                        is Date -> v.time
                        is String -> v.toLongOrNull() ?: fallback
                        else -> fallback
                    }
                }
                return fallback
            }

            fun parseInt(vararg keys: String, fallback: Int = 0): Int {
                for (key in keys) {
                    val v = data[key] ?: continue
                    return when (v) {
                        is Number -> v.toInt()
                        is String -> v.toIntOrNull() ?: fallback
                        else -> fallback
                    }
                }
                return fallback
            }

            fun parseDouble(vararg keys: String, fallback: Double = 0.0): Double {
                for (key in keys) {
                    val v = data[key] ?: continue
                    return when (v) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: fallback
                        else -> fallback
                    }
                }
                return fallback
            }

            fun parseBoolean(vararg keys: String, fallback: Boolean = false): Boolean {
                for (key in keys) {
                    val v = data[key] ?: continue
                    return when (v) {
                        is Boolean -> v
                        is String -> v.toBoolean()
                        is Number -> v.toInt() != 0
                        else -> fallback
                    }
                }
                return fallback
            }

            val uid = parseString("uid").ifBlank { doc.id }
            val name = parseString("name", "displayName", "fullName")
            val email = parseString("email")
            val employeeId = parseString("employeeId", "empId")
            val department = parseString("department", fallback = "Administration").ifBlank { "Administration" }

            val rawRole = parseString("role", fallback = ROLE_USER)
            val role = when {
                rawRole.trim().uppercase() == ROLE_ADMIN -> ROLE_ADMIN
                parseBoolean("isAdmin", fallback = false) -> ROLE_ADMIN
                else -> ROLE_USER
            }

            val rawStatus = parseString("accountStatus", "status", fallback = STATUS_ACTIVE)
            val accountStatus = when (rawStatus.trim().uppercase()) {
                STATUS_PENDING_APPROVAL -> STATUS_PENDING_APPROVAL
                STATUS_BLOCKED -> STATUS_BLOCKED
                STATUS_REMOVED -> STATUS_REMOVED
                else -> STATUS_ACTIVE
            }

            val phone = parseString("phone", "phoneNumber")
            val townshipLocation = parseString("townshipLocation", "location", fallback = "EMs").ifBlank { "EMs" }
            val currentMode = parseString("currentMode", fallback = MODE_PASSENGER)
            val defaultRolePreference = parseString("defaultRolePreference", fallback = MODE_PASSENGER)
            val defaultDriverSeats = parseInt("defaultDriverSeats", "seats", fallback = 3).coerceIn(1, 8)
            val muteUntilTimestamp = parseLong("muteUntilTimestamp", fallback = 0L)
            val isPasswordChanged = parseBoolean("isPasswordChanged", fallback = true)

            val createdAt = parseLong("createdAt", fallback = System.currentTimeMillis())
            val blockedAt = parseLong("blockedAt", fallback = 0L)
            val blockedBy = parseString("blockedBy")
            val blockReason = parseString("blockReason")
            val removedAt = parseLong("removedAt", fallback = 0L)
            val removedBy = parseString("removedBy")
            val removeReason = parseString("removeReason")

            val approvalRequestedAt = parseLong("approvalRequestedAt", fallback = 0L)
            val approvedAt = parseLong("approvedAt", fallback = 0L)
            val approvedBy = parseString("approvedBy")
            val consentAcceptedAt = parseLong("consentAcceptedAt", fallback = 0L)
            val consentVersion = parseInt("consentVersion", fallback = 1)
            val privacyNoticeVersion = parseInt("privacyNoticeVersion", fallback = 1)
            val totalCo2SavedKg = parseDouble("totalCo2SavedKg", "co2Saved", "co2SavedKg", fallback = 0.0)
            val totalCompletedRides = parseInt("totalCompletedRides", "completedRides", fallback = 0)

            val raw = User(
                uid = uid,
                name = name,
                email = email,
                employeeId = employeeId,
                department = department,
                role = role,
                accountStatus = accountStatus,
                phone = phone,
                townshipLocation = townshipLocation,
                currentMode = currentMode,
                defaultRolePreference = defaultRolePreference,
                defaultDriverSeats = defaultDriverSeats,
                muteUntilTimestamp = muteUntilTimestamp,
                isPasswordChanged = isPasswordChanged,
                createdAt = createdAt,
                blockedAt = blockedAt,
                blockedBy = blockedBy,
                blockReason = blockReason,
                removedAt = removedAt,
                removedBy = removedBy,
                removeReason = removeReason,
                approvalRequestedAt = approvalRequestedAt,
                approvedAt = approvedAt,
                approvedBy = approvedBy,
                consentAcceptedAt = consentAcceptedAt,
                consentVersion = consentVersion,
                privacyNoticeVersion = privacyNoticeVersion,
                totalCo2SavedKg = totalCo2SavedKg,
                totalCompletedRides = totalCompletedRides
            )

            return raw.copy(
                role = raw.effectiveRole,
                accountStatus = raw.effectiveAccountStatus,
                currentMode = raw.effectiveMode
            )
        }
    }
}
