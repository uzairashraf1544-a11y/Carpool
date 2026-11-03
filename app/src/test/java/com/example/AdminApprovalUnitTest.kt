package com.example

import com.example.data.model.AuditLog
import com.example.data.model.SystemConfig
import com.example.data.model.User
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminApprovalUnitTest {

    @Test
    fun testNewUserPendingApprovalStatus() {
        val user = User(
            uid = "new-user-001",
            name = "Test Pending User",
            email = "pending@example.com",
            accountStatus = User.STATUS_PENDING_APPROVAL,
            approvalRequestedAt = System.currentTimeMillis(),
            consentAcceptedAt = System.currentTimeMillis(),
            consentVersion = 1,
            privacyNoticeVersion = 1
        )

        assertTrue("New user should be pending approval", user.isPendingApproval())
        assertFalse("Pending user should not be active", user.isActive())
        assertFalse("Pending user should not be blocked", user.isBlocked())
        assertFalse("Pending user should not be removed", user.isRemoved())
        assertEquals("Account status must be PENDING_APPROVAL", User.STATUS_PENDING_APPROVAL, user.effectiveAccountStatus)
    }

    @Test
    fun testExistingUserBackwardCompatibilityDefaultActive() {
        // Existing users in production have no accountStatus field stored in Firestore
        val legacyUser = User(
            uid = "legacy-user-001",
            name = "Existing Active Member",
            email = "legacy@example.com",
            accountStatus = ""
        )

        assertEquals("Legacy user without status must default to ACTIVE", User.STATUS_ACTIVE, legacyUser.effectiveAccountStatus)
        assertTrue("Legacy user must be active", legacyUser.isActive())
        assertFalse("Legacy user must not be pending approval", legacyUser.isPendingApproval())
        assertFalse("Legacy user must not be blocked", legacyUser.isBlocked())
        assertFalse("Legacy user must not be removed", legacyUser.isRemoved())
    }

    @Test
    fun testActiveUserStatus() {
        val activeUser = User(
            uid = "active-user-001",
            name = "Active User",
            email = "active@example.com",
            accountStatus = User.STATUS_ACTIVE
        )

        assertTrue("User must be active", activeUser.isActive())
        assertFalse("Active user is not pending", activeUser.isPendingApproval())
        assertEquals(User.STATUS_ACTIVE, activeUser.effectiveAccountStatus)
    }

    @Test
    fun testBlockedAndRemovedUsersAreNotPending() {
        val blockedUser = User(
            uid = "blocked-user-001",
            accountStatus = User.STATUS_BLOCKED
        )
        assertTrue("User must be blocked", blockedUser.isBlocked())
        assertFalse("Blocked user is not pending", blockedUser.isPendingApproval())
        assertFalse("Blocked user is not active", blockedUser.isActive())

        val removedUser = User(
            uid = "removed-user-001",
            accountStatus = User.STATUS_REMOVED
        )
        assertTrue("User must be removed", removedUser.isRemoved())
        assertFalse("Removed user is not pending", removedUser.isPendingApproval())
        assertFalse("Removed user is not active", removedUser.isActive())
    }

    @Test
    fun testConsentMetadataTracking() {
        val now = System.currentTimeMillis()
        val user = User(
            uid = "consent-user-001",
            consentAcceptedAt = now,
            consentVersion = 1,
            privacyNoticeVersion = 1,
            approvalRequestedAt = now
        )

        assertEquals(now, user.consentAcceptedAt)
        assertEquals(1, user.consentVersion)
        assertEquals(1, user.privacyNoticeVersion)
        assertEquals(now, user.approvalRequestedAt)
        assertEquals(0L, user.approvedAt)
        assertEquals("", user.approvedBy)
    }

    @Test
    fun testApprovalTransitionsUserToActive() {
        val pendingUser = User(
            uid = "target-user-123",
            name = "Jane Doe",
            email = "jane@example.com",
            accountStatus = User.STATUS_PENDING_APPROVAL,
            approvalRequestedAt = 1000L
        )

        assertTrue(pendingUser.isPendingApproval())

        val approvedTime = 2000L
        val adminUid = "admin-super-999"
        val approvedUser = pendingUser.copy(
            accountStatus = User.STATUS_ACTIVE,
            approvedAt = approvedTime,
            approvedBy = adminUid
        )

        assertFalse("Approved user must no longer be pending", approvedUser.isPendingApproval())
        assertTrue("Approved user must be active", approvedUser.isActive())
        assertEquals(User.STATUS_ACTIVE, approvedUser.effectiveAccountStatus)
        assertEquals(approvedTime, approvedUser.approvedAt)
        assertEquals(adminUid, approvedUser.approvedBy)
    }

    @Test
    fun testAuditLogActionApproveUserConstant() {
        assertEquals("APPROVE_USER", AuditLog.ACTION_APPROVE_USER)
    }

    @Test
    fun testSystemConfigApproverInfo() {
        val config = SystemConfig()
        assertEquals("Administrator", config.approverDisplayName)
        assertEquals("", config.approverEmail)

        val customConfig = SystemConfig(
            approverDisplayName = "Sarah Mobility Manager",
            approverEmail = "sarah.admin@company.com"
        )
        assertEquals("Sarah Mobility Manager", customConfig.approverDisplayName)
        assertEquals("sarah.admin@company.com", customConfig.approverEmail)
    }

    @Test
    fun testFirestoreSecurityRulesEnforcePendingApprovalAndAdminOnly() {
        val rulesFile = File("../firestore.rules").takeIf { it.exists() }
            ?: File("firestore.rules").takeIf { it.exists() }
            ?: File("../../firestore.rules")

        assertTrue("firestore.rules must exist", rulesFile.exists())
        val content = rulesFile.readText()

        // 1. PENDING_APPROVAL must be recognized in validAccountStatus
        assertTrue("validAccountStatus must contain 'PENDING_APPROVAL'", content.contains("'PENDING_APPROVAL'"))

        // 2. isActiveUser helper must exist
        assertTrue("Rules must define isActiveUser() helper", content.contains("function isActiveUser("))

        // 3. Rules must govern accountStatus lifecycle
        assertTrue("Rules must enforce accountStatus constraints",
            content.contains("accountStatus")
        )

        // 4. Admin role required to approve
        assertTrue("Rules must require isAdmin() to mutate protected status fields",
            content.contains("isAdmin()")
        )

        // 5. Creating rides requires active user status
        assertTrue("Rides creation must require active user",
            content.contains("isActiveUser()") && content.contains("// CREATE RIDE")
        )

        // 6. Creating requests requires active user status
        assertTrue("RideRequests creation must require active user",
            content.contains("match /rideRequests/{requestId}") && content.contains("&& isActiveUser()")
        )

        // 7. Creating passengerNeeds requires active user status
        assertTrue("PassengerNeeds creation must require active user",
            content.contains("match /passengerNeeds/{needId}") && content.contains("&& isActiveUser()")
        )
    }
}
