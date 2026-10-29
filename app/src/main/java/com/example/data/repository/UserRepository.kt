package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.User
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    /**
     * Auth-aware profile stream. Unlike the previous implementation this does not capture
     * a null UID once and then close forever. It follows FirebaseAuth across startup restore,
     * login and logout, and swaps the Firestore listener when the authenticated UID changes.
     */
    fun getCurrentUserFlow(): Flow<User?> = callbackFlow {
        var registration: ListenerRegistration? = null

        fun attach(uid: String?) {
            registration?.remove()
            registration = null

            if (uid == null) {
                trySend(null)
                return
            }

            registration = db.collection("users").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        logFirestoreError("getCurrentUserFlow", "users/$uid", uid, error)
                        return@addSnapshotListener
                    }

                    val rawUser = snapshot?.toObject(User::class.java)
                    val user = rawUser?.copy(
                        role = rawUser.effectiveRole,
                        accountStatus = rawUser.effectiveAccountStatus,
                        currentMode = rawUser.effectiveMode
                    )

                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "FirestoreDebug",
                            "USER_EMITTED | uid=$uid | role=${user?.role} | mode=${user?.currentMode}"
                        )
                    }
                    trySend(user)
                }
        }

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            attach(firebaseAuth.currentUser?.uid)
        }

        auth.addAuthStateListener(authListener)
        attach(auth.currentUser?.uid)

        awaitClose {
            registration?.remove()
            auth.removeAuthStateListener(authListener)
        }
    }

    suspend fun getCurrentUser(): User? {
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val rawUser = doc.toObject(User::class.java) ?: return null
            rawUser.copy(
                role = rawUser.effectiveRole,
                accountStatus = rawUser.effectiveAccountStatus,
                currentMode = rawUser.effectiveMode
            )
        } catch (e: Exception) {
            logFirestoreError("getCurrentUser", "users/$uid", uid, e)
            null
        }
    }

    suspend fun updateCurrentMode(newMode: String): Result<Unit> {
        val firebaseUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("No authenticated Firebase user."))

        val uid = firebaseUser.uid
        val mode = newMode.trim().uppercase()
        if (mode != User.MODE_PASSENGER && mode != User.MODE_DRIVER) {
            return Result.failure(IllegalArgumentException("Invalid travel mode: $newMode"))
        }

        val ref = db.collection("users").document(uid)

        return runCatching {
            if (BuildConfig.DEBUG) {
                val before = ref.get().await()
                Log.d(
                    "FirestoreDebug",
                    "MODE_SWITCH_START | project=${FirebaseApp.getInstance().options.projectId} | " +
                        "uid=$uid | docUid=${before.getString("uid")} | role=${before.getString("role")} | " +
                        "from=${before.getString("currentMode")} | requested=$mode"
                )
            }

            // Narrow partial write: role/identity/default preference are never touched.
            ref.update("currentMode", mode).await()

            if (BuildConfig.DEBUG) {
                val after = ref.get().await()
                Log.d(
                    "FirestoreDebug",
                    "MODE_SWITCH_FIRESTORE_SUCCESS | uid=$uid | storedMode=${after.getString("currentMode")}"
                )
            }
            Unit
        }.onFailure { error ->
            if (error is FirebaseFirestoreException) {
                Log.e(
                    "FirestoreDebug",
                    "MODE_SWITCH_FIRESTORE_FAILURE | project=${FirebaseApp.getInstance().options.projectId} | " +
                        "path=users/$uid | uid=$uid | code=${error.code} | message=${error.message}",
                    error
                )
            } else {
                Log.e("FirestoreDebug", "MODE_SWITCH_FAILURE | uid=$uid", error)
            }
        }
    }

    suspend fun updateUserProfile(
        name: String,
        phone: String,
        department: String,
        townshipLocation: String,
        defaultRolePreference: String,
        defaultDriverSeats: Int
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not authenticated."))

        val pref = if (defaultRolePreference.trim().uppercase() == User.MODE_DRIVER) {
            User.MODE_DRIVER
        } else {
            User.MODE_PASSENGER
        }

        val updates = mapOf(
            "name" to name.trim(),
            "phone" to phone.trim(),
            "department" to department,
            "townshipLocation" to townshipLocation,
            "defaultRolePreference" to pref,
            "defaultDriverSeats" to defaultDriverSeats.coerceIn(1, 8)
        )

        return runCatching {
            db.collection("users").document(uid).update(updates).await()
            Unit
        }.onFailure { error ->
            logFirestoreError("updateUserProfile", "users/$uid", uid, error)
        }
    }

    suspend fun setMuteSchedule(muteUntilTimestamp: Long): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("User not authenticated."))

        return runCatching {
            db.collection("users").document(uid)
                .update("muteUntilTimestamp", muteUntilTimestamp.coerceAtLeast(0L))
                .await()
            Unit
        }.onFailure { error ->
            logFirestoreError("setMuteSchedule", "users/$uid", uid, error)
        }
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
