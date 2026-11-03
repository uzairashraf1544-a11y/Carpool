package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.User
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed interface AuthState {
    data object Loading : AuthState
    data class Authenticated(
        val firebaseUser: FirebaseUser,
        val userProfile: User?,
        val profileExists: Boolean,
        val profileLoadFailed: Boolean = false
    ) : AuthState
    data object Unauthenticated : AuthState
}

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    val currentUserUid: String?
        get() = auth.currentUser?.uid

    val authStateFlow: Flow<AuthState> = callbackFlow {
        trySend(AuthState.Loading)

        if (BuildConfig.DEBUG) {
            Log.d(
                "AuthDebug",
                "AUTH_INIT_START | project=${FirebaseApp.getInstance().options.projectId}"
            )
        }

        var profileRegistration: ListenerRegistration? = null

        fun observeProfile(firebaseUser: FirebaseUser?) {
            profileRegistration?.remove()
            profileRegistration = null

            if (firebaseUser == null) {
                if (BuildConfig.DEBUG) Log.d("AuthDebug", "AUTH_STATE: UNAUTHENTICATED")
                trySend(AuthState.Unauthenticated)
                return
            }

            if (BuildConfig.DEBUG) {
                Log.d(
                    "AuthDebug",
                    "AUTH_STATE: AUTHENTICATED | uid=${firebaseUser.uid} | email=${firebaseUser.email}"
                )
            }

            profileRegistration = db.collection("users").document(firebaseUser.uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (BuildConfig.DEBUG) {
                            Log.e("AuthDebug", "PROFILE_LOAD_FAILED | uid=${firebaseUser.uid}", error)
                        }
                        trySend(
                            AuthState.Authenticated(
                                firebaseUser = firebaseUser,
                                userProfile = null,
                                profileExists = true,
                                profileLoadFailed = true
                            )
                        )
                        return@addSnapshotListener
                    }

                    if (snapshot == null || !snapshot.exists()) {
                        trySend(
                            AuthState.Authenticated(
                                firebaseUser = firebaseUser,
                                userProfile = null,
                                profileExists = false
                            )
                        )
                        return@addSnapshotListener
                    }

                    val normalized = try {
                        User.fromSnapshot(snapshot)
                    } catch (e: Exception) {
                        Log.e("AuthDebug", "Failed to deserialize user profile", e)
                        null
                    }

                    // Automatic restriction enforcement on active sessions
                    if (normalized != null && (normalized.isBlocked() || normalized.isRemoved())) {
                        Log.w("AuthDebug", "RESTRICTED_USER_SESSION_TERMINATED | uid=${firebaseUser.uid} | status=${normalized.accountStatus}")
                        auth.signOut()
                        trySend(AuthState.Unauthenticated)
                        return@addSnapshotListener
                    }

                    trySend(
                        AuthState.Authenticated(
                            firebaseUser = firebaseUser,
                            userProfile = normalized,
                            profileExists = true,
                            profileLoadFailed = normalized == null
                        )
                    )
                }
        }

        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            observeProfile(firebaseAuth.currentUser)
        }

        auth.addAuthStateListener(listener)
        observeProfile(auth.currentUser)

        awaitClose {
            profileRegistration?.remove()
            auth.removeAuthStateListener(listener)
        }
    }

    suspend fun login(emailInput: String, passwordInput: String): Result<User> {
        return runCatching {
            val email = emailInput.trim()
            require(email.contains("@")) { "Please enter your complete email address." }

            val authResult = auth.signInWithEmailAndPassword(email, passwordInput).await()
            val firebaseUser = authResult.user ?: error("Authentication succeeded but no user was returned.")

            val userDoc = db.collection("users").document(firebaseUser.uid).get().await()
            val userToReturn = if (!userDoc.exists()) {
                val now = System.currentTimeMillis()
                val recoveredUser = User(
                    uid = firebaseUser.uid,
                    name = firebaseUser.displayName?.trim()?.ifBlank { null }
                        ?: email.substringBefore("@").replace(".", " ").split(" ")
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                    email = email,
                    role = User.ROLE_USER,
                    accountStatus = User.STATUS_PENDING_APPROVAL,
                    createdAt = now,
                    approvalRequestedAt = now,
                    isPasswordChanged = true
                )
                db.collection("users").document(firebaseUser.uid).set(recoveredUser).await()
                recoveredUser
            } else {
                User.fromSnapshot(userDoc)
                    ?: error("Your TravelBuddy profile could not be read.")
            }

            if (userToReturn.isBlocked() || userToReturn.isRemoved()) {
                auth.signOut()
                throw IllegalStateException("Your account is currently restricted. Please contact the administrator.")
            }

            userToReturn
        }
    }

    suspend fun register(
        name: String,
        email: String,
        password: String,
        employeeId: String,
        department: String,
        modePreference: String,
        townshipLocation: String,
        defaultSeats: Int
    ): Result<User> {
        return runCatching {
            val cleanName = name.trim()
            val cleanEmail = email.trim()
            val cleanEmployeeId = employeeId.trim()
            val normalizedEmpId = cleanEmployeeId.replace(" ", "").uppercase()
            val now = System.currentTimeMillis()

            require(cleanName.isNotBlank()) { "Please enter your full name." }
            require(cleanEmail.contains("@") && cleanEmail.contains(".")) { "Please enter a valid email address." }
            require(password.length >= 6) { "Password must be at least 6 characters long." }

            val safeMode = if (modePreference.trim().uppercase() == User.MODE_DRIVER) {
                User.MODE_DRIVER
            } else {
                User.MODE_PASSENGER
            }

            // 1. Create Firebase Auth user
            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val createdUser = authResult.user ?: error("Failed to register user in authentication system.")
            val uid = createdUser.uid

            // 2. Validate uniqueness of Employee ID
            if (normalizedEmpId.isNotBlank()) {
                val regRef = db.collection("employeeRegistry").document(normalizedEmpId)
                val regDoc = try {
                    regRef.get().await()
                } catch (e: Exception) {
                    Log.w("AuthDebug", "Employee registry lookup exception: ${e.message}")
                    null
                }
                if (regDoc != null && regDoc.exists() && regDoc.getString("uid") != uid) {
                    runCatching { createdUser.delete().await() }
                    auth.signOut()
                    throw IllegalArgumentException("This Employee ID is already registered to another account.")
                }
            }

            val newUser = User(
                uid = uid,
                name = cleanName,
                email = cleanEmail,
                employeeId = cleanEmployeeId,
                department = department,
                role = User.ROLE_USER,
                accountStatus = User.STATUS_PENDING_APPROVAL,
                phone = "",
                townshipLocation = townshipLocation,
                currentMode = safeMode,
                defaultRolePreference = safeMode,
                defaultDriverSeats = defaultSeats.coerceIn(1, 8),
                isPasswordChanged = true,
                createdAt = now,
                approvalRequestedAt = now,
                consentAcceptedAt = now,
                consentVersion = 1,
                privacyNoticeVersion = 1
            )

            val userRef = db.collection("users").document(uid)
            val regRef = if (normalizedEmpId.isNotBlank()) db.collection("employeeRegistry").document(normalizedEmpId) else null

            try {
                db.runBatch { batch ->
                    batch.set(userRef, newUser)
                    if (regRef != null) {
                        batch.set(
                            regRef,
                            mapOf(
                                "uid" to uid,
                                "createdAt" to now
                            )
                        )
                    }
                }.await()
            } catch (writeErr: Exception) {
                Log.e("AuthDebug", "Failed to write user profile documents", writeErr)
                runCatching { createdUser.delete().await() }
                auth.signOut()
                throw writeErr
            }

            newUser
        }
    }

    suspend fun signInWithGoogleCredential(credential: AuthCredential): Result<User> {
        return runCatching {
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: error("Google sign in failed.")
            val uid = firebaseUser.uid

            val userDoc = db.collection("users").document(uid).get().await()
            if (userDoc.exists()) {
                val normalized = User.fromSnapshot(userDoc)
                    ?: error("Your TravelBuddy profile could not be read.")
                if (normalized.isBlocked() || normalized.isRemoved()) {
                    auth.signOut()
                    throw IllegalStateException("Your account is currently restricted. Please contact the administrator.")
                }
                normalized
            } else {
                User(
                    uid = uid,
                    name = firebaseUser.displayName ?: "",
                    email = firebaseUser.email.orEmpty().trim(),
                    role = User.ROLE_USER,
                    accountStatus = User.STATUS_PENDING_APPROVAL,
                    createdAt = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun completeGoogleProfile(
        employeeId: String,
        department: String,
        townshipLocation: String,
        defaultMode: String,
        defaultSeats: Int,
        phone: String
    ): Result<User> {
        val firebaseUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("No authenticated Google user."))

        return runCatching {
            val cleanEmployeeId = employeeId.trim()
            val cleanEmail = firebaseUser.email.orEmpty().trim()
            val normalizedEmpId = cleanEmployeeId.replace(" ", "").uppercase()
            val now = System.currentTimeMillis()

            Log.d("ProfileSetupDebug", "PROFILE_LOCAL_VALIDATION_PASSED | uid=${firebaseUser.uid} | email=$cleanEmail | employeeId=$cleanEmployeeId")

            // Deterministic Employee ID Uniqueness Check via employeeRegistry
            if (normalizedEmpId.isNotBlank()) {
                Log.d("ProfileSetupDebug", "PROFILE_DUPLICATE_CHECK_START | employeeId=$normalizedEmpId")
                val regRef = db.collection("employeeRegistry").document(normalizedEmpId)
                val regDoc = regRef.get().await()
                if (regDoc.exists()) {
                    val registeredUid = regDoc.getString("uid")
                    if (registeredUid != firebaseUser.uid) {
                        Log.w("ProfileSetupDebug", "PROFILE_DUPLICATE_CHECK_FAILED | employeeId=$normalizedEmpId already claimed by $registeredUid")
                        throw IllegalArgumentException("This Employee ID is already registered.")
                    }
                }
                Log.d("ProfileSetupDebug", "PROFILE_DUPLICATE_CHECK_SUCCESS | employeeId=$normalizedEmpId")
            }

            val safeMode = if (defaultMode.trim().uppercase() == User.MODE_DRIVER) {
                User.MODE_DRIVER
            } else {
                User.MODE_PASSENGER
            }

            val newUser = User(
                uid = firebaseUser.uid,
                name = firebaseUser.displayName?.trim().orEmpty().ifBlank { "Employee" },
                email = cleanEmail,
                employeeId = cleanEmployeeId,
                department = department,
                role = User.ROLE_USER,
                accountStatus = User.STATUS_PENDING_APPROVAL,
                phone = phone.trim(),
                townshipLocation = townshipLocation,
                currentMode = safeMode,
                defaultRolePreference = safeMode,
                defaultDriverSeats = defaultSeats.coerceIn(1, 8),
                isPasswordChanged = true,
                createdAt = now,
                approvalRequestedAt = now,
                consentAcceptedAt = now,
                consentVersion = 1,
                privacyNoticeVersion = 1
            )

            val userRef = db.collection("users").document(firebaseUser.uid)
            val regRef = if (normalizedEmpId.isNotBlank()) db.collection("employeeRegistry").document(normalizedEmpId) else null

            Log.d(
                "ProfileSetupDebug",
                "PROFILE_FIRESTORE_CREATE_START | path=users/${firebaseUser.uid} | role=${newUser.role} | " +
                    "accountStatus=${newUser.accountStatus} | currentMode=${newUser.currentMode} | employeeId=${newUser.employeeId} | " +
                    "project=${FirebaseApp.getInstance().options.projectId}"
            )

            db.runBatch { batch ->
                batch.set(userRef, newUser)
                if (regRef != null) {
                    batch.set(
                        regRef,
                        mapOf(
                            "uid" to firebaseUser.uid,
                            "createdAt" to now
                        )
                    )
                }
            }.await()

            // Confirm creation by re-reading user document
            val checkDoc = userRef.get().await()
            if (!checkDoc.exists()) {
                throw IllegalStateException("Profile creation write completed but document was not found in Firestore.")
            }

            Log.d(
                "ProfileSetupDebug",
                "PROFILE_FIRESTORE_CREATE_SUCCESS | path=users/${firebaseUser.uid} | name=${newUser.name} | " +
                    "role=${checkDoc.getString("role")} | accountStatus=${checkDoc.getString("accountStatus")}"
            )
            newUser
        }.onFailure { error ->
            Log.e(
                "ProfileSetupDebug",
                "PROFILE_FIRESTORE_CREATE_FAILURE | uid=${firebaseUser.uid} | " +
                    "errorClass=${error.javaClass.simpleName} | message=${error.message}",
                error
            )
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return runCatching {
            auth.sendPasswordResetEmail(email.trim()).await()
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = resetPassword(email)

    suspend fun changePassword(newPassword: String): Result<Unit> {
        return runCatching {
            val user = auth.currentUser ?: error("User not authenticated.")
            user.updatePassword(newPassword).await()
            db.collection("users").document(user.uid).update("isPasswordChanged", true).await()
            Unit
        }
    }

    suspend fun refreshUserProfile(): Result<User> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No authenticated user."))
        return runCatching {
            val userDoc = db.collection("users").document(user.uid).get().await()
            if (!userDoc.exists()) error("Profile does not exist.")
            User.fromSnapshot(userDoc) ?: error("Failed to read profile.")
        }
    }

    suspend fun getApproverInfo(): Map<String, String> {
        return runCatching {
            val doc = db.collection("systemConfig").document("appApproval").get().await()
            if (doc.exists()) {
                val name = doc.getString("approverDisplayName") ?: doc.getString("name") ?: ""
                val email = doc.getString("approverEmail") ?: doc.getString("email") ?: ""
                if (name.isNotBlank()) return@runCatching mapOf("name" to name, "email" to email)
            }
            val sustDoc = db.collection("systemConfig").document("sustainability").get().await()
            val name = sustDoc.getString("approverDisplayName") ?: "Administrator"
            val email = sustDoc.getString("approverEmail") ?: ""
            mapOf("name" to name, "email" to email)
        }.getOrDefault(mapOf("name" to "Administrator", "email" to ""))
    }

    fun signOut() {
        auth.signOut()
    }

    fun logout() {
        auth.signOut()
    }
}
