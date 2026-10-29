# TRAVELBUDDY — DATA MODEL & SCHEMA AUDIT
**Inspection Date**: 2026-09-03  
**Status**: Comprehensive Field & Collection Inventory  

---

## 1. FIRESTORE COLLECTION INVENTORY

| Collection | Purpose | Typical Document ID | Read Scope | Write Scope | Realtime vs One-shot |
|---|---|---|---|---|---|
| `users` | Employee account, security role, travel mode, contact info | `{userId}` (Firebase Auth UID) | Owner self OR Admin | Self (profile only) OR Admin (role/status) | Realtime profile listener |
| `employeeRegistry` | Unique employee ID registry for deterministic onboarding | `{normalizedEmployeeId}` (e.g. `FFL-01981`) | All signed-in users (Point-read get only) | User self (claim during onboarding) OR Admin | One-shot get during onboarding |
| `rides` | Driver posted ride availability & active seat state | `{driverId}_{date}_{timestamp}` | Signed-in users on matching side / open status | Driver owner OR Matched Passenger (join/complete) | Realtime discovery listener |
| `passengerNeeds` | Passenger ride requests & demand posts | `{passengerId}_{date}_{timestamp}` | Looking needs open to all; matched open to matched driver & passenger | Passenger owner OR Matched Driver | Realtime discovery listener |
| `rideRequests` | Participation record linking passenger to specific ride | `{rideId}_{passengerId}` | Passenger owner OR Driver owner OR Admin | Passenger (join, self-close) OR Driver (accept, master-close) | Realtime active ride listener |
| `systemConfig` | Global sustainability conversion baseline & factors | `"sustainability"` | All signed-in users | Admin only | Realtime config stream |
| `auditLogs` | Immutable record of administrative member actions | `{timestamp}_{action}_{targetUid}` | Admin only | Admin only | Realtime audit stream in Admin Dashboard |
| `co2Records` | [LEGACY] Standalone sustainability records | Arbitrary | Admin only | Admin only | Deprecated; replaced by direct aggregation |
| `adminAnalytics`| [LEGACY] Pre-computed analytics summaries | Arbitrary | Admin only | Admin only | Deprecated; replaced by live aggregation |

---

## 2. DETAILED SCHEMA DEFINITIONS

### 2.1 Collection: `users/{userId}`

```kotlin
data class User(
    val uid: String = "",                       // Primary Key (Auth UID)
    val name: String = "",                      // Full Name
    val email: String = "",                     // Google or Email Auth identifier
    val employeeId: String = "",                // Employee Registration Number (e.g. FFL-01981)
    val department: String = "Administration",  // Organization Department
    val role: String = ROLE_USER,               // Security Role: "USER" or "ADMIN"
    val accountStatus: String = STATUS_ACTIVE,  // Account State: "ACTIVE", "BLOCKED", "REMOVED"
    val phone: String = "",                     // Phone Number
    val townshipLocation: String = "EMs",       // Default Township Area
    val currentMode: String = MODE_PASSENGER,   // Active UX Mode: "PASSENGER" or "DRIVER"
    val defaultRolePreference: String = MODE_PASSENGER, // Default launch preference
    val defaultDriverSeats: Int = 3,            // Default seat capacity (1-8)
    val muteUntilTimestamp: Long = 0L,          // Timestamp until which reminders are muted
    val isPasswordChanged: Boolean = true,      // Initial password reset flag
    val createdAt: Long = System.currentTimeMillis(),
    val blockedAt: Long = 0L,                   // Timestamp of administrative block
    val blockedBy: String = "",                 // Admin UID
    val blockReason: String = "",               // Reason string
    val removedAt: Long = 0L,                   // Timestamp of administrative soft-removal
    val removedBy: String = "",                 // Admin UID
    val removeReason: String = ""               // Reason string
)
```

- **Effective Property Getters**:
  - `effectiveRole`: returns `"ADMIN"` if uppercase equals `"ADMIN"`, else `"USER"`.
  - `effectiveAccountStatus`: returns `"BLOCKED"`, `"REMOVED"`, or defaults to `"ACTIVE"`.
  - `effectiveMode`: returns `currentMode` if valid (`"PASSENGER"` or `"DRIVER"`), else falls back to `defaultRolePreference`, else `"PASSENGER"`.
