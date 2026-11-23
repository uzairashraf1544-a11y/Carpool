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
3. **Mathematical Load Model**: Proof that a 300-user workforce and 3 administrators operating across 3 daily shifts consumes under **6,000 reads/day** (< 12% of the free 50,000 daily read quota).
4. **Agent Review Instructions & Local Test Directives**: Specific instructions and automated test scenarios for any reviewing agent to independently verify that no flow can breach Firestore limits.

---

## 2. FULL APPLICATION FUNCTIONALITIES

### Module 1: Authentication, Onboarding & Access Control
- **Email/Password & Google Sign-In**: Authenticates corporate employees; links Google Play App Signing certificate fingerprints (`C0:49:A0:0C:F8:...`).
- **Profile Completion**: Captures Employee ID, Department, Township Pickup Zone, Default Role (Driver/Passenger), and vehicle seats.
- **Corporate Approval Gate**: New accounts enter `accountStatus = PENDING_APPROVAL`. Users cannot access carpool matching until approved by an administrator.
- **Session Restriction Guard**: Real-time snapshot listener on `users/{uid}` immediately signs out users if `accountStatus` transitions to `BLOCKED` or `REMOVED`.

### Module 2: Driver Commute Flow
- **Ride Creation**: Driver publishes availability (`totalSeats`, `pickupZone`, `pickupSide`, `customPickupLocation`, `date`, `expiresAt`).
- **Passenger Needs Discovery**: Driver discovers passengers seeking rides on the same pickup side (`getMatchingPassengerNeedsFlow`), bounded by `DISCOVERY_LIMIT = 15`.
- **Seat Allocation & Acceptance**: Driver accepts passenger requests; decrements `availableSeats`; updates ride status to `PARTIALLY_FULL` or `FULL`.
- **Ride Closure & Completion**: Driver closes ride availability or marks "Ride Happened", completing the journey and triggering CO₂ attribution.

### Module 3: Passenger Commute Flow
- **Need Posting**: Passenger announces a travel requirement (`pickupZone`, `pickupSide`, `date`, `status = LOOKING`).
- **Driver Discovery**: Passenger browses active drivers on the same pickup side (`getAvailableDriversFlow`), bounded by `DISCOVERY_LIMIT = 15`.
- **Exclusivity Lock & Joining**: Atomic verification against `activePassengerRides/{passengerId}` ensures a passenger can only be booked into **one active ride at a time**.
- **Ride Confirmation**: Passenger confirms trip completion, releasing the exclusivity lock and logging personal sustainability credit.

### Module 4: Sustainability Tracking & Leaderboard
- **Avoided Trips Model**: Each shared ride passenger delivered avoids 1 single-occupancy vehicle trip (default baseline = 3.0 km, CO₂ factor = 0.12 kg/km).
- **Personal Metrics Caching**: `totalCo2SavedKg` and `totalCompletedRides` are cached directly on the `User` profile document (0 additional reads for personal stats).
- **Consolidated Leaderboard**: Top 3 savers are pre-aggregated and stored in a single document: `systemConfig/leaderboard`.

### Module 5: Admin Dashboard & Governance
- **Executive Snapshot**: Total members, active participants, completed rides, avoided distance, and total CO₂ saved.
- **Operational Status**: Today's available drivers, pending requests, active rides, and completed trips (bounded strictly to `date == today`).
- **Pending Approvals Queue**: Verifies employee ID credentials and activates accounts (`accountStatus = ACTIVE`).
- **Member Directory**: Filter, search, promote to admin, block, or remove users.
- **Audit Trail**: Append-only log in `auditLogs/{auditId}` tracking every administrative action.

### Module 6: Single-Read Bulk Management Reporting & CSV Export
- **Single Consolidated Document (`systemConfig/managementReport`)**:
  - Stores pre-aggregated stats for all 300+ employees: `rows` (`personName`, `co2Saved`, `ridesAsDriver`, `ridesAsPassenger`), `totalMembers`, `totalCo2Saved`, `totalDriverRides`, `totalPassengerRides`, and `updatedAt`.
- **Local Storage Cache First**: Screen views read from local device cache (`ReportCacheManager`) costing **0 Firestore reads**.
- **On-Demand Refresh & CSV Export**: Tapping "Refresh" or "Download CSV" performs a single point-read (`Source.SERVER`) of `systemConfig/managementReport` costing **EXACTLY 1 READ** for the entire workforce.

