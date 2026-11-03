package com.example.ui.screens.auth

import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.User
import com.example.data.repository.AuthRepository
import com.example.ui.components.ErrorContainer
import com.example.ui.theme.*
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authRepository: AuthRepository = remember { AuthRepository() },
    onLoginSuccess: (User) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToCompleteProfile: (User) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var resetMessage by remember { mutableStateOf<String?>(null) }

    fun launchGoogleSignIn() {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val webClientId = if (resId != 0) {
            context.getString(resId)
        } else {
            ""
        }.ifBlank {
            "462593078430-egd4i0jhcbpqdssrtggmfnp4v1nmo1kr.apps.googleusercontent.com"
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        coroutineScope.launch {
            isGoogleLoading = true
            errorMessage = null
            try {
                val result = credentialManager.getCredential(context = context, request = request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                    val authRes = authRepository.signInWithGoogleCredential(firebaseCredential)
                    isGoogleLoading = false
                    authRes.onSuccess { user ->
                        if (user.employeeId.isBlank()) {
                            onNavigateToCompleteProfile(user)
                        } else {
                            onLoginSuccess(user)
                        }
                    }.onFailure { err ->
                        Log.e("AuthDebug", "signInWithGoogleCredential failed", err)
                        val msg = err.message.orEmpty()
                        errorMessage = when {
                            msg.contains("restricted", ignoreCase = true) ->
                                "Your account is currently restricted. Please contact the administrator."
                            msg.contains("PERMISSION_DENIED", ignoreCase = true) || msg.contains("permissions", ignoreCase = true) ->
                                "Account permission error. Please verify Firebase project permissions."
                            msg.isNotBlank() -> msg
                            else -> "Google sign-in couldn't be completed. Please try again."
                        }
                    }
                } else {
                    isGoogleLoading = false
                }
            } catch (e: GetCredentialException) {
                isGoogleLoading = false
                Log.e("AuthDebug", "CredentialManager exception: type=${e.type}, message=${e.message}", e)
                val msg = e.message.orEmpty()
                errorMessage = when {
                    msg.contains("16") || msg.contains("10") || msg.contains("DEVELOPER_ERROR", ignoreCase = true) || msg.contains("Caller is not authorized", ignoreCase = true) ->
                        "Google Sign-In configuration error: Signing certificate (SHA-1/SHA-256) or OAuth client is not registered in Firebase Console."
                    e.type.contains("Cancellation", ignoreCase = true) || msg.contains("cancel", ignoreCase = true) ->
                        "Sign in was cancelled."
                    else ->
                        "Google sign-in unavailable (${e.message ?: "Please try again or use email sign-in."})"
                }
            } catch (e: Exception) {
                isGoogleLoading = false
                Log.e("AuthDebug", "Unexpected Google sign-in exception", e)
                errorMessage = e.message ?: "Google sign-in failed. Please try again."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Approved PNG Brand Logo
            Image(
                painter = painterResource(id = R.drawable.travelbuddy_logo),
                contentDescription = "TravelBuddy Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(180.dp)
                    .height(44.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Travel smarter. Together.",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Share your commute. Reduce trips.\nMake every journey count.",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            errorMessage?.let { msg ->
                ErrorContainer(message = msg)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Compact Login Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp)),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    OutlinedTextField(
                        value = identifier,
                        onValueChange = { identifier = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                        },
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (identifier.isBlank() || password.isBlank()) {
                                errorMessage = "Please enter both your email address and password."
                                return@Button
                            }

                            isLoading = true
                            errorMessage = null

                            coroutineScope.launch {
                                val result = authRepository.login(identifier, password)
                                isLoading = false
                                result.onSuccess { user ->
                                    if (!user.isPasswordChanged) {
                                        onNavigateToChangePassword()
                                    } else {
                                        onLoginSuccess(user)
                                    }
                                }.onFailure { err ->
                                    val msg = err.message.orEmpty()
                                    errorMessage = when {
                                        msg.contains("complete email", ignoreCase = true) -> msg
                                        msg.contains("restricted", ignoreCase = true) -> msg
                                        msg.contains("no user record", ignoreCase = true) || msg.contains("user-not-found", ignoreCase = true) ->
                                            "No account found with this email. Please click 'Create Account' below to register."
                                        msg.contains("wrong-password", ignoreCase = true) || msg.contains("invalid-credential", ignoreCase = true) ->
                                            "Incorrect email or password. Please verify your credentials or click 'Forgot Password?'."
                                        msg.contains("network", ignoreCase = true) ->
                                            "Network error. Please check your internet connection."
                                        msg.isNotBlank() -> msg
                                        else -> "Couldn't sign in. Check your email/password and internet connection."
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White
                            )
                        } else {
                            Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // OR Divider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(Divider))
                        Text(
                            text = "  OR  ",
                            fontSize = 12.sp,
                            color = TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(Divider))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Continue with Google Button (Credential Manager)
                    OutlinedButton(
                        onClick = { launchGoogleSignIn() },
                        enabled = !isGoogleLoading,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isGoogleLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Primary)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("G ", color = PrimaryDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Continue with Google", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onNavigateToRegister) {
                            Text("Create Account", color = Primary)
                        }
                        TextButton(onClick = { showResetDialog = true }) {
                            Text("Forgot Password?", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Password") },
            text = {
                Column {
                    Text("Enter your registered employee email to receive a password reset link.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    resetMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = Primary)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (resetEmail.isBlank()) return@Button
                        coroutineScope.launch {
                            val res = authRepository.sendPasswordReset(resetEmail)
                            res.onSuccess {
                                resetMessage = "Reset link sent! Please check your inbox."
                            }.onFailure {
                                resetMessage = "Error: ${it.message}"
                            }
                        }
                    }
                ) {
                    Text("Send Link")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
