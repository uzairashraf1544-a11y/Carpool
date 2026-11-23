# TRAVELBUDDY — FIRESTORE OPERATIONS AUDIT, CAPACITY MODEL & AGENT REVIEW SPECIFICATION

> **Target Audience:** Autonomous Agents, Security Auditors, Backend/Mobile Engineers  
> **Application:** TravelBuddy (Corporate Community Carpool Platform — Township ↔ Plant)  
> **Platform Stack:** Android (Jetpack Compose, Kotlin Coroutines/Flow, Architecture Components), Firebase (Auth, Firestore, Cloud Storage), DataStore  
> **Package ID:** `com.aistudio.carpool.kjqxcv`  
> **Target Scale:** 300 Active Employees (50 Drivers, 247 Passengers, 3 Admins) + Onboarding Queue  
> **Hosting Plan Quota:** Firebase Spark (Free Tier) — **50,000 document reads/day**, **20,000 document writes/day**, **20,000 deletes/day**

---

## 1. EXECUTIVE CONTEXT & PURPOSE

This document provides an exhaustive, function-by-function audit of every Firestore read, write, update, delete, and real-time snapshot listener in the TravelBuddy codebase. 

It defines:
1. **Full Application Functionality & User Journeys**: Comprehensive breakdown of Driver, Passenger, Administrator, and Auth flows.
2. **Exhaustive Query & Mutation Catalog**: Every Firestore touchpoint, its trigger event, bounds, caching mechanisms, and exact read/write cost.
3. **Fan-Out-Aware Billing Capacity Model**: Mathematical proof that a 300-user workforce and 3 administrators operating across 3 daily shifts consumes under **17,000 reads/day** on normal days and **24,000 reads/day** on busy days, remaining safely within Spark Free Tier quotas (50,000 reads/day).
4. **Agent Review Instructions & Local Test Directives**: Specific instructions and automated test scenarios for any reviewing agent to independently verify that no flow can breach Firestore limits.

---

## 2. FULL APPLICATION FUNCTIONALITIES

### Module 1: Authentication, Onboarding & Access Control
- **Email/Password & Google Sign-In**: Authenticates corporate employees; links Google Play App Signing certificate fingerprints (`C0:49:A0:0C:F8:...`).
- **Profile Completion**: Captures Employee ID, Department, Township Pickup Zone, Default Role (Driver/Passenger), and vehicle seats.
- **Corporate Approval Gate**: New accounts enter `accountStatus = PENDING_APPROVAL`. Users cannot access carpool matching until approved by an administrator.
- **Session Restriction Guard**: Real-time snapshot listener on `users/{uid}` immediately signs out users if `accountStatus` transitions to `BLOCKED` or `REMOVED` (Invariant F4).

### Module 2: Driver Commute Flow
- **Ride Creation**: Driver publishes availability (`totalSeats`, `pickupZone`, `pickupSide`, `customPickupLocation`, `date`, `expiresAt`).
- **Passenger Needs Discovery**: Driver discovers passengers seeking rides on the same pickup side (`getMatchingPassengerNeedsFlow`), bounded by `DISCOVERY_LIMIT = 10` with "Load more" paging via `startAfter` (Invariant F7).
- **Seat Allocation & Acceptance**: Driver accepts passenger requests; decrements `availableSeats`; updates ride status to `PARTIALLY_FULL` or `FULL`.
- **Ride Closure & Completion**: Driver closes ride availability or marks "Ride Happened", completing the journey and triggering CO₂ attribution.

### Module 3: Passenger Commute Flow
- **Need Posting**: Passenger announces a travel requirement (`pickupZone`, `pickupSide`, `date`, `status = LOOKING`).
- **Driver Discovery**: Passenger browses active drivers on the same pickup side (`getAvailableDriversFlow`), bounded by `DISCOVERY_LIMIT = 10` with "Load more" paging via `startAfter` (Invariant F6).
- **Exclusivity Lock & Joining**: Atomic verification against `activePassengerRides/{passengerId}` ensures a passenger can only be booked into **one active ride at a time** (Invariant F1, F2).
- **Ride Confirmation**: Passenger confirms trip completion, releasing the exclusivity lock and logging personal sustainability credit.