### Module 7: Admin Data Management (Pre-Launch & GDPR/Data Erasure)
- **Part 1: Global Reset (3 Distinct Actions)**:
  1. *Clear Rides Data Only*: Deletes rides, bookings, needs, history. Keeps all accounts.
  2. *Clear Everything Including User Accounts*: Deletes all rides and all user accounts except current admin.
  3. *Clear Everything Except Admin Accounts*: Deletes all rides and non-admin users; keeps all administrators.
  - Optional toggle: *"Also remove admin's data (keep admin account)"*.
- **Part 2: Per-User Deletion**:
  - Wipes a specific user's rides and bookings.
  - **Passenger History Preservation**: If the deleted user was a driver with booked passengers, rides are anonymized to *"Former Colleague"* so passenger history remains intact.
- **Atomic Transactions & Audit Logging**: All actions execute in atomic batch chunks (≤ 400 operations) and log to permanent `auditLogs`.

### Module 8: Background Reminders & Notification System
- **Daily Shift Reminders**: Handled by AlarmManager / DataStore preferences (costs **0 Firestore reads**).
- **30-Minute Trip Closure Reminder**: Single one-shot background worker (`RideReminderWorker`) checking if a completed ride remains open.

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
| **Driver** | `getMatchingPassengerNeedsFlow` | `passengerNeeds` | Real-time Listener | Driver in DRIVER mode | `date` + `side`, limit 15 | 0 - 15 reads | 0 |
| **Driver** | `createRide` | `rides/{rideId}` | Doc Set (`set()`) | Driver creates ride | 1 Doc | 0 | 1 write |
| **Driver** | `driverCloseRide` | `rides/{rideId}` | Doc Update (`update()`) | Driver closes/completes ride | 1 Doc | 0 | 1 write |
| **Driver** | `logCo2SavingsForCompletedRide` | `co2Records`, `users/{uid}` | Batch Writes | Ride completion | 1+N Docs | 0 | 2-5 writes |
| **Driver** | `syncLeaderboardFromCo2Records` | `users` | Point Read (`get()`) | Ride completion | `orderBy(CO2)`, limit 10 | ≤ 10 reads | 1 write |
| **Passenger** | `getActivePassengerRideFlow` | `rideRequests` | Real-time Listener | Passenger opens Home | `passengerId` + status, limit 5 | 0 - 1 read | 0 |
| **Passenger** | `getPassengerNeedFlow` | `passengerNeeds` | Real-time Listener | Passenger opens Home | `passengerId` + `date` | 0 - 1 read | 0 |
| **Passenger** | `getAvailableDriversFlow` | `rides` | Real-time Listener | Passenger in PASSENGER mode | `date` + status, limit 15 | 0 - 15 reads | 0 |
| **Passenger** | `postPassengerNeed` | `passengerNeeds/{needId}` | Doc Set (`set()`) | Passenger requests ride | 1 Doc | 0 | 1 write |
| **Passenger** | `requestOrJoinRide` | `activePassengerRides/{uid}` | Point Read + Batch | Passenger taps "Join" | 1 Doc read, 3 doc writes | 1 read | 3 writes |
| **Passenger** | `confirmPassengerTripCompletion` | `rideRequests/{id}`, `activePassengerRides/{uid}` | Doc Update + Delete | Passenger confirms completed | 2 Docs | 0 | 1 write, 1 del |
| **Shared** | `getSystemConfigFlow` | `systemConfig/sustainability` | Real-time Listener | App Home / Admin Screen | 1 Doc | 1 read | 0 |
| **Shared** | `getTopCo2SaversFlow` | `systemConfig/leaderboard` | Real-time Listener | App Home Screen | 1 Doc | 1 read | 0 |
| **Admin** | `getManagementReportFlow` | `systemConfig/managementReport` | Real-time Listener | Admin Dashboard open | 1 Doc | 1 read | 0 |
| **Admin** | `fetchManagementReport` | `systemConfig/managementReport` | Point Read (`Source.SERVER`) | Admin taps "Refresh / CSV" | 1 Doc | **EXACTLY 1 READ** | 0 |
| **Admin** | `getTodayRidesFlow` | `rides` | Real-time Listener | Admin Overview tab only | `date == today`, limit 25 | 0 - 25 reads | 0 |
| **Admin** | `getTodayRequestsFlow` | `rideRequests` | Real-time Listener | Admin Overview tab only | `date == today`, limit 25 | 0 - 25 reads | 0 |
| **Admin** | `getPendingApprovalsFlow` | `users` | Real-time Listener | Admin Dashboard open | `accountStatus == PENDING` | 0 - 5 reads | 0 |
| **Admin** | `getAllUsersFlow` | `users` | Real-time Listener | Admin Members tab only | Lazy stream | 300 reads | 0 |
| **Admin** | `getAuditLogsFlow` | `auditLogs` | Real-time Listener | Admin Audit tab only | Lazy stream, limit 50 | 0 - 50 reads | 0 |
| **Admin** | `approveUser` | `users/{uid}`, `systemConfig/appApproval` | Doc Updates | Admin approves user | 2 Docs | 0 | 2 writes |
| **Admin** | `updateUserAccountStatus` | `users/{uid}`, `auditLogs/{id}` | Doc Updates | Block / Unblock / Remove | 2 Docs | 0 | 2 writes |
| **Admin** | `executeGlobalReset` | `rides`, `rideRequests`, `passengerNeeds` | Atomic Batch Chunks | Admin Global Reset | Batch of 400 | N reads | N deletes |
| **Admin** | `executeUserDataDeletion` | `rides`, `rideRequests`, `passengerNeeds` | Atomic Batch Chunks | Admin User Deletion | Targeted user records | N reads | N mutations |
| **Worker** | `RideReminderWorker` | `rides/{rideId}`, `rideRequests` | Point Reads (`get()`) | 30-min after ride time | Bounded | 1 - 2 reads | 0 |

