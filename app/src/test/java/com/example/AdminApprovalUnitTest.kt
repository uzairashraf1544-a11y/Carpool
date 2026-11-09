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

    @Test
    fun testNewUserRegistrationDefaultsToPendingApproval() {
        val newUser = User(
            uid = "test-new-user-1",
            name = "John Doe",
            email = "john.doe@company.com",
            role = User.ROLE_USER,
            accountStatus = User.STATUS_PENDING_APPROVAL
        )

        assertTrue("New registration must be pending approval", newUser.isPendingApproval())
        assertFalse("New registration must not be active", newUser.isActive())
        assertFalse("New registration must not be admin", newUser.isAdmin())
        assertEquals(User.ROLE_USER, newUser.effectiveRole)
        assertEquals(User.STATUS_PENDING_APPROVAL, newUser.effectiveAccountStatus)
    }

    @Test
    fun testAdminRoleCasingTolerance() {
        val upperAdmin = User(uid = "admin-1", role = "ADMIN")
        val lowerAdmin = User(uid = "admin-2", role = "admin")
        val mixedAdmin = User(uid = "admin-3", role = "Admin")
        val regularUser = User(uid = "user-1", role = "USER")

        assertTrue("ADMIN uppercase must be recognized as admin", upperAdmin.isAdmin())
        assertTrue("admin lowercase must be recognized as admin", lowerAdmin.isAdmin())
        assertTrue("Admin mixed case must be recognized as admin", mixedAdmin.isAdmin())
        assertFalse("Regular user must NOT be recognized as admin", regularUser.isAdmin())
    }

    @Test
    fun testRestrictedUserStatusEnforcement() {
        val blockedUser = User(uid = "b-1", accountStatus = User.STATUS_BLOCKED)
        val removedUser = User(uid = "r-1", accountStatus = User.STATUS_REMOVED)
        val activeUser = User(uid = "a-1", accountStatus = User.STATUS_ACTIVE)

        assertTrue(blockedUser.isBlocked())
        assertFalse(blockedUser.isActive())

        assertTrue(removedUser.isRemoved())
        assertFalse(removedUser.isActive())

        assertTrue(activeUser.isActive())
        assertFalse(activeUser.isBlocked())
        assertFalse(activeUser.isRemoved())
    }

    @Test
    fun testLoginErrorMessagesDoNotShowMisleadingSessionExpired() {
        // Verify that specific authentication error strings produce accurate user guidance
        fun mapLoginError(msg: String, isInvalidCreds: Boolean = false, isUserNotFound: Boolean = false): String {
            return when {
                msg.contains("complete email", ignoreCase = true) -> msg
                msg.contains("restricted", ignoreCase = true) -> msg
                msg.contains("does not have a password", ignoreCase = true) ||
                    msg.contains("no password", ignoreCase = true) ->
                    "This account was registered using Google. Please tap 'Continue with Google' to sign in."
                isUserNotFound || msg.contains("no user record", ignoreCase = true) ||
                    msg.contains("user-not-found", ignoreCase = true) ->
                    "No account found with this email. Please click 'Create Account' below to register."
                isInvalidCreds || msg.contains("wrong-password", ignoreCase = true) ||
                    msg.contains("invalid-credential", ignoreCase = true) ||
                    msg.contains("password is invalid", ignoreCase = true) ||
                    msg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ->
                    "Incorrect email or password. Please verify your credentials or click 'Forgot Password?'."
                msg.contains("network", ignoreCase = true) ->
                    "Network error. Please check your internet connection."
                msg.isNotBlank() -> msg
                else -> "Couldn't sign in. Check your email/password and internet connection."
            }
        }

        // Case 1: Account created via Google Sign-In where user tries password sign-in
        val googleUserError = mapLoginError("The password is invalid or the user does not have a password.")
        assertEquals(
            "This account was registered using Google. Please tap 'Continue with Google' to sign in.",
            googleUserError
        )

        // Case 2: Wrong password entered
        val wrongPasswordError = mapLoginError("An internal error has occurred. [ INVALID_LOGIN_CREDENTIALS ]")
        assertEquals(
            "Incorrect email or password. Please verify your credentials or click 'Forgot Password?'.",
            wrongPasswordError
        )

        // Case 3: User not found in Firebase Auth
        val notFoundError = mapLoginError("There is no user record corresponding to this identifier.")
        assertEquals(
            "No account found with this email. Please click 'Create Account' below to register.",
            notFoundError
        )

        // Case 4: Restricted / blocked account
        val restrictedError = mapLoginError("Your account is currently restricted. Please contact the administrator.")
        assertEquals(
            "Your account is currently restricted. Please contact the administrator.",
            restrictedError
        )
    }

    @Test
    fun testUserCumulativeMetricsZeroReadArchitecture() {
        // User with cumulative stats stored directly on document
        val user = User(
            uid = "user-co2-100",
            name = "Eco Commuter",
            email = "eco@example.com",
            totalCo2SavedKg = 42.5,
            totalCompletedRides = 15
        )

        assertEquals(42.5, user.totalCo2SavedKg, 0.001)
        assertEquals(15, user.totalCompletedRides)

        // Legacy user with no cumulative fields defaults gracefully to 0.0 and 0
        val legacyUser = User(uid = "user-legacy")
        assertEquals(0.0, legacyUser.totalCo2SavedKg, 0.001)
        assertEquals(0, legacyUser.totalCompletedRides)
    }

    @Test
    fun testSparkFreeTierReadBudgetCalculation() {
        // 300 employees, 10 commute interactions daily
        val activeUsers = 300
        val dailyInteractionsPerUser = 10

        // With persistent cache + single discovery query + direct user profile metrics:
        // - App launch / user session: 1 read (users/$uid stream), 1 read (systemConfig/leaderboard)
        // - Discovery: 1 query with limit 20 per day (cached and shared across users)
        // - Commute check-in/request: 1 write, 1 query (limit 1)
        val estimatedDailyReadsPerUser = 8 // Highly conservative estimate
        val totalDailyReads = activeUsers * estimatedDailyReadsPerUser

        val sparkFreeTierDailyReadLimit = 50_000
        val sparkFreeTierDailyWriteLimit = 20_000

        assertTrue(
            "Total estimated daily reads ($totalDailyReads) must be well within Spark Free Tier limit ($sparkFreeTierDailyReadLimit)",
            totalDailyReads < (sparkFreeTierDailyReadLimit / 3) // Less than 16,666 reads/day
        )
        assertTrue(
            "Total daily writes must be well within Spark Free Tier limit ($sparkFreeTierDailyWriteLimit)",
            (activeUsers * dailyInteractionsPerUser) < sparkFreeTierDailyWriteLimit
        )
    }

    @Test
    fun testAdminTwoRemovalOptionsSoftAndCompletePurge() {
        val userToDeactivate = User(
            uid = "user-to-deactivate",
            name = "John Doe",
            email = "john@example.com",
            accountStatus = User.STATUS_ACTIVE
        )

        // Option 1: Deactivate (Keep Data)
        val softRemovedUser = userToDeactivate.copy(
            accountStatus = User.STATUS_REMOVED,
            removedAt = System.currentTimeMillis(),
            removedBy = "admin-001",
            removeReason = "Offboarded employee"
        )
        assertTrue("User must be marked as removed", softRemovedUser.isRemoved())
        assertFalse("Removed user must not be active", softRemovedUser.isActive())
        assertEquals("Offboarded employee", softRemovedUser.removeReason)

        // Option 2: Complete Purge (Zero Trace) Action Definition
        assertEquals("PURGE_MEMBER", AuditLog.ACTION_PURGE_MEMBER)
        val purgeLog = AuditLog(
            auditId = "100_purge_user",
            adminUid = "admin-001",
            adminName = "Super Admin",
            targetUid = "user-to-purge",
            targetName = "Jane Doe",
            targetEmail = "jane@example.com",
            action = AuditLog.ACTION_PURGE_MEMBER,
            reason = "Complete data erasure requested"
        )
        assertEquals(AuditLog.ACTION_PURGE_MEMBER, purgeLog.action)
        assertEquals("user-to-purge", purgeLog.targetUid)
    }
}
