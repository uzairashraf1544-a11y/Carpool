# TRAVELBUDDY — FIRESTORE SECURITY RULES & CONFIGURATION AUDIT
**Inspection Date**: 2026-09-03  
**Current Rules Version**: `v_PURE_PICKUP_SIDE_DISCOVERY_2026_09`  
**Target Firebase Project**: `gen-lang-client-0717949368`  

---

## 1. COMPLETE FIRESTORE SECURITY RULES SOURCE

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ============================================================
    // GLOBAL HELPERS
    // ============================================================

    function signedIn() {
      return request.auth != null;
    }

    function currentUserExists() {
      return signedIn()
        && exists(
          /databases/$(database)/documents/users/$(request.auth.uid)
        );
    }

    function currentUser() {
      return get(
        /databases/$(database)/documents/users/$(request.auth.uid)
      );
    }

    function isAdmin() {
      return currentUserExists()
        && currentUser().data.role == 'ADMIN';
    }

    function validRole(role) {
      return role in [
        'USER',
        'ADMIN'
      ];
    }

    function validAccountStatus(status) {
      return status in [
        'ACTIVE',
        'BLOCKED',
        'REMOVED'
      ];
    }

    function validMode(mode) {
      return mode in [
        'PASSENGER',
        'DRIVER'
      ];
    }

    // ============================================================
    // STATUS HELPERS
    // ============================================================

    function validRideStatus(status) {
      return status in [
        'OFFLINE',
        'AVAILABLE',
        'PARTIALLY_FULL',
        'FULL',
        'RIDE_STARTED',
        'COMPLETED',
        'CANCELLED',
        'EXPIRED'
      ];
    }

    function validRequestStatus(status) {
      return status in [
        'REQUESTED',
        'ACCEPTED',
        'RIDE_STARTED',
        'REJECTED',
        'CANCELLED',
        'CANCELLED_BY_PASSENGER',
        'CANCELLED_BY_DRIVER',
        'CLOSED_UNCONFIRMED',
        'EXPIRED',
        'COMPLETED'
      ];
    }

    function validNeedStatus(status) {
      return status in [
        'LOOKING',
        'REQUESTED',
        'MATCHED',
        'COMPLETED',
        'CANCELLED',
        'EXPIRED'
      ];
    }

    function validPickupSide(side) {
      return side in [
        'TOWNSHIP',
        'PLANT',
        'OTHER'
      ];
    }

    function validCustomPickup(data) {
      return !('pickupSide' in data)
        || data.pickupSide != 'OTHER'
        || (
          'customPickupLocation' in data
          && data.customPickupLocation is string
          && data.customPickupLocation.size() > 0
        );
    }

    // ============================================================
    // RIDE LIST HELPERS
    // ============================================================

    function acceptedIds(data) {
      return data.get('acceptedPassengerIds', []);
    }

    function completedIds(data) {
      return data.get('completedPassengerIds', []);
    }

    function validRideSeatCounts(data) {
      return data.totalSeats >= 1
        && data.availableSeats >= 0
        && data.availableSeats <= data.totalSeats;
    }

    function sameRideDriver() {
      return request.resource.data.driverId
        == resource.data.driverId;
    }

    // ============================================================
    // USERS
    // ============================================================

    match /users/{userId} {

      allow read: if signedIn()
        && (
          request.auth.uid == userId
          || isAdmin()
        );

      allow create: if signedIn()
        && request.auth.uid == userId
        && request.resource.data.role == 'USER'
        && (
          !('accountStatus' in request.resource.data)
          || request.resource.data.accountStatus == 'ACTIVE'
        );

      allow update: if signedIn()
        && (
          // USER SELF UPDATE (Profile, Mode - Cannot elevate role or change accountStatus)
          (
            request.auth.uid == userId
            && request.resource.data.role == resource.data.role
            && validRole(request.resource.data.role)
            && (
              !('accountStatus' in request.resource.data)
              || request.resource.data.accountStatus == resource.data.get('accountStatus', 'ACTIVE')
            )
            && (
              !('currentMode' in request.resource.data)
              || validMode(request.resource.data.currentMode)
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'name',
                'phone',
                'department',
                'currentMode',
                'defaultRolePreference',
                'defaultDriverSeats',
                'isPasswordChanged',
                'muteUntilTimestamp',
                'townshipLocation',
                'updatedAt'
              ])
          )
          ||
          // ADMIN UPDATE
          (
            isAdmin()
            && validRole(request.resource.data.role)
            && (
              !('accountStatus' in request.resource.data)
              || validAccountStatus(request.resource.data.accountStatus)
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'role',
                'accountStatus',
                'blockedAt',
                'blockedBy',
                'blockReason',
                'removedAt',
                'removedBy',
                'removeReason',
                'updatedAt'
              ])
          )
        );

      allow delete: if isAdmin();
    }

    // ============================================================
    // EMPLOYEE REGISTRY (DETERMINISTIC ONBOARDING UNIQUE IDENTIFIERS)
    // ============================================================

    match /employeeRegistry/{empId} {
      // Point read allowed for signed-in employees during onboarding validation
      allow get: if signedIn();
      // Whole collection list restricted strictly to Admin
      allow list: if signedIn() && isAdmin();

      // Employee can claim their unique identifier during onboarding
      allow create: if signedIn()
        && request.resource.data.uid == request.auth.uid;

      allow update, delete: if signedIn() && isAdmin();
    }

    // ============================================================
    // RIDES
    // ============================================================

    match /rides/{rideId} {

      allow read: if signedIn()
        && (
          resource.data.status in ['AVAILABLE', 'PARTIALLY_FULL']
          || resource.data.driverId == request.auth.uid
          || request.auth.uid in acceptedIds(resource.data)
          || isAdmin()
        );

      // CREATE RIDE
      allow create: if signedIn()
        && request.resource.data.driverId == request.auth.uid
        && validRideSeatCounts(request.resource.data)
        && validPickupSide(request.resource.data.pickupSide)
        && validCustomPickup(request.resource.data)
        && request.resource.data.status in [
          'AVAILABLE',
          'PARTIALLY_FULL',
          'FULL'
        ];

      // UPDATE RIDE
      allow update: if signedIn()
        && (
          // DRIVER OWNER CLOSE AVAILABILITY / CANCEL (no passenger impact)
          (
            resource.data.driverId == request.auth.uid
            && sameRideDriver()
            && request.resource.data.status in ['CANCELLED', 'OFFLINE']
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'updatedAt'
              ])
          )
          ||
          // DRIVER OWNER MASTER CLOSE (COMPLETED or CANCELLED)
          (
            resource.data.driverId == request.auth.uid
            && sameRideDriver()
            && request.resource.data.status in ['COMPLETED', 'CANCELLED']
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'completedPassengerIds',
                'completedAt',
                'updatedAt'
              ])
          )
          ||
          // DRIVER OWNER EDIT DETAILS / DIRECT ACCEPT SEAT ALLOCATION
          (
            resource.data.driverId == request.auth.uid
            && sameRideDriver()
            && validRideSeatCounts(request.resource.data)
            && validRideStatus(request.resource.data.status)
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'acceptedPassengerIds',
                'completedPassengerIds',
                'availableSeats',
                'status',
                'creationSource',
                'createdAt',
                'startedAt',
                'completedAt',
                'expiresAt',
                'updatedAt',
                'pickupZone',
                'pickupSide',
                'customPickupLocation',
                'totalSeats',
                'message',
                'commuteWindow',
                'direction',
                'destinationZone'
              ])
          )
          ||
          // PASSENGER DIRECTLY JOINS DRIVER-POSTED RIDE
          (
            resource.data.status in ['AVAILABLE', 'PARTIALLY_FULL']
            && request.resource.data.status in ['PARTIALLY_FULL', 'FULL']
            && sameRideDriver()
            && !(request.auth.uid in acceptedIds(resource.data))
            && request.auth.uid in acceptedIds(request.resource.data)
            && acceptedIds(request.resource.data).size() == acceptedIds(resource.data).size() + 1
            && acceptedIds(request.resource.data).hasAll(acceptedIds(resource.data))
            && request.resource.data.availableSeats == resource.data.availableSeats - 1
            && request.resource.data.totalSeats == resource.data.totalSeats
            && (
              (request.resource.data.availableSeats == 0 && request.resource.data.status == 'FULL')
              ||
              (request.resource.data.availableSeats > 0 && request.resource.data.status == 'PARTIALLY_FULL')
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'acceptedPassengerIds',
                'availableSeats',
                'status',
                'updatedAt'
              ])
          )
          ||
          // PASSENGER CANCELS THEIR OWN SEAT
          (
            resource.data.status in ['AVAILABLE', 'PARTIALLY_FULL', 'FULL']
            && request.auth.uid in acceptedIds(resource.data)
            && !(request.auth.uid in acceptedIds(request.resource.data))
            && acceptedIds(request.resource.data).size() == acceptedIds(resource.data).size() - 1
            && acceptedIds(resource.data).hasAll(acceptedIds(request.resource.data))
            && request.resource.data.availableSeats == resource.data.availableSeats + 1
            && request.resource.data.totalSeats == resource.data.totalSeats
            && sameRideDriver()
            && (
              (acceptedIds(request.resource.data).size() == 0 && request.resource.data.status == 'AVAILABLE')
              ||
              (acceptedIds(request.resource.data).size() > 0 && request.resource.data.status == 'PARTIALLY_FULL')
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'acceptedPassengerIds',
                'availableSeats',
                'status',
                'updatedAt'
              ])
          )
          ||
          // PASSENGER COMPLETES OWN ACTIVE JOURNEY
          (
            resource.data.status in ['AVAILABLE', 'PARTIALLY_FULL', 'FULL', 'RIDE_STARTED']
            && request.auth.uid in acceptedIds(resource.data)
            && !(request.auth.uid in completedIds(resource.data))
            && request.auth.uid in completedIds(request.resource.data)
            && completedIds(request.resource.data).size() == completedIds(resource.data).size() + 1
            && completedIds(request.resource.data).hasAll(completedIds(resource.data))
            && acceptedIds(request.resource.data) == acceptedIds(resource.data)
            && acceptedIds(request.resource.data).hasAll(completedIds(request.resource.data))
            && sameRideDriver()
            && request.resource.data.totalSeats == resource.data.totalSeats
            && request.resource.data.availableSeats == resource.data.availableSeats
            && request.resource.data.status in ['PARTIALLY_FULL', 'FULL', 'RIDE_STARTED', 'COMPLETED']
            && (
              request.resource.data.status != 'COMPLETED'
              || completedIds(request.resource.data).size() == acceptedIds(request.resource.data).size()
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'completedPassengerIds',
                'status',
                'completedAt',
                'updatedAt'
              ])
          )
          ||
          // ADMIN UPDATE
          (
            isAdmin()
            && sameRideDriver()
            && validRideSeatCounts(request.resource.data)
            && validRideStatus(request.resource.data.status)
          )
        );

      allow delete: if signedIn()
        && (
          isAdmin()
          || resource.data.driverId == request.auth.uid
        );
    }

    // ============================================================
    // RIDE REQUESTS / PARTICIPATION
    // ============================================================

    match /rideRequests/{requestId} {

      allow read: if signedIn()
        && (
          resource.data.passengerId == request.auth.uid
          || resource.data.driverId == request.auth.uid
          || isAdmin()
        );

      // CREATE
      allow create: if signedIn()
        && validRequestStatus(request.resource.data.status)
        && (
          // Passenger Joins Driver-Posted Ride
          (
            request.resource.data.passengerId == request.auth.uid
            && request.resource.data.status == 'ACCEPTED'
            && getAfter(
              /databases/$(database)/documents/rides/$(request.resource.data.rideId)
            ).data.driverId == request.resource.data.driverId
            && request.auth.uid in acceptedIds(
              getAfter(/databases/$(database)/documents/rides/$(request.resource.data.rideId)).data
            )
          )
          ||
          // Driver Directly Accepts Broadcast Passenger Need
          (
            request.resource.data.driverId == request.auth.uid
            && request.resource.data.status == 'ACCEPTED'
            && 'needId' in request.resource.data
            && getAfter(
              /databases/$(database)/documents/passengerNeeds/$(request.resource.data.needId)
            ).data.status == 'MATCHED'
            && getAfter(
              /databases/$(database)/documents/passengerNeeds/$(request.resource.data.needId)
            ).data.matchedDriverId == request.auth.uid
            && getAfter(
              /databases/$(database)/documents/passengerNeeds/$(request.resource.data.needId)
            ).data.passengerId == request.resource.data.passengerId
            && getAfter(
              /databases/$(database)/documents/passengerNeeds/$(request.resource.data.needId)
            ).data.matchedRideId == request.resource.data.rideId
            && getAfter(
              /databases/$(database)/documents/rides/$(request.resource.data.rideId)
            ).data.driverId == request.auth.uid
            && request.resource.data.passengerId in acceptedIds(
              getAfter(/databases/$(database)/documents/rides/$(request.resource.data.rideId)).data
            )
          )
        );

      // UPDATE
      allow update: if signedIn()
        && request.resource.data.passengerId == resource.data.passengerId
        && request.resource.data.driverId == resource.data.driverId
        && request.resource.data.rideId == resource.data.rideId
        && validRequestStatus(request.resource.data.status)
        && (
          // PASSENGER OWNER TRANSITIONS
          (
            resource.data.passengerId == request.auth.uid
            && (
              // Cancel pending request
              (
                resource.data.status == 'REQUESTED'
                && request.resource.data.status in ['CANCELLED', 'CANCELLED_BY_PASSENGER']
              )
              ||
              // Accepted Passenger cancels / didn't travel
              (
                resource.data.status == 'ACCEPTED'
                && request.resource.data.status == 'CANCELLED_BY_PASSENGER'
              )
              ||
              // Passenger confirms completion
              (
                resource.data.status in ['ACCEPTED', 'RIDE_STARTED']
                && request.resource.data.status == 'COMPLETED'
              )
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'updatedAt',
                'cancelledAt',
                'completedAt',
                'passengerCompletedAt',
                'responseAt'
              ])
          )
          ||
          // DRIVER TRANSITIONS (CLOSED_UNCONFIRMED, CANCELLED_BY_DRIVER — DRIVER CANNOT WRITE COMPLETED FOR PASSENGER!)
          (
            resource.data.driverId == request.auth.uid
            && (
              (
                resource.data.status == 'REQUESTED'
                && request.resource.data.status in ['REJECTED', 'CANCELLED_BY_DRIVER']
              )
              ||
              (
                resource.data.status in ['ACCEPTED', 'RIDE_STARTED']
                && request.resource.data.status in ['CLOSED_UNCONFIRMED', 'CANCELLED_BY_DRIVER']
              )
            )
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'updatedAt',
                'responseAt',
                'startedAt',
                'cancelledAt',
                'closedAt'
              ])
          )
          ||
          // ADMIN OVERRIDE
          isAdmin()
        );

      allow delete: if isAdmin();
    }

    // ============================================================
    // PASSENGER NEEDS
    // ============================================================

    match /passengerNeeds/{needId} {

      allow read: if signedIn()
        && (
          resource.data.status == 'LOOKING'
          || resource.data.passengerId == request.auth.uid
          || resource.data.get('matchedDriverId', '') == request.auth.uid
          || isAdmin()
        );

      allow create: if signedIn()
        && request.resource.data.passengerId == request.auth.uid
        && validPickupSide(request.resource.data.pickupSide)
        && validCustomPickup(request.resource.data)
        && request.resource.data.status in [
          'LOOKING',
          'REQUESTED'
        ];

      allow update: if signedIn()
        && request.resource.data.passengerId == resource.data.passengerId
        && validNeedStatus(request.resource.data.status)
        && (
          // Passenger edits open need
          (
            resource.data.passengerId == request.auth.uid
            && resource.data.status in ['LOOKING', 'REQUESTED']
            && request.resource.data.status in ['LOOKING', 'REQUESTED', 'CANCELLED', 'EXPIRED']
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'date',
                'direction',
                'commuteWindow',
                'pickupZone',
                'pickupSide',
                'customPickupLocation',
                'destinationZone',
                'message',
                'status',
                'expiresAt',
                'updatedAt',
                'cancelledAt',
                'expiredAt'
              ])
          )
          ||
          // Passenger terminal update after match (Completion / Cancellation)
          (
            resource.data.passengerId == request.auth.uid
            && resource.data.status == 'MATCHED'
            && request.resource.data.status in ['COMPLETED', 'CANCELLED']
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'updatedAt',
                'completedAt',
                'cancelledAt'
              ])
          )
          ||
          // Direct Driver Match
          (
            resource.data.status in ['LOOKING', 'REQUESTED']
            && request.resource.data.status == 'MATCHED'
            && request.resource.data.matchedDriverId == request.auth.uid
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'matchedDriverId',
                'matchedRideId',
                'matchedAt',
                'updatedAt'
              ])
          )
          ||
          // Driver cancellations after match
          (
            resource.data.status == 'MATCHED'
            && resource.data.matchedDriverId == request.auth.uid
            && request.resource.data.status in ['CANCELLED', 'LOOKING']
            && request.resource.data
              .diff(resource.data)
              .affectedKeys()
              .hasOnly([
                'status',
                'updatedAt',
                'cancelledAt'
              ])
          )
          ||
          // ADMIN OVERRIDE
          isAdmin()
        );

      allow delete: if isAdmin()
        || (signedIn() && resource.data.passengerId == request.auth.uid);
    }

    // ============================================================
    // SYSTEM CONFIGURATION
    // ============================================================

    match /systemConfig/{docId} {
      allow read: if signedIn();
      allow write: if signedIn() && isAdmin();
    }

    // ============================================================
    // AUDIT LOGS
    // ============================================================

    match /auditLogs/{logId} {
      allow read: if signedIn() && isAdmin();
      allow create: if signedIn() && isAdmin();
      allow update, delete: if false;
    }

    // ============================================================
    // LEGACY COLLECTIONS
    // ============================================================

    match /co2Records/{recordId} {
      allow read: if signedIn() && isAdmin();
      allow write: if signedIn() && isAdmin();
    }

    match /adminAnalytics/{docId} {
      allow read: if signedIn() && isAdmin();
      allow write: if signedIn() && isAdmin();
    }
  }
}
```

---

## 2. SECURITY RULES PERMISSION MATRIX

| Collection | Read Rule Summary | Create Rule Summary | Update Rule Summary | Delete Rule Summary |
|---|---|---|---|---|
| `users/{userId}` | `request.auth.uid == userId \|\| isAdmin()` | Signed-in, `uid == auth.uid`, `role == 'USER'` | Owner updates approved fields only (no role change) OR Admin updates role/status | Admin only |
| `employeeRegistry/{id}` | Point `get`: Signed-in.<br>Collection `list`: Admin only. | Signed-in, `data.uid == auth.uid` (claim ID) | Admin only | Admin only |
| `rides/{rideId}` | Open status (`AVAILABLE`/`PARTIALLY_FULL`) OR Driver owner OR Accepted passenger OR Admin | Signed-in, `driverId == auth.uid`, valid seat counts, valid side & custom pickup | Driver edits/closes OR Passenger joins/cancels/completes (atomic seat & list diff validation) | Admin OR Driver owner |
| `passengerNeeds/{id}` | Status `LOOKING` OR Passenger owner OR Matched driver OR Admin | Signed-in, `passengerId == auth.uid`, valid side & custom pickup | Passenger edits/closes OR Matched driver claims (`status == 'MATCHED'`) | Admin OR Passenger owner |
| `rideRequests/{id}` | Passenger owner OR Driver owner OR Admin | Cross-document `getAfter()` verifies seat reservation and participation in `rides` | Passenger confirms completion/cancellation OR Driver executes `CLOSED_UNCONFIRMED` / `CANCELLED_BY_DRIVER` | Admin only |
| `systemConfig/{id}` | All signed-in employees | Admin only | Admin only | Admin only |
| `auditLogs/{id}` | Admin only | Admin only | **Disallowed** (Immutable) | **Disallowed** (Immutable) |

---

## 3. FIRESTORE INDEXES & `firestore.indexes.json`

### File Content (`firestore.indexes.json`):
```json
{
  "indexes": [],
  "fieldOverrides": []
}
```

### Analysis of Production Queries
1. `rides`:
   - `whereEqualTo("date", date).whereIn("status", ["AVAILABLE", "PARTIALLY_FULL"]).limit(20)`
   - `whereEqualTo("date", date).whereEqualTo("pickupSide", side).whereIn("status", ["AVAILABLE", "PARTIALLY_FULL"]).limit(20)`
   - **Index Requirement**: Single-field automatic indexes satisfy equality + single `in` clause without ordering. No composite index required.
2. `passengerNeeds`:
   - `whereEqualTo("date", date).whereEqualTo("pickupSide", side).whereEqualTo("status", "LOOKING").limit(20)`
   - **Index Requirement**: All equality filters (`==`). Handled automatically by Firestore index merger.
3. `rideRequests`:
   - `whereEqualTo("passengerId", id).limit(50)`
   - In-memory sorted by timestamp in Kotlin. Handled automatically.

**Conclusion**: Zero composite indexes are required by current production code, and zero `FAILED_PRECONDITION` index errors are generated.