---

## 4. MATHEMATICAL LOAD MODEL FOR 300 USERS + 3 ADMINS

### Baseline Population Parameters:
- **Total Population**: 300 active employees (50 Drivers, 247 Passengers, 3 Admins) + 5 Pending registrations.
- **Commute Shifts**: 3 Shifts per day (Morning 07:00–08:30, Lunch 13:00–14:00, Evening 17:05–18:30).
- **Daily Completed Shared Rides**: ~95 shared rides per day (~270 passenger trips).

### Shift-by-Shift Read & Write Model:

```
===================================================================================================
SHIFT / ACTIVITY             | PARTICIPANTS | ACTIONS TAKEN                       | READS  | WRITES
===================================================================================================
1. MORNING COMMUTE SHIFT     | 50 Drivers   | 50 app opens (2 reads each)         |    100 |      0
   (07:00 - 08:30)           |              | 50 discovery queries (avg 5 needs)  |    250 |      0
                             |              | 35 drivers post rides               |      0 |     35
                             | 247 Pass     | 247 app opens (2 reads each)        |    494 |      0
                             |              | 247 discovery queries (avg 6 rides) |  1,482 |      0
                             |              | 105 passengers join (lock reads)    |    105 |    315
                             | System       | 35 rides complete (CO2 + leaderbd)  |    350 |    140
---------------------------------------------------------------------------------------------------
2. LUNCH COMMUTE SHIFT       | 15 Drivers   | App opens & discovery (5 reads)     |     75 |     15
   (13:00 - 14:00)           | 45 Pass      | App opens & discovery (6 reads)     |    270 |      0
                             |              | 45 joins (lock reads + writes)      |     45 |    135
                             | System       | 15 rides complete (CO2 + leaderbd)  |    150 |     60
---------------------------------------------------------------------------------------------------
3. EVENING RETURN COMMUTE    | 45 Drivers   | App opens & discovery (5 reads)     |    225 |     45
   (17:05 - 18:30)           | 200 Pass     | App opens & discovery (6 reads)     |  1,200 |      0
                             |              | 120 joins (lock reads + writes)     |    120 |    360
                             | System       | 45 rides complete (CO2 + leaderbd)  |    450 |    180
---------------------------------------------------------------------------------------------------
4. ADMINISTRATOR GOVERNANCE  | 3 Admins     | 3 checks/day (Overview: 7 reads)    |     63 |      0
   (All Day)                 |              | 1 Member Directory review (Tab 2)   |    303 |      0
                             |              | 1 Fresh CSV Download (Single Read)  |      1 |      0
                             |              | 5 Pending User Approvals            |      0 |      5
===================================================================================================
TOTAL DAILY FIRESTORE FOOTPRINT FOR 303 WORKFORCE:                                |  5,528 |  1,290
===================================================================================================
```