- **Security Constraints**:
  - Creation requires `role == 'USER'` and `accountStatus == 'ACTIVE'`.
  - User self-updates **cannot** alter `role` or `accountStatus`.
  - Admin updates can alter `role`, `accountStatus`, `blockedAt`, `blockedBy`, `blockReason`, `removedAt`, `removedBy`, `removeReason`.

---

### 2.2 Collection: `employeeRegistry/{normalizedEmployeeId}`

```json
{
  "uid": "FIREBASE_AUTH_UID_STRING",
  "createdAt": 1788390000000
}
```

- **Document ID**: Normalized uppercase employee ID with whitespace stripped (e.g. `FFL-02376`).
- **Security Rules**:
  - `allow get: if signedIn()` (Allows point-read uniqueness checks without collection listing).
  - `allow list: if signedIn() && isAdmin()` (Prevents user enumeration).
  - `allow create: if signedIn() && request.resource.data.uid == request.auth.uid`.

---

### 2.3 Collection: `rides/{rideId}`

```kotlin
data class Ride(
    val rideId: String = "",                    // Primary Key: {driverId}_{date}_{timestamp}
    val driverId: String = "",                  // Driver Auth UID (Immutable ownership)
    val driverName: String = "",                // Denormalized driver full name
    val driverPhone: String = "",               // Denormalized driver phone
    val driverDepartment: String = "",          // Denormalized driver department
    val date: String = "",                      // ISO Date: YYYY-MM-DD
    val pickupZone: String = "",                // Pickup location ("EMs", "OTHER", etc.)
    val pickupSide: String = "TOWNSHIP",        // Side: "TOWNSHIP", "PLANT", "OTHER"
    val customPickupLocation: String = "",      // Required non-blank if pickupSide == "OTHER"
    val totalSeats: Int = 3,                    // Total seats offered (1-8)
    val availableSeats: Int = 3,                // Seats currently open for joining
    val acceptedPassengerIds: List<String> = emptyList(), // UIDs of accepted passengers
    val completedPassengerIds: List<String> = emptyList(), // UIDs of confirmed completed passengers
    val message: String = "",                   // Driver notes / instructions (max 250 chars)
    val status: String = STATUS_AVAILABLE,      // Lifecycle status (see State Machine)
    val expiresAt: Long = 0L,                   // Expiry epoch (creation + 18 hours)
    val creationSource: String = "APP",         // "APP" or "DIRECT_ACCEPT"
    val createdAt: Long = 0L,                   // Creation epoch
    val startedAt: Long = 0L,                   // [LEGACY] Start epoch
    val completedAt: Long = 0L,                 // Completion epoch
    val updatedAt: Long = 0L,                   // Last modification epoch
    // Legacy fields preserved for backward compatibility:
    val commuteSlotId: String = "",             // [DEAD CODE]
    val commuteWindow: String = "",             // [LEGACY] Defaults to "Any"
    val direction: String = "",                 // [LEGACY] Defaults to "Township → Plant" or "Plant → Township"
    val destinationZone: String = "",           // [LEGACY]
    val pickupDetails: String = ""              // [LEGACY]
)
```

- **Helper Property**:
  ```kotlin
  val displayPickup: String
      get() = if (pickupSide == "OTHER" || customPickupLocation.isNotBlank()) {
          customPickupLocation.ifBlank { pickupZone }
      } else {
          pickupZone
      }
  ```

---

### 2.4 Collection: `passengerNeeds/{needId}`

