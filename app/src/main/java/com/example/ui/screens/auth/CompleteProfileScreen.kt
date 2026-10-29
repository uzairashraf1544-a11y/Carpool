package com.example.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MasterData
import com.example.data.model.User
import com.example.data.repository.AuthRepository
import com.example.ui.components.ErrorContainer
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteProfileScreen(
    userDraft: User,
    authRepository: AuthRepository = remember { AuthRepository() },
    onProfileComplete: (User) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var employeeId by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedDept by remember { mutableStateOf(MasterData.DEPARTMENTS.first()) }
    var selectedTownship by remember { mutableStateOf(MasterData.TOWNSHIP_LOCATIONS.first()) }
    var selectedMode by remember { mutableStateOf(User.MODE_PASSENGER) }
    var seatsCount by remember { mutableStateOf(3) }

    var deptExpanded by remember { mutableStateOf(false) }
    var townshipExpanded by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            Text("Complete Profile", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Welcome ${userDraft.name}! Enter your employee details to continue.", fontSize = 14.5.sp, color = TextSecondary)

            Spacer(modifier = Modifier.height(20.dp))

            errorMessage?.let { msg ->
                ErrorContainer(message = msg)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = userDraft.email,
                        onValueChange = {},
                        enabled = false,
                        label = { Text("Email (Google)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = employeeId,
                        onValueChange = { employeeId = it },
                        label = { Text("Employee ID (e.g. FFL-01981)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = deptExpanded,
                        onExpandedChange = { deptExpanded = !deptExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedDept,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Department") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deptExpanded) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = deptExpanded, onDismissRequest = { deptExpanded = false }) {
                            MasterData.DEPARTMENTS.forEach { d ->
                                DropdownMenuItem(text = { Text(d) }, onClick = { selectedDept = d; deptExpanded = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = townshipExpanded,
                        onExpandedChange = { townshipExpanded = !townshipExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedTownship,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Township Location") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = townshipExpanded) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = townshipExpanded, onDismissRequest = { townshipExpanded = false }) {
                            MasterData.TOWNSHIP_LOCATIONS.forEach { loc ->
                                DropdownMenuItem(text = { Text(loc) }, onClick = { selectedTownship = loc; townshipExpanded = false })
                            }
                        }
                    }

                    var consentAgreed by remember { mutableStateOf(false) }

                    Spacer(modifier = Modifier.height(18.dp))

                    ConsentAgreementCard(
                        consentAgreed = consentAgreed,
                        onConsentAgreedChange = { consentAgreed = it }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            android.util.Log.d(
                                "ProfileSetupDebug",
                                "PROFILE_SUBMIT_CLICKED | uid=${userDraft.uid} | email=${userDraft.email} | employeeId=$employeeId"
                            )
                            if (employeeId.isBlank()) {
                                errorMessage = "Please enter your Employee ID."
                                return@Button
                            }
                            if (!consentAgreed) {
                                errorMessage = "Please review and accept the app permissions and terms of use."
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            coroutineScope.launch {
                                val res = authRepository.completeGoogleProfile(
                                    employeeId = employeeId,
                                    department = selectedDept,
                                    townshipLocation = selectedTownship,
                                    defaultMode = selectedMode,
                                    defaultSeats = seatsCount,
                                    phone = phone
                                )
                                isLoading = false
                                res.onSuccess { completedUser ->
                                    onProfileComplete(completedUser)
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Failed to save profile."
                                }
                            }
                        },
                        enabled = !isLoading && consentAgreed,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Submit Access Request", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
