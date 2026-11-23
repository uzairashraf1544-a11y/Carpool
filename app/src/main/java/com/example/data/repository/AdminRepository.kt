package com.example.data.repository

import android.util.Log
import com.example.data.model.AuditLog
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PassengerNeed
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.SystemConfig
import com.example.data.model.User
import com.example.util.FirestoreReadMeter
import com.example.util.ManagementReportRow
import com.example.util.RemoteConfigDefaults
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await

class AdminRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private var cachedSystemConfig: SystemConfig? = null
        private var cachedLeaderboard: List<LeaderboardEntry>? = null
        private var lastLeaderboardFetchAt: Long = 0L
    }

    /**
     * Cache-first single get() flow for systemConfig/sustainability.
     * Changes almost never; costs 0 server reads when cached.
     */
    fun getSystemConfigFlow(): Flow<SystemConfig> = flow {
        cachedSystemConfig?.let { emit(it) }
        val config = try {
            val snapshot = try {
                db.collection("systemConfig").document("sustainability")
                    .get(Source.CACHE)
                    .await()
            } catch (_: Exception) {
                db.collection("systemConfig").document("sustainability")
                    .get()
                    .await()
            }
            FirestoreReadMeter.recordDocumentSnapshot("systemConfig/sustainability", snapshot)
            val parsed = snapshot?.toObject(SystemConfig::class.java) ?: SystemConfig()
            cachedSystemConfig = parsed
            parsed
        } catch (e: Exception) {
            FirestoreReadMeter.handleFirestoreError("systemConfig/sustainability", e)
            cachedSystemConfig ?: SystemConfig()
        }
        emit(config)
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
            cachedSystemConfig = null // invalidate cache
            FirestoreReadMeter.recordWrite("systemConfig/sustainability", 1)
            Unit
        }
    }

    /**
     * Cache-first one-shot get() flow for systemConfig/leaderboard (Task T1).
     * Replaces snapshot listener to eliminate fan-out reads to all active users.
     * Enforces TTL (6h default, 24h safe mode).
     */
    fun getTopCo2SaversFlow(): Flow<List<LeaderboardEntry>> = flow {
        val now = System.currentTimeMillis()
        val ttlMs = RemoteConfigDefaults.leaderboardCacheHours * 3600 * 1000L
        if (cachedLeaderboard != null && (now - lastLeaderboardFetchAt < ttlMs)) {
            emit(cachedLeaderboard!!)
            return@flow
        }

        val entries = try {
            val snapshot = try {
                db.collection("systemConfig").document("leaderboard")
                    .get(Source.CACHE)
                    .await()
            } catch (_: Exception) {
                db.collection("systemConfig").document("leaderboard")
                    .get()
                    .await()
            }
            FirestoreReadMeter.recordDocumentSnapshot("systemConfig/leaderboard", snapshot)
            parseLeaderboardEntries(snapshot)
        } catch (e: Exception) {
            FirestoreReadMeter.handleFirestoreError("systemConfig/leaderboard", e)
            cachedLeaderboard ?: emptyList()
        }

        cachedLeaderboard = entries
        lastLeaderboardFetchAt = now
        emit(entries)
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    private fun parseLeaderboardEntries(snapshot: DocumentSnapshot?): List<LeaderboardEntry> {
        if (snapshot == null || !snapshot.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val rawList = snapshot.get("entries") as? List<Map<String, Any>> ?: return emptyList()
        return rawList.mapNotNull { map ->
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
    }

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

    /**
     * Paged users fetch for Members tab (Task T2).
     * Costs <= 25 reads per page instead of streaming all 300 users.
     */
    suspend fun fetchUsersPage(
        pageSize: Long = 25L,
        startAfterDoc: DocumentSnapshot? = null
    ): Result<Pair<List<User>, DocumentSnapshot?>> = runCatching {
        var query = db.collection("users")
            .orderBy("fullName")
            .limit(pageSize)
        if (startAfterDoc != null) {
            query = query.startAfter(startAfterDoc)
        }
        val snapshot = query.get().await()
        FirestoreReadMeter.recordQuerySnapshot("users", snapshot)
        val users = snapshot.documents.mapNotNull { doc ->
            try { User.fromSnapshot(doc) } catch (_: Exception) { null }
        }
        val lastDoc = snapshot.documents.lastOrNull()
        Pair(users, lastDoc)
    }

    /**
     * Server-side prefix range search by full name or employee ID (Task T2).
     * Replaces client-side "load everyone and filter".
     */
    suspend fun searchUsers(
        searchQuery: String,
        limit: Long = 25L
    ): Result<List<User>> = runCatching {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return@runCatching emptyList()

        val query = if (trimmed.all { it.isDigit() }) {
            db.collection("users")
                .whereEqualTo("employeeId", trimmed)
                .limit(limit)
        } else {
            db.collection("users")
                .whereGreaterThanOrEqualTo("fullName", trimmed)
                .whereLessThanOrEqualTo("fullName", trimmed + "\uf8ff")
                .limit(limit)
        }
        val snapshot = query.get().await()
        FirestoreReadMeter.recordQuerySnapshot("users", snapshot)
        snapshot.documents.mapNotNull { doc ->
            try { User.fromSnapshot(doc) } catch (_: Exception) { null }
        }
    }

    /**
     * Bounded flow fallback for callers expecting a Flow (caps at 25 instead of 300).
     */
    fun getAllUsersFlow(): Flow<List<User>> = callbackFlow {
        FirestoreReadMeter.recordListenerStart("users/pagedFallback")
        val listener = db.collection("users")
            .orderBy("fullName")
            .limit(25)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("AdminRepository", "getAllUsersFlow listener error: ${error.message}", error)
                    FirestoreReadMeter.handleFirestoreError("users", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("users", snapshot)
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
        FirestoreReadMeter.recordListenerStart("rides/adminAll")
        val listener = db.collection("rides")
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirestoreReadMeter.handleFirestoreError("rides", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("rides", snapshot)
                trySend(snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getAllRequestsFlow(): Flow<List<RideRequest>> = callbackFlow {
        FirestoreReadMeter.recordListenerStart("rideRequests/adminAll")
        val listener = db.collection("rideRequests")
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirestoreReadMeter.handleFirestoreError("rideRequests", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("rideRequests", snapshot)
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getAllRideRequestsFlow(): Flow<List<RideRequest>> = getAllRequestsFlow()

    fun getTodayRidesFlow(date: String): Flow<List<Ride>> = callbackFlow {
        FirestoreReadMeter.recordListenerStart("rides/adminToday")
        val listener = db.collection("rides")
            .whereEqualTo("date", date)
            .limit(25)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirestoreReadMeter.handleFirestoreError("rides", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("rides", snapshot)
                trySend(snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getTodayRequestsFlow(date: String): Flow<List<RideRequest>> = callbackFlow {
        FirestoreReadMeter.recordListenerStart("rideRequests/adminToday")
        val listener = db.collection("rideRequests")
            .whereEqualTo("date", date)
            .limit(25)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirestoreReadMeter.handleFirestoreError("rideRequests", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("rideRequests", snapshot)
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /**
     * Bounded audit logs flow (Task T6: limit 20).
     */
    fun getAuditLogsFlow(): Flow<List<AuditLog>> = callbackFlow {
        FirestoreReadMeter.recordListenerStart("auditLogs/stream")
        val listener = db.collection("auditLogs")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(20)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirestoreReadMeter.handleFirestoreError("auditLogs", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                FirestoreReadMeter.recordQuerySnapshot("auditLogs", snapshot)
                val logs = snapshot?.documents?.mapNotNull { it.toObject(AuditLog::class.java) }
                    ?.sortedByDescending { it.timestamp }
                    .orEmpty()
                trySend(logs)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun fetchMoreAuditLogs(
        startAfterTimestamp: Long,
        limit: Long = 20L
    ): Result<List<AuditLog>> = runCatching {
        val snapshot = db.collection("auditLogs")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .startAfter(startAfterTimestamp)
            .limit(limit)
            .get()
            .await()
        FirestoreReadMeter.recordQuerySnapshot("auditLogs", snapshot)
        snapshot.documents.mapNotNull { it.toObject(AuditLog::class.java) }
    }

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

    /**
     * Complete Purge: Permanently erases all user profile records, active requests,
     * rides, passenger needs, and CO₂ records from Firestore (Zero trace).
     */
    suspend fun purgeMemberCompletely(adminUser: User, targetUser: User, reason: String = ""): Result<Unit> {
        return runCatching {
            require(adminUser.isAdmin()) { "Only an administrator can purge member data." }
            require(targetUser.uid != adminUser.uid) { "Administrators cannot purge their own account." }

            val now = System.currentTimeMillis()
            val targetUid = targetUser.uid

            // 1. Purge active passenger ride lock
            try {
                db.collection("activePassengerRides").document(targetUid).delete().await()
            } catch (_: Exception) {}

            // 2. Purge passengerNeeds for this user
            try {
                val needs = db.collection("passengerNeeds")
                    .whereEqualTo("passengerId", targetUid)
                    .get()
                    .await()
                if (!needs.isEmpty) {
                    val batch = db.batch()
                    for (doc in needs.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (_: Exception) {}

            // 3. Purge rideRequests where user was passenger or driver
            try {
                val reqsPassenger = db.collection("rideRequests")
                    .whereEqualTo("passengerId", targetUid)
                    .get()
                    .await()
                if (!reqsPassenger.isEmpty) {
                    val batch = db.batch()
                    for (doc in reqsPassenger.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }

                val reqsDriver = db.collection("rideRequests")
                    .whereEqualTo("driverId", targetUid)
                    .get()
                    .await()
                if (!reqsDriver.isEmpty) {
                    val batch = db.batch()
                    for (doc in reqsDriver.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (_: Exception) {}

            // 4. Purge rides where user was driver
            try {
                val ridesDriver = db.collection("rides")
                    .whereEqualTo("driverId", targetUid)
                    .get()
                    .await()
                if (!ridesDriver.isEmpty) {
                    val batch = db.batch()
                    for (doc in ridesDriver.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (_: Exception) {}

            // 5. Remove user from acceptedPassengerIds in active/scheduled rides
            try {
                val passengerInRides = db.collection("rides")
                    .whereArrayContains("acceptedPassengerIds", targetUid)
                    .get()
                    .await()
                if (!passengerInRides.isEmpty) {
                    val batch = db.batch()
                    for (doc in passengerInRides.documents) {
                        batch.update(
                            doc.reference,
                            "acceptedPassengerIds", FieldValue.arrayRemove(targetUid),
                            "completedPassengerIds", FieldValue.arrayRemove(targetUid)
                        )
                    }
                    batch.commit().await()
                }
            } catch (_: Exception) {}

            // 6. Purge co2Records where user was driver or passenger contributor
            try {
                val co2Docs = db.collection("co2Records")
                    .whereEqualTo("driverId", targetUid)
                    .get()
                    .await()
                if (!co2Docs.isEmpty) {
                    val batch = db.batch()
                    for (doc in co2Docs.documents) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            } catch (_: Exception) {}

            // 7. Remove user from leaderboard if listed
            try {
                val leaderboardDoc = db.collection("systemConfig").document("leaderboard").get().await()
                if (leaderboardDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val entries = leaderboardDoc.get("entries") as? List<Map<String, Any>>
                    if (entries != null && entries.any { it["uid"] == targetUid }) {
                        val filtered = entries.filter { it["uid"] != targetUid }
                        db.collection("systemConfig").document("leaderboard").update(
                            mapOf(
                                "entries" to filtered,
                                "updatedAt" to now
                            )
                        ).await()
                    }
                }
            } catch (_: Exception) {}

            // 8. Delete user document from users collection (Erases user profile)
            db.collection("users").document(targetUid).delete().await()

            // 9. Record administrative audit log of the purge action
            val auditId = "${now}_purge_${targetUid}"
            val log = AuditLog(
                auditId = auditId,
                adminUid = adminUser.uid,
                adminName = adminUser.name,
                adminEmail = adminUser.email,
                targetUid = targetUid,
                targetName = targetUser.name,
                targetEmail = targetUser.email,
                action = AuditLog.ACTION_PURGE_MEMBER,
                reason = reason.trim().ifBlank { "Full data purge requested by admin" },
                timestamp = now
            )
            try {
                db.collection("auditLogs").document(auditId).set(log).await()
            } catch (_: Exception) {}

            Unit
        }
    }

    fun getPendingApprovalsFlow(): Flow<List<User>> = callbackFlow {
        val listener = db.collection("users")
            .whereEqualTo("accountStatus", User.STATUS_PENDING_APPROVAL)
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

    /**
     * Direct one-shot server query for pending approvals to guarantee instantaneous updates.
     */
    suspend fun fetchPendingApprovals(forceServer: Boolean = true): Result<List<User>> = runCatching {
        val source = if (forceServer) Source.SERVER else Source.DEFAULT
        val snapshot = db.collection("users")
            .whereEqualTo("accountStatus", User.STATUS_PENDING_APPROVAL)
            .get(source)
            .await()
        snapshot.documents.mapNotNull { doc ->
            try {
                User.fromSnapshot(doc)?.takeIf { it.isPendingApproval() }
            } catch (_: Exception) { null }
        }.sortedByDescending { it.approvalRequestedAt.takeIf { t -> t > 0L } ?: it.createdAt }
    }

    /**
     * Fetch the management report as a SINGLE Firestore document read (systemConfig/managementReport).
     * Costs EXACTLY 1 read for all employees and all rides.
     */
    suspend fun fetchManagementReport(forceServer: Boolean = true): Result<Pair<List<ManagementReportRow>, Long>> = runCatching {
        val source = if (forceServer) Source.SERVER else Source.DEFAULT
        val docRef = db.collection("systemConfig").document("managementReport")
        val doc = try {
            docRef.get(source).await()
        } catch (_: Exception) {
            docRef.get(Source.DEFAULT).await()
        }

        if (doc != null && doc.exists()) {
            val timestamp = doc.getLong("updatedAt") ?: System.currentTimeMillis()
            @Suppress("UNCHECKED_CAST")
            val rawList = doc.get("rows") as? List<Map<String, Any>>
            if (rawList != null) {
                val rows = rawList.mapIndexed { idx, map ->
                    ManagementReportRow(
                        serialNumber = (map["serialNumber"] as? Number)?.toInt() ?: (idx + 1),
                        personName = map["personName"] as? String ?: "User",
                        co2Saved = (map["co2Saved"] as? Number)?.toDouble() ?: 0.0,
                        ridesAsDriver = (map["ridesAsDriver"] as? Number)?.toInt() ?: 0,
                        ridesAsPassenger = (map["ridesAsPassenger"] as? Number)?.toInt() ?: 0
                    )
                }
                return@runCatching Pair(rows, timestamp)
            }
        }

        // Only aggregate if document does not exist in Firestore
        recalculateAndPersistManagementReport().getOrThrow()
    }

    /**
     * Real-time stream of the single aggregated report document (systemConfig/managementReport).
     * Costs EXACTLY 1 read for the entire screen.
     */
    fun getManagementReportFlow(): Flow<ManagementReportSummary> = callbackFlow {
        val listener = db.collection("systemConfig").document("managementReport")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(ManagementReportSummary())
                    return@addSnapshotListener
                }
                val ts = snapshot.getLong("updatedAt") ?: System.currentTimeMillis()
                val totalMembers = snapshot.getLong("totalMembers")?.toInt() ?: 0
                val totalCo2 = snapshot.getDouble("totalCo2Saved") ?: 0.0
                val totalDriverRides = snapshot.getLong("totalDriverRides")?.toInt() ?: 0
                val totalPassengerRides = snapshot.getLong("totalPassengerRides")?.toInt() ?: 0

                @Suppress("UNCHECKED_CAST")
                val rawList = snapshot.get("rows") as? List<Map<String, Any>>
                val rows = rawList?.mapIndexed { idx, map ->
                    ManagementReportRow(
                        serialNumber = (map["serialNumber"] as? Number)?.toInt() ?: (idx + 1),
                        personName = map["personName"] as? String ?: "User",
                        co2Saved = (map["co2Saved"] as? Number)?.toDouble() ?: 0.0,
                        ridesAsDriver = (map["ridesAsDriver"] as? Number)?.toInt() ?: 0,
                        ridesAsPassenger = (map["ridesAsPassenger"] as? Number)?.toInt() ?: 0
                    )
                }.orEmpty()

                trySend(
                    ManagementReportSummary(
                        rows = rows,
                        totalMembers = if (totalMembers > 0) totalMembers else rows.size,
                        totalCo2Saved = if (totalCo2 > 0.0) totalCo2 else rows.sumOf { it.co2Saved },
                        totalDriverRides = totalDriverRides,
                        totalPassengerRides = totalPassengerRides,
                        updatedAt = ts
                    )
                )
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /**
     * Recalculates the management report, writes it to the single document systemConfig/managementReport,
     * and returns the rows with the timestamp.
     */
    suspend fun recalculateAndPersistManagementReport(): Result<Pair<List<ManagementReportRow>, Long>> = runCatching {
        val now = System.currentTimeMillis()
        val usersSnap = db.collection("users").get().await()
        val usersList = usersSnap.documents.mapNotNull { User.fromSnapshot(it) }

        val ridesSnap = db.collection("rides").whereEqualTo("status", Ride.STATUS_COMPLETED).get().await()
        val completedRides = ridesSnap.documents.mapNotNull { it.toObject(Ride::class.java) }

        val reqsSnap = db.collection("rideRequests").whereEqualTo("status", RideRequest.STATUS_COMPLETED).get().await()
        val completedRequests = reqsSnap.documents.mapNotNull { it.toObject(RideRequest::class.java) }

        val sysConfigDoc = try { db.collection("systemConfig").document("sustainability").get().await() } catch (_: Exception) { null }
        val baselineDistanceKm = sysConfigDoc?.getDouble("baselineDistanceKm") ?: 3.0
        val co2Factor = sysConfigDoc?.getDouble("co2FactorKgPerKm") ?: 0.12

        val rawRows = usersList.map { person ->
            val driverRides = completedRides.filter { it.driverId == person.uid }
            val driverRideCount = driverRides.size
            val passengersDelivered = driverRides.sumOf {
                if (it.finalPassengerCount > 0) it.finalPassengerCount else it.completedPassengerIds.size.coerceAtLeast(it.acceptedPassengerIds.size)
            }

            val passengerReqs = completedRequests.filter { it.passengerId == person.uid }
            val passengerRideCount = passengerReqs.size

            val avoidedDistanceKm = (passengersDelivered + passengerRideCount) * baselineDistanceKm
            val personCo2Saved = avoidedDistanceKm * co2Factor

            ManagementReportRow(
                serialNumber = 0,
                personName = person.name.ifBlank { "User ${person.uid.take(5)}" },
                co2Saved = personCo2Saved,
                ridesAsDriver = driverRideCount,
                ridesAsPassenger = passengerRideCount
            )
        }.sortedByDescending { it.co2Saved }.mapIndexed { index, row ->
            row.copy(serialNumber = index + 1)
        }

        val rowsMap = rawRows.map { row ->
            mapOf(
                "serialNumber" to row.serialNumber,
                "personName" to row.personName,
                "co2Saved" to row.co2Saved,
                "ridesAsDriver" to row.ridesAsDriver,
                "ridesAsPassenger" to row.ridesAsPassenger
            )
        }

        val reportDocData = mapOf(
            "rows" to rowsMap,
            "updatedAt" to now,
            "totalMembers" to rawRows.size,
            "totalCo2Saved" to rawRows.sumOf { it.co2Saved },
            "totalDriverRides" to completedRides.size,
            "totalPassengerRides" to completedRequests.size
        )

        db.collection("systemConfig").document("managementReport")
            .set(reportDocData, com.google.firebase.firestore.SetOptions.merge())
            .await()

        Pair(rawRows, now)
    }

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

    // =========================================================================
    // DATA MANAGEMENT: GLOBAL RESET & PER-USER DELETION
    // =========================================================================

    suspend fun previewGlobalReset(
        adminUser: User,
        resetType: GlobalResetType,
        removeAdminData: Boolean
    ): Result<GlobalResetPreview> = runCatching {
        require(adminUser.isAdmin()) { "Access denied. Administrator privileges are required." }
        val currentAdminUid = adminUser.uid
        val shouldWipeAdminData = removeAdminData || resetType == GlobalResetType.RIDES_ONLY

        // 1. Calculate rides count via server aggregation (1 read per 1,000 docs)
        val totalRidesCount = db.collection("rides").count().get(AggregateSource.SERVER).await().count
        val affectedRidesCount = if (shouldWipeAdminData) {
            totalRidesCount.toInt()
        } else {
            val adminRidesCount = db.collection("rides")
                .whereEqualTo("driverId", currentAdminUid)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
            (totalRidesCount - adminRidesCount).toInt().coerceAtLeast(0)
        }

        // 2. Calculate bookings count via server aggregation
        val totalReqsCount = db.collection("rideRequests").count().get(AggregateSource.SERVER).await().count
        val affectedBookingsCount = if (shouldWipeAdminData) {
            totalReqsCount.toInt()
        } else {
            val adminPassengerReqsCount = db.collection("rideRequests")
                .whereEqualTo("passengerId", currentAdminUid)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
            val adminDriverReqsCount = db.collection("rideRequests")
                .whereEqualTo("driverId", currentAdminUid)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
            (totalReqsCount - adminPassengerReqsCount - adminDriverReqsCount).toInt().coerceAtLeast(0)
        }

        // 3. Calculate passenger needs count via server aggregation
        val totalNeedsCount = db.collection("passengerNeeds").count().get(AggregateSource.SERVER).await().count
        val affectedNeedsCount = if (shouldWipeAdminData) {
            totalNeedsCount.toInt()
        } else {
            val adminNeedsCount = db.collection("passengerNeeds")
                .whereEqualTo("passengerId", currentAdminUid)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
            (totalNeedsCount - adminNeedsCount).toInt().coerceAtLeast(0)
        }

        // 4. Calculate accounts to delete via server aggregation
        val totalUsersCount = db.collection("users").count().get(AggregateSource.SERVER).await().count
        val affectedUsersCount = when (resetType) {
            GlobalResetType.RIDES_ONLY -> 0
            GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN -> {
                (totalUsersCount - 1).toInt().coerceAtLeast(0)
            }
            GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS -> {
                val adminUsersCount = db.collection("users")
                    .whereEqualTo("role", User.ROLE_ADMIN)
                    .count()
                    .get(AggregateSource.SERVER)
                    .await()
                    .count
                (totalUsersCount - adminUsersCount).toInt().coerceAtLeast(0)
            }
        }

        GlobalResetPreview(
            ridesCount = affectedRidesCount,
            bookingsCount = affectedBookingsCount,
            passengerNeedsCount = affectedNeedsCount,
            userAccountsCount = affectedUsersCount
        )
    }

    suspend fun previewUserDeletion(
        adminUser: User,
        targetUser: User,
        deleteAccount: Boolean
    ): Result<UserDataDeletionPreview> = runCatching {
        require(adminUser.isAdmin()) { "Access denied. Administrator privileges are required." }
        if (deleteAccount) {
            require(targetUser.uid != adminUser.uid) { "Cannot delete your own administrator account." }
        }
        val targetUid = targetUser.uid

        // Rides given as driver - fetch driver rides to accurately count anonymized vs unbooked
        val driverRidesSnap = db.collection("rides").whereEqualTo("driverId", targetUid).get().await()
        val driverRides = driverRidesSnap.documents.mapNotNull { it.toObject(Ride::class.java) }
        val anonymizedCount = driverRides.count { it.acceptedPassengerIds.isNotEmpty() || it.completedPassengerIds.isNotEmpty() }

        // Bookings taken as passenger (server aggregation count)
        val passengerReqsCount = db.collection("rideRequests")
            .whereEqualTo("passengerId", targetUid)
            .count()
            .get(AggregateSource.SERVER)
            .await()
            .count

        // Needs posted as passenger (server aggregation count)
        val needsCount = db.collection("passengerNeeds")
            .whereEqualTo("passengerId", targetUid)
            .count()
            .get(AggregateSource.SERVER)
            .await()
            .count

        UserDataDeletionPreview(
            targetUid = targetUid,
            targetName = targetUser.name,
            ridesAsDriverCount = driverRides.size,
            bookingsAsPassengerCount = passengerReqsCount.toInt(),
            passengerNeedsCount = needsCount.toInt(),
            ridesAnonymizedForPassengersCount = anonymizedCount,
            isAccountDeleted = deleteAccount
        )
    }

    suspend fun executeGlobalReset(
        adminUser: User,
        resetType: GlobalResetType,
        removeAdminData: Boolean,
        reason: String = ""
    ): Result<GlobalResetPreview> = runCatching {
        require(adminUser.isAdmin()) { "Access denied. Administrator privileges are required." }
        require(adminUser.uid.isNotBlank()) { "Invalid administrator credentials." }
        val now = System.currentTimeMillis()
        val currentAdminUid = adminUser.uid

        // 1. Get preview counts first
        val preview = previewGlobalReset(adminUser, resetType, removeAdminData).getOrThrow()

        // 2. Fetch all collections
        val ridesSnap = db.collection("rides").get().await()
        val reqsSnap = db.collection("rideRequests").get().await()
        val needsSnap = db.collection("passengerNeeds").get().await()
        val activeLocksSnap = db.collection("activePassengerRides").get().await()
        val co2Snap = db.collection("co2Records").get().await()
        val usersSnap = db.collection("users").get().await()

        val allUsers = usersSnap.documents.mapNotNull { User.fromSnapshot(it) }

        val shouldWipeAdminData = removeAdminData || resetType == GlobalResetType.RIDES_ONLY

        // Determine which docs to delete
        val ridesToDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()
        for (doc in ridesSnap.documents) {
            val dUid = doc.getString("driverId").orEmpty()
            if (shouldWipeAdminData || dUid != currentAdminUid) {
                ridesToDelete.add(doc.reference)
            }
        }

        val reqsToDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()
        for (doc in reqsSnap.documents) {
            val pUid = doc.getString("passengerId").orEmpty()
            val dUid = doc.getString("driverId").orEmpty()
            if (shouldWipeAdminData || (pUid != currentAdminUid && dUid != currentAdminUid)) {
                reqsToDelete.add(doc.reference)
            }
        }

        val needsToDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()
        for (doc in needsSnap.documents) {
            val pUid = doc.getString("passengerId").orEmpty()
            if (shouldWipeAdminData || pUid != currentAdminUid) {
                needsToDelete.add(doc.reference)
            }
        }

        val locksToDelete = mutableListOf<com.google.firebase.firestore.DocumentReference>()
        for (doc in activeLocksSnap.documents) {
            val pUid = doc.getString("passengerId").orEmpty()
            if (shouldWipeAdminData || pUid != currentAdminUid) {
                locksToDelete.add(doc.reference)
            }
        }

        val co2ToDelete = co2Snap.documents.map { it.reference }

        // User accounts to delete vs keep
        val usersToDelete = mutableListOf<User>()
        val usersToResetCounters = mutableListOf<User>()

        for (u in allUsers) {
            if (u.uid == currentAdminUid) {
                // NEVER delete current admin account, but reset counters to zero
                if (shouldWipeAdminData || resetType == GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS) {
                    usersToResetCounters.add(u)
                }
                continue
            }
            when (resetType) {
                GlobalResetType.RIDES_ONLY -> {
                    // Keep all accounts, reset counters
                    usersToResetCounters.add(u)
                }
                GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN -> {
                    // Delete all other users
                    usersToDelete.add(u)
                }
                GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS -> {
                    if (u.isAdmin()) {
                        usersToResetCounters.add(u)
                    } else {
                        usersToDelete.add(u)
                    }
                }
            }
        }

        // Execute batch operations in chunks (up to 450 per batch)
        var currentBatch = db.batch()
        var opCount = 0

        suspend fun commitBatchIfNeeded() {
            if (opCount >= 450) {
                currentBatch.commit().await()
                currentBatch = db.batch()
                opCount = 0
            }
        }

        for (ref in ridesToDelete) {
            currentBatch.delete(ref)
            opCount++
            commitBatchIfNeeded()
        }

        for (ref in reqsToDelete) {
            currentBatch.delete(ref)
            opCount++
            commitBatchIfNeeded()
        }

        for (ref in needsToDelete) {
            currentBatch.delete(ref)
            opCount++
            commitBatchIfNeeded()
        }

        for (ref in locksToDelete) {
            currentBatch.delete(ref)
            opCount++
            commitBatchIfNeeded()
        }

        for (ref in co2ToDelete) {
            currentBatch.delete(ref)
            opCount++
            commitBatchIfNeeded()
        }

        // Delete users and their employeeRegistry
        for (u in usersToDelete) {
            currentBatch.delete(db.collection("users").document(u.uid))
            opCount++
            commitBatchIfNeeded()

            if (u.employeeId.isNotBlank()) {
                currentBatch.delete(db.collection("employeeRegistry").document(u.employeeId.uppercase().trim()))
                opCount++
                commitBatchIfNeeded()
            }
        }

        // Reset counters on kept users
        for (u in usersToResetCounters) {
            currentBatch.update(
                db.collection("users").document(u.uid),
                mapOf(
                    "totalCo2SavedKg" to 0.0,
                    "totalCompletedRides" to 0,
                    "updatedAt" to now
                )
            )
            opCount++
            commitBatchIfNeeded()
        }

        if (opCount > 0) {
            currentBatch.commit().await()
        }

        // Reset leaderboard and management report
        try {
            db.collection("systemConfig").document("leaderboard").set(
                mapOf(
                    "entries" to emptyList<Map<String, Any>>(),
                    "updatedAt" to now
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        } catch (_: Exception) {}

        try {
            db.collection("systemConfig").document("managementReport").set(
                mapOf(
                    "rows" to emptyList<Map<String, Any>>(),
                    "updatedAt" to now,
                    "totalMembers" to if (resetType == GlobalResetType.RIDES_ONLY) allUsers.size else (allUsers.size - usersToDelete.size),
                    "totalCo2Saved" to 0.0,
                    "totalDriverRides" to 0,
                    "totalPassengerRides" to 0
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        } catch (_: Exception) {}

        // Write immutable audit log
        val auditAction = when (resetType) {
            GlobalResetType.RIDES_ONLY -> AuditLog.ACTION_RESET_RIDES_ONLY
            GlobalResetType.ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN -> AuditLog.ACTION_RESET_ALL_EXCEPT_CURRENT_ADMIN
            GlobalResetType.ALL_EXCEPT_ADMIN_ACCOUNTS -> AuditLog.ACTION_RESET_ALL_EXCEPT_ADMINS
        }
        val auditId = "${now}_global_reset_${auditAction.lowercase()}"
        val auditLog = AuditLog(
            auditId = auditId,
            adminUid = adminUser.uid,
            adminName = adminUser.name,
            adminEmail = adminUser.email,
            targetUid = "ALL",
            targetName = resetType.name,
            targetEmail = "",
            action = auditAction,
            reason = (reason.trim().ifBlank { "Global data reset executed before app launch" }) +
                    " [Rides: ${preview.ridesCount}, Bookings: ${preview.bookingsCount}, Needs: ${preview.passengerNeedsCount}, Accounts: ${preview.userAccountsCount}, AdminDataRemoved: $removeAdminData]",
            timestamp = now
        )
        try {
            db.collection("auditLogs").document(auditId).set(auditLog).await()
        } catch (e: Exception) {
            Log.e("AdminRepository", "Failed to write audit log for global reset", e)
        }

        preview
    }

    suspend fun executePerUserDataDeletion(
        adminUser: User,
        targetUser: User,
        deleteAccount: Boolean,
        reason: String = ""
    ): Result<UserDataDeletionPreview> = runCatching {
        require(adminUser.isAdmin()) { "Access denied. Administrator privileges are required." }
        if (deleteAccount) {
            require(targetUser.uid != adminUser.uid) { "Cannot delete your own administrator account." }
        }
        val now = System.currentTimeMillis()
        val targetUid = targetUser.uid

        // 1. Get preview
        val preview = previewUserDeletion(adminUser, targetUser, deleteAccount).getOrThrow()

        // 2. Query driver rides given by targetUser
        val driverRidesSnap = db.collection("rides").whereEqualTo("driverId", targetUid).get().await()
        val driverRides = driverRidesSnap.documents.mapNotNull { doc ->
            doc.toObject(Ride::class.java)?.let { Pair(doc.reference, it) }
        }

        var currentBatch = db.batch()
        var opCount = 0

        suspend fun commitBatchIfNeeded() {
            if (opCount >= 450) {
                currentBatch.commit().await()
                currentBatch = db.batch()
                opCount = 0
            }
        }

        for ((rideRef, ride) in driverRides) {
            val hasPassengers = ride.acceptedPassengerIds.isNotEmpty() || ride.completedPassengerIds.isNotEmpty()
            if (hasPassengers) {
                // PRESERVE PASSENGERS' HISTORY: Do NOT delete ride! Anonymize driver personal details.
                currentBatch.update(
                    rideRef,
                    mapOf(
                        "driverName" to "Former Colleague",
                        "driverPhone" to "",
                        "driverDepartment" to "—",
                        "updatedAt" to now
                    )
                )
                opCount++
                commitBatchIfNeeded()

                // Update passenger bookings linked to this ride to anonymize driver details as well
                val bookingsForRideSnap = db.collection("rideRequests")
                    .whereEqualTo("rideId", ride.rideId)
                    .get()
                    .await()
                for (bookingDoc in bookingsForRideSnap.documents) {
                    currentBatch.update(
                        bookingDoc.reference,
                        mapOf(
                            "driverName" to "Former Colleague",
                            "driverDepartment" to "—",
                            "updatedAt" to now
                        )
                    )
                    opCount++
                    commitBatchIfNeeded()
                }
            } else {
                // No passengers ever booked: safe to delete completely
                currentBatch.delete(rideRef)
                opCount++
                commitBatchIfNeeded()
            }
        }

        // 3. Rides where targetUser was PASSENGER
        val passengerReqsSnap = db.collection("rideRequests").whereEqualTo("passengerId", targetUid).get().await()
        for (reqDoc in passengerReqsSnap.documents) {
            val rideId = reqDoc.getString("rideId").orEmpty()
            currentBatch.delete(reqDoc.reference)
            opCount++
            commitBatchIfNeeded()

            if (rideId.isNotBlank()) {
                currentBatch.update(
                    db.collection("rides").document(rideId),
                    "acceptedPassengerIds", FieldValue.arrayRemove(targetUid),
                    "completedPassengerIds", FieldValue.arrayRemove(targetUid)
                )
                opCount++
                commitBatchIfNeeded()
            }
        }

        // 4. Passenger needs posted by targetUser
        val needsSnap = db.collection("passengerNeeds").whereEqualTo("passengerId", targetUid).get().await()
        for (needDoc in needsSnap.documents) {
            currentBatch.delete(needDoc.reference)
            opCount++
            commitBatchIfNeeded()
        }

        // 5. Active passenger exclusivity lock
        currentBatch.delete(db.collection("activePassengerRides").document(targetUid))
        opCount++
        commitBatchIfNeeded()

        // 6. CO2 records where targetUser was driver
        val co2Snap = db.collection("co2Records").whereEqualTo("driverId", targetUid).get().await()
        for (co2Doc in co2Snap.documents) {
            currentBatch.delete(co2Doc.reference)
            opCount++
            commitBatchIfNeeded()
        }

        // 7. Account handling (Delete Account vs Delete Data Only)
        if (deleteAccount) {
            // Delete user document
            currentBatch.delete(db.collection("users").document(targetUid))
            opCount++
            commitBatchIfNeeded()

            // Delete employeeRegistry document
            if (targetUser.employeeId.isNotBlank()) {
                currentBatch.delete(db.collection("employeeRegistry").document(targetUser.employeeId.uppercase().trim()))
                opCount++
                commitBatchIfNeeded()
            }
        } else {
            // Keep account, reset personal counters to 0
            currentBatch.update(
                db.collection("users").document(targetUid),
                mapOf(
                    "totalCo2SavedKg" to 0.0,
                    "totalCompletedRides" to 0,
                    "updatedAt" to now
                )
            )
            opCount++
            commitBatchIfNeeded()
        }

        if (opCount > 0) {
            currentBatch.commit().await()
        }

        // 8. Remove targetUser from leaderboard if listed
        try {
            val leaderboardDoc = db.collection("systemConfig").document("leaderboard").get().await()
            if (leaderboardDoc.exists()) {
                @Suppress("UNCHECKED_CAST")
                val entries = leaderboardDoc.get("entries") as? List<Map<String, Any>>
                if (entries != null && entries.any { it["uid"] == targetUid }) {
                    val filtered = entries.filter { it["uid"] != targetUid }
                    db.collection("systemConfig").document("leaderboard").update(
                        mapOf(
                            "entries" to filtered,
                            "updatedAt" to now
                        )
                    ).await()
                }
            }
        } catch (_: Exception) {}

        // 9. Update management report
        try {
            recalculateAndPersistManagementReport()
        } catch (_: Exception) {}

        // 10. Audit log
        val auditAction = if (deleteAccount) AuditLog.ACTION_DELETE_USER_AND_ACCOUNT else AuditLog.ACTION_DELETE_USER_DATA_ONLY
        val auditId = "${now}_user_delete_${targetUid}"
        val auditLog = AuditLog(
            auditId = auditId,
            adminUid = adminUser.uid,
            adminName = adminUser.name,
            adminEmail = adminUser.email,
            targetUid = targetUid,
            targetName = targetUser.name,
            targetEmail = targetUser.email,
            action = auditAction,
            reason = (reason.trim().ifBlank { if (deleteAccount) "User requested complete erasure (Data + Account)" else "User requested data deletion (Account retained)" }) +
                    " [DriverRides: ${preview.ridesAsDriverCount}, PassengerBookings: ${preview.bookingsAsPassengerCount}, Needs: ${preview.passengerNeedsCount}, PassengerRidesAnonymized: ${preview.ridesAnonymizedForPassengersCount}]",
            timestamp = now
        )
        try {
            db.collection("auditLogs").document(auditId).set(auditLog).await()
        } catch (e: Exception) {
            Log.e("AdminRepository", "Failed to write audit log for user deletion", e)
        }

        preview
    }
}

enum class GlobalResetType {
    RIDES_ONLY,
    ALL_INCLUDING_USERS_EXCEPT_CURRENT_ADMIN,
    ALL_EXCEPT_ADMIN_ACCOUNTS
}

data class GlobalResetPreview(
    val ridesCount: Int,
    val bookingsCount: Int,
    val passengerNeedsCount: Int,
    val userAccountsCount: Int
)

data class UserDataDeletionPreview(
    val targetUid: String,
    val targetName: String,
    val ridesAsDriverCount: Int,
    val bookingsAsPassengerCount: Int,
    val passengerNeedsCount: Int,
    val ridesAnonymizedForPassengersCount: Int,
    val isAccountDeleted: Boolean
)

data class ManagementReportSummary(
    val rows: List<ManagementReportRow> = emptyList(),
    val totalMembers: Int = 0,
    val totalCo2Saved: Double = 0.0,
    val totalDriverRides: Int = 0,
    val totalPassengerRides: Int = 0,
    val updatedAt: Long = 0L
)
