# TRAVELBUDDY — FLOW MATRIX & STATE TRANSITIONS
**Inspection Date**: 2026-09-03  
**Status**: Complete Architectural Execution Traces & Diagrams  

---

## 1. END-TO-END FLOW DIAGRAMS

### 1.1 Authentication & Session Restoration Flow

```text
[App Launch]
     │
     ▼
SplashScreen
     │
     ▼
AuthRepository.authStateFlow (observes FirebaseAuth.AuthStateListener)
     │
     ├─────────────────────────────────────────┐
     │ firebaseUser == null                    │ firebaseUser != null
     ▼                                         ▼
[Unauthenticated]                      [Authenticated]
     │                                         │
     ▼                                         ▼
LoginScreen                             Observe users/{uid} Snapshot
     │                                         │
     │ Email Login /                           ├──────────────────────────────────┐
     │ Google Sign-In                          │ snapshot == null || !exists()    │ snapshot.exists()
     ▼                                         ▼                                  ▼
AuthRepository.login() /                Navigate to                        Parse User Object
AuthRepository.signInWithGoogle()       CompleteProfileScreen                     │
     │                                                                            ├──────────────────────────┐
     │                                                                            │ accountStatus is         │ accountStatus
     │                                                                            │ BLOCKED or REMOVED       │ is ACTIVE
     │                                                                            ▼                          ▼
     │                                                                        Sign out & terminate       Navigate to
     └───────────────────────────────────────────────────────────────────────► session                  HomeScreen
```

---

### 1.2 New Google User Onboarding Flow

```text
LoginScreen
     │
     ▼
Tap [Sign in with Google]
     │
     ▼
CredentialManager.getCredential() ──► Resolves GoogleIdTokenCredential
     │
     ▼
AuthRepository.signInWithGoogleCredential()
     │
     ├──► FirebaseAuth.signInWithCredential() ──► Creates Auth UID
     │
     └──► Point-read users/{uid}.get() ──► Returns false (does not exist yet)
               │
               ▼
Navigates to CompleteProfileScreen
     │
     ▼
User fills: Employee ID, Phone, Department, Township
     │
     ▼
Tap [Complete & Continue]
     │
     ▼
AuthRepository.completeGoogleProfile()
     │
     ├──► Point-read employeeRegistry/{normalizedEmpId}.get()
     │         │
     │         ├── Document exists with different UID ──► Throw "Already registered" (Abort)
     │         └── Document empty or matches UID ──► Continue
     │
     ├──► Construct User (role="USER", accountStatus="ACTIVE", currentMode=safeMode)
     │
     ├──► Atomic WriteBatch:
     │         ├── batch.set(users/{uid}, newUser)
     │         └── batch.set(employeeRegistry/{normalizedEmpId}, {uid, createdAt})
     │
     └──► Verification Read-back: users/{uid}.get() exists
               │
               ▼
Navigate to HomeScreen
```

---

### 1.3 Driver Availability & Publishing Flow

```text
HomeScreen (Driver Mode)
     │
     ▼
Card: OFFER A RIDE TO COLLEAGUES
     │
     ├── Select Pickup Area (PickupAreaSelector: Township / Plant / Other)
     │         │
     │         └── If OTHER: Enter required Custom Pickup Location (max 150 chars)
     │
     ├── Set Available Seats (Counter: 1 to 8)
     │
     ├── Optional Message (OutlinedTextField: max 250 chars)
     │
     ▼
Tap [PUBLISH AVAILABILITY]
     │
     ▼
RideRepository.createOrUpdateDriverAvailability()
     │
     ├── Check existing active ride for driverId + date:
     │         ├── If exists: reuse rideId, recalculate available seats
     │         └── If new: generate rideId = {driverId}_{date}_{timestamp}
     │
     ├── Set status = (if availableSeats == 0 FULL else if acceptedPassengers.isNotEmpty PARTIALLY_FULL else AVAILABLE)
     │
     └── db.collection("rides").document(rideId).set(ride)
               │
               ▼
UI displays "TODAY'S ACTIVE RIDE" card with confirmed passengers and [CLOSE RIDE] button.
```

---

### 1.4 Passenger Post Ride Request Flow