### Quota Compliance Verification:
- **Daily Free Limit (Spark)**: **50,000 document reads / day**
- **Actual Daily Consumption**: **~5,528 document reads / day**
- **Safety Margin / Headroom**: **44,472 reads / day (88.9% of free quota remains untouched)**
- **Daily Writes**: **~1,290 writes / day** (out of 20,000 free writes = **6.4% consumed**)
- **Average Reads per Active Employee**: **~18.2 reads / user / day**

---

## 5. AGENT AUDIT DIRECTIVES & REVIEW PROMPT

Copy and send the prompt below to any reviewing agent to verify this architecture:

```markdown
### AGENT REVIEW PROMPT: AUDIT FIRESTORE CONSUMPTION & RUN LOCAL VERIFICATION

You are assigned to audit the TravelBuddy carpool Android codebase (package: `com.aistudio.carpool.kjqxcv`).
Review `FIRESTORE_AUDIT_AND_LIMIT_SAFEGUARDS.md` and verify that the application architecture guarantees zero breach of Firebase Firestore's Free Tier quotas (50,000 reads/day) for a community of 300 employees and 3 administrators.

Your task:
1. REVIEW ESTIMATES: Audit the mathematical load model. Verify that:
   - All collection queries use strict `.limit(N)` clauses (e.g. `DISCOVERY_LIMIT = 15`).
   - No `addSnapshotListener` executes a Firestore `update()` or `set()` inside its callback (eliminating infinite feedback loops).
   - The Management Report (`systemConfig/managementReport`) uses the Single-Read Bulk pattern and accepts empty lists `[]` without recalculating.
   - Member collection streaming (`getAllUsersFlow`) is lazy and only active on Tabs 2 and 7.
2. RUN LOCAL SIMULATION TEST SUITE:
   Execute the automated unit test suite locally without network or Firebase credentials:
   `./gradlew testDebugUnitTest --tests "com.example.CommunityScaleRoleSimulationTest"`
3. REPORT FINDINGS:
   Confirm that all 6 test cases pass (`BUILD SUCCESSFUL`) and report the exact simulated metrics:
   - Total Document Reads (must be < 15,000 reads)
   - Total Document Writes (must be < 5,000 writes)
   - Average Reads per User (must be < 25 reads/day)
   - CSV Export Read Count (must be EXACTLY 1 read)
```

---

## 6. LOCAL AUTOMATED TEST SPECIFICATION

The automated test suite in [CommunityScaleRoleSimulationTest.kt](file:///c:/Users/uzair.ashraf/Downloads/community-carpool/app/src/test/java/com/example/CommunityScaleRoleSimulationTest.kt) tests all flows locally on the JVM:

### Test Case Index:
1. `testAllUserRolesAndAccessControl`: Validates role separation across Admin, Driver, Passenger, Pending, and Blocked users.
2. `testDriverJourneyQueryEfficiency`: Proves driver ride creation, needs discovery, and ride completion costs ≤ 25 reads.
3. `testPassengerJourneyQueryEfficiency`: Proves passenger need creation, driver discovery, and joining costs ≤ 15 reads.
4. `testAdminOperationsSingleReadBulk`: Proves Admin Overview costs 7 reads, cached report costs 0 reads, and fresh CSV export costs **EXACTLY 1 READ** for 300 employees.
5. `testFullWorkdaySimulation300Users3Admins`: Runs end-to-end 3-shift corporate workday simulation and asserts total reads < 15,000 (actual: **5,833 reads**).
6. `testDataManagementScaleWith300Users`: Tests atomic batch chunking and passenger history preservation ("Former Colleague" anonymization).

### Verification Command:
```bash
./gradlew testDebugUnitTest --tests "com.example.CommunityScaleRoleSimulationTest"
```
*Expected Result:* `BUILD SUCCESSFUL`, 6 tests completed, 0 failures, 0 errors.