### Module 4: Sustainability Tracking & Leaderboard
- **Avoided Trips Model**: Each shared ride passenger delivered avoids 1 single-occupancy vehicle trip (default baseline = 3.0 km, CO₂ factor = 0.12 kg/km).
- **Personal Metrics Caching**: `totalCo2SavedKg` and `totalCompletedRides` are cached directly on the `User` profile document (0 additional reads for personal stats).
- **Consolidated Leaderboard**: Top 3 savers are pre-aggregated and stored in a single document: `systemConfig/leaderboard`. Screen views use cache-first one-shot `get()` with 6-hour TTL (0 server reads when fresh). Ride completion uses a 1-read transaction and writes ONLY if the completing user enters or changes top-3.

### Module 5: Admin Dashboard & Governance
- **Executive Snapshot**: Total members, active participants, completed rides, avoided distance, and total CO₂ saved.
- **Operational Status**: Today's available drivers, pending requests, active rides, and completed trips (bounded strictly to `date == today` in `Asia/Karachi`, limit 25).
- **Pending Approvals Queue**: Verifies employee ID credentials and activates accounts (`accountStatus = ACTIVE`).
- **Member Directory**: Paged 25 per page (`fetchUsersPage`) with server-side prefix search (`searchUsers`) instead of streaming all 300 users.
- **Audit Trail**: Append-only log in `auditLogs/{auditId}` tracking every administrative action (limited to 20 per page).

### Module 6: Single-Read Bulk Management Reporting & CSV Export
- **Single Consolidated Document (`systemConfig/managementReport`)**:
  - Stores pre-aggregated stats for all 300+ employees: `rows` (`personName`, `co2Saved`, `ridesAsDriver`, `ridesAsPassenger`), `totalMembers`, `totalCo2Saved`, `totalDriverRides`, `totalPassengerRides`, and `updatedAt`.
- **Local Storage Cache First**: Screen views read from local device cache (`ReportCacheManager`) costing **0 Firestore reads**.
- **On-Demand Refresh & CSV Export**: Tapping "Refresh" or "Download CSV" performs a single point-read (`Source.SERVER`) of `systemConfig/managementReport` costing **EXACTLY 1 READ** for the entire workforce (Invariant F11).

### Module 7: Admin Data Management (Pre-Launch & GDPR/Data Erasure)
- **Part 1: Global Reset (3 Distinct Actions)**:
  1. *Clear Rides Data Only*: Deletes rides, bookings, needs, history. Keeps all accounts.
  2. *Clear Everything Including User Accounts*: Deletes all rides and all user accounts except current admin.
  3. *Clear Everything Except Admin Accounts*: Deletes all rides and non-admin users; keeps all administrators.
  - Optional toggle: *"Also remove admin's data (keep admin account)"*.
- **Part 2: Per-User Deletion**:
  - Wipes a specific user's rides and bookings.
  - **Passenger History Preservation**: If the deleted user was a driver with booked passengers, rides are anonymized to *"Former Colleague"* so passenger history remains intact (Invariant F10).
- **Atomic Transactions & Audit Logging**: All actions execute in atomic batch chunks (≤ 400 operations) and log to permanent `auditLogs`.

---

## 3. EXHAUSTIVE CATALOG OF ALL FIRESTORE READ & WRITE TRIGGERS

The table below catalogs every Firestore interaction across all repository classes and services:

| Component | Method / Operation | Firestore Path | Query Type | Trigger Event | Bound / Limit | Read Cost | Write Cost |
|---|---|---|---|---|---|---|---|
| **Auth** | `authStateFlow` | `users/{uid}` | Real-time Listener | Auth state change / Startup | 1 Doc | 1 read | 0 |
| **Auth** | `login` | `users/{uid}` | Point Read (`get()`) | User taps "Sign In" | 1 Doc | 1 read | 0 |
| **Auth** | `register` | `users/{uid}`, `employeeRegistry/{id}` | Batch Set (`runBatch`) | User registration | 2 Docs | 0 | 2 writes |
| **Auth** | `signInWithGoogleCredential` | `users/{uid}` | Point Read (`get()`) | Google Sign-In button | 1 Doc | 1 read | 0 |
| **Auth** | `getApproverInfo` | `systemConfig/appApproval` | Point Read (`get()`) | Pending Approval screen | 1 Doc | 1 read | 0 |
| **Driver** | `getDriverTodayRideFlow` | `rides` | Real-time Listener | Driver opens Home | `driverId` + `date` | 0 - 1 read | 0 |
| **Driver** | `getIncomingRequestsForDriverFlow` | `rideRequests` | Real-time Listener | Driver opens Home | `driverId` + `date` | 0 - 4 reads | 0 |
| **Driver** | `getMatchingPassengerNeedsFlow` | `passengerNeeds` | Real-time Listener | Driver in DRIVER mode | `date` + `side`, limit 10 | 0 - 10 reads | 0 |
| **Driver** | `fetchMorePassengerNeeds` | `passengerNeeds` | Paged Read (`startAfter`) | Driver taps "Load more" | limit 10 | ≤ 10 reads | 0 |
| **Driver** | `createRide` | `rides/{rideId}` | Doc Set (`set()`) | Driver creates ride | 1 Doc | 0 | 1 write |
| **Driver** | `driverCloseRide` | `rides/{rideId}` | Doc Update (`update()`) | Driver closes/completes ride | 1 Doc | 0 | 1 write |
| **Driver** | `logCo2SavingsForCompletedRide` | `co2Records`, `users/{uid}` | Batch Writes | Ride completion | 1+N Docs | 0 | 2-5 writes |
| **Driver** | `syncLeaderboardFromCo2Records` | `systemConfig/leaderboard` | Transaction (1 Read) | Ride completion | 1 Doc | 1 read | 0 or 1 write |
| **Passenger** | `getActivePassengerRideFlow` | `rideRequests` | Real-time Listener | Passenger opens Home | `passengerId` + status, limit 5 | 0 - 1 read | 0 |
| **Passenger** | `getPassengerNeedFlow` | `passengerNeeds` | Real-time Listener | Passenger opens Home | `passengerId` + `date` | 0 - 1 read | 0 |
| **Passenger** | `getAvailableDriversFlow` | `rides` | Real-time Listener | Passenger in PASSENGER mode | `date` + status, limit 10 | 0 - 10 reads | 0 |
| **Passenger** | `fetchMoreAvailableDrivers` | `rides` | Paged Read (`startAfter`) | Passenger taps "Load more" | limit 10 | ≤ 10 reads | 0 |
| **Passenger** | `postPassengerNeed` | `passengerNeeds/{needId}` | Doc Set (`set()`) | Passenger requests ride | 1 Doc | 0 | 1 write |
| **Passenger** | `joinRide` | `rides/{id}`, `activePassengerRides/{uid}` | Transaction (2 Reads) | Passenger taps "Join" | 2 Docs read, atomic write | 2 reads | 3 writes |
| **Passenger** | `confirmPassengerTripCompletion` | `rideRequests/{id}`, `activePassengerRides/{uid}` | Doc Update + Delete | Passenger confirms completed | 2 Docs | 0 | 1 write, 1 del |
| **Shared** | `getSystemConfigFlow` | `systemConfig/sustainability` | Cache-first `get()` | App Home / Admin Screen | 1 Doc (Cache 6h) | 0 (cached) / 1 (server) | 0 |
| **Shared** | `getTopCo2SaversFlow` | `systemConfig/leaderboard` | Cache-first `get()` | App Home Screen | 1 Doc (Cache 6h) | 0 (cached) / 1 (server) | 0 |
| **Admin** | `getManagementReportFlow` | `systemConfig/managementReport` | Cache-first `get()` | Admin Dashboard open | 1 Doc | 0 (cached) / 1 (server) | 0 |
| **Admin** | `fetchManagementReport` | `systemConfig/managementReport` | Point Read (`Source.SERVER`) | Admin taps "Refresh / CSV" | 1 Doc | **EXACTLY 1 READ** | 0 |
| **Admin** | `getTodayRidesFlow` | `rides` | Real-time Listener | Admin Overview tab only | `date == today`, limit 25 | 0 - 25 reads | 0 |
| **Admin** | `getTodayRequestsFlow` | `rideRequests` | Real-time Listener | Admin Overview tab only | `date == today`, limit 25 | 0 - 25 reads | 0 |
| **Admin** | `getPendingApprovalsFlow` | `users` | Real-time Listener | Admin Dashboard open | `accountStatus == PENDING` | 0 - 5 reads | 0 |
| **Admin** | `fetchUsersPage` | `users` | Paged Read (`get()`) | Admin Members tab | 25 per page | ≤ 25 reads | 0 |
| **Admin** | `searchUsers` | `users` | Prefix Range Read (`get()`) | Admin searches member | limit 25 | ≤ 25 reads | 0 |
| **Admin** | `getAuditLogsFlow` | `auditLogs` | Real-time Listener | Admin Audit tab only | Lazy stream, limit 20 | 0 - 20 reads | 0 |
| **Admin** | `approveUser` | `users/{uid}`, `systemConfig/appApproval` | Doc Updates | Admin approves user | 2 Docs | 0 | 2 writes |
| **Admin** | `updateUserAccountStatus` | `users/{uid}`, `auditLogs/{id}` | Doc Updates | Block / Unblock / Remove | 2 Docs | 0 | 2 writes |
| **Admin** | `executeGlobalReset` | `rides`, `rideRequests`, `passengerNeeds` | Atomic Batch Chunks | Admin Global Reset | Batch of 400 | N reads | N deletes |
| **Admin** | `executePerUserDataDeletion` | `rides`, `rideRequests`, `passengerNeeds` | Atomic Batch Chunks | Admin User Deletion | Targeted user records | N reads | N mutations |
| **Worker** | `RideReminderWorker` | `rides/{rideId}`, `rideRequests` | Point Reads (`get()`) | 30-min after ride time | Bounded | 1 - 2 reads | 0 |