```text
HomeScreen (Passenger Mode)
     │
     ▼
Card: REQUEST A RIDE
     │
     ├── Select Pickup Area (PickupAreaSelector: Township / Plant / Other)
     │         │
     │         └── If OTHER: Enter required Custom Pickup Location
     │
     ├── Optional Message (OutlinedTextField: max 250 chars)
     │
     ▼
Tap [POST RIDE REQUEST]
     │
     ▼
RideRepository.createPassengerNeed()
     │
     ├── Check existing active need for passengerId + date:
     │         ├── If exists: reuse needId
     │         └── If new: generate needId = {passengerId}_{date}_{timestamp}
     │
     ├── Set status = LOOKING, expiresAt = now + 18 hours
     │
     └── db.collection("passengerNeeds").document(needId).set(need)
               │
               ▼
UI displays "LOOKING FOR A RIDE TODAY" card with [CANCEL REQUEST] button.
```

---

### 1.5 One-Tap Matching: Flow A (Passenger Joins Driver)

```text
Passenger views AVAILABLE DRIVERS discovery list
     │
     ▼
Passenger taps [JOIN RIDE] on Driver card
     │
     ▼
RideRepository.joinRide(passengerUser, ride)
     │
     ▼
Firestore Transaction (rides/{rideId}):
     │
     ├── tx.get(rides/{rideId})
     │         │
     │         ├── Check status in [AVAILABLE, PARTIALLY_FULL] && availableSeats > 0
     │         ├── Check passenger not already in acceptedPassengerIds
     │         └── Abort if full or already joined
     │
     ├── Calculate: newAccepted = accepted + currentUid
     │              newSeats = totalSeats - newAccepted.size
     │              newStatus = if (newSeats == 0) FULL else PARTIALLY_FULL
     │
     ├── tx.update(rides/{rideId}, {acceptedPassengerIds, availableSeats, status})
     │
     └── tx.set(rideRequests/{rideId}_{passengerId}, RideRequest(status = ACCEPTED))
     │
     ▼
Transaction Committed
     │
     ├── Close any active PassengerNeed for today (status = MATCHED, matchedRideId = rideId)
     │
     └── Show Heads-Up Notification to Driver: "{passengerName} joined your ride."
     │
     ▼
RIDE IS IMMEDIATELY ACTIVE
Passenger UI:
  - Header displays "MY RIDE"
  - Joined Driver card is removed from discovery list
  - [JOIN RIDE] buttons hidden on remaining discovery cards
```

---

### 1.6 One-Tap Matching: Flow B (Driver Accepts Passenger Need)

```text
Driver views PEOPLE LOOKING FOR A RIDE discovery list
(Driver does NOT need an active ride beforehand!)
     │
     ▼
Driver taps [DRIVE & ACCEPT] on Passenger Need card
     │
     ▼
Modal displays passenger details: Name, Department, Pickup Area, Note
     │
     ▼
Driver confirms [DRIVE & ACCEPT]
     │
     ▼
RideRepository.acceptPassengerNeedDirectly(driverUser, need)
     │
     ├── Query existing driver ride for today
     │
     ▼
Firestore Transaction:
     │
     ├── tx.get(passengerNeeds/{needId})
     │         │
     │         ├── Verify status == LOOKING
     │         └── Abort if already taken or cancelled
     │
     ├── tx.set(rides/{rideId}, updatedRide):
     │         ├── Append passengerId to acceptedPassengerIds
     │         └── Status = if (availableSeats == 0) FULL else PARTIALLY_FULL
     │
     ├── tx.update(passengerNeeds/{needId}, {status = MATCHED, matchedDriverId, matchedRideId})
     │
     └── tx.set(rideRequests/{rideId}_{passengerId}, RideRequest(status = ACCEPTED))
     │
     ▼
Transaction Committed
     │
     └── Show Heads-Up Notification to Passenger: "{driverName} confirmed your ride."
     │
     ▼
RIDE IS IMMEDIATELY ACTIVE
Driver UI updates to show active ride and confirmed passenger list.
Passenger UI updates to show "MY RIDE" with driver details.
```

---

### 1.7 Ride Closure Workflows

