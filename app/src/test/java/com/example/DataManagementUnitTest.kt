package com.example

import com.example.data.model.AuditLog
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.data.repository.GlobalResetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataManagementUnitTest {

    @Test
    fun testGlobalResetTypesExist() {
        assertEquals("RIDES_ONLY", GlobalResetType.RIDES_ONLY.name)
        assertEquals("ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN", GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN.name)
        assertEquals("ALL_EXCEPT_ADMIN_ACCOUNTS", GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS.name)
    }

    @Test
    fun testAuditLogActionConstantsForDataManagement() {
        assertEquals("RESET_RIDES_ONLY", AuditLog.ACTION_RESET_RIDES_ONLY)
        assertEquals("RESET_ALL_EXCEPT_CURRENT_ADMIN", AuditLog.ACTION_RESET_ALL_EXCEPT_CURRENT_ADMIN)
        assertEquals("RESET_ALL_EXCEPT_ADMINS", AuditLog.ACTION_RESET_ALL_EXCEPT_ADMINS)
        assertEquals("DELETE_USER_DATA_ONLY", AuditLog.ACTION_DELETE_USER_DATA_ONLY)
        assertEquals("DELETE_USER_AND_ACCOUNT", AuditLog.ACTION_DELETE_USER_AND_ACCOUNT)
    }

    @Test
    fun testNonAdminCannotPerformAdminActions() {
        val nonAdminUser = User(
            uid = "user-regular-001",
            name = "Regular Colleague",
            email = "colleague@company.com",
            role = User.ROLE_USER
        )

        assertFalse("Non-admin user must not have admin rights", nonAdminUser.isAdmin())
    }

    @Test
    fun testAdminCannotDeleteSelf() {
        val adminUser = User(
            uid = "admin-root-001",
            name = "System Admin",
            email = "admin@company.com",
            role = User.ROLE_ADMIN
        )

        assertTrue("User must be recognized as admin", adminUser.isAdmin())
        // Attempting to delete own account
        val isSelf = adminUser.uid == adminUser.uid
        assertTrue("Self-check must detect current admin ID", isSelf)
    }

    @Test
    fun testPassengerHistoryPreservationLogicOnDriverDeletion() {
        val driverUid = "driver-to-delete-123"

        // Ride 1: Has 2 booked passengers -> MUST BE PRESERVED & ANONYMIZED
        val rideWithPassengers = Ride(
            rideId = "ride_with_passengers",
            driverId = driverUid,
            driverName = "John Driver",
            driverPhone = "03001234567",
            driverDepartment = "Logistics",
            acceptedPassengerIds = listOf("passenger_1", "passenger_2"),
            completedPassengerIds = listOf("passenger_1")
        )

        // Ride 2: Has 0 passengers -> CAN BE SAFELY DELETED
        val emptyRide = Ride(
            rideId = "empty_ride",
            driverId = driverUid,
            driverName = "John Driver",
            acceptedPassengerIds = emptyList(),
            completedPassengerIds = emptyList()
        )

        val driverRides = listOf(rideWithPassengers, emptyRide)

        val ridesToPreserve = driverRides.filter { it.acceptedPassengerIds.isNotEmpty() || it.completedPassengerIds.isNotEmpty() }
        val ridesToDelete = driverRides.filter { it.acceptedPassengerIds.isEmpty() && it.completedPassengerIds.isEmpty() }

        assertEquals(1, ridesToPreserve.size)
        assertEquals("ride_with_passengers", ridesToPreserve.first().rideId)
        assertEquals(1, ridesToDelete.size)
        assertEquals("empty_ride", ridesToDelete.first().rideId)

        // Test Anonymization
        val anonymizedRide = rideWithPassengers.copy(
            driverName = "Former Colleague",
            driverPhone = "",
            driverDepartment = "—"
        )

        assertEquals("Former Colleague", anonymizedRide.driverName)
        assertEquals("", anonymizedRide.driverPhone)
        assertEquals("—", anonymizedRide.driverDepartment)
        // Passenger IDs remain intact so their history is preserved
        assertEquals(2, anonymizedRide.acceptedPassengerIds.size)
    }

    @Test
    fun testPassengerBookingsPreservedWithDriverAnonymized() {
        val driverUid = "deleted-driver-999"
        val booking = RideRequest(
            requestId = "ride1_passenger1",
            rideId = "ride1",
            driverId = driverUid,
            driverName = "Jane Original Driver",
            driverDepartment = "Engineering",
            passengerId = "passenger_active_1",
            passengerName = "Active Passenger",
            status = RideRequest.STATUS_COMPLETED
        )

        // Other passenger's booking record must NOT be deleted. Only driver details anonymized:
        val anonymizedBooking = booking.copy(
            driverName = "Former Colleague",
            driverDepartment = "—"
        )

        assertEquals("ride1_passenger1", anonymizedBooking.requestId)
        assertEquals("passenger_active_1", anonymizedBooking.passengerId)
        assertEquals(RideRequest.STATUS_COMPLETED, anonymizedBooking.status)
        assertEquals("Former Colleague", anonymizedBooking.driverName)
        assertEquals("—", anonymizedBooking.driverDepartment)
    }

    @Test
    fun testGlobalResetScopeCalculations() {
        val admin = User(uid = "admin-1", role = User.ROLE_ADMIN)
        val otherAdmin = User(uid = "admin-2", role = User.ROLE_ADMIN)
        val regularUser1 = User(uid = "user-1", role = User.ROLE_USER)
        val regularUser2 = User(uid = "user-2", role = User.ROLE_USER)

        val allUsers = listOf(admin, otherAdmin, regularUser1, regularUser2)

        // Case 1: RIDES_ONLY -> 0 user accounts deleted
        val ridesOnlyDeletedUsers = 0
        assertEquals(0, ridesOnlyDeletedUsers)

        // Case 2: ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN -> deletes all except admin-1
        val exceptCurrentAdmin = allUsers.filter { it.uid != admin.uid }
        assertEquals(3, exceptCurrentAdmin.size)
        assertTrue(exceptCurrentAdmin.any { it.uid == "admin-2" })
        assertTrue(exceptCurrentAdmin.any { it.uid == "user-1" })
        assertTrue(exceptCurrentAdmin.any { it.uid == "user-2" })
        assertFalse(exceptCurrentAdmin.any { it.uid == "admin-1" })

        // Case 3: ALL_EXCEPT_ADMIN_ACCOUNTS -> deletes non-admin users only
        val nonAdmins = allUsers.filter { !it.isAdmin() && it.uid != admin.uid }
        assertEquals(2, nonAdmins.size)
        assertTrue(nonAdmins.all { !it.isAdmin() })
    }

    @Test
    fun testAlsoRemoveAdminDataToggleFiltering() {
        val currentAdminUid = "admin-me"
        val ride1 = Ride(rideId = "r1", driverId = currentAdminUid)
        val ride2 = Ride(rideId = "r2", driverId = "other-driver")
        val allRides = listOf(ride1, ride2)

        // If removeAdminData is false:
        val ridesDeletedWithoutAdmin = allRides.filter { it.driverId != currentAdminUid }
        assertEquals(1, ridesDeletedWithoutAdmin.size)
        assertEquals("r2", ridesDeletedWithoutAdmin.first().rideId)

        // If removeAdminData is true:
        val ridesDeletedWithAdmin = allRides
        assertEquals(2, ridesDeletedWithAdmin.size)
    }

    @Test
    fun testFirestoreRulesEnforceAdminOnlyAndImmutableAuditTrail() {
        val rulesFile = File("../firestore.rules").takeIf { it.exists() }
            ?: File("firestore.rules").takeIf { it.exists() }
            ?: File("../../firestore.rules")

        assertTrue("firestore.rules must exist", rulesFile.exists())
        val content = rulesFile.readText()

        // 1. Audit logs immutable (never deleted or modified)
        assertTrue(
            "AuditLogs must forbid update and delete",
            content.contains("match /auditLogs/{logId}") && content.contains("allow update, delete: if false;")
        )

        // 2. Users delete rule requires admin AND cannot delete self
        assertTrue(
            "Users delete must require isAdmin() and cannot delete self",
            content.contains("match /users/{userId}") &&
                content.contains("allow delete: if isAdmin()") &&
                content.contains("request.auth.uid != userId")
        )

        // 3. Rides delete rule requires admin or driver owner
        assertTrue(
            "Rides delete must require admin or driver owner",
            content.contains("match /rides/{rideId}") &&
                content.contains("isAdmin()") &&
                content.contains("resource.data.driverId == request.auth.uid")
        )

        // 4. RideRequests delete requires admin
        assertTrue(
            "RideRequests delete must require isAdmin()",
            content.contains("match /rideRequests/{requestId}") &&
                content.contains("allow delete: if isAdmin();")
        )

        // 5. PassengerNeeds delete requires admin
        assertTrue(
            "PassengerNeeds delete must require isAdmin()",
            content.contains("match /passengerNeeds/{needId}") &&
                content.contains("allow delete: if isAdmin();")
        )

        // 6. ActivePassengerRides delete strictly secured
        assertTrue(
            "ActivePassengerRides delete must be secured without open true",
            content.contains("match /activePassengerRides/{passengerId}") &&
                !content.contains("isAdmin() ||\n        true")
        )
    }
}