---

## 4. MATHEMATICAL LOAD MODEL & SIMULATION RESULTS

### Baseline Population & Operational Parameters:
- **Total Population**: 300 active employees (50 Drivers, 247 Passengers, 3 Admins) + 5 Pending registrations.
- **Commute Shifts**: 3 Shifts per day (Morning 07:00–08:30, Lunch 13:00–14:00, Evening 17:05–18:30).
- **Daily Completed Shared Rides**: ~95 shared rides per day (~270 passenger joins).

### Billing Rules Modelled:
1. Initial result-set reads (capped by limit).
2. Fan-out reads: +1 read per changed document per active listener (only documents inside the limit window).
3. One-shot `get()` re-bills result each call; cache hits bill 0 reads.
4. Listener re-subscription re-bills initial result.

### Monte Carlo Simulation Comparison:

| Scenario | Mean Reads/Day | p95 Reads/Day | Free Quota Used | Headroom Remaining |
|---|---|---|---|---|
| **Baseline (unoptimized), normal day** | 26,945 | 27,723 | 54% | 46% |
| **Baseline (unoptimized), busy day** | 41,535 | 43,474 | 83% | 17% |
| **Optimized (TravelBuddy v6.1), normal day** | **16,696** | **17,190** | **33%** | **67%** |
| **Optimized (TravelBuddy v6.1), busy day** | **23,859** | **24,769** | **48%** | **52%** |

### Where the Reads Go (Optimized Normal Day: ~16,696 Reads):

| Component / Flow | Reads / Day | Share | Mitigation Implemented |
|---|---|---|---|
| **Passenger Discovery (Initial Result)** | ~5,880 | 35% | Limit reduced from 20 to 10 + Load More paging |
| **Passenger Discovery (Fan-out on Seat Changes)** | ~3,734 | 22% | Lifecycle scoping (`collectAsStateWithLifecycle`) detaches when joined/backgrounded |
| **Own User Flows (Active Ride, Requests, Needs)** | ~3,232 | 19% | Single listener per mode, unlistens opposite role |
| **Driver Needs Discovery (Initial + Fan-out)** | ~1,830 | 11% | Limit 10, active status only |
| **Admin Operations (Overview, Audit, Pending)** | ~906 | 5% | Overview limited to 25; Audit logs limited to 20 |
| **Auth & Profile Verification** | ~750 | 5% | Live listener on `users/{uid}` for immediate blocked sign-out |
| **Leaderboard & System Config** | ~364 | 2% | Cache-first `get()` (6h TTL) + 1-read transaction on top-3 change |
| **Admin Members Directory** | ~75 | < 1% | Replaced 300-doc stream with paged 25 docs/page |
| **Total Daily Reads** | **~16,696** | **100%** | **Safely below 50,000 Free Limit (67% headroom)** |

