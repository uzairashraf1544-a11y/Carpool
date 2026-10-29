package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.TravelBuddyApplication
import com.example.data.model.Co2Record
import com.example.data.model.MasterData
import com.example.data.model.PassengerEligibility
import com.example.data.model.PassengerNeed
import com.example.data.model.PickupSide
import com.example.data.model.Ride
import com.example.data.model.RideRequest
import com.example.data.model.User
import com.example.util.TravelBuddyNotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RideRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        const val TAG = "RideRepoDebug"
        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_DID_NOT_TRAVEL = "DID_NOT_TRAVEL"
        const val DISCOVERY_LIMIT = 20L
        const val DEFAULT_EXPIRY_MILLIS = 18 * 3600 * 1000L // 18 hours validity
    }

    // ----------------------------------------------------
    // DRIVER AVAILABILITY & ACTIVE STATE
    // ----------------------------------------------------

    suspend fun createOrUpdateDriverAvailability(
        user: User,
        date: String,
        pickupZone: String,
        totalSeats: Int,
        message: String = "",
        customPickupLocation: String = "",
        commuteSlot: Any? = null,
        destinationZone: String = "",
        pickupDetails: String = ""
    ): Result<Ride> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        if (!user.isActive()) {
            return Result.failure(IllegalStateException("Your account is currently restricted. Please contact the administrator."))
        }

        return runCatching {
            val cleanPickup = pickupZone.trim().ifBlank { "EMs" }
            val side = MasterData.getSideForPickup(cleanPickup).name
            val cleanCustomPickup = customPickupLocation.trim().take(150)
            if (side == "OTHER") {
                require(cleanCustomPickup.isNotBlank()) {
                    "Please enter your pickup location."
                }
            }
            val totalSeatsClamped = totalSeats.coerceIn(1, 8)
            val now = System.currentTimeMillis()
            val expiresAt = now + DEFAULT_EXPIRY_MILLIS

            val existingQuery = db.collection("rides")
                .whereEqualTo("driverId", currentUid)
                .whereEqualTo("date", date)
                .get()
                .await()

            val existingActive = existingQuery.documents
                .mapNotNull { it.toObject(Ride::class.java) }
                .firstOrNull { ride ->
                    ride.status in listOf(
                        Ride.STATUS_AVAILABLE,
                        Ride.STATUS_PARTIALLY_FULL,
                        Ride.STATUS_FULL,
                        Ride.STATUS_RIDE_STARTED
                    )
                }

            val rideId = existingActive?.rideId ?: "${currentUid}_${date}_$now"
            val rideRef = db.collection("rides").document(rideId)

            val acceptedPassengers = existingActive?.acceptedPassengerIds ?: emptyList()
            val availableSeats = (totalSeatsClamped - acceptedPassengers.size).coerceAtLeast(0)
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
                pickupZone = if (side == "OTHER") "OTHER" else cleanPickup,
                pickupSide = side,
                customPickupLocation = if (side == "OTHER") cleanCustomPickup else "",
                totalSeats = totalSeatsClamped,
                availableSeats = availableSeats,
                acceptedPassengerIds = acceptedPassengers,
                completedPassengerIds = existingActive?.completedPassengerIds ?: emptyList(),
                message = message.take(250).trim(),
                status = status,
                expiresAt = expiresAt,
                creationSource = "APP",
                createdAt = existingActive?.createdAt ?: now,
                updatedAt = now,
                startedAt = existingActive?.startedAt ?: 0L,
                completedAt = existingActive?.completedAt ?: 0L,
                // Legacy fields for backward compatibility
                commuteWindow = "Any",
                direction = if (side == "TOWNSHIP") "Township → Plant" else "Plant → Township",
                destinationZone = if (side == "TOWNSHIP") "FFL Plant Main Gate" else "EMs"
            )

            val batch = db.batch()
            batch.set(rideRef, ride)
            val userRef = db.collection("users").document(currentUid)
            batch.update(userRef, mapOf("currentMode" to User.MODE_DRIVER, "updatedAt" to now))
            batch.commit().await()
            Log.d("ModeSwitchDebug", "MODE_SERVER_COMMIT | reason=DRIVER_PUBLISH | mode=DRIVER")
            Log.d(TAG, "DRIVER_POST_CREATED | rideId=$rideId | driverId=$currentUid | date=$date | pickupZone=${ride.pickupZone} | pickupSide=$side | customPickup=$cleanCustomPickup | status=$status | expiresAt=$expiresAt")
            ride
        }.onFailure { error ->
            logFirestoreError("createOrUpdateDriverAvailability", "rides", currentUid, error)
        }
    }

    // Overload for legacy callers
    suspend fun createOrUpdateDriverAvailability(
        user: User,
        date: String,
        commuteWindow: String,
        direction: String,
        pickupZone: String,
        destinationZone: String,
        totalSeats: Int,
        pickupDetails: String = "",
        message: String = "",
        commuteSlot: Any? = null
    ): Result<Ride> {
        return createOrUpdateDriverAvailability(
            user = user,
            date = date,
            pickupZone = pickupZone,
            totalSeats = totalSeats,
            message = message
        )
    }

    suspend fun hasActiveDriverRide(driverId: String, date: String): Boolean {
        return try {
            val snapshot = db.collection("rides")
                .whereEqualTo("driverId", driverId)
                .whereEqualTo("date", date)
                .get()
                .await()
            snapshot.documents.mapNotNull { it.toObject(Ride::class.java) }
                .any { ride ->
                    ride.status in listOf(
                        Ride.STATUS_AVAILABLE,
                        Ride.STATUS_PARTIALLY_FULL,
                        Ride.STATUS_FULL,
                        Ride.STATUS_RIDE_STARTED
                    )
                }
        } catch (e: Exception) {
            false
        }
    }

    fun getDriverTodayRideFlow(driverId: String, date: String): Flow<Ride?> = callbackFlow {
        val listener = db.collection("rides")
            .whereEqualTo("driverId", driverId)
            .whereEqualTo("date", date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getDriverTodayRideFlow", "rides", driverId, error)
                    trySend(null)
                    return@addSnapshotListener
                }
                val rides = snapshot?.documents?.mapNotNull { it.toObject(Ride::class.java) }.orEmpty()

                // Auto-close check: when all accepted passengers have completed
                val runningRide = rides.firstOrNull {
                    it.status in listOf(Ride.STATUS_PARTIALLY_FULL, Ride.STATUS_FULL, Ride.STATUS_RIDE_STARTED)
                }
                if (runningRide != null && runningRide.acceptedPassengerIds.isNotEmpty() &&
                    runningRide.completedPassengerIds.containsAll(runningRide.acceptedPassengerIds)
                ) {
                    db.collection("rides").document(runningRide.rideId).update(
                        mapOf(
                            "status" to Ride.STATUS_COMPLETED,
                            "completedAt" to System.currentTimeMillis(),
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                }

                val activeRide = rides
                    .filter { ride ->
                        ride.status in listOf(
                            Ride.STATUS_AVAILABLE,
                            Ride.STATUS_PARTIALLY_FULL,
                            Ride.STATUS_FULL,
                            Ride.STATUS_RIDE_STARTED
                        )
                    }
                    .maxByOrNull { it.createdAt }
                trySend(activeRide)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    // ----------------------------------------------------
    // PASSENGER DEMAND (PASSENGER NEED) ACTIONS
    // ----------------------------------------------------

    suspend fun createPassengerNeed(
        user: User,
        date: String,
        pickupZone: String,
        message: String = "",
        customPickupLocation: String = "",
        commuteSlot: Any? = null,
        destinationZone: String = "",
        pickupDetails: String = ""
    ): Result<PassengerNeed> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        if (!user.isActive()) {
            return Result.failure(IllegalStateException("Your account is currently restricted. Please contact the administrator."))
        }

        return runCatching {
            // Exclusivity Check 1: Point check on activePassengerRides lock
            val activeLockDoc = db.collection("activePassengerRides").document(currentUid).get().await()
            if (activeLockDoc.exists()) {
                val existingRideId = activeLockDoc.getString("rideId").orEmpty()
                val existingReqId = activeLockDoc.getString("requestId").orEmpty()
                Log.w(
                    "PassengerStateDebug",
                    "DUPLICATE_PASSENGER_ACTION_BLOCKED | action=CREATE_NEED | uid=$currentUid | existingRideId=$existingRideId | existingRequestId=$existingReqId"
                )
                error("You already have an active ride.")
            }

            // Exclusivity Check 2: Active RideRequest check
            val activeReqs = db.collection("rideRequests")
                .whereEqualTo("passengerId", currentUid)
                .whereIn("status", listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED"))
                .limit(1)
                .get()
                .await()
            if (!activeReqs.isEmpty) {
                val existingReq = activeReqs.documents.first()
                Log.w(
                    "PassengerStateDebug",
                    "DUPLICATE_PASSENGER_ACTION_BLOCKED | action=CREATE_NEED | uid=$currentUid | existingRideId=${existingReq.getString("rideId")} | existingRequestId=${existingReq.id}"
                )
                error("You already have an active ride.")
            }

            // Exclusivity Check 3: Check for open LOOKING demand today
            val activeQuery = db.collection("passengerNeeds")
                .whereEqualTo("passengerId", currentUid)
                .whereEqualTo("date", date)
                .get()
                .await()

            val existingLooking = activeQuery.documents
                .mapNotNull { it.toObject(PassengerNeed::class.java) }
                .firstOrNull { it.status == PassengerNeed.STATUS_LOOKING }

            if (existingLooking != null) {
                Log.w(
                    "PassengerStateDebug",
                    "DUPLICATE_PASSENGER_ACTION_BLOCKED | action=CREATE_NEED | uid=$currentUid | existingNeedId=${existingLooking.needId}"
                )
                error("You already have an active ride request.")
            }

            val cleanPickup = pickupZone.trim().ifBlank { "EMs" }
            val side = MasterData.getSideForPickup(cleanPickup).name
            val cleanCustomPickup = customPickupLocation.trim().take(150)
            if (side == "OTHER") {
                require(cleanCustomPickup.isNotBlank()) {
                    "Please enter your pickup location."
                }
            }
            val now = System.currentTimeMillis()
            val expiresAt = now + DEFAULT_EXPIRY_MILLIS

            val needId = "${currentUid}_${date}_$now"
            val needRef = db.collection("passengerNeeds").document(needId)
            val status = PassengerNeed.STATUS_LOOKING

            val need = PassengerNeed(
                needId = needId,
                passengerId = currentUid,
                passengerName = user.name,
                passengerDepartment = user.department,
                passengerPhone = user.phone,
                date = date,
                pickupZone = if (side == "OTHER") "OTHER" else cleanPickup,
                pickupSide = side,
                customPickupLocation = if (side == "OTHER") cleanCustomPickup else "",
                message = message.take(250).trim(),
                status = status,
                expiresAt = expiresAt,
                createdAt = now,
                updatedAt = now,
                matchedRideId = "",
                matchedDriverId = "",
                requestedRideId = "",
                // Legacy fields for backward compatibility
                commuteWindow = "Any",
                direction = if (side == "TOWNSHIP") "Township → Plant" else "Plant → Township",
                destinationZone = if (side == "TOWNSHIP") "FFL Plant Main Gate" else "EMs"
            )

            val batch = db.batch()
            batch.set(needRef, need)
            val userRef = db.collection("users").document(currentUid)
            batch.update(userRef, mapOf("currentMode" to User.MODE_PASSENGER, "updatedAt" to now))
            batch.commit().await()
            Log.d("ModeSwitchDebug", "MODE_SERVER_COMMIT | reason=PASSENGER_POST | mode=PASSENGER")
            Log.d(TAG, "PASSENGER_NEED_CREATED | needId=$needId | passengerId=$currentUid | date=$date | pickupZone=${need.pickupZone} | pickupSide=$side | customPickup=$cleanCustomPickup | status=$status | expiresAt=$expiresAt")
            need
        }.onFailure { error ->
            logFirestoreError("createPassengerNeed", "passengerNeeds", currentUid, error)
        }
    }

    // Overload for legacy callers
    suspend fun createPassengerNeed(
        user: User,
        date: String,
        direction: String,
        commuteWindow: String,
        pickupZone: String,
        destinationZone: String = "",
        pickupDetails: String = "",
        message: String = "",
        commuteSlot: Any? = null
    ): Result<PassengerNeed> {
        return createPassengerNeed(
            user = user,
            date = date,
            pickupZone = pickupZone,
            message = message
        )
    }

    suspend fun cancelPassengerNeed(needId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        return runCatching {
            val ref = db.collection("passengerNeeds").document(needId)
            val doc = ref.get().await()
            if (!doc.exists()) error("Passenger request not found.")
            if (doc.getString("passengerId") != currentUid) {
                throw SecurityException("You can only cancel your own ride request.")
            }
            ref.update(
                mapOf(
                    "status" to PassengerNeed.STATUS_CANCELLED,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
            Unit
        }.onFailure { error ->
            logFirestoreError("cancelPassengerNeed", "passengerNeeds/$needId", currentUid, error)
        }
    }

    fun getPassengerNeedFlow(passengerId: String, date: String): Flow<PassengerNeed?> = callbackFlow {
        val listener = db.collection("passengerNeeds")
            .whereEqualTo("passengerId", passengerId)
            .whereEqualTo("date", date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getPassengerNeedFlow", "passengerNeeds", passengerId, error)
                    trySend(null)
                    return@addSnapshotListener
                }
                val active = snapshot?.documents
                    ?.mapNotNull { it.toObject(PassengerNeed::class.java) }
                    ?.filter { it.status == PassengerNeed.STATUS_LOOKING }
                    ?.maxByOrNull { it.updatedAt }
                trySend(active)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getActivePassengerRideFlow(passengerId: String): Flow<RideRequest?> = callbackFlow {
        val listener = db.collection("rideRequests")
            .whereEqualTo("passengerId", passengerId)
            .whereIn("status", listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED"))
            .limit(5)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logFirestoreError("getActivePassengerRideFlow", "rideRequests", passengerId, error)
                    trySend(null)
                    return@addSnapshotListener
                }
                val activeDocs = snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) } ?: emptyList()
                if (activeDocs.size > 1) {
                    Log.w("PassengerStateDebug", "MULTIPLE_ACTIVE_RIDE_REQUESTS | uid=$passengerId | count=${activeDocs.size}")
                }
                val authoritative = activeDocs.maxByOrNull { it.requestedAt }
                trySend(authoritative)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getPassengerEligibilityFlow(passengerId: String, date: String): Flow<PassengerEligibility> {
        return combine(
            getActivePassengerRideFlow(passengerId),
            getPassengerNeedFlow(passengerId, date)
        ) { activeRideReq, lookingNeed ->
            val eligibility = when {
                activeRideReq != null -> PassengerEligibility.ActiveRide(activeRideReq)
                lookingNeed != null -> PassengerEligibility.LookingForRide(lookingNeed)
                else -> PassengerEligibility.CanCreateRequest
            }
            Log.d(
                "PassengerStateDebug",
                "ELIGIBILITY_UPDATE | uid=$passengerId | activeRide=${activeRideReq?.requestId} | lookingNeed=${lookingNeed?.needId} | state=${eligibility::class.simpleName}"
            )
            eligibility
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    // ----------------------------------------------------
    // DISCOVERY QUERIES (PURE PICKUP SIDE + OTHER WILDCARD - SPARK OPTIMIZED)
    // ----------------------------------------------------

    /**
     * PASSENGER DISCOVERS DRIVERS
     * - TOWNSHIP viewer: TOWNSHIP + OTHER
     * - PLANT viewer: PLANT + OTHER
     * - OTHER viewer: All active same-day driver rides
     */
    fun getAvailableDriversFlow(
        date: String,
        pickupSide: String
    ): Flow<List<Ride>> = callbackFlow {
        val currentUid = auth.currentUser?.uid.orEmpty()
        val normalizedSide = MasterData.getSideForPickup(pickupSide).name

        val list1 = mutableListOf<Ride>()
        val list2 = mutableListOf<Ride>()

        fun emitCombined() {
            val now = System.currentTimeMillis()
            val combined = (list1 + list2)
                .distinctBy { it.rideId }
                .filter { ride ->
                    ride.driverId != currentUid &&
                        ride.availableSeats > 0 &&
                        (ride.expiresAt == 0L || ride.expiresAt > now)
                }
            Log.d(TAG, "DRIVER_DISCOVERY | date=$date | pickupSide=$normalizedSide | resultCount=${combined.size}")
            trySend(combined)
        }

        if (normalizedSide == "OTHER") {
            val reg = db.collection("rides")
                .whereEqualTo("date", date)
                .whereIn("status", listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL))
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getAvailableDriversFlow_OTHER", "rides", currentUid, error)
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    list1.clear()
                    snapshot?.documents?.mapNotNullTo(list1) { it.toObject(Ride::class.java) }
                    emitCombined()
                }
            awaitClose { reg.remove() }
        } else {
            val reg1 = db.collection("rides")
                .whereEqualTo("date", date)
                .whereEqualTo("pickupSide", normalizedSide)
                .whereIn("status", listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL))
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getAvailableDriversFlow_side", "rides", currentUid, error)
                        return@addSnapshotListener
                    }
                    list1.clear()
                    snapshot?.documents?.mapNotNullTo(list1) { it.toObject(Ride::class.java) }
                    emitCombined()
                }

            val reg2 = db.collection("rides")
                .whereEqualTo("date", date)
                .whereEqualTo("pickupSide", "OTHER")
                .whereIn("status", listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL))
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getAvailableDriversFlow_otherWildcard", "rides", currentUid, error)
                        return@addSnapshotListener
                    }
                    list2.clear()
                    snapshot?.documents?.mapNotNullTo(list2) { it.toObject(Ride::class.java) }
                    emitCombined()
                }

            awaitClose {
                reg1.remove()
                reg2.remove()
            }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    // Legacy overload mapping direction/window to side
    fun getAvailableDriversFlow(
        date: String,
        directionFilter: String,
        windowFilter: String
    ): Flow<List<Ride>> {
        val side = if (directionFilter == "Plant → Township") "PLANT" else "TOWNSHIP"
        return getAvailableDriversFlow(date, side)
    }

    /**
     * DRIVER DISCOVERS PASSENGERS LOOKING FOR A RIDE
     * - TOWNSHIP viewer: TOWNSHIP + OTHER
     * - PLANT viewer: PLANT + OTHER
     * - OTHER viewer: All active same-day passenger needs
     * Driver does NOT need an existing active ride!
     */
    fun getMatchingPassengerNeedsFlow(
        date: String,
        pickupSide: String
    ): Flow<List<PassengerNeed>> = callbackFlow {
        val currentUid = auth.currentUser?.uid.orEmpty()
        val normalizedSide = MasterData.getSideForPickup(pickupSide).name

        val list1 = mutableListOf<PassengerNeed>()
        val list2 = mutableListOf<PassengerNeed>()

        fun emitCombined() {
            val now = System.currentTimeMillis()
            val combined = (list1 + list2)
                .distinctBy { it.needId }
                .filter { need ->
                    need.passengerId != currentUid &&
                        (need.expiresAt == 0L || need.expiresAt > now)
                }
            Log.d(TAG, "PASSENGER_DISCOVERY | date=$date | pickupSide=$normalizedSide | resultCount=${combined.size}")
            trySend(combined)
        }

        if (normalizedSide == "OTHER") {
            val reg = db.collection("passengerNeeds")
                .whereEqualTo("date", date)
                .whereEqualTo("status", PassengerNeed.STATUS_LOOKING)
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getMatchingPassengerNeedsFlow_OTHER", "passengerNeeds", currentUid, error)
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    list1.clear()
                    snapshot?.documents?.mapNotNullTo(list1) { it.toObject(PassengerNeed::class.java) }
                    emitCombined()
                }
            awaitClose { reg.remove() }
        } else {
            val reg1 = db.collection("passengerNeeds")
                .whereEqualTo("date", date)
                .whereEqualTo("pickupSide", normalizedSide)
                .whereEqualTo("status", PassengerNeed.STATUS_LOOKING)
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getMatchingPassengerNeedsFlow_side", "passengerNeeds", currentUid, error)
                        return@addSnapshotListener
                    }
                    list1.clear()
                    snapshot?.documents?.mapNotNullTo(list1) { it.toObject(PassengerNeed::class.java) }
                    emitCombined()
                }

            val reg2 = db.collection("passengerNeeds")
                .whereEqualTo("date", date)
                .whereEqualTo("pickupSide", "OTHER")
                .whereEqualTo("status", PassengerNeed.STATUS_LOOKING)
                .limit(DISCOVERY_LIMIT)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getMatchingPassengerNeedsFlow_otherWildcard", "passengerNeeds", currentUid, error)
                        return@addSnapshotListener
                    }
                    list2.clear()
                    snapshot?.documents?.mapNotNullTo(list2) { it.toObject(PassengerNeed::class.java) }
                    emitCombined()
                }

            awaitClose {
                reg1.remove()
                reg2.remove()
            }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    // Legacy overload
    fun getMatchingPassengerNeedsFlow(
        date: String,
        directionFilter: String,
        windowFilter: String
    ): Flow<List<PassengerNeed>> {
        val side = if (directionFilter == "Plant → Township") "PLANT" else "TOWNSHIP"
        return getMatchingPassengerNeedsFlow(date, side)
    }

    // ----------------------------------------------------
    // DIRECT ACCEPT & JOIN RIDE (ONE-TAP CONFIRMATION)
    // ----------------------------------------------------

    /**
     * Driver accepts a PassengerNeed directly (even without an existing Ride).
     */
    suspend fun acceptPassengerNeedDirectly(
        driverUser: User,
        need: PassengerNeed
    ): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        if (!driverUser.isActive()) {
            return Result.failure(IllegalStateException("Your account is currently restricted. Please contact the administrator."))
        }

        if (need.passengerId == currentUid) {
            return Result.failure(IllegalArgumentException("You cannot accept your own ride request."))
        }

        return runCatching {
            val now = System.currentTimeMillis()
            val needRef = db.collection("passengerNeeds").document(need.needId)

            val existingRidesSnapshot = db.collection("rides")
                .whereEqualTo("driverId", currentUid)
                .whereEqualTo("date", need.date)
                .get()
                .await()

            val existingActiveRide = existingRidesSnapshot.documents
                .mapNotNull { it.toObject(Ride::class.java) }
                .firstOrNull { it.status in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL) && it.availableSeats > 0 }

            val rideId = existingActiveRide?.rideId ?: "${currentUid}_${need.date}_$now"
            val rideRef = db.collection("rides").document(rideId)

            db.runTransaction { tx ->
                val needDoc = tx.get(needRef)
                if (!needDoc.exists()) error("Ride request not found.")
                val currentNeedStatus = needDoc.getString("status")
                if (currentNeedStatus != PassengerNeed.STATUS_LOOKING) {
                    error("This ride request is no longer available.")
                }

                // Exclusivity Check: Check activePassengerRides lock
                val activeLockRef = db.collection("activePassengerRides").document(need.passengerId)
                val activeLockDoc = tx.get(activeLockRef)
                if (activeLockDoc.exists()) {
                    error("This passenger already has an active ride.")
                }

                val currentSeats = existingActiveRide?.totalSeats ?: driverUser.defaultDriverSeats.coerceIn(1, 8)
                val currentAccepted = (existingActiveRide?.acceptedPassengerIds ?: emptyList())
                if (currentAccepted.contains(need.passengerId)) {
                    error("Passenger already joined this ride.")
                }
                val newAccepted = currentAccepted + need.passengerId
                val newAvailableSeats = (currentSeats - newAccepted.size).coerceAtLeast(0)
                val newRideStatus = if (newAvailableSeats == 0) Ride.STATUS_FULL else Ride.STATUS_PARTIALLY_FULL

                val pickupZone = existingActiveRide?.pickupZone ?: need.pickupZone
                val pickupSide = need.pickupSide
                val customPickup = need.customPickupLocation

                val updatedRide = Ride(
                    rideId = rideId,
                    driverId = currentUid,
                    driverName = driverUser.name,
                    driverPhone = driverUser.phone,
                    driverDepartment = driverUser.department,
                    date = need.date,
                    pickupZone = pickupZone,
                    pickupSide = pickupSide,
                    customPickupLocation = customPickup,
                    totalSeats = currentSeats,
                    availableSeats = newAvailableSeats,
                    acceptedPassengerIds = newAccepted,
                    completedPassengerIds = existingActiveRide?.completedPassengerIds ?: emptyList(),
                    message = existingActiveRide?.message.orEmpty(),
                    status = newRideStatus,
                    expiresAt = existingActiveRide?.expiresAt ?: (now + DEFAULT_EXPIRY_MILLIS),
                    creationSource = "DIRECT_ACCEPT",
                    createdAt = existingActiveRide?.createdAt ?: now,
                    updatedAt = now
                )

                tx.set(rideRef, updatedRide)

                tx.update(
                    needRef,
                    mapOf(
                        "status" to PassengerNeed.STATUS_MATCHED,
                        "matchedDriverId" to currentUid,
                        "matchedRideId" to rideId,
                        "matchedAt" to now,
                        "updatedAt" to now
                    )
                )

                val requestId = "${rideId}_${need.passengerId}"
                val reqRef = db.collection("rideRequests").document(requestId)
                val rideRequest = RideRequest(
                    requestId = requestId,
                    rideId = rideId,
                    needId = need.needId,
                    driverId = currentUid,
                    driverName = driverUser.name,
                    driverDepartment = driverUser.department,
                    driverPhone = driverUser.phone,
                    passengerId = need.passengerId,
                    passengerName = need.passengerName,
                    passengerDepartment = need.passengerDepartment,
                    passengerPhone = need.passengerPhone,
                    date = need.date,
                    commuteWindow = need.commuteWindow,
                    direction = need.direction,
                    pickupZone = need.pickupZone,
                    customPickupLocation = customPickup,
                    destinationZone = need.destinationZone,
                    pickupDetails = need.pickupDetails,
                    message = need.message,
                    status = RideRequest.STATUS_ACCEPTED,
                    requestedAt = now,
                    responseAt = now
                )
                tx.set(reqRef, rideRequest)

                // Set activePassengerRides exclusivity lock
                tx.set(
                    activeLockRef,
                    mapOf(
                        "rideId" to rideId,
                        "requestId" to requestId,
                        "passengerId" to need.passengerId,
                        "driverId" to currentUid,
                        "createdAt" to now
                    )
                )

                // Synchronize driver's server currentMode to DRIVER
                val driverUserRef = db.collection("users").document(currentUid)
                tx.update(driverUserRef, mapOf("currentMode" to User.MODE_DRIVER, "updatedAt" to now))
            }.await()

            Log.d("ModeSwitchDebug", "MODE_SERVER_COMMIT | reason=DRIVER_ACCEPT | mode=DRIVER")
            Unit
        }.onFailure { error ->
            logFirestoreError("acceptPassengerNeedDirectly", "rides/$currentUid", currentUid, error)
        }
    }

    /**
     * Passenger joins a Driver's available ride.
     */
    suspend fun joinRide(
        passengerUser: User,
        ride: Ride,
        message: String = ""
    ): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        if (!passengerUser.isActive()) {
            return Result.failure(IllegalStateException("Your account is currently restricted. Please contact the administrator."))
        }

        if (ride.driverId == currentUid) {
            return Result.failure(IllegalArgumentException("You cannot join your own ride."))
        }

        return runCatching {
            val now = System.currentTimeMillis()
            val rideRef = db.collection("rides").document(ride.rideId)

            db.runTransaction { tx ->
                val rideDoc = tx.get(rideRef)
                if (!rideDoc.exists()) error("Ride no longer exists.")

                // Exclusivity Check 1: Check activePassengerRides lock
                val activeLockRef = db.collection("activePassengerRides").document(currentUid)
                val activeLockDoc = tx.get(activeLockRef)
                if (activeLockDoc.exists()) {
                    val existingRideId = activeLockDoc.getString("rideId").orEmpty()
                    Log.w(
                        "PassengerStateDebug",
                        "DUPLICATE_PASSENGER_ACTION_BLOCKED | action=JOIN_RIDE | uid=$currentUid | existingRideId=$existingRideId"
                    )
                    error("You already have an active ride.")
                }

                val status = rideDoc.getString("status") ?: Ride.STATUS_AVAILABLE
                val availableSeats = rideDoc.getLong("availableSeats")?.toInt() ?: 0
                val totalSeats = rideDoc.getLong("totalSeats")?.toInt() ?: 3
                val acceptedList = (rideDoc.get("acceptedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                if (status !in listOf(Ride.STATUS_AVAILABLE, Ride.STATUS_PARTIALLY_FULL) || availableSeats <= 0) {
                    error("This ride is already full.")
                }

                if (acceptedList.contains(currentUid)) {
                    error("You have already joined this ride.")
                }

                val newAccepted = acceptedList + currentUid
                val newAvailableSeats = (totalSeats - newAccepted.size).coerceAtLeast(0)
                val newStatus = if (newAvailableSeats == 0) Ride.STATUS_FULL else Ride.STATUS_PARTIALLY_FULL

                tx.update(
                    rideRef,
                    mapOf(
                        "acceptedPassengerIds" to newAccepted,
                        "availableSeats" to newAvailableSeats,
                        "status" to newStatus,
                        "updatedAt" to now
                    )
                )

                val requestId = "${ride.rideId}_$currentUid"
                val reqRef = db.collection("rideRequests").document(requestId)
                val rideRequest = RideRequest(
                    requestId = requestId,
                    rideId = ride.rideId,
                    driverId = ride.driverId,
                    driverName = ride.driverName,
                    driverDepartment = ride.driverDepartment,
                    driverPhone = ride.driverPhone,
                    passengerId = currentUid,
                    passengerName = passengerUser.name,
                    passengerDepartment = passengerUser.department,
                    passengerPhone = passengerUser.phone,
                    date = ride.date,
                    commuteWindow = ride.commuteWindow,
                    direction = ride.direction,
                    pickupZone = ride.pickupZone,
                    customPickupLocation = ride.customPickupLocation,
                    destinationZone = ride.destinationZone,
                    pickupDetails = ride.pickupDetails,
                    message = message.take(250).trim(),
                    status = RideRequest.STATUS_ACCEPTED,
                    requestedAt = now,
                    responseAt = now
                )
                tx.set(reqRef, rideRequest)

                // Set activePassengerRides exclusivity lock
                tx.set(
                    activeLockRef,
                    mapOf(
                        "rideId" to ride.rideId,
                        "requestId" to requestId,
                        "passengerId" to currentUid,
                        "driverId" to ride.driverId,
                        "createdAt" to now
                    )
                )

                // Synchronize passenger's server currentMode to PASSENGER
                val passengerUserRef = db.collection("users").document(currentUid)
                tx.update(passengerUserRef, mapOf("currentMode" to User.MODE_PASSENGER, "updatedAt" to now))
            }.await()

            Log.d("ModeSwitchDebug", "MODE_SERVER_COMMIT | reason=PASSENGER_JOIN | mode=PASSENGER")

            // Close any active PassengerNeed for this user today
            try {
                val needSnapshot = db.collection("passengerNeeds")
                    .whereEqualTo("passengerId", currentUid)
                    .whereEqualTo("date", ride.date)
                    .get()
                    .await()
                needSnapshot.documents.forEach { doc ->
                    if (doc.getString("status") == PassengerNeed.STATUS_LOOKING) {
                        doc.reference.update(
                            mapOf(
                                "status" to PassengerNeed.STATUS_MATCHED,
                                "matchedDriverId" to ride.driverId,
                                "matchedRideId" to ride.rideId,
                                "matchedAt" to now,
                                "updatedAt" to now
                            )
                        )
                    }
                }
            } catch (_: Exception) {}

            Unit
        }.onFailure { error ->
            logFirestoreError("joinRide", "rides/${ride.rideId}", currentUid, error)
        }
    }

    // ----------------------------------------------------
    // RIDE CLOSURE (DRIVER MASTER CLOSE & PASSENGER SELF-CLOSE)
    // ----------------------------------------------------

    suspend fun closeRideAsDriver(rideId: String, outcome: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        val projectId = try { com.google.firebase.FirebaseApp.getInstance().options.projectId } catch (_: Exception) { "unknown" }
        Log.d("RideCloseDebug", "DRIVER_CLOSE_START | projectId=$projectId | driverUid=$currentUid | rideId=$rideId | outcome=$outcome")

        return runCatching {
            val now = System.currentTimeMillis()
            val rideRef = db.collection("rides").document(rideId)
            var driverNameSaved = ""
            var driverDeptSaved = ""
            var passengerCountSaved = 0
            var completedPassengerIdsSaved = emptyList<String>()

            db.runTransaction { tx ->
                val rDoc = tx.get(rideRef)
                if (!rDoc.exists()) error("Ride not found.")
                if (rDoc.getString("driverId") != currentUid) {
                    throw SecurityException("You can only close your own ride.")
                }

                val currentStatus = rDoc.getString("status") ?: Ride.STATUS_AVAILABLE
                if (currentStatus in listOf(Ride.STATUS_COMPLETED, Ride.STATUS_CANCELLED, Ride.STATUS_OFFLINE)) {
                    Log.d("RideCloseDebug", "DRIVER_CLOSE_ALREADY_CLOSED | rideId=$rideId | status=$currentStatus")
                    return@runTransaction
                }

                driverNameSaved = rDoc.getString("driverName").orEmpty()
                driverDeptSaved = rDoc.getString("driverDepartment").orEmpty()

                val acceptedPassengers = (rDoc.get("acceptedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val currentCompleted = (rDoc.get("completedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                // 1. READ ALL REQUESTS AND CORRESPONDING NEEDS
                val reqDocs = acceptedPassengers.associateWith { pUid ->
                    val reqRef = db.collection("rideRequests").document("${rideId}_$pUid")
                    reqRef to tx.get(reqRef)
                }

                val needDocs = reqDocs.values.mapNotNull { (_, reqDoc) ->
                    val needId = reqDoc.getString("needId")
                    if (!needId.isNullOrBlank()) {
                        val needRef = db.collection("passengerNeeds").document(needId)
                        needId to (needRef to tx.get(needRef))
                    } else null
                }.toMap()

                Log.d(
                    "RideCloseDebug",
                    "DRIVER_READ_COMPLETE | acceptedCount=${acceptedPassengers.size} | reqDocCount=${reqDocs.size} | needDocCount=${needDocs.size}"
                )

                // 2. CALCULATE FINAL STATES
                var confirmedCount = 0
                var finalCount = 0

                acceptedPassengers.forEach { pUid ->
                    val (reqRef, reqDoc) = reqDocs[pUid] ?: return@forEach
                    val curReqStatus = reqDoc.getString("status") ?: ""

                    if (outcome == OUTCOME_COMPLETED) {
                        // RIDE HAPPENED
                        if (curReqStatus == RideRequest.STATUS_COMPLETED) {
                            confirmedCount++
                            finalCount++
                            // Passenger already confirmed completion: preserve!
                        } else if (curReqStatus in listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED", RideRequest.STATUS_REQUESTED)) {
                            finalCount++
                            // Unconfirmed active passenger becomes CLOSED_UNCONFIRMED
                            tx.update(
                                reqRef,
                                mapOf(
                                    "status" to RideRequest.STATUS_CLOSED_UNCONFIRMED,
                                    "closedAt" to now,
                                    "responseAt" to now,
                                    "updatedAt" to now
                                )
                            )
                            // Terminalize unconfirmed PassengerNeed to CANCELLED (zero credit)
                            val needId = reqDoc.getString("needId")
                            if (!needId.isNullOrBlank()) {
                                needDocs[needId]?.let { (needRef, needDoc) ->
                                    if (needDoc.exists() && needDoc.getString("status") == PassengerNeed.STATUS_MATCHED) {
                                        tx.update(
                                            needRef,
                                            mapOf(
                                                "status" to PassengerNeed.STATUS_CANCELLED,
                                                "cancelledAt" to now,
                                                "updatedAt" to now
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (curReqStatus == RideRequest.STATUS_CLOSED_UNCONFIRMED) {
                            finalCount++
                        }
                    } else {
                        // RIDE DIDN'T HAPPEN
                        if (curReqStatus in listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED", RideRequest.STATUS_REQUESTED)) {
                            tx.update(
                                reqRef,
                                mapOf(
                                    "status" to RideRequest.STATUS_CANCELLED_BY_DRIVER,
                                    "cancelledAt" to now,
                                    "responseAt" to now,
                                    "updatedAt" to now
                                )
                            )
                            val needId = reqDoc.getString("needId")
                            if (!needId.isNullOrBlank()) {
                                needDocs[needId]?.let { (needRef, needDoc) ->
                                    if (needDoc.exists() && needDoc.getString("status") == PassengerNeed.STATUS_MATCHED) {
                                        tx.update(
                                            needRef,
                                            mapOf(
                                                "status" to PassengerNeed.STATUS_CANCELLED,
                                                "cancelledAt" to now,
                                                "updatedAt" to now
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Release activePassengerRides exclusivity lock for this passenger
                    val pLockRef = db.collection("activePassengerRides").document(pUid)
                    tx.delete(pLockRef)
                }

                val targetRideStatus = if (outcome == OUTCOME_COMPLETED) Ride.STATUS_COMPLETED else Ride.STATUS_CANCELLED
                val rideUpdates = mutableMapOf<String, Any>(
                    "status" to targetRideStatus,
                    "completedPassengerIds" to if (outcome == OUTCOME_COMPLETED) currentCompleted else emptyList<String>(),
                    "finalPassengerCount" to if (outcome == OUTCOME_COMPLETED) finalCount else 0,
                    "confirmedPassengerCount" to if (outcome == OUTCOME_COMPLETED) confirmedCount else 0,
                    "updatedAt" to now
                )
                if (targetRideStatus == Ride.STATUS_COMPLETED) {
                    rideUpdates["completedAt"] = now
                }

                if (outcome == OUTCOME_COMPLETED) {
                    passengerCountSaved = finalCount
                    completedPassengerIdsSaved = currentCompleted
                }

                Log.d(
                    "RideCloseDebug",
                    "DRIVER_WRITING_RIDE | rideId=$rideId | status=$targetRideStatus | finalPassengerCount=${rideUpdates["finalPassengerCount"]} | confirmedPassengerCount=${rideUpdates["confirmedPassengerCount"]}"
                )
                tx.update(rideRef, rideUpdates)
            }.await()

            // Automatically log backend CO2 savings and update calculated community leaderboard
            if (outcome == OUTCOME_COMPLETED && passengerCountSaved > 0) {
                runCatching {
                    logCo2SavingsForCompletedRide(
                        rideId = rideId,
                        driverId = currentUid,
                        driverName = driverNameSaved,
                        driverDepartment = driverDeptSaved,
                        passengerCount = passengerCountSaved,
                        completedPassengerIds = completedPassengerIdsSaved
                    )
                }
            }

            // Cancel notifications
            TravelBuddyNotificationManager.cancelRideNotifications(
                context = TravelBuddyApplication.appContext,
                rideId = rideId
            )
            Log.d("RideCloseDebug", "COMMIT_SUCCESS | Driver master close finished for rideId=$rideId | outcome=$outcome")
            Unit
        }.onFailure { error ->
            if (error is FirebaseFirestoreException) {
                Log.e("RideCloseDebug", "DRIVER_CLOSE_FAILED | code=${error.code} | msg=${error.message}", error)
            } else {
                Log.e("RideCloseDebug", "DRIVER_CLOSE_FAILED | msg=${error.message}", error)
            }
            logFirestoreError("closeRideAsDriver", "rides/$rideId", currentUid, error)
        }
    }

    suspend fun closeMyRide(requestId: String, outcome: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not authenticated."))

        val projectId = try { com.google.firebase.FirebaseApp.getInstance().options.projectId } catch (_: Exception) { "unknown" }
        Log.d("RideCloseDebug", "PASSENGER_CLOSE_START | projectId=$projectId | uid=$currentUid | requestId=$requestId | outcome=$outcome")

        return runCatching {
            val reqRef = db.collection("rideRequests").document(requestId)
            val initialReqDoc = reqRef.get().await()
            if (!initialReqDoc.exists()) error("Ride request not found.")
            if (initialReqDoc.getString("passengerId") != currentUid) {
                throw SecurityException("You can only close your own ride participation.")
            }

            val curStatus = initialReqDoc.getString("status") ?: ""
            if (curStatus in listOf(
                    RideRequest.STATUS_COMPLETED,
                    RideRequest.STATUS_CANCELLED_BY_PASSENGER,
                    RideRequest.STATUS_CANCELLED_BY_DRIVER,
                    RideRequest.STATUS_CLOSED_UNCONFIRMED
                )
            ) {
                Log.d("RideCloseDebug", "PASSENGER_ALREADY_TERMINAL | requestId=$requestId | status=$curStatus")
                throw IllegalStateException("This ride has already been closed.")
            }

            val rideId = initialReqDoc.getString("rideId").orEmpty()
            val needId = initialReqDoc.getString("needId").orEmpty()
            val rideRef = db.collection("rides").document(rideId)
            val now = System.currentTimeMillis()

            db.runTransaction { tx ->
                // 1. READ ALL REQUIRED DOCUMENTS
                val liveReqDoc = tx.get(reqRef)
                val liveReqStatus = liveReqDoc.getString("status") ?: ""
                if (liveReqStatus in listOf(
                        RideRequest.STATUS_COMPLETED,
                        RideRequest.STATUS_CANCELLED_BY_PASSENGER,
                        RideRequest.STATUS_CANCELLED_BY_DRIVER,
                        RideRequest.STATUS_CLOSED_UNCONFIRMED
                    )
                ) {
                    throw IllegalStateException("This ride has already been closed.")
                }

                val liveRideDoc = tx.get(rideRef)
                if (!liveRideDoc.exists()) error("Ride not found.")

                val needRef = if (needId.isNotBlank()) db.collection("passengerNeeds").document(needId) else null
                val needDoc = if (needRef != null) tx.get(needRef) else null

                val acceptedPassengers = (liveRideDoc.get("acceptedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val currentCompleted = (liveRideDoc.get("completedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()

                // Read other accepted passengers' requests
                val otherReqDocs = acceptedPassengers
                    .filter { it != currentUid }
                    .associateWith { pUid ->
                        val oRef = db.collection("rideRequests").document("${rideId}_$pUid")
                        tx.get(oRef)
                    }

                // 2. CALCULATE PASSENGER STATUS & AUTO-CLOSE EVALUATION
                val newThisStatus = if (outcome == OUTCOME_COMPLETED) RideRequest.STATUS_COMPLETED else RideRequest.STATUS_CANCELLED_BY_PASSENGER

                val terminalSet = setOf(
                    RideRequest.STATUS_COMPLETED,
                    RideRequest.STATUS_CANCELLED_BY_PASSENGER,
                    RideRequest.STATUS_CANCELLED_BY_DRIVER,
                    RideRequest.STATUS_CLOSED_UNCONFIRMED
                )

                // Check if all accepted passengers are terminal
                val allTerminal = acceptedPassengers.isNotEmpty() && acceptedPassengers.all { pUid ->
                    val st = if (pUid == currentUid) newThisStatus else otherReqDocs[pUid]?.getString("status").orEmpty()
                    st in terminalSet
                }

                val confirmedCount = acceptedPassengers.count { pUid ->
                    val st = if (pUid == currentUid) newThisStatus else otherReqDocs[pUid]?.getString("status").orEmpty()
                    st == RideRequest.STATUS_COMPLETED
                }

                val finalCount = acceptedPassengers.count { pUid ->
                    val st = if (pUid == currentUid) newThisStatus else otherReqDocs[pUid]?.getString("status").orEmpty()
                    st in listOf(RideRequest.STATUS_COMPLETED, RideRequest.STATUS_CLOSED_UNCONFIRMED)
                }

                val newCompleted = if (outcome == OUTCOME_COMPLETED && !currentCompleted.contains(currentUid)) {
                    currentCompleted + currentUid
                } else {
                    currentCompleted
                }

                Log.d(
                    "RideCloseDebug",
                    "PASSENGER_CALC | allTerminal=$allTerminal | confirmedCount=$confirmedCount | finalCount=$finalCount | newCompleted=$newCompleted"
                )

                // 3. EXECUTE WRITES
                // A. Update passenger's own rideRequest
                val reqUpdates = mutableMapOf<String, Any>(
                    "status" to newThisStatus,
                    "responseAt" to now,
                    "updatedAt" to now
                )
                if (outcome == OUTCOME_COMPLETED) {
                    reqUpdates["passengerCompletedAt"] = now
                    reqUpdates["completedAt"] = now
                } else {
                    reqUpdates["cancelledAt"] = now
                }
                tx.update(reqRef, reqUpdates)

                // B. Update passenger's own PassengerNeed (if present and matched)
                if (needRef != null && needDoc != null && needDoc.exists() && needDoc.getString("status") == PassengerNeed.STATUS_MATCHED) {
                    val needUpdates = mutableMapOf<String, Any>(
                        "status" to if (outcome == OUTCOME_COMPLETED) PassengerNeed.STATUS_COMPLETED else PassengerNeed.STATUS_CANCELLED,
                        "updatedAt" to now
                    )
                    if (outcome == OUTCOME_COMPLETED) {
                        needUpdates["completedAt"] = now
                    } else {
                        needUpdates["cancelledAt"] = now
                    }
                    tx.update(needRef, needUpdates)
                }

                // C. Update Ride
                if (outcome == OUTCOME_COMPLETED) {
                    val rideUpdates = mutableMapOf<String, Any>(
                        "completedPassengerIds" to newCompleted,
                        "updatedAt" to now
                    )
                    if (allTerminal) {
                        val targetRideStatus = if (confirmedCount > 0) Ride.STATUS_COMPLETED else Ride.STATUS_CANCELLED
                        rideUpdates["status"] = targetRideStatus
                        rideUpdates["finalPassengerCount"] = finalCount
                        rideUpdates["confirmedPassengerCount"] = confirmedCount
                        if (targetRideStatus == Ride.STATUS_COMPLETED) {
                            rideUpdates["completedAt"] = now
                        }
                    }
                    tx.update(rideRef, rideUpdates)
                } else {
                    // outcome == OUTCOME_DID_NOT_TRAVEL
                    if (allTerminal) {
                        val targetRideStatus = if (confirmedCount > 0) Ride.STATUS_COMPLETED else Ride.STATUS_CANCELLED
                        val rideUpdates = mutableMapOf<String, Any>(
                            "status" to targetRideStatus,
                            "completedPassengerIds" to currentCompleted,
                            "finalPassengerCount" to finalCount,
                            "confirmedPassengerCount" to confirmedCount,
                            "updatedAt" to now
                        )
                        if (targetRideStatus == Ride.STATUS_COMPLETED) {
                            rideUpdates["completedAt"] = now
                        }
                        tx.update(rideRef, rideUpdates)
                    }
                }

                // D. Release activePassengerRides exclusivity lock
                val activeLockRef = db.collection("activePassengerRides").document(currentUid)
                tx.delete(activeLockRef)
            }.await()

            // Automatically log backend passenger CO2 savings and update calculated community leaderboard
            if (outcome == OUTCOME_COMPLETED) {
                runCatching {
                    val pName = initialReqDoc.getString("passengerName").orEmpty()
                    val pDept = initialReqDoc.getString("passengerDepartment").orEmpty()
                    logPassengerCo2Savings(
                        rideId = rideId,
                        passengerId = currentUid,
                        passengerName = pName,
                        passengerDepartment = pDept
                    )
                }
            }

            // Cancel notification
            TravelBuddyNotificationManager.cancelRideNotifications(
                context = TravelBuddyApplication.appContext,
                rideId = rideId,
                requestId = requestId,
                needId = needId
            )
            Log.d("RideCloseDebug", "COMMIT_SUCCESS | Passenger closed requestId=$requestId | rideId=$rideId")
            Unit
        }.onFailure { error ->
            if (error is FirebaseFirestoreException) {
                Log.e("RideCloseDebug", "PASSENGER_CLOSE_FAILED | code=${error.code} | msg=${error.message}", error)
            } else {
                Log.e("RideCloseDebug", "PASSENGER_CLOSE_FAILED | msg=${error.message}", error)
            }
            logFirestoreError("closeMyRide", "rideRequests/$requestId", currentUid, error)
        }
    }

    private suspend fun checkAndAutoCloseRide(rideId: String) {
        if (rideId.isBlank()) return
        try {
            val rideRef = db.collection("rides").document(rideId)
            val rideDoc = rideRef.get().await()
            if (!rideDoc.exists()) return

            val currentRideStatus = rideDoc.getString("status")
            if (currentRideStatus in listOf(Ride.STATUS_COMPLETED, Ride.STATUS_CANCELLED, Ride.STATUS_OFFLINE)) {
                return
            }

            val acceptedPassengers = (rideDoc.get("acceptedPassengerIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            if (acceptedPassengers.isEmpty()) return

            val reqs = db.collection("rideRequests")
                .whereEqualTo("rideId", rideId)
                .get()
                .await()

            val terminalStatuses = setOf(
                RideRequest.STATUS_COMPLETED,
                RideRequest.STATUS_CANCELLED_BY_PASSENGER,
                RideRequest.STATUS_CANCELLED_BY_DRIVER,
                RideRequest.STATUS_CLOSED_UNCONFIRMED,
                RideRequest.STATUS_REJECTED,
                RideRequest.STATUS_EXPIRED
            )

            val acceptedReqs = reqs.documents.filter { doc ->
                val pId = doc.getString("passengerId")
                pId in acceptedPassengers
            }

            val allTerminal = acceptedReqs.size >= acceptedPassengers.size && acceptedReqs.all { doc ->
                val st = doc.getString("status") ?: ""
                st in terminalStatuses
            }

            if (allTerminal) {
                val hasCompleted = acceptedReqs.any { it.getString("status") == RideRequest.STATUS_COMPLETED }
                val targetRideStatus = if (hasCompleted) Ride.STATUS_COMPLETED else Ride.STATUS_CANCELLED
                val confirmedCount = acceptedReqs.count { it.getString("status") == RideRequest.STATUS_COMPLETED }
                val finalCount = acceptedReqs.count { it.getString("status") in listOf(RideRequest.STATUS_COMPLETED, RideRequest.STATUS_CLOSED_UNCONFIRMED) }
                val now = System.currentTimeMillis()
                rideRef.update(
                    mapOf(
                        "status" to targetRideStatus,
                        "finalPassengerCount" to finalCount,
                        "confirmedPassengerCount" to confirmedCount,
                        "completedAt" to if (hasCompleted) now else 0L,
                        "updatedAt" to now
                    )
                ).await()
                Log.d(TAG, "RIDE_AUTOCLOSED | rideId=$rideId | status=$targetRideStatus | hasCompleted=$hasCompleted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkAndAutoCloseRide failed | rideId=$rideId", e)
        }
    }

    // ----------------------------------------------------
    // REQUEST & HISTORY FLOWS
    // ----------------------------------------------------

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
                val requests = snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty()
                val activeRequests = requests.filter { it.status in listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED") }
                trySend(activeRequests)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

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
                val requests = snapshot?.documents?.mapNotNull { it.toObject(RideRequest::class.java) }.orEmpty()
                val activeRequests = requests.filter { it.status in listOf(RideRequest.STATUS_ACCEPTED, "RIDE_STARTED") }
                trySend(activeRequests)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getPassengerHistoryFlow(passengerId: String): Flow<List<RideRequest>> = callbackFlow {
        val listener = db.collection("rideRequests")
            .whereEqualTo("passengerId", passengerId)
            .limit(50)
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
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun getDriverRideHistoryFlow(driverId: String): Flow<List<Ride>> = callbackFlow {
        val listener = db.collection("rides")
            .whereEqualTo("driverId", driverId)
            .limit(50)
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
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun logCo2SavingsForCompletedRide(
        rideId: String,
        driverId: String,
        driverName: String,
        driverDepartment: String,
        passengerCount: Int,
        completedPassengerIds: List<String>
    ): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val monthYear = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(now))

        val sysDoc = try {
            db.collection("systemConfig").document("sustainability").get().await()
        } catch (_: Exception) { null }
        val baselineKm = sysDoc?.getDouble("baselineDistanceKm") ?: 3.0
        val co2Factor = sysDoc?.getDouble("co2FactorKgPerKm") ?: 0.12

        // 1. Log Driver CO2 Record (driver avoids 1 single-occupancy trip per passenger carried)
        if (driverId.isNotBlank() && passengerCount > 0) {
            val totalAvoidedKm = passengerCount * baselineKm
            val co2SavedKg = totalAvoidedKm * co2Factor
            val record = Co2Record(
                recordId = "${rideId}_driver_${driverId}",
                rideId = rideId,
                driverId = driverId,
                driverName = driverName.ifBlank { "Driver" },
                driverDepartment = driverDepartment.ifBlank { "Operations" },
                passengerCount = passengerCount,
                baselineDistanceKm = baselineKm,
                totalAvoidedKm = totalAvoidedKm,
                co2FactorKgPerKm = co2Factor,
                co2SavedKg = co2SavedKg,
                monthYear = monthYear,
                timestamp = now
            )
            db.collection("co2Records").document(record.recordId).set(record).await()
            Log.d(TAG, "LOGGED_CO2_DRIVER | uid=$driverId | name=$driverName | co2=$co2SavedKg kg")
        }

        // 2. Log Passenger CO2 Records (each passenger avoids 1 personal trip)
        for (pUid in completedPassengerIds) {
            val pDoc = try { db.collection("users").document(pUid).get().await() } catch (_: Exception) { null }
            val pName = pDoc?.getString("name").orEmpty().ifBlank { "Passenger" }
            val pDept = pDoc?.getString("department").orEmpty().ifBlank { "Operations" }
            val pAvoidedKm = 1.0 * baselineKm
            val pCo2SavedKg = pAvoidedKm * co2Factor

            val pRecord = Co2Record(
                recordId = "${rideId}_passenger_${pUid}",
                rideId = rideId,
                driverId = pUid,
                driverName = pName,
                driverDepartment = pDept,
                passengerCount = 1,
                baselineDistanceKm = baselineKm,
                totalAvoidedKm = pAvoidedKm,
                co2FactorKgPerKm = co2Factor,
                co2SavedKg = pCo2SavedKg,
                monthYear = monthYear,
                timestamp = now
            )
            db.collection("co2Records").document(pRecord.recordId).set(pRecord).await()
            Log.d(TAG, "LOGGED_CO2_PASSENGER | uid=$pUid | name=$pName | co2=$pCo2SavedKg kg")
        }

        // 3. Recalculate and update the backend leaderboard
        syncLeaderboardFromCo2Records()
    }

    suspend fun logPassengerCo2Savings(
        rideId: String,
        passengerId: String,
        passengerName: String,
        passengerDepartment: String
    ): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        val monthYear = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(now))

        val sysDoc = try {
            db.collection("systemConfig").document("sustainability").get().await()
        } catch (_: Exception) { null }
        val baselineKm = sysDoc?.getDouble("baselineDistanceKm") ?: 3.0
        val co2Factor = sysDoc?.getDouble("co2FactorKgPerKm") ?: 0.12

        val pAvoidedKm = 1.0 * baselineKm
        val pCo2SavedKg = pAvoidedKm * co2Factor

        val pRecord = Co2Record(
            recordId = "${rideId}_passenger_${passengerId}",
            rideId = rideId,
            driverId = passengerId,
            driverName = passengerName.ifBlank { "Passenger" },
            driverDepartment = passengerDepartment.ifBlank { "Operations" },
            passengerCount = 1,
            baselineDistanceKm = baselineKm,
            totalAvoidedKm = pAvoidedKm,
            co2FactorKgPerKm = co2Factor,
            co2SavedKg = pCo2SavedKg,
            monthYear = monthYear,
            timestamp = now
        )
        db.collection("co2Records").document(pRecord.recordId).set(pRecord).await()
        Log.d(TAG, "LOGGED_CO2_PASSENGER | uid=$passengerId | name=$passengerName | co2=$pCo2SavedKg kg")

        syncLeaderboardFromCo2Records()
    }

    suspend fun syncLeaderboardFromCo2Records(): Result<Unit> = runCatching {
        val snapshot = db.collection("co2Records").get().await()
        if (snapshot.isEmpty) return@runCatching

        val records = snapshot.documents.mapNotNull { it.toObject(Co2Record::class.java) }
        if (records.isEmpty()) return@runCatching

        data class Accumulator(
            val uid: String,
            val name: String,
            val department: String,
            var co2SavedKg: Double = 0.0,
            var rideCount: Int = 0
        )

        val userMap = mutableMapOf<String, Accumulator>()
        for (rec in records) {
            val uid = rec.driverId
            if (uid.isBlank()) continue
            val current = userMap.getOrPut(uid) {
                Accumulator(
                    uid = uid,
                    name = rec.driverName.ifBlank { "Contributor" },
                    department = rec.driverDepartment.ifBlank { "Operations" }
                )
            }
            current.co2SavedKg += rec.co2SavedKg
            current.rideCount += 1
        }

        val topSavers = userMap.values
            .filter { it.co2SavedKg > 0.0 }
            .sortedByDescending { it.co2SavedKg }
            .take(3)
            .mapIndexed { idx, acc ->
                mapOf(
                    "uid" to acc.uid,
                    "name" to acc.name,
                    "department" to acc.department,
                    "co2SavedKg" to acc.co2SavedKg,
                    "rideCount" to acc.rideCount,
                    "rank" to idx + 1
                )
            }

        if (topSavers.isNotEmpty()) {
            db.collection("systemConfig").document("leaderboard").set(
                mapOf(
                    "entries" to topSavers,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
            Log.d(TAG, "LEADERBOARD_SYNCED | count=${topSavers.size}")
        }
    }

    private fun logFirestoreError(opName: String, path: String, uid: String, error: Throwable) {
        if (BuildConfig.DEBUG && error is FirebaseFirestoreException) {
            Log.e(
                TAG,
                "Op=$opName | Path=$path | UID=$uid | Code=${error.code} | Msg=${error.message}",
                error
            )
        }
    }
}