```text
                        ┌──────────────────────────────┐
                        │   ACTIVE CONFIRMED RIDE      │
                        └──────────────┬───────────────┘
                                       │
                ┌──────────────────────┴──────────────────────┐
                │                                             │
                ▼                                             ▼
       DRIVER MASTER CLOSE                           PASSENGER SELF-CLOSE
       [CLOSE RIDE] Button                           [CLOSE YOUR RIDE] Button
                │                                             │
      ┌─────────┴─────────┐                         ┌─────────┴─────────┐
      │                   │                         │                   │
      ▼                   ▼                         ▼                   ▼
[RIDE HAPPENED]   [RIDE DIDN'T HAPPEN]     [I COMPLETED THIS RIDE][I DIDN'T TAKE THIS RIDE]
      │                   │                         │                   │
Ride status =       Ride status =             RideRequest =       RideRequest =
COMPLETED           CANCELLED                 COMPLETED           CANCELLED_BY_PASSENGER
      │                   │                         │                   │
Unconfirmed         Unconfirmed               PassengerNeed =     PassengerNeed =
requests become     requests become           COMPLETED           CANCELLED
CLOSED_UNCONFIRMED  CANCELLED_BY_DRIVER             │                   │
(Zero CO2 credit)   (Zero CO2 credit)         CO2 Credit Awarded  Zero Credit
                                                    │                   │
                                                    └─────────┬─────────┘
                                                              │
                                                              ▼
                                                   checkAndAutoCloseRide()
                                                              │
                                            Are all accepted passengers terminal?
                                                              │
                                              ├───────────────┴───────────────┐
                                              │ YES                           │ NO
                                              ▼                               ▼
                                  Did at least 1 passenger complete?     Leave Ride in
                                              │                          active state
                                      ├───────┴───────┐
                                      │ YES           │ NO (all cancelled)
                                      ▼               ▼
                                  Ride status =   Ride status =
                                  COMPLETED       CANCELLED
```

---

## 2. CONCURRENCY & RACE CONDITIONS MATRIX

| Scenario | Risk | Current Mechanism | Outcome |
|---|---|---|---|
| **Last-Seat Contention** (P1 & P2 tap Join simultaneously with 1 seat left) | Overbooking (negative available seats) | Firestore `runTransaction` reads `availableSeats`, validates `availableSeats > 0`, and decrements atomically. | The first transaction succeeds and reserves the seat. The second transaction detects `availableSeats == 0` and fails with `"This ride is already full."` Overbooking is mathematically impossible. |
| **Two Drivers Accepting Same Passenger** (D1 & D2 tap Drive & Accept simultaneously) | Duplicate assignment to two different vehicles | Firestore `runTransaction` reads `passengerNeeds/{needId}` and verifies `status == LOOKING`. | The first transaction marks the need `MATCHED`. The second transaction detects `status != LOOKING` and fails with `"This ride request is no longer available."` |
| **Simultaneous Passenger Self-Close & Driver Master Close** | Status collision or contradictory CO₂ credits | Security rules prevent driver from writing `COMPLETED` on `rideRequests`. Passenger's `completedPassengerIds` in Ride is preserved when driver executes `RIDE HAPPENED`. | Full audit integrity maintained. Passenger's personal completion always takes precedence. |
| **Rapid Double-Tap on Join / Drive & Accept Button** | Multiple rapid requests from same client | Compose buttons disable upon click (`enabled = !isLoading`). Repository verifies `!(acceptedList.contains(uid))`. | Second invocation is blocked at the UI state layer and rejected by the transaction. |

---

## 3. OFFLINE BEHAVIOR & CONNECTIVITY HANDLING

| Action | Online Behavior | Offline Behavior |
|---|---|---|
| **Open Pickup Area Selector** | Displays Township, Plant, and Other | **PASS**. Static local `MasterData` items; 100% functional offline with zero Firestore reads. |
| **Local Reminders (07:00, 13:00, etc.)** | Fires system notification at target time | **PASS**. Scheduled via Android `WorkManager` locally; executes with zero network dependency. |
| **Post Ride / Post Need Offline** | Writes immediately to Firestore | Firestore local persistence caches write; UI suspends or awaits network confirmation based on coroutine `.await()`. |
| **Join Ride Offline** | Executes atomic transaction | Transactions require online server contact. Fails with friendly error: `"Connect to the internet to confirm this ride."` |
| **Drive & Accept Offline** | Executes atomic transaction | Transaction requires server contact. Fails with friendly error. |
| **Close Ride Offline** | Commits batch / transaction | Suspends until network is restored; UI indicates operation pending or network error. |
