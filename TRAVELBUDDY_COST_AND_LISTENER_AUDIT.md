# TRAVELBUDDY — FIRESTORE COST & SNAPSHOT LISTENER AUDIT
**Inspection Date**: 2026-09-03  
**Firebase Plan Target**: Firebase Spark Plan (Free Tier)  
**Free Tier Quotas**:
- **Document Reads**: `50,000 / day`
- **Document Writes**: `20,000 / day`
- **Document Deletes**: `20,000 / day`
- **Simultaneous Connections**: `100 peak`

---

## 1. SNAPSHOT LISTENER INVENTORY

| # | Screen / Location | Repository Function | Collection | Query Filter & Bounds | Lifecycle / Disposal | Initial Read Cost | Churn Read Cost |
|---|---|---|---|---|---|---|---|
| 1 | `MainActivity` / `AppNavigation` | `UserRepository.getCurrentUserFlow()` | `users` | `.document(currentUid)` | Bound to authenticated session | 1 read | 1 read per profile edit |
| 2 | `HomeScreen` | `RideRepository.getDriverTodayRideFlow()` | `rides` | `whereEqualTo("driverId", uid).whereEqualTo("date", today)` | Bound to HomeScreen composition | 1 read (or 0 if none) | 1 read per driver ride update |
| 3 | `HomeScreen` | `RideRepository.getIncomingRequestsForDriverFlow()` | `rideRequests` | `whereEqualTo("driverId", uid).whereEqualTo("date", today)` | Bound to HomeScreen composition | 0–4 reads | 1 read per request change |
| 4 | `HomeScreen` | `RideRepository.getPassengerRequestsFlow()` | `rideRequests` | `whereEqualTo("passengerId", uid).whereEqualTo("date", today)` | Bound to HomeScreen composition | 0–2 reads | 1 read per request change |
| 5 | `HomeScreen` | `RideRepository.getPassengerNeedFlow()` | `passengerNeeds` | `whereEqualTo("passengerId", uid).whereEqualTo("date", today)` | Bound to HomeScreen composition | 0–1 read | 1 read per need change |
| 6 | `HomeScreen` | `RideRepository.getMatchingPassengerNeedsFlow()` (Target Side) | `passengerNeeds` | `whereEqualTo("date", today).whereEqualTo("pickupSide", side).whereEqualTo("status", "LOOKING").limit(20)` | Bound to HomeScreen composition | 0–10 reads | 1 read per new/updated need |
| 7 | `HomeScreen` | `RideRepository.getMatchingPassengerNeedsFlow()` (OTHER Wildcard) | `passengerNeeds` | `whereEqualTo("date", today).whereEqualTo("pickupSide", "OTHER").whereEqualTo("status", "LOOKING").limit(20)` | Bound to HomeScreen composition | 0–5 reads | 1 read per new/updated need |
| 8 | `HomeScreen` | `RideRepository.getAvailableDriversFlow()` (Target Side) | `rides` | `whereEqualTo("date", today).whereEqualTo("pickupSide", side).whereIn("status", ["AVAILABLE", "PARTIALLY_FULL"]).limit(20)` | Bound to HomeScreen composition | 0–10 reads | 1 read per driver update |
| 9 | `HomeScreen` | `RideRepository.getAvailableDriversFlow()` (OTHER Wildcard) | `rides` | `whereEqualTo("date", today).whereEqualTo("pickupSide", "OTHER").whereIn("status", ["AVAILABLE", "PARTIALLY_FULL"]).limit(20)` | Bound to HomeScreen composition | 0–5 reads | 1 read per driver update |
| 10 | `HomeScreen` | `RideRepository.getPassengerHistoryFlow()` | `rideRequests` | `whereEqualTo("passengerId", uid).limit(50)` | Bound to HomeScreen composition | Up to 50 reads (10–30 typical) | 1 read per historical request |
| 11 | `HomeScreen` | `RideRepository.getDriverRideHistoryFlow()` | `rides` | `whereEqualTo("driverId", uid).limit(50)` | Bound to HomeScreen composition | Up to 50 reads (5–15 typical) | 1 read per historical ride |
| 12 | `HomeScreen` | `AdminRepository.getSystemConfigFlow()` | `systemConfig` | `.document("sustainability")` | Bound to HomeScreen composition | 1 read | 1 read on admin config change |
| 13 | `ActivityScreen` | `RideRepository.getPassengerHistoryFlow()` | `rideRequests` | `whereEqualTo("passengerId", uid).limit(50)` | Bound to ActivityScreen | Up to 50 reads | 1 read per request change |
| 14 | `ActivityScreen` | `RideRepository.getDriverRideHistoryFlow()` | `rides` | `whereEqualTo("driverId", uid).limit(50)` | Bound to ActivityScreen | Up to 50 reads | 1 read per ride change |
| 15 | `AdminDashboardScreen` | `AdminRepository.getAllUsersFlow()` | `users` | Entire collection | Bound to AdminDashboard | **$N$ reads ($N$ = total users, e.g. 300)** | 1 read per user change |
| 16 | `AdminDashboardScreen` | `AdminRepository.getAllRidesFlow()` | `rides` | Entire collection | Bound to AdminDashboard | **$M$ reads ($M$ = total rides, e.g. 500)** | 1 read per ride change |
| 17 | `AdminDashboardScreen` | `AdminRepository.getAllRideRequestsFlow()` | `rideRequests` | Entire collection | Bound to AdminDashboard | **$K$ reads ($K$ = total requests, e.g. 1000)**| 1 read per request change |
| 18 | `AdminDashboardScreen` | `AdminRepository.getAuditLogsFlow()` | `auditLogs` | Entire collection | Bound to AdminDashboard | **$L$ reads ($L$ = total logs, e.g. 200)** | 1 read per new log |
| 19 | `AdminDashboardScreen` | `AdminRepository.getSystemConfigFlow()` | `systemConfig` | `.document("sustainability")` | Bound to AdminDashboard | 1 read | 1 read per config change |