---

## 5. AGENT AUDIT DIRECTIVES & REVIEW PROMPT

Copy and send the prompt below to any reviewing agent to verify this architecture:

```markdown
### AGENT REVIEW PROMPT: AUDIT FIRESTORE CONSUMPTION & RUN LOCAL VERIFICATION

You are assigned to audit the TravelBuddy carpool Android codebase (package: `com.aistudio.carpool.kjqxcv`).
Review `FIRESTORE_AUDIT_AND_LIMIT_SAFEGUARDS.md` and verify that the application architecture guarantees zero breach of Firebase Firestore's Free Tier quotas (50,000 reads/day) for a community of 300 employees and 3 administrators.

Your task:
1. REVIEW IMPLEMENTATION:
   - All collection queries use strict `.limit(N)` clauses (e.g. `DISCOVERY_LIMIT = 10`).
   - Discovery flows support "Load More" paging via `startAfter` (Invariants F6, F7).
   - Leaderboard uses cache-first `get()` with 6h TTL; ride completions use a 1-read transaction.
   - Admin Members tab uses paged 25 docs/page (`fetchUsersPage`) instead of streaming all users.
   - Corporate date computations strictly use `TimeUtils.todayKarachiString()`.
2. RUN LOCAL SIMULATION TEST SUITE:
   Execute the automated unit test suite locally without network or Firebase credentials:
   `./gradlew testDebugUnitTest --tests "com.example.CommunityScaleRoleSimulationTest"`
3. REPORT FINDINGS:
   Confirm all test cases pass (`BUILD SUCCESSFUL`) and report the exact simulated metrics.
```

---

## 6. LOCAL AUTOMATED TEST SPECIFICATION

The automated test suite in `app/src/test/java/com/example/CommunityScaleRoleSimulationTest.kt` tests all flows locally on the JVM:

### Test Case Index:
1. `testAllUserRolesAndAccessControl`: Validates role separation across Admin, Driver, Passenger, Pending, and Blocked users.
2. `testDriverJourneyQueryEfficiency`: Proves driver ride creation, needs discovery, and ride completion costs ≤ 25 reads.
3. `testPassengerJourneyQueryEfficiency`: Proves passenger need creation, driver discovery, and joining costs ≤ 15 reads.
4. `testAdminOperationsSingleReadBulk`: Proves Admin Overview costs 7 reads, cached report costs 0 reads, and fresh CSV export costs **EXACTLY 1 READ** for 300 employees.
5. `testFullWorkdaySimulation300Users3Admins`: Runs end-to-end 3-shift corporate workday simulation.
6. `testDataManagementScaleWith300Users`: Tests atomic batch chunking and passenger history preservation ("Former Colleague" anonymization).
7. `testFanOutAwareMonteCarloModel`: Tests fan-out-aware Monte Carlo billing model for normal day (≤ 20,000 reads) and busy day (≤ 30,000 reads).
8. `testReSubscriptionGuardrail`: Simulates tab switching, screen rotation, background/foreground; verifies listener starts ≤ 1 per visit.
9. `testJoinConcurrencyRaceCondition`: Simulates 10 passengers racing for 1 seat; verifies exactly 1 succeeds, 9 fail with friendly message, seats never < 0.
10. `testAllFunctionalInvariantsProof`: Explicitly proves all 11 functional invariants (F1 through F11).

### Verification Command:
```bash
./gradlew testDebugUnitTest --tests "com.example.CommunityScaleRoleSimulationTest"
```
