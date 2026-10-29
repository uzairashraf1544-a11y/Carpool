package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.MasterData
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class RideRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun hasActiveDriverRide(driverId: String, date: String): Boolean {
        return try {
            val rideId = "${driverId}_$date"
            val doc = db.collection("rides").document(rideId).get().await()
            val ride = doc.toObject(Ride::class.java) ?: return false
            ride.status in listOf(
                Ride.STATUS_AVAILABLE,
                Ride.STATUS_PARTIALLY_FULL,
                Ride.STATUS_FULL,
                Ride.STATUS_RIDE_STARTED
            )
        } catch (e: Exception) {
            logFirestoreError("hasActiveDriverRide", "rides/${driverId}_$date", driverId, e)
            false
        }
    }

    // ----------------------------------------------------
    // DRIVER ACTIONS
    // ----------------------------------------------------

    suspend fun createOrUpdateDriverAvailability(
        user: User,
        date: String,
        commuteWindow: String,
        direction: String,
        pickupZone: String,
        destinationZone: String,
        totalSeats: Int
    ): Result<Ride> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))
        if (totalSeats < 1) return Result.failure(IllegalArgumentException("Total seats must be at least 1."))

        return runCatching {
            // Never trust a stale User object for ownership.
            val rideId = "${currentUid}_$date"
            val rideRef = db.collection("rides").document(rideId)

            val existingDoc = rideRef.get().await()
            val existingRide = if (existingDoc.exists()) existingDoc.toObject(Ride::class.java) else null
            val acceptedPassengers = existingRide?.acceptedPassengerIds.orEmpty()
            val safeSeats = totalSeats.coerceIn(1, 8)
            val availableSeats = (safeSeats - acceptedPassengers.size).coerceIn(0, safeSeats)

            val status = when {
                availableSeats == 0 -> Ride.STATUS_FULL
                acceptedPassengers.isNotEmpty() -> Ride.STATUS_PARTIALLY_FULL
                else -> Ride.STATUS_AVAILABLE
            }

            val ride = Ride(
                rideId = rideId,
                driverId = currentUid,
                driverName = user.name,
                driverPhone = user.phone,
                driverDepartment = user.department,
                date = date,
                commuteWindow = commuteWindow,
                direction = direction,
                pickupZone = pickupZone,
                destinationZone = destinationZone,
                totalSeats = safeSeats,
                availableSeats = availableSeats,
                acceptedPassengerIds = acceptedPassengers,
                status = status,
                createdAt = existingRide?.createdAt ?: System.currentTimeMillis(),
                startedAt = existingRide?.startedAt ?: 0L,
                completedAt = existingRide?.completedAt ?: 0L
            )

            rideRef.set(ride).await()
            ride
        }.onFailure { error ->
            logFirestoreError("createOrUpdateDriverAvailability", "rides/${currentUid}_$date", currentUid, error)
        }
    }

    suspend fun setDriverOffline(driverId: String, date: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))
        if (currentUid != driverId) {
            return Result.failure(SecurityException("You can only change your own ride availability."))
        }

        return runCatching {
            val rideId = "${driverId}_$date"
            db.collection("rides").document(rideId)
                .update("status", Ride.STATUS_OFFLINE)
                .await()
            Unit
        }.onFailure { error ->
            logFirestoreError("setDriverOffline", "rides/${driverId}_$date", currentUid, error)
        }
    }

    fun getDriverTodayRideFlow(driverId: String, date: String): Flow<Ride?> = callbackFlow {
        val rideId = "${driverId}_$date"
        val listener = db.collection("rides").document(rideId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getDriverTodayRideFlow", "rides/$rideId", driverId, error)
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(Ride::class.java))
            }
        awaitClose { listener.remove() }
    }

    fun getIncomingRequestsForDriverFlow(driverId: String, date: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .whereEqualTo("driverId", driverId)
            .whereEqualTo("date", date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getIncomingRequestsForDriverFlow", "rideRequests", driverId, error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }

    suspend fun acceptRideRequestTransaction(
        requestId: String,
        rideId: String,
        passengerId: String
    ): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            val rideRef = db.collection("rides").document(rideId)
            val requestRef = db.collection("rideRequests").document(requestId)

            db.runTransaction { transaction ->
                val rideSnap = transaction.get(rideRef)
                val requestSnap = transaction.get(requestRef)

                if (!rideSnap.exists() || !requestSnap.exists()) {
                    throw FirebaseFirestoreException(
                        "Ride request no longer exists.",
                        FirebaseFirestoreException.Code.NOT_FOUND
                    )
                }

                val requestDriverId = requestSnap.getString("driverId").orEmpty()
                val requestPassengerId = requestSnap.getString("passengerId").orEmpty()
                val requestStatus = requestSnap.getString("status").orEmpty()

                if (requestDriverId != currentUid || requestPassengerId != passengerId) {
                    throw FirebaseFirestoreException(
                        "This request does not belong to the current driver.",
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    )
                }
                if (requestStatus != RideRequest.STATUS_REQUESTED) {
                    throw FirebaseFirestoreException(
                        "This request has already been processed.",
                        FirebaseFirestoreException.Code.ABORTED
                    )
                }

                val currentAvailable = rideSnap.getLong("availableSeats") ?: 0L
                val currentStatus = rideSnap.getString("status").orEmpty()
                val currentPassengers = (rideSnap.get("acceptedPassengerIds") as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    .orEmpty()

                if (rideSnap.getString("driverId") != currentUid) {
                    throw FirebaseFirestoreException(
                        "You are not the owner of this ride.",
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    )
                }

                if (
                    currentAvailable <= 0 ||
                    currentStatus in listOf(
                        Ride.STATUS_FULL,
                        Ride.STATUS_OFFLINE,
                        Ride.STATUS_CANCELLED,
                        Ride.STATUS_COMPLETED,
                        Ride.STATUS_RIDE_STARTED
                    )
                ) {
                    throw FirebaseFirestoreException(
                        "Sorry, this ride is no longer accepting passengers.",
                        FirebaseFirestoreException.Code.ABORTED
                    )
                }

                val newPassengers = if (currentPassengers.contains(passengerId)) {
                    currentPassengers
                } else {
                    currentPassengers + passengerId
                }
                val newAvailable = if (currentPassengers.contains(passengerId)) {
                    currentAvailable
                } else {
                    (currentAvailable - 1L).coerceAtLeast(0L)
                }
                val newStatus = if (newAvailable == 0L) {
                    Ride.STATUS_FULL
                } else {
                    Ride.STATUS_PARTIALLY_FULL
                }

                transaction.update(
                    rideRef,
                    mapOf(
                        "availableSeats" to newAvailable,
                        "acceptedPassengerIds" to newPassengers,
                        "status" to newStatus
                    )
                )
                transaction.update(
                    requestRef,
                    mapOf(
                        "status" to RideRequest.STATUS_ACCEPTED,
                        "responseAt" to System.currentTimeMillis()
                    )
                )
            }.await()
            Unit
        }.onFailure { error ->
            logFirestoreError("acceptRideRequestTransaction", "rides/$rideId", currentUid, error)
        }
    }

    suspend fun rejectRideRequest(requestId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            db.collection("rideRequests").document(requestId)
                .update(
                    mapOf(
                        "status" to RideRequest.STATUS_REJECTED,
                        "responseAt" to System.currentTimeMillis()
                    )
                )
                .await()
            Unit
        }.onFailure { error ->
            logFirestoreError("rejectRideRequest", "rideRequests/$requestId", currentUid, error)
        }
    }

    suspend fun startRide(rideId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            db.collection("rides").document(rideId)
                .update(
                    mapOf(
                        "status" to Ride.STATUS_RIDE_STARTED,
                        "startedAt" to System.currentTimeMillis()
                    )
                )
                .await()
            Unit
        }.onFailure { error ->
            logFirestoreError("startRide", "rides/$rideId", currentUid, error)
        }
    }

    /**
     * Spark-plan V1: completion records the actual ride and passenger request states only.
     * CO2 is derived by the Admin dashboard from completed rides + systemConfig. Normal
     * drivers therefore never need permission to write an Admin-only co2Records collection.
     */
    suspend fun finishRide(rideId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            val rideRef = db.collection("rides").document(rideId)
            val rideDoc = rideRef.get().await()
            val ride = rideDoc.toObject(Ride::class.java)
                ?: error("Ride not found.")

            if (ride.driverId != currentUid) {
                throw SecurityException("You can only finish your own ride.")
            }

            val acceptedRequests = db.collection("rideRequests")
                .whereEqualTo("rideId", rideId)
                .whereEqualTo("driverId", currentUid)
                .get()
                .await()
                .documents
                .filter { it.getString("status") == RideRequest.STATUS_ACCEPTED }

            val batch = db.batch()
            batch.update(
                rideRef,
                mapOf(
                    "status" to Ride.STATUS_COMPLETED,
                    "completedAt" to System.currentTimeMillis()
                )
            )
            acceptedRequests.forEach { requestDoc ->
                batch.update(
                    requestDoc.reference,
                    mapOf(
                        "status" to RideRequest.STATUS_COMPLETED,
                        "responseAt" to System.currentTimeMillis()
                    )
                )
            }
            batch.commit().await()
            Unit
        }.onFailure { error ->
            logFirestoreError("finishRide", "rides/$rideId", currentUid, error)
        }
    }

    // ----------------------------------------------------
    // PASSENGER ACTIONS
    // ----------------------------------------------------

    fun getAvailableDriversFlow(
        date: String,
        directionFilter: String,
        windowFilter: String
    ): Flow<List<Ride>> = callbackFlow {
        val listener = db.collection("rides")
            .whereEqualTo("date", date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getAvailableDriversFlow", "rides", auth.currentUser?.uid.orEmpty(), error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val rides = snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty()
                val filtered = rides.filter { ride ->
                    ride.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL) &&
                        ride.availableSeats > 0 &&
                        (directionFilter == "All" || ride.direction == directionFilter) &&
                        commuteWindowMatches(ride.commuteWindow, windowFilter)
                }
                trySend(filtered)
            }
        awaitClose { listener.remove() }
    }

    fun getPassengerRequestsFlow(passengerId: String, date: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .whereEqualTo("passengerId", passengerId)
            .whereEqualTo("date", date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getPassengerRequestsFlow", "rideRequests", passengerId, error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty())
            }
        awaitClose { listener.remove() }
    }

    fun getPassengerHistoryFlow(passengerId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .whereEqualTo("passengerId", passengerId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getPassengerHistoryFlow", "rideRequests", passengerId, error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents
                        ?.mapNotNull { it.toObject(RideRequest::class.java) }
                        ?.sortedByDescending { maxOf(it.requestedAt, it.responseAt) }
                        .orEmpty()
                )
            }
        awaitClose { listener.remove() }
    }

    fun getDriverRideHistoryFlow(driverId: String): Flow<List<Ride>> = callbackFlow {
        val listener = db.collection("rides")
            .whereEqualTo("driverId", driverId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getDriverRideHistoryFlow", "rides", driverId, error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents
                        ?.mapNotNull { it.toObject(Ride::class.java) }
                        ?.sortedByDescending { maxOf(it.createdAt, it.startedAt, it.completedAt) }
                        .orEmpty()
                )
            }
        awaitClose { listener.remove() }
    }

    suspend fun requestRideFromDriver(passenger: User, ride: Ride): Result<RideRequest> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            val requestId = "${currentUid}_${ride.rideId}"
            val request = RideRequest(
                requestId = requestId,
                rideId = ride.rideId,
                driverId = ride.driverId,
                driverName = ride.driverName,
                passengerId = currentUid,
                passengerName = passenger.name,
                passengerPhone = passenger.phone,
                passengerDepartment = passenger.department,
                pickupZone = ride.pickupZone,
                destinationZone = ride.destinationZone,
                commuteWindow = ride.commuteWindow,
                date = ride.date,
                status = RideRequest.STATUS_REQUESTED,
                requestedAt = System.currentTimeMillis()
            )
            db.collection("rideRequests").document(requestId).set(request).await()
            request
        }.onFailure { error ->
            logFirestoreError("requestRideFromDriver", "rideRequests/${currentUid}_${ride.rideId}", currentUid, error)
        }
    }

    suspend fun cancelPassengerRequest(requestId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            val ref = db.collection("rideRequests").document(requestId)
            val doc = ref.get().await()
            if (!doc.exists()) error("Ride request not found.")
            if (doc.getString("passengerId") != currentUid) {
                throw SecurityException("You can only cancel your own request.")
            }
            if (doc.getString("status") != RideRequest.STATUS_REQUESTED) {
                throw IllegalStateException("Only a pending request can be cancelled. Contact the driver if your seat is already confirmed.")
            }
            ref.update("status", RideRequest.STATUS_CANCELLED).await()
            Unit
        }.onFailure { error ->
            logFirestoreError("cancelPassengerRequest", "rideRequests/$requestId", currentUid, error)
        }
    }

    private fun commuteWindowMatches(stored: String, selected: String): Boolean {
        if (selected == "All" || stored == selected) return true
        val selectedInfo = MasterData.COMMUTE_WINDOWS.firstOrNull { it.name == selected || it.timeSlot == selected }
        val storedInfo = MasterData.COMMUTE_WINDOWS.firstOrNull { it.name == stored || it.timeSlot == stored }
        return selectedInfo != null && storedInfo != null && selectedInfo.name == storedInfo.name
    }

    private fun logFirestoreError(opName: String, path: String, uid: String, error: Throwable) {
        if (BuildConfig.DEBUG && error is FirebaseFirestoreException) {
            Log.e(
                "FirestoreDebug",
                "Op=$opName | Path=$path | UID=$uid | Code=${error.code} | Msg=${error.message}",
                error
            )
        }
    }
}