---

## 2. PROFILE READS PER DISCOVERY CARD

A major efficiency review was conducted on whether discovery cards trigger individual `users/{uid}` reads.

### Result: ZERO SECONDARY PROFILE READS
- The application stores **denormalized participant data** directly within `Ride`, `PassengerNeed`, and `RideRequest`:
  - `Ride`: stores `driverName`, `driverDepartment`, `driverPhone`, `displayPickup`.
  - `PassengerNeed`: stores `passengerName`, `passengerDepartment`, `displayPickup`.
  - `RideRequest`: stores `driverName`, `driverDepartment`, `passengerName`, `passengerDepartment`, `passengerPhone`.
- **UI Implementation**: `HomeScreen` and `DriverGroupDetailSheet` render profile details directly from the stream of `Ride` and `PassengerNeed` documents.
- **Cost Verdict**: Rendering 20 discovery cards incurs **exactly 20 document reads**, with **zero additional profile document lookups**.

---

## 3. FIRESTORE READ COST PER USER ACTION

| Action | Reads | Writes | Detailed Operation Breakdown |
|---|---|---|---|
| **App Launch to Home** | $\approx 30 - 35$ | 0 | 1 user profile read + 1 driver ride + 2 requests + 1 need + 10 discovery (side + other) + 15 passenger history + 5 driver history + 1 config |
| **Switch Mode (Passenger $\leftrightarrow$ Driver)** | 0 | 1 | `userRef.update("currentMode", newMode)` |
| **Passenger Post Need** | 1 | 1 | 1 point check for existing need + 1 `set(need)` |
| **Driver Publish Availability** | 1 | 1 | 1 point check for existing ride + 1 `set(ride)` |
| **Passenger Join Ride** | 2 | 3 | 1 transaction read on `rides/{rideId}` + 1 read on `passengerNeeds` + 1 write on `rides` + 1 write on `rideRequests` + 1 update on `passengerNeeds` |
| **Driver Accept Passenger Need** | 2 | 3 | 1 point read on existing ride + 1 transaction read on `passengerNeeds/{needId}` + 1 write on `rides` + 1 write on `passengerNeeds` + 1 write on `rideRequests` |
| **Passenger Confirm Completion** | 3 | 3 | 1 read on `rideRequests` + 1 transaction read on `rides` + 1 read on `passengerNeeds` + 1 write on each (3 total) |
| **Driver Master Close** | $\approx 4$ | $\approx 4$ | 1 read on `rides` + 1 query on `rideRequests` for ride (e.g. 3 docs) + batch update across all (4 total) |
| **Open Admin Dashboard** | $\mathbf{\approx 2,000+}$ | 0 | Whole collection scans across `users` (300) + `rides` (500) + `rideRequests` (1,000) + `auditLogs` (200) |