```kotlin
data class PassengerNeed(
    val needId: String = "",                    // Primary Key: {passengerId}_{date}_{timestamp}
    val passengerId: String = "",              // Passenger Auth UID (Immutable ownership)
    val passengerName: String = "",            // Denormalized passenger full name
    val passengerDepartment: String = "",      // Denormalized passenger department
    val date: String = "",                      // ISO Date: YYYY-MM-DD
    val pickupZone: String = "",                // Pickup area ("C-Type", "OTHER", etc.)
    val pickupSide: String = "TOWNSHIP",        // Side: "TOWNSHIP", "PLANT", "OTHER"
    val customPickupLocation: String = "",      // Required non-blank if pickupSide == "OTHER"
    val message: String = "",                   // Passenger note (max 250 chars)
    val status: String = STATUS_LOOKING,        // "LOOKING", "REQUESTED", "MATCHED", "COMPLETED", "CANCELLED", "EXPIRED"
    val matchedRideId: String = "",             // ID of ride if matched
    val matchedDriverId: String = "",           // Driver UID if matched
    val requestedRideId: String = "",           // [LEGACY]
    val expiresAt: Long = 0L,                   // Expiry epoch (creation + 18 hours)
    val createdAt: Long = 0L,                   // Creation epoch
    val updatedAt: Long = 0L,                   // Update epoch
    val matchedAt: Long = 0L,                   // Match epoch
    val completedAt: Long = 0L,                 // Completion epoch
    val cancelledAt: Long = 0L,                 // Cancellation epoch
    val expiredAt: Long = 0L,                   // Expiry epoch
    // Legacy fields:
    val commuteSlotId: String = "",             // [DEAD CODE]
    val commuteWindow: String = "",             // [LEGACY] Defaults to "Any"
    val direction: String = "",                 // [LEGACY]
    val destinationZone: String = "",           // [LEGACY]
    val pickupDetails: String = ""              // [LEGACY]
)
```

- **Helper Property**:
  ```kotlin
  val displayPickup: String
      get() = if (pickupSide == "OTHER" || customPickupLocation.isNotBlank()) {
          customPickupLocation.ifBlank { pickupZone }
      } else {
          pickupZone
      }
  ```

---

### 2.5 Collection: `rideRequests/{requestId}`

```kotlin
data class RideRequest(
    val requestId: String = "",                 // Primary Key: {rideId}_{passengerId}
    val rideId: String = "",                    // Foreign Key to rides/{rideId}
    val needId: String = "",                    // Foreign Key to passengerNeeds/{needId}
    val driverId: String = "",                  // Driver Auth UID
    val driverName: String = "",                // Denormalized driver full name
    val driverDepartment: String = "",          // Denormalized driver department
    val passengerId: String = "",              // Passenger Auth UID
    val passengerName: String = "",            // Denormalized passenger full name
    val passengerDepartment: String = "",      // Denormalized passenger department
    val passengerPhone: String = "",           // Denormalized passenger phone
    val date: String = "",                      // ISO Date: YYYY-MM-DD
    val pickupZone: String = "",                // Pickup location
    val customPickupLocation: String = "",      // Custom location string
    val message: String = "",                   // Note
    val status: String = STATUS_ACCEPTED,       // "ACCEPTED", "COMPLETED", "CANCELLED_BY_PASSENGER", "CANCELLED_BY_DRIVER", "CLOSED_UNCONFIRMED"
    val requestedAt: Long = 0L,                 // Request / Join epoch
    val responseAt: Long = 0L,                  // Confirmation epoch
    val passengerCompletedAt: Long = 0L,        // Passenger self-completion epoch
    val cancelledAt: Long = 0L,                 // Cancellation epoch
    val closedAt: Long = 0L,                    // Driver master close epoch
    val updatedAt: Long = 0L,                   // Update epoch
    // Legacy fields:
    val commuteWindow: String = "",
    val direction: String = "",
    val destinationZone: String = "",
    val pickupDetails: String = ""
)
```

---

### 2.6 Collection: `systemConfig/sustainability`

```kotlin
data class SystemConfig(
    val baselineDistanceKm: Double = 15.0,      // Default avoided trip distance in km
    val co2FactorKgPerKm: Double = 0.171        // Emission factor (kg CO2 / km)
)
```

---

### 2.7 Collection: `auditLogs/{auditId}`

```kotlin
data class AuditLog(
    val auditId: String = "",                   // Primary Key: {timestamp}_{action}_{targetUid}
    val adminUid: String = "",                  // Admin who performed action
    val adminName: String = "",                 // Admin name
    val adminEmail: String = "",                // Admin email
    val targetUid: String = "",                 // Member affected
    val targetName: String = "",                // Member name
    val targetEmail: String = "",               // Member email
    val action: String = "",                    // "BLOCK_MEMBER", "UNBLOCK_MEMBER", "REMOVE_MEMBER", "ROLE_CHANGE"
    val reason: String = "",                    // Admin reason string
    val timestamp: Long = System.currentTimeMillis()
)
```
