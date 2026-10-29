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

    /**
     * FirebaseAuth is the source of truth for session persistence. Firebase restores a
     * previously authenticated user from encrypted app storage; the app must not sign out
     * merely because an Activity/process was recreated.
     */
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

            // Observe the profile instead of doing a one-shot get. This avoids a race during
            // first registration/Google profile completion where FirebaseAuth becomes signed
            // in milliseconds before users/{uid} has been created.
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

                    val rawUser = snapshot.toObject(User::class.java)
                    val normalized = rawUser?.copy(
                        role = rawUser.effectiveRole,
                        currentMode = rawUser.effectiveMode
                    )

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
        // FirebaseAuth normally invokes the listener immediately, but this also makes the
        // startup intent explicit and harmlessly attaches to an already restored session.
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
            if (!userDoc.exists()) error("Your TravelBuddy profile is not configured yet.")

            val rawUser = userDoc.toObject(User::class.java)
                ?: error("Your TravelBuddy profile could not be read.")

            rawUser.copy(
                role = rawUser.effectiveRole,
                currentMode = rawUser.effectiveMode
            )
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
            val safeMode = if (modePreference.trim().uppercase() == User.MODE_DRIVER) {
                User.MODE_DRIVER
            } else {
                User.MODE_PASSENGER
            }
            val cleanEmail = email.trim()

            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val uid = authResult.user?.uid ?: error("Failed to register user.")

            // Registration can never self-grant ADMIN. Existing Admins promote users later.
            val newUser = User(
                uid = uid,
                name = name.trim(),
                email = cleanEmail,
                employeeId = employeeId.trim(),
                department = department,
                role = User.ROLE_USER,
                phone = "",
                townshipLocation = townshipLocation,
                currentMode = safeMode,
                defaultRolePreference = safeMode,
                defaultDriverSeats = defaultSeats.coerceIn(1, 8),
                isPasswordChanged = true
            )

            db.collection("users").document(uid).set(newUser).await()
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
                val rawUser = userDoc.toObject(User::class.java)
                    ?: error("Your TravelBuddy profile could not be read.")
                rawUser.copy(
                    role = rawUser.effectiveRole,
                    currentMode = rawUser.effectiveMode
                )
            } else {
                // Draft only. CompleteProfileScreen creates a normal USER document.
                User(
                    uid = uid,
                    name = firebaseUser.displayName ?: "",
                    email = firebaseUser.email ?: "",
                    role = User.ROLE_USER
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
            val safeMode = if (defaultMode.trim().uppercase() == User.MODE_DRIVER) {
                User.MODE_DRIVER
            } else {
                User.MODE_PASSENGER
            }

            val newUser = User(
                uid = firebaseUser.uid,
                name = firebaseUser.displayName?.trim().orEmpty().ifBlank { "Employee" },
                email = firebaseUser.email.orEmpty(),
                employeeId = employeeId.trim(),
                department = department,
                role = User.ROLE_USER,
                phone = phone.trim(),
                townshipLocation = townshipLocation,
                currentMode = safeMode,
                defaultRolePreference = safeMode,
                defaultDriverSeats = defaultSeats.coerceIn(1, 8),
                isPasswordChanged = true
            )

            db.collection("users").document(firebaseUser.uid).set(newUser).await()
            newUser
        }
    }

    suspend fun changePassword(newPassword: String): Result<Unit> {
        return runCatching {
            val user = auth.currentUser ?: error("No authenticated user.")
            user.updatePassword(newPassword).await()
            db.collection("users").document(user.uid)
                .update("isPasswordChanged", true)
                .await()
            Unit
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return runCatching {
            auth.sendPasswordResetEmail(email.trim()).await()
            Unit
        }
    }

    fun logout() {
        if (BuildConfig.DEBUG) {
            Log.d("AuthDebug", "SIGN_OUT_CALLED | uid=${auth.currentUser?.uid}")
        }
        auth.signOut()
    }
}
