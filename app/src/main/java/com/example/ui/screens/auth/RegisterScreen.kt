package com.example.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.MasterData
import com.example.data.model.User
import com.example.data.repository.AuthRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    authRepository: AuthRepository = remember { AuthRepository() },
    onRegisterSuccess: (User) -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var selectedDepartment by remember { mutableStateOf(MasterData.DEPARTMENTS.first()) }
    var selectedTownship by remember { mutableStateOf(MasterData.TOWNSHIP_LOCATIONS.first()) }
    var selectedMode by remember { mutableStateOf(User.MODE_PASSENGER) }
    var defaultSeats by remember { mutableStateOf("3") }

    var deptExpanded by remember { mutableStateOf(false) }
    var townshipExpanded by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Employee Registration") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            errorMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = employeeId,
                onValueChange = { employeeId = it },
                label = { Text("Employee ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Department Dropdown
            ExposedDropdownMenuBox(
                expanded = deptExpanded,
                onExpandedChange = { deptExpanded = !deptExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedDepartment,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Department") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deptExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = deptExpanded,
                    onDismissRequest = { deptExpanded = false }
                ) {
                    MasterData.DEPARTMENTS.forEach { dept ->
                        DropdownMenuItem(
                            text = { Text(dept) },
                            onClick = {
                                selectedDepartment = dept
                                deptExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Township Dropdown
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
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = townshipExpanded,
                    onDismissRequest = { townshipExpanded = false }
                ) {
                    MasterData.TOWNSHIP_LOCATIONS.forEach { loc ->
                        DropdownMenuItem(
                            text = { Text(loc) },
                            onClick = {
                                selectedTownship = loc
                                townshipExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Default Mode Preference", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMode == User.MODE_PASSENGER,
                        onClick = { selectedMode = User.MODE_PASSENGER }
                    )
                    Text("Passenger")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMode == User.MODE_DRIVER,
                        onClick = { selectedMode = User.MODE_DRIVER }
                    )
                    Text("Driver")
                }
            }

            if (selectedMode == User.MODE_DRIVER) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = defaultSeats,
                    onValueChange = { defaultSeats = it.filter { char -> char.isDigit() } },
                    label = { Text("Default Passenger Seats") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                    val cleanEmail = email.trim()
                    if (name.isBlank() || cleanEmail.isBlank() || password.isBlank()) {
                        errorMessage = "Please fill all required fields."
                        return@Button
                    }
                    if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                        errorMessage = "Please enter a valid corporate email address."
                        return@Button
                    }
                    if (password.length < 6) {
                        errorMessage = "Password must be at least 6 characters long."
                        return@Button
                    }
                    if (!consentAgreed) {
                        errorMessage = "Please review and accept the app permissions and terms of use."
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    coroutineScope.launch {
                        val seats = defaultSeats.toIntOrNull() ?: 3
                        val res = authRepository.register(
                            name = name,
                            email = cleanEmail,
                            password = password,
                            employeeId = employeeId,
                            department = selectedDepartment,
                            modePreference = selectedMode,
                            townshipLocation = selectedTownship,
                            defaultSeats = seats
                        )
                        isLoading = false
                        res.onSuccess(onRegisterSuccess).onFailure { err ->
                            val msg = err.message.orEmpty()
                            errorMessage = when {
                                msg.contains("already in use", ignoreCase = true) || msg.contains("email-already-in-use", ignoreCase = true) ->
                                    "An account already exists with this email address. Please log in instead."
                                msg.contains("weak-password", ignoreCase = true) ->
                                    "Password is too weak. Please use at least 6 characters."
                                msg.isNotBlank() -> msg
                                else -> "Registration failed. Please check your network connection and try again."
                            }
                        }
                    }
                },
                enabled = !isLoading && consentAgreed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.Primary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White)
                } else {
                    Text("Submit Access Request", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onNavigateToLogin) {
                Text("Already have an account? Log in")
            }
        }
    }
}