---

## 4. 300 ACTIVE EMPLOYEES DAILY USAGE & QUOTA ANALYSIS

### Deployment Assumptions
- **User Base**: 300 registered employees.
- **Commute Windows**: 4 daily commute events (`07:00`, `13:00`, `14:00`, `17:05`).
- **Participation**: 30 active drivers, 100 active passengers daily.
- **Sessions**: Average employee opens the app 3 to 4 times per day.

### Daily Read Consumption Projection

```text
1. HOME SCREEN OPENS:
   300 users × 4 sessions/day = 1,200 app sessions/day
   1,200 sessions × 32 initial reads = 38,400 reads/day

2. DISCOVERY STREAMING CHURN:
   30 drivers publishing / updating seats
   100 passengers posting / joining
   Average 10 document updates received per active listener during peak hours:
   300 users × 10 churn events = 3,000 reads/day

3. MATCHING & CLOSURE ACTIONS:
   100 joins / accepts = 100 × 2 reads = 200 reads/day
   100 closures = 100 × 3 reads = 300 reads/day

4. ADMIN DASHBOARD INSPECTION:
   Admin visits dashboard 3 times per day:
   3 visits × 2,000 reads = 6,000 reads/day

================================================================================
TOTAL PROJECTED DAILY READS:   ~47,900 to 54,000 reads/day
FIREBASE SPARK FREE QUOTA:      50,000 reads/day
HEADROOM / DEFICIT:             -4,000 to +2,100 reads (BORDERLINE QUOTA EXHAUSTION)
================================================================================
```

### Risk Verdict
At 300 daily active users, TravelBuddy is operating at **95% to 108% of the Firebase Spark daily read quota**. A busy day or an admin refreshing the dashboard 5 times will trigger `RESOURCE_EXHAUSTED` (HTTP 429), locking out the application until midnight Pacific Time.

---

## 5. ACTIONABLE OPTIMIZATIONS TO STAY UNDER SPARK BUDGET

### 1. Decouple Personal History from HomeScreen (Saves ~24,000 reads/day)
- **Current Issue**: `HomeScreen.kt:170-176` launches `getPassengerHistoryFlow(limit 50)` and `getDriverRideHistoryFlow(limit 50)` solely to compute personal CO₂ savings.
- **Optimization**: Compute and persist the user's total completed rides count directly into `users/{uid}.totalCompletedRides`. Read `totalCompletedRides` from the already-loaded `User` profile.
- **Impact**: Eliminates up to 20 reads per HomeScreen open, reducing baseline reads from 32 down to 12.

### 2. Mode-Partition Discovery Listeners (Saves ~6,000 reads/day)
- **Current Issue**: `HomeScreen` launches **both** `availableDrivers` (for passenger) and `matchingPassengerNeeds` (for driver) simultaneously regardless of current mode.
- **Optimization**: Launch `availableDrivers` **only** when `currentMode == PASSENGER`. Launch `matchingPassengerNeeds` **only** when `currentMode == DRIVER`.
- **Impact**: Halves the number of active discovery snapshot listeners.

### 3. Paginate Admin Members & Analytics (Saves ~5,000 reads/day)
- **Current Issue**: `AdminRepository` queries entire collections without `limit()`.
- **Optimization**: Add `.limit(25)` to `getAllUsersFlow()` with next-page pagination. For analytics and reports, convert snapshot listeners into one-shot `.get()` calls triggered explicitly on tab selection.
