package com.example.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.CommuteSelection
import com.example.data.model.Ride
import com.example.data.model.User
import com.example.data.repository.AdminRepository
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthState
import com.example.data.repository.RideRepository
import com.example.data.repository.UserRepository
import com.example.ui.components.ChangeCommuteBottomSheet
import com.example.ui.components.DriverGroupDetailSheet
import com.example.ui.components.TravelBuddyBottomNavigation
import com.example.ui.screens.activity.ActivityScreen
import com.example.ui.screens.admin.AdminDashboardScreen
import com.example.ui.screens.auth.ChangePasswordScreen
import com.example.ui.screens.auth.CompleteProfileScreen
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.RegisterScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.splash.SplashScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    authRepository: AuthRepository = remember { AuthRepository() },
    userRepository: UserRepository = remember { UserRepository() },
    rideRepository: RideRepository = remember { RideRepository() },
    adminRepository: AdminRepository = remember { AdminRepository() }
) {
    val authState by authRepository.authStateFlow.collectAsState(initial = AuthState.Loading)
    val currentUserState by userRepository.getCurrentUserFlow().collectAsState(initial = null)

    when (val state = authState) {
        is AuthState.Loading -> {
            SplashScreen()
        }
        is AuthState.Unauthenticated -> {
            MainNavigation(
                startDestination = "login",
                currentUser = null,
                authRepository = authRepository,
                userRepository = userRepository,
                rideRepository = rideRepository,
                adminRepository = adminRepository
            )
        }
        is AuthState.Authenticated -> {
            when {
                state.profileLoadFailed -> {
                    // A temporary Firestore/network problem must not be mistaken for a
                    // brand-new employee profile and must not sign the user out.
                    SplashScreen(message = "Restoring your TravelBuddy profile…")
                }
                !state.profileExists -> {
                    val draft = state.userProfile ?: User(
                        uid = state.firebaseUser.uid,
                        name = state.firebaseUser.displayName.orEmpty(),
                        email = state.firebaseUser.email.orEmpty()
                    )
                    MainNavigation(
                        startDestination = "complete_profile",
                        currentUser = draft,
                        authRepository = authRepository,
                        userRepository = userRepository,
                        rideRepository = rideRepository,
                        adminRepository = adminRepository
                    )
                }
                currentUserState == null && state.userProfile == null -> {
                    SplashScreen(message = "Loading your profile…")
                }
                else -> {
                    val user = currentUserState ?: state.userProfile!!
                    MainNavigation(
                        startDestination = "home",
                        currentUser = user,
                        authRepository = authRepository,
                        userRepository = userRepository,
                        rideRepository = rideRepository,
                        adminRepository = adminRepository
                    )
                }
            }
        }
    }
}

@Composable
private fun MainNavigation(
    startDestination: String,
    currentUser: User?,
    authRepository: AuthRepository,
    userRepository: UserRepository,
    rideRepository: RideRepository,
    adminRepository: AdminRepository
) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: startDestination

    val userRole = currentUser?.effectiveRole ?: User.ROLE_USER
    val isAuthScreen = currentRoute == "login" || currentRoute == "register" || currentRoute == "change_password" || currentRoute == "complete_profile"

    // Dynamic Commute Selection State
    var commuteSelection by remember { mutableStateOf(CommuteSelection()) }
    var showChangeCommuteSheet by remember { mutableStateOf(false) }
    var selectedGroupForDetail by remember { mutableStateOf<String?>(null) }
    var matchingRidesForDetail by remember { mutableStateOf<List<Ride>>(emptyList()) }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isAuthScreen) {
                TravelBuddyBottomNavigation(
                    currentRoute = currentRoute,
                    userRole = userRole,
                    onNavigate = { targetRoute ->
                        navController.navigate(targetRoute) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("login") {
                LoginScreen(
                    authRepository = authRepository,
                    onLoginSuccess = { _ ->
                        navController.navigate("home") { popUpTo("login") { inclusive = true } }
                    },
                    onNavigateToRegister = { navController.navigate("register") },
                    onNavigateToChangePassword = { navController.navigate("change_password") },
                    onNavigateToCompleteProfile = { draftUser ->
                        navController.navigate("complete_profile")
                    }
                )
            }

            composable("register") {
                RegisterScreen(
                    authRepository = authRepository,
                    onRegisterSuccess = { _ ->
                        navController.navigate("home") { popUpTo("register") { inclusive = true } }
                    },
                    onNavigateToLogin = { navController.popBackStack() }
                )
            }

            composable("complete_profile") {
                val user = currentUser ?: User()
                CompleteProfileScreen(
                    userDraft = user,
                    authRepository = authRepository,
                    onProfileComplete = {
                        navController.navigate("home") { popUpTo("complete_profile") { inclusive = true } }
                    }
                )
            }

            composable("change_password") {
                ChangePasswordScreen(
                    authRepository = authRepository,
                    onPasswordChangeSuccess = {
                        navController.navigate("home") { popUpTo("change_password") { inclusive = true } }
                    }
                )
            }

            composable("home") {
                val user = currentUser ?: User()
                HomeScreen(
                    user = user,
                    commuteSelection = commuteSelection,
                    onChangeCommuteClick = { showChangeCommuteSheet = true },
                    onOpenGroupDetail = { pickupZone, rides ->
                        selectedGroupForDetail = pickupZone
                        matchingRidesForDetail = rides
                    },
                    rideRepository = rideRepository,
                    userRepository = userRepository,
                    adminRepository = adminRepository
                )
            }

            composable("activity") {
                val user = currentUser ?: User()
                ActivityScreen(
                    user = user,
                    rideRepository = rideRepository
                )
            }

            composable("admin_dashboard") {
                val user = currentUser ?: User()
                AdminDashboardScreen(
                    user = user,
                    onNavigateBack = { navController.popBackStack() },
                    adminRepository = adminRepository
                )
            }

            composable("profile") {
                val user = currentUser ?: User()
                ProfileScreen(
                    user = user,
                    onNavigateBack = { navController.popBackStack() },
                    onLogout = {
                        authRepository.logout()
                    },
                    userRepository = userRepository
                )
            }
        }
    }

    if (showChangeCommuteSheet) {
        ChangeCommuteBottomSheet(
            initialSelection = commuteSelection,
            onDismiss = { showChangeCommuteSheet = false },
            onApply = { newCommute ->
                commuteSelection = newCommute
                showChangeCommuteSheet = false
            }
        )
    }

    selectedGroupForDetail?.let { zone ->
        DriverGroupDetailSheet(
            pickupZone = zone,
            commuteSelection = commuteSelection,
            matchingRides = matchingRidesForDetail,
            onDismiss = { selectedGroupForDetail = null },
            onRequestRide = { ride ->
                val passenger = currentUser
                if (passenger == null) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Your profile is still loading. Please try again.")
                    }
                } else {
                    coroutineScope.launch {
                        rideRepository.requestRideFromDriver(passenger, ride)
                            .onSuccess {
                                selectedGroupForDetail = null
                                snackbarHostState.showSnackbar("Ride request sent to ${ride.driverName}.")
                            }
                            .onFailure { error ->
                                val message = when {
                                    error.message?.contains("permission", ignoreCase = true) == true ->
                                        "Couldn't send the ride request. Please refresh and try again."
                                    else -> error.message ?: "Couldn't send the ride request. Please try again."
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                    }
                }
            }
        )
    }
}
