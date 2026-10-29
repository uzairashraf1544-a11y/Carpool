package com.example.data.repository

import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.SystemConfig
import com.example.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    }

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

    fun getAllUsersFlow(): Flow<List<User>> = callbackFlow {
        val listener = db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val users = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(User::class.java)?.let { raw ->
                        raw.copy(role = raw.effectiveRole, currentMode = raw.effectiveMode)
                    }
                }.orEmpty()
                trySend(users)
            }
        awaitClose { listener.remove() }
    }

    fun getAllRidesFlow(): Flow<List<Ride>> = callbackFlow {
        val listener = db.collection("rides")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }

    fun getAllRideRequestsFlow(): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }

    suspend fun setUserAdminRole(targetUid: String, makeAdmin: Boolean): Result<Unit> {
        return runCatching {
            val currentUid = auth.currentUser?.uid ?: error("Not authenticated.")
            require(targetUid.isNotBlank()) { "Target user ID cannot be empty." }

            val currentDoc = db.collection("users").document(currentUid).get().await()
            if (currentDoc.getString("role")?.uppercase() != User.ROLE_ADMIN) {
                throw SecurityException("Only administrators can change administrator access.")
            }

            if (targetUid == currentUid && !makeAdmin) {
                throw IllegalStateException("You cannot remove your own Administrator access.")
            }

            val targetDoc = db.collection("users").document(targetUid).get().await()
            if (!targetDoc.exists()) error("Target employee does not exist.")

            db.collection("users").document(targetUid)
                .update("role", if (makeAdmin) User.ROLE_ADMIN else User.ROLE_USER)
                .await()
            Unit
        }
    }
}
