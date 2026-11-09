package com.example.data.repository

import android.util.Log
import com.example.data.model.AuditLog
import com.example.data.model.LeaderboardEntry
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.SystemConfig
import com.example.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await

class AdminRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun getSystemConfigFlow(): Flow<SystemConfig> = callbackFlow {
        val listener = db.collection("systemConfig").document("sustainability")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(SystemConfig())
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(SystemConfig::class.java) ?: SystemConfig())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun updateSustainabilityConfig(baselineKm: Double, co2Factor: Double): Result<Unit> {
        return runCatching {
            require(baselineKm > 0.0) { "Baseline distance must be greater than zero." }
            require(co2Factor > 0.0) { "CO₂ factor must be greater than zero." }
            db.collection("systemConfig").document("sustainability")
                .set(
                    mapOf(
                        "baselineDistanceKm" to baselineKm,
                        "co2FactorKgPerKm" to co2Factor
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Unit
        }
    }

    fun getTopCo2SaversFlow(): Flow<List<LeaderboardEntry>> = callbackFlow {
        val listener = db.collection("systemConfig").document("leaderboard")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val rawList = snapshot.get("entries") as? List<Map<String, Any>>
                if (rawList.isNullOrEmpty()) {
                    trySend(emptyList())
                } else {
                    val entries = rawList.mapNotNull { map ->
                        val co2 = (map["co2SavedKg"] as? Number)?.toDouble() ?: 0.0
                        if (co2 > 0.0) {
                            LeaderboardEntry(
                                uid = map["uid"] as? String ?: "",
                                name = map["name"] as? String ?: "Contributor",
                                department = map["department"] as? String ?: "Operations",
                                co2SavedKg = co2,
                                rideCount = (map["rideCount"] as? Number)?.toInt() ?: 0,
                                rank = (map["rank"] as? Number)?.toInt() ?: 1
                            )
                        } else null
                    }.sortedByDescending { it.co2SavedKg }.take(3).mapIndexed { idx, item ->
                        item.copy(rank = idx + 1)
                    }
                    trySend(entries)
                }
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun updateLeaderboard(entries: List<LeaderboardEntry>): Result<Unit> {
        return runCatching {
            val listData = entries.filter { it.co2SavedKg > 0.0 }.take(3).map {
                mapOf(
                    "uid" to it.uid,
                    "name" to it.name,
                    "department" to it.department,
                    "co2SavedKg" to it.co2SavedKg,
                    "rideCount" to it.rideCount
                )
            }
            db.collection("systemConfig").document("leaderboard")
                .set(
                    mapOf(
                        "entries" to listData,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
                .await()
            Unit
        }
    }

    suspend fun recalculateAndSyncLeaderboard(): Result<List<LeaderboardEntry>> = runCatching {
        // Fast path: Query top users by totalCo2SavedKg descending (takes <= 10 reads)
        val snapshot = try {
            db.collection("users")
                .orderBy("totalCo2SavedKg", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
        } catch (_: Exception) { null }

        val topUsers = snapshot?.documents?.mapNotNull { doc ->
            User.fromSnapshot(doc)
        }?.filter { it.isActive() && it.totalCo2SavedKg > 0.0 }.orEmpty()

        if (topUsers.isNotEmpty()) {
            val topEntries = topUsers.take(3).mapIndexed { idx, u ->
                LeaderboardEntry(
                    uid = u.uid,
                    name = u.name.ifBlank { "Contributor" },
                    department = u.department.ifBlank { "Operations" },
                    co2SavedKg = u.totalCo2SavedKg,
                    rideCount = u.totalCompletedRides,
                    rank = idx + 1
                )
            }
            updateLeaderboard(topEntries)
            return@runCatching topEntries
        }

        // Fallback / One-time backfill from completed rides & requests (bounded to 100)
        val ridesSnap = db.collection("rides")
            .whereEqualTo("status", Ride.STATUS_COMPLETED)
            .limit(100)
            .get()
            .await()
        val completedRides = ridesSnap.documents.mapNotNull { it.toObject(Ride::class.java) }

        val reqsSnap = db.collection("rideRequests")
            .whereEqualTo("status", RideRequest.STATUS_COMPLETED)
            .limit(100)
            .get()
            .await()
        val completedRequests = reqsSnap.documents.mapNotNull { it.toObject(RideRequest::class.java) }

        val sysConfigDoc = try { db.collection("systemConfig").document("sustainability").get().await() } catch (_: Exception) { null }
        val baselineDistanceKm = sysConfigDoc?.getDouble("baselineDistanceKm") ?: 3.0
        val co2Factor = sysConfigDoc?.getDouble("co2FactorKgPerKm") ?: 0.12

        data class Accumulator(
            val uid: String,
            var name: String,
            var department: String,
            var totalCo2SavedKg: Double = 0.0,
            var totalRides: Int = 0
        )

        val userAggregates = mutableMapOf<String, Accumulator>()

        for (ride in completedRides) {
            val dUid = ride.driverId
            if (dUid.isNotBlank()) {
                val acc = userAggregates.getOrPut(dUid) {
                    Accumulator(
                        uid = dUid,
                        name = ride.driverName.ifBlank { "Driver" },
                        department = ride.driverDepartment.ifBlank { "Operations" }
                    )
                }
                val passengers = if (ride.finalPassengerCount > 0) ride.finalPassengerCount else ride.completedPassengerIds.size.coerceAtLeast(ride.acceptedPassengerIds.size)
                acc.totalCo2SavedKg += (passengers * baselineDistanceKm * co2Factor)
                acc.totalRides += 1
            }
        }

        for (req in completedRequests) {
            val pUid = req.passengerId
            if (pUid.isNotBlank()) {
                val acc = userAggregates.getOrPut(pUid) {
                    Accumulator(
                        uid = pUid,
                        name = req.passengerName.ifBlank { "Passenger" },
                        department = req.passengerDepartment.ifBlank { "Operations" }
                    )
                }
                acc.totalCo2SavedKg += (1.0 * baselineDistanceKm * co2Factor)
                acc.totalRides += 1
            }
        }

        // Backfill user documents with cumulative metrics for subsequent fast-path hits
        for ((uid, acc) in userAggregates) {
            try {
                db.collection("users").document(uid).update(
                    mapOf(
                        "totalCo2SavedKg" to acc.totalCo2SavedKg,
                        "totalCompletedRides" to acc.totalRides
                    )
                ).await()
            } catch (_: Exception) {}
        }

        val topEntries = userAggregates.values
            .filter { it.totalCo2SavedKg > 0.0 }
            .sortedByDescending { it.totalCo2SavedKg }
            .take(3)
            .mapIndexed { idx, acc ->
                LeaderboardEntry(
                    uid = acc.uid,
                    name = acc.name,
                    department = acc.department,
                    co2SavedKg = acc.totalCo2SavedKg,
                    rideCount = acc.totalRides,
                    rank = idx + 1
                )
            }

        updateLeaderboard(topEntries)
        topEntries
    }

    fun getDefaultLeaderboardEntries(): List<LeaderboardEntry> = emptyList()

    fun getAllUsersFlow(): Flow<List<User>> = callbackFlow {
        val listener = db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("AdminRepository", "getAllUsersFlow listener error: ${error.message}", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val users = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        User.fromSnapshot(doc)
                    } catch (e: Exception) {
                        Log.w("AdminRepository", "Failed to parse user document ${doc.id}", e)
                        null
                    }
                }.orEmpty()
                trySend(users)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getAllRidesFlow(): Flow<List<Ride>> = callbackFlow {
        val listener = db.collection("rides")
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getAllRequestsFlow(): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getAllRideRequestsFlow(): Flow<List<RideRequest>> = getAllRequestsFlow()

    fun getAuditLogsFlow(): Flow<List<AuditLog>> = callbackFlow {
        val listener = db.collection("auditLogs")
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val logs = snapshot?.documents?.mapNotNull { it.toObject(AuditLog::class.java) }
                    ?.sortedByDescending { it.timestamp }
                    .orEmpty()
                trySend(logs)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun setUserRole(targetUid: String, role: String, adminUser: User? = null): Result<Unit> {
        return runCatching {
            require(role in listOf(User.ROLE_USER, User.ROLE_ADMIN)) { "Invalid role." }
            db.collection("users").document(targetUid).update("role", role).await()

            if (adminUser != null) {
                val targetDoc = db.collection("users").document(targetUid).get().await()
                val targetUser = User.fromSnapshot(targetDoc)
                val auditId = "${System.currentTimeMillis()}_${targetUid}"
                val log = AuditLog(
                    auditId = auditId,
                    adminUid = adminUser.uid,
                    adminName = adminUser.name,
                    adminEmail = adminUser.email,
                    targetUid = targetUid,
                    targetName = targetUser?.name.orEmpty(),
                    targetEmail = targetUser?.email.orEmpty(),
                    action = AuditLog.ACTION_ROLE_CHANGE,
                    reason = "Role changed to $role",
                    timestamp = System.currentTimeMillis()
                )
                db.collection("auditLogs").document(auditId).set(log).await()
            }
            Unit
        }
    }

    suspend fun setUserAdminRole(targetUid: String, role: String): Result<Unit> = setUserRole(targetUid, role)

    suspend fun setUserAdminRole(targetUid: String, makeAdmin: Boolean, adminUser: User? = null): Result<Unit> {
        val role = if (makeAdmin) User.ROLE_ADMIN else User.ROLE_USER
        return setUserRole(targetUid, role, adminUser)
    }

    suspend fun blockMember(adminUser: User, targetUser: User, reason: String = ""): Result<Unit> {
        return runCatching {
            val now = System.currentTimeMillis()
            db.collection("users").document(targetUser.uid).update(
                mapOf(
                    "accountStatus" to User.STATUS_BLOCKED,
                    "blockedAt" to now,
                    "blockedBy" to adminUser.uid,
                    "blockReason" to reason.trim(),
                    "updatedAt" to now
                )
            ).await()

            val auditId = "${now}_block_${targetUser.uid}"
            val log = AuditLog(
                auditId = auditId,
                adminUid = adminUser.uid,
                adminName = adminUser.name,
                adminEmail = adminUser.email,
                targetUid = targetUser.uid,
                targetName = targetUser.name,
                targetEmail = targetUser.email,
                action = AuditLog.ACTION_BLOCK_MEMBER,
                reason = reason.trim(),
                timestamp = now
            )
            db.collection("auditLogs").document(auditId).set(log).await()
            Unit
        }
    }

    suspend fun unblockMember(adminUser: User, targetUser: User): Result<Unit> {
        return runCatching {
            val now = System.currentTimeMillis()
            db.collection("users").document(targetUser.uid).update(
                mapOf(
                    "accountStatus" to User.STATUS_ACTIVE,
                    "blockedAt" to 0L,
                    "blockedBy" to "",
                    "blockReason" to "",
                    "updatedAt" to now
                )
            ).await()

            val auditId = "${now}_unblock_${targetUser.uid}"
            val log = AuditLog(
                auditId = auditId,
                adminUid = adminUser.uid,
                adminName = adminUser.name,
                adminEmail = adminUser.email,
                targetUid = targetUser.uid,
                targetName = targetUser.name,
                targetEmail = targetUser.email,
                action = AuditLog.ACTION_UNBLOCK_MEMBER,
                reason = "Unblocked by admin",
                timestamp = now
            )
            db.collection("auditLogs").document(auditId).set(log).await()
            Unit
        }
    }

    suspend fun removeMember(adminUser: User, targetUser: User, reason: String = ""): Result<Unit> {
        return runCatching {
            val now = System.currentTimeMillis()
            // Soft-Remove: update accountStatus and retain all historical rides, rideRequests, and sustainability records
            db.collection("users").document(targetUser.uid).update(
                mapOf(
                    "accountStatus" to User.STATUS_REMOVED,
                    "removedAt" to now,
                    "removedBy" to adminUser.uid,
                    "removeReason" to reason.trim(),
                    "updatedAt" to now
                )
            ).await()

            val auditId = "${now}_remove_${targetUser.uid}"
            val log = AuditLog(
                auditId = auditId,
                adminUid = adminUser.uid,
                adminName = adminUser.name,
                adminEmail = adminUser.email,
                targetUid = targetUser.uid,
                targetName = targetUser.name,
                targetEmail = targetUser.email,
                action = AuditLog.ACTION_REMOVE_MEMBER,
                reason = reason.trim(),
                timestamp = now
            )
            db.collection("auditLogs").document(auditId).set(log).await()
            Unit
        }
    }

    fun getPendingApprovalsFlow(): Flow<List<User>> = callbackFlow {
        val listener = db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("AdminRepository", "getPendingApprovalsFlow listener error: ${error.message}", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val pending = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        User.fromSnapshot(doc)?.takeIf { it.isPendingApproval() }
                    } catch (e: Exception) {
                        Log.w("AdminRepository", "Failed to parse pending user document ${doc.id}", e)
                        null
                    }
                }?.sortedByDescending { it.approvalRequestedAt.takeIf { t -> t > 0L } ?: it.createdAt }
                .orEmpty()
                trySend(pending)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun approveUser(adminUser: User, targetUser: User): Result<Unit> {
        return runCatching {
            require(adminUser.isAdmin()) { "Only an administrator can approve accounts." }
            val now = System.currentTimeMillis()
            db.collection("users").document(targetUser.uid).update(
                mapOf(
                    "accountStatus" to User.STATUS_ACTIVE,
                    "approvedAt" to now,
                    "approvedBy" to adminUser.uid,
                    "updatedAt" to now
                )
            ).await()

            runCatching {
                db.collection("systemConfig").document("appApproval").set(
                    mapOf(
                        "approverDisplayName" to adminUser.name.ifBlank { "Administrator" },
                        "approverEmail" to adminUser.email,
                        "updatedAt" to now
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()
            }

            val auditId = "${now}_approve_${targetUser.uid}"
            val log = AuditLog(
                auditId = auditId,
                adminUid = adminUser.uid,
                adminName = adminUser.name,
                adminEmail = adminUser.email,
                targetUid = targetUser.uid,
                targetName = targetUser.name,
                targetEmail = targetUser.email,
                action = AuditLog.ACTION_APPROVE_USER,
                reason = "Account approved by administrator",
                timestamp = now
            )
            db.collection("auditLogs").document(auditId).set(log).await()
            Unit
        }
    }
}
