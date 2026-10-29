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
import com.example.ui.screens.auth.PendingApprovalScreen
import com.example.ui.screens.auth.RegisterScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.splash.SplashScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    authRepository: AuthRepository = remember { AuthRepository() },
    userRepository: UserRepository = remember { UserRepository() },
    rideRepository: RideRepository = remember { RideRepository() },
    adminRepository: AdminRepository = remember { AdminRepository() },
    openCommuteSlotId: String? = null
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
                adminRepository = adminRepository,
                openCommuteSlotId = openCommuteSlotId
            )
        }
        is AuthState.Authenticated -> {
            when {
                state.profileLoadFailed -> {
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
                        adminRepository = adminRepository,
                        openCommuteSlotId = openCommuteSlotId
                    )
                }
                currentUserState == null && state.userProfile == null -> {
                    SplashScreen(message = "Loading your profile…")
                }
                else -> {
                    val user = currentUserState ?: state.userProfile!!
                    val startDest = if (user.isPendingApproval()) "pending_approval" else "home"
                    MainNavigation(
                        startDestination = startDest,
                        currentUser = user,
                        authRepository = authRepository,
                        userRepository = userRepository,
                        rideRepository = rideRepository,
                        adminRepository = adminRepository,
                        openCommuteSlotId = openCommuteSlotId
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
    adminRepository: AdminRepository,
    openCommuteSlotId: String? = null
) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: startDestination

    val userRole = currentUser?.effectiveRole ?: User.ROLE_USER
    val isAuthScreen = currentRoute in listOf("login", "register", "change_password", "complete_profile", "settings", "pending_approval")

    // Real-time approval listener & route guard
    LaunchedEffect(currentUser?.accountStatus) {
        if (currentUser != null) {
            if (currentUser.isPendingApproval() && currentRoute !in listOf("pending_approval", "complete_profile", "login", "register")) {
                navController.navigate("pending_approval") {
                    popUpTo(0) { inclusive = true }
                }
            } else if (!currentUser.isPendingApproval() && currentRoute == "pending_approval") {
                navController.navigate("home") {
                    popUpTo("pending_approval") { inclusive = true }
                }
            }
        }
    }

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
                    onLoginSuccess = { loggedInUser ->
                        val target = if (loggedInUser.isPendingApproval()) "pending_approval" else "home"
                        navController.navigate(target) { popUpTo("login") { inclusive = true } }
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
                        navController.navigate("pending_approval") { popUpTo("register") { inclusive = true } }
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
                        navController.navigate("pending_approval") { popUpTo("complete_profile") { inclusive = true } }
                    }
                )
            }

            composable("pending_approval") {
                val user = currentUser ?: User()
                PendingApprovalScreen(
                    user = user,
                    authRepository = authRepository,
                    onApproved = {
                        navController.navigate("home") { popUpTo("pending_approval") { inclusive = true } }
                    },
                    onSignOut = {
                        authRepository.logout()
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
                    openCommuteSlotId = openCommuteSlotId
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
                    onNavigateToSettings = { navController.navigate("settings") },
                    onLogout = {
                        authRepository.logout()
                    },
                    userRepository = userRepository
                )
            }

            composable("settings") {
                val user = currentUser ?: User()
                SettingsScreen(
                    user = user,
                    onNavigateBack = { navController.popBackStack() }
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
            onJoinRide = { ride ->
                val passenger = currentUser
                if (passenger == null) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Your profile is still loading. Please try again.")
                    }
                } else {
                    coroutineScope.launch {
                        rideRepository.joinRide(passenger, ride)
                            .onSuccess {
                                selectedGroupForDetail = null
                                snackbarHostState.showSnackbar("Ride confirmed! You're riding with ${ride.driverName}.")
                            }
                            .onFailure { error ->
                                val message = when {
                                    error.message?.contains("last seat", ignoreCase = true) == true ->
                                        "Sorry, the last seat was just taken."
                                    error.message?.contains("permission", ignoreCase = true) == true ->
                                        "Couldn't join the ride. Please refresh and try again."
                                    else -> error.message ?: "Couldn't join the ride. Please try again."
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                    }
                }
            }
        )
    }
}
